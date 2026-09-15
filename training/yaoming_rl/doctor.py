"""Verify Python, the actual PyTorch device, model gradients and both rule engines."""
from __future__ import annotations

import argparse
import json
import platform
import subprocess
import sys

from .bridge import Simulator, find_java


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", choices=["cpu", "cuda"], default="cuda")
    parser.add_argument("--java", help="Optional path to a Java 21+ executable")
    args = parser.parse_args(argv)
    try:
        import numpy as np
        import torch
        from .model import Policy, resolve_device

        torch.set_num_threads(1)
        device = resolve_device(args.device)
        java_version = subprocess.run([find_java(args.java), "-version"], capture_output=True,
                                      text=True, timeout=15, check=True)
        report = {"python": platform.python_version(), "torch": torch.__version__, "numpy": np.__version__,
                  "device": str(device), "java": (java_version.stderr or java_version.stdout).splitlines()[0],
                  "torch_cuda_runtime": torch.version.cuda}
        if device.type == "cuda":
            properties = torch.cuda.get_device_properties(device)
            torch.cuda.reset_peak_memory_stats(device)
            report.update(gpu=properties.name, dedicated_vram_gib=round(properties.total_memory / 2**30, 2))
        with Simulator(java=args.java, workers=2, heap_mb=512) as simulator:
            info = simulator.hello()
            report.update(engine_hash=simulator.engine_hash, observation_size=info["observationSize"],
                          action_size=info["actionSize"])
            policy = Policy(info["observationSize"], info["actionSize"], hidden=256).to(device)
            optimizer = torch.optim.Adam(policy.parameters(), lr=3e-4)
            initial = simulator.batch([{"cmd": "reset", "env": i, "rule": rule, "seed": 100 + i}
                                       for i, rule in enumerate(("yaoming-3p", "yaoming-4p"))])
            for state in initial:
                assert not state["done"] and any(state["mask"])
                assert len(state["obs"]) == info["observationSize"]
                assert np.isfinite(state["obs"]).all()
                assert sum(state["scores"]) == 10 * state["playerCount"]
            # Exercise the requested device with the default training minibatch size.
            observations = torch.tensor([state["obs"] for state in initial] * 128, device=device)
            masks = torch.tensor([state["mask"] for state in initial] * 128, device=device)
            distribution, values = policy.distribution(observations, masks)
            actions = distribution.sample()
            loss = -distribution.log_prob(actions).mean() + values.square().mean() - 0.01 * distribution.entropy().mean()
            optimizer.zero_grad()
            loss.backward()
            assert torch.isfinite(loss)
            assert all(parameter.grad is None or torch.isfinite(parameter.grad).all() for parameter in policy.parameters())
            optimizer.step()
            states = initial
            for _ in range(10):
                selected = policy.actions(states, device)
                states = simulator.batch([{"cmd": "step", "env": i, "action": action}
                                          for i, action in enumerate(selected)])
                for i, state in enumerate(states):
                    if state["done"]:
                        states[i] = simulator.request({"cmd": "reset", "env": i,
                                                       "rule": "yaoming-3p" if i == 0 else "yaoming-4p", "seed": 300 + i})
            report["model_parameters"] = sum(parameter.numel() for parameter in policy.parameters())
            report["simulator_smoke"] = "3p and 4p: legal decisions passed"
            report["gradient_smoke"] = "finite forward/backward/Adam update passed"
        if device.type == "cuda":
            torch.cuda.synchronize(device)
            report["cuda_peak_allocated_mib"] = round(torch.cuda.max_memory_allocated(device) / 2**20, 2)
            report["cuda_peak_reserved_mib"] = round(torch.cuda.max_memory_reserved(device) / 2**20, 2)
            report["memory_note"] = "PyTorch allocator peak for this smoke test only; excludes driver/display usage and shared RAM."
        else:
            report["memory_note"] = "CPU test only: no CUDA memory or 3060 Ti performance claim."
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except Exception as exc:
        print(f"Environment check FAILED: {exc}", file=sys.stderr)
        if args.device == "cuda":
            print("Check NVIDIA driver and CUDA PyTorch wheel. To intentionally test without GPU, use --device cpu.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
