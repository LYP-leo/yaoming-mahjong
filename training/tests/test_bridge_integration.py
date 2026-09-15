"""Integration tests against the actual bundled Java engine, not a Python rules mock."""
from __future__ import annotations

import json
from pathlib import Path
import shutil

import numpy as np
import pytest

from yaoming_rl.bridge import Simulator, SimulatorError


@pytest.fixture(scope="module")
def simulator():
    with Simulator(workers=2, heap_mb=512) as value:
        yield value


def test_both_rule_profiles_and_seed_reproducibility(simulator):
    hello = simulator.hello()
    assert hello["protocol"] == 1 and hello["actionSize"] == 194
    assert hello["observationSize"] == 1152
    assert len(hello["engineHash"]) == 64
    rules = {item["id"]: item for item in hello["rules"]}
    assert rules["yaoming-3p"]["minimumFan"] == 4
    assert rules["yaoming-4p"]["minimumFan"] == 3
    for rule, players in (("yaoming-3p", 3), ("yaoming-4p", 4)):
        request = {"cmd": "reset", "env": 0, "rule": rule, "seed": 20260912}
        original = simulator.request(request)
        assert original == simulator.request(request)
        assert original["playerCount"] == players
        assert len(original["obs"]) == 1152 and np.isfinite(original["obs"]).all()
        assert len(original["mask"]) == 194 and any(original["mask"])
        assert sum(original["scores"]) == players * 10


@pytest.mark.parametrize("rule", ["yaoming-3p", "yaoming-4p"])
def test_complete_seeded_matches_and_terminal_contract(simulator, rule):
    states = simulator.batch([{"cmd": "reset", "env": i, "rule": rule, "seed": 31 + i} for i in range(3)])
    for _ in range(4000):
        active = [state for state in states if not state["done"]]
        if not active:
            break
        next_states = simulator.batch([{"cmd": "step", "env": state["env"], "policy": "random"} for state in active])
        for state in next_states:
            states[state["env"]] = state
            assert sum(state["scores"]) == state["playerCount"] * 10
            assert all(score >= 0 for score in state["scores"])
            assert np.isfinite(state["obs"]).all()
    assert all(state["done"] for state in states)
    for state in states:
        assert state["actor"] == -1
        assert not any(state["mask"]) and not any(state["obs"])
        assert 1 <= state["handsCompleted"] <= state["playerCount"] * 2
        assert sum(state["wins"]) <= state["handsCompleted"]
        with pytest.raises(SimulatorError, match="ended"):
            simulator.request({"cmd": "step", "env": state["env"], "action": 0})


def test_illegal_action_does_not_advance_and_baseline_is_legal(simulator):
    reset = {"cmd": "reset", "env": 10, "rule": "yaoming-3p", "seed": 91}
    state = simulator.request(reset)
    illegal = next(i for i, flag in enumerate(state["mask"]) if not flag)
    with pytest.raises(SimulatorError, match="Illegal action"):
        simulator.request({"cmd": "step", "env": 10, "action": illegal})
    label = simulator.request({"cmd": "baseline", "env": 10, "policy": "heuristic"})["action"]
    assert state["mask"][label]
    continued = simulator.request({"cmd": "step", "env": 10, "action": label})
    simulator.request(reset)
    assert continued == simulator.request({"cmd": "step", "env": 10, "action": label})


def test_batch_validation_and_unknown_rule(simulator):
    with pytest.raises(SimulatorError, match="Duplicate"):
        simulator.batch([{"cmd": "reset", "env": 20, "rule": "yaoming-3p", "seed": 1}] * 2)
    with pytest.raises(SimulatorError):
        simulator.request({"cmd": "reset", "env": 20, "rule": "not-a-rule", "seed": 1})
    with pytest.raises(SimulatorError):
        simulator.request({"cmd": "reset", "env": 20, "rule": "yaoming-3p", "seed": 1.5})
    assert simulator.batch([]) == []


def test_closed_transport_and_argument_bounds():
    with pytest.raises(ValueError):
        Simulator(workers=0)
    with Simulator(workers=1, heap_mb=256) as simulator:
        assert simulator.hello()["protocol"] == 1
    simulator.close()
    with pytest.raises(SimulatorError, match="closed"):
        simulator.hello()


def test_checksum_and_manifest_path_are_verified(tmp_path):
    original = Path(__file__).resolve().parents[1] / "simulator"
    copied = tmp_path / "isolated-simulator"
    shutil.copytree(original, copied)
    manifest_path = copied / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["artifacts"]["simulator.jar"] = "0" * 64
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(SimulatorError, match="checksum"):
        Simulator(jar_dir=copied)
    manifest["artifacts"] = json.loads((original / "manifest.json").read_text(encoding="utf-8"))["artifacts"]
    manifest["artifacts"]["../outside.jar"] = "0" * 64
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(SimulatorError, match="invalid"):
        Simulator(jar_dir=copied)
