"""Versioned portable checkpoints. Loading uses PyTorch's restricted loader."""

from __future__ import annotations

import os
from pathlib import Path
import random

import torch


FORMAT_VERSION = 1


def _cpu_tree(value):
    if isinstance(value, torch.Tensor):
        return value.detach().cpu()
    if isinstance(value, dict):
        return {key: _cpu_tree(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_cpu_tree(item) for item in value]
    if isinstance(value, tuple):
        return tuple(_cpu_tree(item) for item in value)
    return value


def save_checkpoint(path, *, model, opponent, optimizer, config, steps, updates, engine_hash):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"format_version": FORMAT_VERSION, "engine_hash": engine_hash, "config": dict(config),
               "steps": int(steps), "updates": int(updates), "model": _cpu_tree(model.state_dict()),
               "opponent": _cpu_tree(opponent.state_dict()), "optimizer": _cpu_tree(optimizer.state_dict()),
               "python_random_state": random.getstate(), "torch_random_state": torch.get_rng_state(),
               "cuda_random_states": [state.cpu() for state in torch.cuda.get_rng_state_all()] if torch.cuda.is_available() else [],
               "resume_note": "Simulator episodes restart on resume; this is not bit-exact mid-game continuation."}
    temporary = path.with_name(path.name + ".tmp")
    try:
        torch.save(payload, temporary)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def load_checkpoint(path):
    value = torch.load(Path(path), map_location="cpu", weights_only=True)
    required = {"format_version", "engine_hash", "config", "steps", "updates", "model", "opponent", "optimizer", "python_random_state", "torch_random_state"}
    if not isinstance(value, dict) or not required.issubset(value):
        raise ValueError("not a complete Yaoming training checkpoint")
    if value["format_version"] != FORMAT_VERSION:
        raise ValueError("checkpoint format is incompatible")
    if not isinstance(value["config"], dict) or not isinstance(value["steps"], int) or value["steps"] < 0:
        raise ValueError("invalid checkpoint metadata")
    return value


def verify_engine(checkpoint, metadata):
    config = checkpoint["config"]
    if checkpoint["engine_hash"] != metadata["engineHash"]:
        raise ValueError("checkpoint engine fingerprint differs from this simulator; do not mix rule versions")
    if config["observation_size"] != metadata["observationSize"] or config["action_size"] != metadata["actionSize"]:
        raise ValueError("checkpoint observation/action dimensions are incompatible")


def restore_random_states(checkpoint, device):
    random.setstate(checkpoint["python_random_state"])
    torch.set_rng_state(checkpoint["torch_random_state"])
    states = checkpoint.get("cuda_random_states", [])
    if device.type == "cuda" and states:
        if len(states) != torch.cuda.device_count():
            raise ValueError("checkpoint CUDA device count differs; choose a matching visible-device configuration")
        torch.cuda.set_rng_state_all(states)
