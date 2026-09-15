"""Small masked PPO baseline: python -m yaoming_rl.train --help."""

from __future__ import annotations

import argparse
import copy
import json
import math
import os
from pathlib import Path
import random
import signal
import time

import torch

from .bridge import Simulator
from .checkpoint import load_checkpoint, restore_random_states, save_checkpoint, verify_engine
from .env import LearnerVecEnv
from .learning import generalized_advantage, ppo_update
from .model import Policy, resolve_device


DEFAULTS = {"rule": "yaoming-3p", "num_envs": 8, "horizon": 128, "minibatch": 256, "epochs": 4,
            "hidden": 256, "seed": 20260912, "opponent": "selfplay", "snapshot_every": 10,
            "learning_rate": 3e-4, "gamma": 1.0, "gae_lambda": 0.95, "clip": 0.2,
            "entropy_coef": 0.01, "value_coef": 0.5, "max_grad_norm": 0.5}


class SafeInterrupt:
    """One Ctrl+C finishes the update; a second preserves only older checkpoints."""

    def __init__(self):
        self.requested = False
        self.previous = None

    def __enter__(self):
        self.previous = signal.getsignal(signal.SIGINT)
        signal.signal(signal.SIGINT, self._handle)
        return self

    def _handle(self, signum, frame):
        if self.requested:
            raise KeyboardInterrupt
        self.requested = True
        # Avoid re-entering Python's buffered print machinery if Ctrl+C arrived
        # while a progress line was already being written.
        os.write(2, b'{"status":"interrupt_pending","note":"Finishing this complete PPO update, then saving. A second Ctrl+C interrupts immediately and retains only the previous safe checkpoint."}\n')

    def __exit__(self, exc_type, exc, traceback):
        signal.signal(signal.SIGINT, self.previous)


def positive_int(value):
    result = int(value)
    if result < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return result


def finite_float(value):
    result = float(value)
    if not math.isfinite(result):
        raise argparse.ArgumentTypeError("must be finite")
    return result


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--rule", choices=("yaoming-3p", "yaoming-4p"))
    result.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    result.add_argument("--steps", type=positive_int, default=10_000_000, help="total target learning-seat decisions, including already completed steps on resume")
    result.add_argument("--run", type=Path)
    result.add_argument("--resume", type=Path)
    result.add_argument("--init", type=Path, help="initialize only policy weights from a compatible checkpoint; start a NEW run and optimizer")
    result.add_argument("--simulator", type=Path, default=Path(__file__).resolve().parents[1] / "simulator")
    result.add_argument("--java")
    result.add_argument("--workers", type=positive_int, default=4)
    result.add_argument("--heap-mb", type=positive_int, default=1024)
    result.add_argument("--torch-threads", type=positive_int, default=4)
    result.add_argument("--checkpoint-every", type=positive_int, default=10, help="checkpoint every N PPO updates, also at successful completion")
    for name in ("num_envs", "horizon", "minibatch", "epochs", "hidden", "snapshot_every"):
        result.add_argument("--" + name.replace("_", "-"), type=positive_int)
    result.add_argument("--seed", type=int)
    result.add_argument("--opponent", choices=("selfplay", "heuristic", "random"))
    for name in ("learning_rate", "gamma", "gae_lambda", "clip", "entropy_coef", "value_coef", "max_grad_norm"):
        result.add_argument("--" + name.replace("_", "-"), type=finite_float)
    return result


def resolve_config(args, checkpoint=None):
    if checkpoint is not None and checkpoint["config"].get("training_stage", "ppo") != "ppo":
        raise ValueError("this is an imitation checkpoint; use --init and a new --run to begin PPO")
    saved = checkpoint["config"] if checkpoint is not None else DEFAULTS
    config = dict(saved)
    config["training_stage"] = "ppo"
    for name in DEFAULTS:
        specified = getattr(args, name)
        if checkpoint is not None and specified is not None and specified != saved[name]:
            raise ValueError(f"--{name.replace('_', '-')} conflicts with checkpoint ({saved[name]!r}); resume cannot silently change training configuration")
        config[name] = saved[name] if specified is None else specified
    for name in ("gamma", "gae_lambda"):
        if not 0 <= config[name] <= 1:
            raise ValueError(f"{name} must be between zero and one")
    if not 0 < config["clip"] < 1:
        raise ValueError("clip must be between zero and one")
    for name in ("learning_rate", "max_grad_norm"):
        if config[name] <= 0:
            raise ValueError(f"{name} must be positive")
    for name in ("entropy_coef", "value_coef"):
        if config[name] < 0:
            raise ValueError(f"{name} cannot be negative")
    return config


