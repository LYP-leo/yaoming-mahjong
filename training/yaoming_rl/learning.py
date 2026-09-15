"""PPO math kept separate from the process bridge for deterministic unit tests."""

from __future__ import annotations

import torch


def generalized_advantage(rewards, dones, values, next_value, gamma=1.0, gae_lambda=0.95):
    if rewards.shape != dones.shape or rewards.shape != values.shape:
        raise ValueError("reward/done/value rollout shapes differ")
    advantages = torch.zeros_like(rewards)
    carry = torch.zeros_like(next_value)
    for t in reversed(range(rewards.shape[0])):
        live = (~dones[t].bool()).to(rewards.dtype)
        following = next_value if t == rewards.shape[0] - 1 else values[t + 1]
        delta = rewards[t] + gamma * following * live - values[t]
        carry = delta + gamma * gae_lambda * live * carry
        advantages[t] = carry
    return advantages, advantages + values


def ppo_update(model, optimizer, data, *, device, epochs=4, minibatch=256,
               clip=0.2, entropy_coef=0.01, value_coef=0.5, max_grad_norm=0.5):
    observations, masks, actions, old_logp, old_values, advantages, returns = data
    size = observations.shape[0]
    advantages = (advantages - advantages.mean()) / (advantages.std(unbiased=False) + 1e-8)
    totals = {"policy_loss": 0.0, "value_loss": 0.0, "entropy": 0.0, "approx_kl": 0.0, "grad_norm": 0.0}
    updates = 0
    for _ in range(epochs):
        permutation = torch.randperm(size)
        for start in range(0, size, minibatch):
            indexes = permutation[start:start + minibatch]
            obs, legal, action, previous_logp, previous_value, advantage, target = (
                tensor[indexes].to(device) for tensor in (observations, masks, actions, old_logp, old_values, advantages, returns))
            distribution, value = model.distribution(obs, legal)
            logp = distribution.log_prob(action)
            log_ratio = logp - previous_logp
            ratio = log_ratio.exp()
            policy_loss = torch.maximum(-advantage * ratio, -advantage * ratio.clamp(1 - clip, 1 + clip)).mean()
            clipped_value = previous_value + (value - previous_value).clamp(-clip, clip)
            value_loss = 0.5 * torch.maximum((value - target).square(), (clipped_value - target).square()).mean()
            entropy = distribution.entropy().mean()
            loss = policy_loss + value_coef * value_loss - entropy_coef * entropy
            if not torch.isfinite(loss):
                raise FloatingPointError("non-finite PPO loss; checkpoint was not overwritten")
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            grad_norm = torch.nn.utils.clip_grad_norm_(model.parameters(), max_grad_norm, error_if_nonfinite=True)
            optimizer.step()
            totals["policy_loss"] += policy_loss.item()
            totals["value_loss"] += value_loss.item()
            totals["entropy"] += entropy.item()
            totals["approx_kl"] += ((ratio - 1) - log_ratio).mean().item()
            totals["grad_norm"] += float(grad_norm)
            updates += 1
    return {key: value / updates for key, value in totals.items()}
