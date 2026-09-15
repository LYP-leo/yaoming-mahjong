"""End-to-end CPU smoke tests. They verify executability, not playing strength."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys

import pytest
import torch

from yaoming_rl.checkpoint import load_checkpoint


ROOT = Path(__file__).resolve().parents[1]


def cli(module, arguments, *, success=True):
    environment = dict(os.environ)
    environment["PYTHONUTF8"] = "1"
    result = subprocess.run([sys.executable, "-m", "yaoming_rl." + module, *map(str, arguments)], cwd=ROOT,
                            env=environment, capture_output=True, text=True, encoding="utf-8", timeout=120)
    if success:
        assert result.returncode == 0, result.stdout + "\n" + result.stderr
    else:
        assert result.returncode != 0
    return result


@pytest.mark.parametrize("rule,players", [("yaoming-3p", 3), ("yaoming-4p", 4)])
def test_train_save_resume_evaluate_real_engine(tmp_path, rule, players):
    run = tmp_path / rule
    trained = cli("train", ["--rule", rule, "--device", "cpu", "--run", run, "--steps", "256",
                            "--num-envs", "2", "--horizon", "16", "--minibatch", "32", "--hidden", "32",
                            "--epochs", "1", "--snapshot-every", "2", "--checkpoint-every", "2", "--torch-threads", "1"])
    initial = load_checkpoint(run / "latest.pt")
    assert initial["steps"] == 256 and initial["updates"] == 8
    assert initial["config"]["rule"] == rule and initial["optimizer"]["state"]
    assert all(torch.isfinite(value).all() for value in initial["model"].values())
    assert (run / "step-000000000256.pt").is_file()
    rows = [json.loads(line) for line in (run / "metrics.jsonl").read_text(encoding="utf-8").splitlines()]
    assert rows[-1]["steps"] == 256 and rows[-1]["learner_decisions_per_second"] > 0
    resumed = cli("train", ["--resume", run / "latest.pt", "--device", "cpu", "--steps", "320", "--torch-threads", "1"])
    checkpoint = load_checkpoint(run / "latest.pt")
    assert checkpoint["steps"] == 320 and checkpoint["updates"] == 10
    assert '"resume_restarts_episodes": true' in resumed.stdout
    assert any(not torch.equal(checkpoint["model"][key], initial["model"][key]) for key in initial["model"])
    report = tmp_path / "evaluation.json"
    cli("evaluate", ["--checkpoint", run / "latest.pt", "--device", "cpu", "--opponent", "heuristic",
                     "--matches", players, "--seed", "99001", "--output", report, "--torch-threads", "1"])
    evaluation = json.loads(report.read_text(encoding="utf-8"))
    assert evaluation["matches"] == players
    assert evaluation["rule"] == rule and evaluation["hands"] >= players
    assert [row["seat"] for row in evaluation["seed_seat_schedule"]] == list(range(players))
    assert all(row["seed"] == 99001 for row in evaluation["seed_seat_schedule"])
    assert all(sum(row["scores"]) == 10 * players for row in evaluation["results"])
    assert 1 <= evaluation["mean_rank"] <= players
    assert 0 <= evaluation["hand_win_rate"] <= 1
    wrong = "yaoming-4p" if players == 3 else "yaoming-3p"
    conflict = cli("train", ["--resume", run / "latest.pt", "--rule", wrong, "--steps", "400"], success=False)
    assert "conflicts" in conflict.stderr
    assert load_checkpoint(run / "latest.pt")["steps"] == 320
    # Fresh training must never clobber an existing run.
    refusal = cli("train", ["--run", run, "--steps", "1"], success=False)
    assert "not empty" in refusal.stderr


def test_heuristic_imitation_initializes_new_ppo_run(tmp_path):
    teacher = tmp_path / "teacher.pt"
    cli("imitate", ["--rule", "yaoming-3p", "--device", "cpu", "--steps", "64", "--output", teacher,
                    "--hidden", "32", "--num-envs", "2", "--batch-size", "32", "--epochs", "1", "--torch-threads", "1"])
    checkpoint = load_checkpoint(teacher)
    assert checkpoint["steps"] == 64 and checkpoint["config"]["training_stage"] == "imitation"
    refusal = cli("train", ["--resume", teacher, "--steps", "128"], success=False)
    assert "--init" in refusal.stderr
    run = tmp_path / "warm-ppo"
    cli("train", ["--init", teacher, "--rule", "yaoming-3p", "--device", "cpu", "--steps", "32", "--run", run,
                  "--hidden", "32", "--num-envs", "2", "--horizon", "8", "--minibatch", "16", "--epochs", "1", "--torch-threads", "1"])
    warmed = load_checkpoint(run / "latest.pt")
    assert warmed["steps"] == 32 and warmed["config"]["training_stage"] == "ppo"
    assert checkpoint["engine_hash"] == warmed["engine_hash"]
    assert any(not torch.equal(warmed["model"][key], checkpoint["model"][key]) for key in checkpoint["model"])


def test_doctor_cpu_and_cuda_does_not_silently_fallback():
    report = json.loads(cli("doctor", ["--device", "cpu"]).stdout)
    assert report["device"] == "cpu" and report["model_parameters"] == 411075
    if not torch.cuda.is_available():
        failure = cli("doctor", ["--device", "cuda"], success=False)
        assert "CUDA" in failure.stderr and "FAILED" in failure.stderr