def _rollout(model, env, states, length, config, device):
    observations, masks, actions, logps, values, rewards, dones, infos = [], [], [], [], [], [], [], []
    for _ in range(length):
        obs = torch.tensor([state["obs"] for state in states], dtype=torch.float32)
        legal = torch.tensor([state["mask"] for state in states], dtype=torch.bool)
        with torch.no_grad():
            distribution, value = model.distribution(obs.to(device), legal.to(device))
            action = distribution.sample()
            logp = distribution.log_prob(action)
        states, reward, done, information = env.step(action.cpu().tolist())
        observations.append(obs)
        masks.append(legal)
        actions.append(action.cpu())
        logps.append(logp.cpu())
        values.append(value.cpu())
        rewards.append(torch.tensor(reward, dtype=torch.float32))
        dones.append(torch.tensor(done, dtype=torch.bool))
        infos.extend(info for info in information if info is not None)
    with torch.no_grad():
        _, next_value = model(torch.tensor([state["obs"] for state in states], dtype=torch.float32, device=device))
    reward_tensor, done_tensor, value_tensor = torch.stack(rewards), torch.stack(dones), torch.stack(values)
    advantage, returns = generalized_advantage(reward_tensor, done_tensor, value_tensor, next_value.cpu(), config["gamma"], config["gae_lambda"])
    data = tuple(tensor.flatten(0, 1) for tensor in (torch.stack(observations), torch.stack(masks), torch.stack(actions),
                                                    torch.stack(logps), value_tensor, advantage, returns))
    return states, data, infos, reward_tensor.sum().item()


