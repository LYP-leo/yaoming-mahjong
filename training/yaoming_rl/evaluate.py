"""Evaluate saved weights on a reproducible seed schedule and rotating seats."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import time

import torch

from .bridge import Simulator
from .checkpoint import load_checkpoint, verify_engine
from .model import Policy, resolve_device
from .train import positive_int


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--checkpoint", type=Path, required=True)
    result.add_argument("--opponent", choices=("heuristic", "random", "tsumogiri"), default="heuristic")
    result.add_argument("--matches", type=positive_int, default=30)
    result.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    result.add_argument("--seed", type=int, default=700000)
    result.add_argument("--output", type=Path)
    result.add_argument("--simulator", type=Path, default=Path(__file__).resolve().parents[1] / "simulator")
    result.add_argument("--java")
    result.add_argument("--workers", type=positive_int, default=1)
    result.add_argument("--heap-mb", type=positive_int, default=1024)
    result.add_argument("--torch-threads", type=positive_int, default=4)
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    if args.output and args.output.exists():
        raise ValueError("evaluation output already exists; choose another --output path")
    device = resolve_device(args.device)
    torch.set_num_threads(args.torch_threads)
    checkpoint = load_checkpoint(args.checkpoint)
    config = checkpoint["config"]
    model = Policy(config["observation_size"], config["action_size"], config["hidden"]).to(device)
    model.load_state_dict(checkpoint["model"])
    model.eval()
    results, decisions = [], 0
    started = time.perf_counter()
    with Simulator(args.simulator, java=args.java, workers=args.workers, heap_mb=args.heap_mb) as simulator:
        verify_engine(checkpoint, simulator.hello())
        players = 3 if config["rule"] == "yaoming-3p" else 4
        # Every seed is played from every seat before advancing to the next seed.
        for match in range(args.matches):
            seat, seed = match % players, args.seed + match // players
            state = simulator.request({"cmd": "reset", "env": 0, "rule": config["rule"], "seed": seed})
            for _ in range(1000000):
                if state["done"]:
                    break
                if state["actor"] == seat:
                    action = model.actions([state], device, deterministic=True)[0]
                    state = simulator.request({"cmd": "step", "env": 0, "action": action})
                    decisions += 1
                else:
                    state = simulator.request({"cmd": "step", "env": 0, "policy": args.opponent})
            else:
                raise RuntimeError("evaluation exceeded 1000000 total decisions in one match")
            scores = state["scores"]
            rank = 1 + sum(score > scores[seat] for score in scores) + 0.5 * (sum(score == scores[seat] for score in scores) - 1)
            result = {"seat": seat, "seed": seed, "score": scores[seat], "scores": scores, "rank": rank,
                      "wins": state.get("wins", [0] * players)[seat], "hands": state.get("handsCompleted", 0)}
            results.append(result)
            print(json.dumps({"match": match + 1, **result}, ensure_ascii=False), flush=True)
    hands = sum(result["hands"] for result in results)
    summary = {"checkpoint": str(args.checkpoint), "trained_steps": checkpoint["steps"], "rule": config["rule"], "opponent": args.opponent,
               "matches": len(results), "deterministic_policy": True, "seed_seat_schedule": [{"seed": r["seed"], "seat": r["seat"]} for r in results],
               "mean_final_score": sum(result["score"] for result in results) / len(results),
               "mean_rank": sum(result["rank"] for result in results) / len(results),
               "first_place_rate_including_ties": sum(result["score"] == max(result["scores"]) for result in results) / len(results),
               "hand_win_rate": sum(result["wins"] for result in results) / hands if hands else None,
               "hands": hands, "learner_decisions": decisions, "elapsed_seconds": time.perf_counter() - started,
               "results": results, "note": "Small samples are a smoke test, not statistical evidence of playing strength. Compare checkpoints with the same seeds and full seat cycles."}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
