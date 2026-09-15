"""Small masked policy; the rollout buffer deliberately stays in CPU memory."""

from __future__ import annotations

import torch
from torch import nn
from torch.distributions import Categorical


def resolve_device(name: str) -> torch.device:
    if name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA was requested but is unavailable. Install the CUDA PyTorch build and NVIDIA driver, or explicitly use --device cpu.")
    if name not in ("cpu", "cuda"):
        raise ValueError("device must be cpu or cuda")
    return torch.device(name)


class Policy(nn.Module):
    def __init__(self, observation_size: int, action_size: int, hidden: int = 256):
        super().__init__()
        self.encoder = nn.Sequential(nn.Linear(observation_size, hidden), nn.Tanh(), nn.Linear(hidden, hidden), nn.Tanh())
        self.actor = nn.Linear(hidden, action_size)
        self.critic = nn.Linear(hidden, 1)
        for layer in self.modules():
            if isinstance(layer, nn.Linear):
                nn.init.orthogonal_(layer.weight, gain=2 ** 0.5)
                nn.init.zeros_(layer.bias)
        nn.init.orthogonal_(self.actor.weight, gain=0.01)
        nn.init.orthogonal_(self.critic.weight, gain=1.0)

    def forward(self, observations: torch.Tensor):
        features = self.encoder(observations)
        return self.actor(features), self.critic(features).squeeze(-1)

    def distribution(self, observations: torch.Tensor, masks: torch.Tensor):
        logits, values = self(observations)
        masks = masks.bool()
        if masks.shape != logits.shape:
            raise ValueError("legal-action mask shape differs from policy logits")
        if not masks.any(dim=-1).all():
            raise ValueError("terminal or invalid state has no legal actions; reset it before sampling")
        # -inf gives exactly zero illegal-action probability, not merely a small probability.
        return Categorical(logits=logits.masked_fill(~masks, -torch.inf)), values

    @torch.no_grad()
    def actions(self, states: list[dict], device: torch.device, deterministic: bool = False) -> list[int]:
        observations = torch.tensor([state["obs"] for state in states], dtype=torch.float32, device=device)
        masks = torch.tensor([state["mask"] for state in states], dtype=torch.bool, device=device)
        distribution, _ = self.distribution(observations, masks)
        actions = distribution.logits.argmax(-1) if deterministic else distribution.sample()
        return actions.cpu().tolist()