def main(argv=None):
    args = parser().parse_args(argv)
    device = resolve_device(args.device)
    torch.set_num_threads(args.torch_threads)
    checkpoint = load_checkpoint(args.resume) if args.resume else None
    if args.resume and args.init:
        raise ValueError("--resume and --init cannot be combined")
    initial = load_checkpoint(args.init) if args.init else None
    config = resolve_config(args, checkpoint)
    run = args.run or (args.resume.resolve().parent if args.resume else None)
    if run is None:
        raise ValueError("new training requires --run; use a different directory for each experiment")
    run = run.resolve()
    if checkpoint is None and run.exists() and any(run.iterdir()):
        raise ValueError("run directory is not empty; use --resume or choose a new --run directory")
    if checkpoint is not None and run != args.resume.resolve().parent:
        raise ValueError("resume must write into the checkpoint's run directory; --run differs")
    steps, updates = (checkpoint["steps"], checkpoint["updates"]) if checkpoint else (0, 0)
    if steps >= args.steps:
        print(json.dumps({"status": "target_already_reached", "steps": steps, "target": args.steps}))
        return
    random.seed(config["seed"])
    torch.manual_seed(config["seed"])
    if device.type == "cuda":
        torch.cuda.manual_seed_all(config["seed"])
    with Simulator(args.simulator, java=args.java, workers=args.workers, heap_mb=args.heap_mb) as simulator:
        metadata = simulator.hello()
        if metadata.get("protocol") != 1:
            raise ValueError("unsupported simulator protocol")
        if checkpoint:
            verify_engine(checkpoint, metadata)
        else:
            config.update(observation_size=metadata["observationSize"], action_size=metadata["actionSize"])
        if initial:
            verify_engine(initial, metadata)
            if any(initial["config"][key] != config[key] for key in ("rule", "hidden", "observation_size", "action_size")):
                raise ValueError("initial policy rule or network architecture differs; match --rule and --hidden")
        model = Policy(config["observation_size"], config["action_size"], config["hidden"]).to(device)
        if initial:
            model.load_state_dict(initial["model"])
        opponent = copy.deepcopy(model).eval()
        opponent.requires_grad_(False)
        optimizer = torch.optim.Adam(model.parameters(), lr=config["learning_rate"], eps=1e-5)
        if checkpoint:
            model.load_state_dict(checkpoint["model"])
            opponent.load_state_dict(checkpoint["opponent"])
            optimizer.load_state_dict(checkpoint["optimizer"])
            restore_random_states(checkpoint, device)
        run.mkdir(parents=True, exist_ok=True)
        config_path = run / "config.json"
        if not config_path.exists():
            config_path.write_text(json.dumps({**config, "engine_hash": metadata["engineHash"]}, ensure_ascii=False, indent=2), encoding="utf-8")
        if checkpoint is None:
            save_checkpoint(run / "latest.pt", model=model, opponent=opponent, optimizer=optimizer, config=config,
                            steps=0, updates=0, engine_hash=metadata["engineHash"])
        env = LearnerVecEnv(simulator, rule=config["rule"], num_envs=config["num_envs"], seed=config["seed"] + steps * 1009,
                            opponent=config["opponent"], opponent_actions=lambda states: opponent.actions(states, device))
        states = env.reset()
        started, starting_steps = time.perf_counter(), steps
        print(json.dumps({"status": "training", "device": str(device), "config": config, "engine_hash": metadata["engineHash"],
                          "resume": bool(checkpoint), "resume_restarts_episodes": bool(checkpoint)}, ensure_ascii=False), flush=True)
        # First interrupt waits for the same complete update boundary as normal
        # checkpointing. A second interrupt cannot publish half-updated weights.
        with SafeInterrupt() as interrupt, (run / "metrics.jsonl").open("a", encoding="utf-8") as metrics:
            while steps < args.steps:
                length = min(config["horizon"], math.ceil((args.steps - steps) / config["num_envs"]))
                states, data, infos, reward_sum = _rollout(model, env, states, length, config, device)
                losses = ppo_update(model, optimizer, data, device=device, epochs=config["epochs"], minibatch=config["minibatch"],
                                    clip=config["clip"], entropy_coef=config["entropy_coef"], value_coef=config["value_coef"], max_grad_norm=config["max_grad_norm"])
                steps += length * config["num_envs"]
                updates += 1
                if config["opponent"] == "selfplay" and updates % config["snapshot_every"] == 0:
                    opponent.load_state_dict(model.state_dict())
                elapsed = time.perf_counter() - started
                record = {"steps": steps, "updates": updates, "elapsed_seconds": elapsed,
                          "learner_decisions_per_second": (steps - starting_steps) / elapsed, "rollout_reward_sum": reward_sum,
                          "skipped_zero_decision_episodes": env.skipped_zero_decision_episodes,
                          "completed_matches": len(infos), "mean_match_score": sum(i["score"] for i in infos) / len(infos) if infos else None,
                          "mean_match_rank": sum(i["rank"] for i in infos) / len(infos) if infos else None, **losses}
                if device.type == "cuda":
                    record["peak_cuda_allocated_mb"] = torch.cuda.max_memory_allocated(device) / 2 ** 20
                line = json.dumps(record, ensure_ascii=False)
                metrics.write(line + "\n")
                metrics.flush()
                print(line, flush=True)
                if updates % args.checkpoint_every == 0 or steps >= args.steps or interrupt.requested:
                    save_checkpoint(run / "latest.pt", model=model, opponent=opponent, optimizer=optimizer, config=config,
                                    steps=steps, updates=updates, engine_hash=metadata["engineHash"])
                    archive = run / f"step-{steps:012d}.pt"
                    if not archive.exists():
                        save_checkpoint(archive, model=model, opponent=opponent, optimizer=optimizer, config=config,
                                        steps=steps, updates=updates, engine_hash=metadata["engineHash"])
                if interrupt.requested:
                    break
        print(json.dumps({"status": "stopped_safely" if interrupt.requested else "completed", "checkpoint": str(run / "latest.pt"), "steps": steps,
                          "note": "Step budget is rounded up by at most num_envs-1 decisions. No playing strength is implied."}), flush=True)


if __name__ == "__main__":
    main()
