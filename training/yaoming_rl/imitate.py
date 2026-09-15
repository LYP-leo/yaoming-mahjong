"""Optional supervised warm start from the existing, non-cheating rule bot.

Samples are decisions from all seats, unlike PPO's learning-seat-only count.
Use the result with train --init, not as evidence of improved playing strength.
"""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path
import random
import time

import torch

from .bridge import Simulator
from .checkpoint import save_checkpoint
from .model import Policy, resolve_device
from .train import DEFAULTS, positive_int


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--rule", choices=("yaoming-3p", "yaoming-4p"), default="yaoming-3p")
    result.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    result.add_argument("--steps", type=positive_int, default=10000)
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--num-envs", type=positive_int, default=4)
    result.add_argument("--batch-size", type=positive_int, default=256)
    result.add_argument("--epochs", type=positive_int, default=2)
    result.add_argument("--hidden", type=positive_int, default=256)
    result.add_argument("--teacher", choices=("heuristic", "tsumogiri"), default="heuristic")
    result.add_argument("--seed", type=int, default=20260912)
    result.add_argument("--java")
    result.add_argument("--workers", type=positive_int, default=4)
    result.add_argument("--heap-mb", type=positive_int, default=1024)
    result.add_argument("--torch-threads", type=positive_int, default=4)
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    if args.output.exists():
        raise ValueError("Imitation checkpoint exists; choose a new --output to preserve it")
    device = resolve_device(args.device)
    torch.set_num_threads(args.torch_threads)
    random.seed(args.seed)
    torch.manual_seed(args.seed)
    with Simulator(java=args.java, workers=args.workers, heap_mb=args.heap_mb) as simulator:
        info = simulator.hello()
        config = {**DEFAULTS, "rule": args.rule, "hidden": args.hidden, "seed": args.seed,
                  "training_stage": "imitation", "teacher": args.teacher,
                  "observation_size": info["observationSize"], "action_size": info["actionSize"]}
        model = Policy(info["observationSize"], info["actionSize"], args.hidden).to(device)
        optimizer = torch.optim.Adam(model.parameters(), lr=DEFAULTS["learning_rate"], eps=1e-5)
        states = simulator.batch([{"cmd": "reset", "env": i, "rule": args.rule, "seed": args.seed + i}
                                  for i in range(args.num_envs)])
        episodes = [0] * args.num_envs
        steps = updates = 0
        started = time.perf_counter()
        while steps < args.steps:
            target = min(args.batch_size, args.steps - steps)
            observations, masks, labels = [], [], []
            while len(labels) < target:
                active = list(range(min(args.num_envs, target - len(labels))))
                answers = simulator.batch([{"cmd": "baseline", "env": i, "policy": args.teacher} for i in active])
                choices = [answer["action"] for answer in answers]
                for i, action in zip(active, choices):
                    if states[i]["done"] or not states[i]["mask"][action]:
                        raise RuntimeError("Teacher label does not belong to the current legal state")
                    observations.append(states[i]["obs"])
                    masks.append(states[i]["mask"])
                    labels.append(action)
                advanced = simulator.batch([{"cmd": "step", "env": i, "action": action} for i, action in zip(active, choices)])
                reset_requests = []
                for i, state in zip(active, advanced):
                    states[i] = state
                    if state["done"]:
                        episodes[i] += 1
                        reset_requests.append({"cmd": "reset", "env": i, "rule": args.rule,
                                               "seed": args.seed + episodes[i] * args.num_envs + i})
                for state in simulator.batch(reset_requests):
                    states[state["env"]] = state
            obs = torch.tensor(observations, dtype=torch.float32, device=device)
            legal = torch.tensor(masks, dtype=torch.bool, device=device)
            actions = torch.tensor(labels, dtype=torch.long, device=device)
            for _ in range(args.epochs):
                distribution, _ = model.distribution(obs, legal)
                loss = -distribution.log_prob(actions).mean()
                if not torch.isfinite(loss):
                    raise FloatingPointError("Non-finite imitation loss")
                optimizer.zero_grad(set_to_none=True)
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), 0.5, error_if_nonfinite=True)
                optimizer.step()
                updates += 1
            steps += len(labels)
            print(json.dumps({"stage": "imitation", "steps": steps, "updates": updates,
                              "cross_entropy": loss.item(), "teacher": args.teacher,
                              "all_seat_decisions_per_second": steps / (time.perf_counter() - started)}), flush=True)
        opponent = copy.deepcopy(model).eval()
        save_checkpoint(args.output, model=model, opponent=opponent, optimizer=optimizer,
                        config=config, steps=steps, updates=updates, engine_hash=info["engineHash"])
        print(json.dumps({"status": "completed", "checkpoint": str(args.output), "steps": steps,
                          "next": "Use train --init CHECKPOINT --rule SAME_RULE --run NEW_DIRECTORY; PPO step count starts at zero."}), flush=True)


if __name__ == "__main__":
    main()
