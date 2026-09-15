from __future__ import annotations

import copy

import pytest
import torch

from yaoming_rl.checkpoint import load_checkpoint, restore_random_states, save_checkpoint, verify_engine
from yaoming_rl.env import LearnerVecEnv
from yaoming_rl.learning import generalized_advantage, ppo_update
from yaoming_rl.model import Policy, resolve_device
from yaoming_rl.train import DEFAULTS, SafeInterrupt, parser, resolve_config


@pytest.fixture(autouse=True)
def small_cpu_pool():
    old = torch.get_num_threads()
    torch.set_num_threads(1)
    yield
    torch.set_num_threads(old)


def state(actor=0, scores=None, done=False):
    return {"env": 0, "actor": -1 if done else actor, "scores": scores or [10, 10, 10],
            "done": done, "obs": [0.0] * 6, "mask": [not done, False, False],
            "wins": [1, 0, 0] if done else [0, 0, 0], "handsCompleted": 1 if done else 0}


class ScriptedSimulator:
    def __init__(self, states):
        self.states = iter(states)
        self.requests = []

    def batch(self, requests):
        self.requests.extend(requests)
        return [copy.deepcopy(next(self.states)) for _ in requests]


def test_illegal_action_probability_is_exactly_zero():
    model = Policy(6, 3, 8)
    observations = torch.randn(64, 6)
    masks = torch.tensor([[True, False, True]] * 64)
    distribution, _ = model.distribution(observations, masks)
    assert torch.equal(distribution.probs[:, 1], torch.zeros(64))
    assert not (distribution.sample((100,)) == 1).any()
    assert torch.allclose(distribution.probs.sum(-1), torch.ones(64))


def test_all_false_terminal_mask_is_rejected():
    with pytest.raises(ValueError, match="no legal actions"):
        Policy(6, 3, 8).distribution(torch.zeros(1, 6), torch.zeros(1, 3, dtype=torch.bool))


def test_mask_shape_is_checked():
    with pytest.raises(ValueError, match="shape"):
        Policy(6, 3, 8).distribution(torch.zeros(1, 6), torch.ones(1, 4, dtype=torch.bool))


def test_gae_does_not_bootstrap_across_terminal_or_mix_episodes():
    reward = torch.tensor([[1.0], [2.0], [3.0]])
    done = torch.tensor([[False], [True], [False]])
    value = torch.tensor([[0.5], [0.7], [0.9]])
    advantage, returns = generalized_advantage(reward, done, value, torch.tensor([1.1]), gamma=1.0, gae_lambda=1.0)
    assert torch.allclose(advantage, torch.tensor([[2.5], [1.3], [3.2]]))
    assert torch.allclose(returns, torch.tensor([[3.0], [2.0], [4.1]]))


def test_terminal_last_step_ignores_reset_state_value():
    advantage, returns = generalized_advantage(torch.tensor([[2.0]]), torch.tensor([[True]]), torch.tensor([[1.0]]), torch.tensor([999.0]))
    assert advantage.item() == 1.0
    assert returns.item() == 2.0


def test_gae_lambda_zero_is_one_step_td():
    advantage, _ = generalized_advantage(torch.tensor([[2.0], [3.0]]), torch.tensor([[False], [False]]),
                                         torch.tensor([[1.0], [4.0]]), torch.tensor([5.0]), gamma=0.5, gae_lambda=0.0)
    assert torch.allclose(advantage, torch.tensor([[3.0], [1.5]]))


def test_reward_includes_opponents_and_returns_same_learning_seat():
    simulator = ScriptedSimulator([state(0), state(1), state(2, [8, 12, 10]), state(0, [20, 0, 10])])
    env = LearnerVecEnv(simulator, rule="yaoming-3p", num_envs=1, seed=15)
    env.reset()
    states, rewards, dones, infos = env.step([0])
    assert states[0]["actor"] == 0
    assert rewards == [1.0]
    assert dones == [False] and infos == [None]
    assert [request.get("policy") for request in simulator.requests[2:]] == ["heuristic", "heuristic"]


def test_terminal_reward_is_taken_before_reset_and_seat_rotation():
    simulator = ScriptedSimulator([state(), state(scores=[25, -5, 10], done=True), state(1, [100, 100, 100])])
    env = LearnerVecEnv(simulator, rule="yaoming-3p", num_envs=1, seed=20)
    env.reset()
    states, rewards, dones, infos = env.step([0])
    assert rewards == [1.5] and dones == [True]
    assert infos[0]["seat"] == 0 and infos[0]["score"] == 25 and infos[0]["seed"] == 20
    assert env.seats == [1] and states[0]["actor"] == 1
    assert simulator.requests[-1]["seed"] == 21


def test_zero_decision_initial_episode_is_skipped_not_crashed():
    simulator = ScriptedSimulator([state(done=True), state(1)])
    env = LearnerVecEnv(simulator, rule="yaoming-3p", num_envs=1, seed=20)
    assert env.reset()[0]["actor"] == 1
    assert env.skipped_zero_decision_episodes == 1
    assert env.episode_seeds == [21]


def test_zero_decision_reset_episode_cannot_leak_its_reward():
    simulator = ScriptedSimulator([state(), state(scores=[20, -1, 11], done=True),
                                   state(scores=[10, -50, 70], done=True), state(2)])
    env = LearnerVecEnv(simulator, rule="yaoming-3p", num_envs=1, seed=20)
    env.reset()
    states, rewards, dones, infos = env.step([0])
    assert rewards == [1.0] and dones == [True]
    assert infos[0]["score"] == 20
    assert env.skipped_zero_decision_episodes == 1
    assert states[0]["actor"] == 2 and env.seats == [2]


def test_learning_seat_must_own_action():
    simulator = ScriptedSimulator([state()])
    env = LearnerVecEnv(simulator, rule="yaoming-3p", num_envs=1, seed=20)
    env.reset()
    with pytest.raises(ValueError, match="illegal"):
        env.step([1])


def test_selfplay_opponents_use_callback_and_never_sample_terminal():
    simulator = ScriptedSimulator([state(), state(1), state(2), state()])
    sampled = []

    def policy(states):
        sampled.extend(s["actor"] for s in states)
        assert all(not s["done"] for s in states)
        return [0] * len(states)

    env = LearnerVecEnv(simulator, rule="yaoming-3p", num_envs=1, seed=20, opponent="selfplay", opponent_actions=policy)
    env.reset()
    env.step([0])
    assert sampled == [1, 2]


def test_ppo_finite_gradients_and_immutable_behavior_log_probability():
    model = Policy(6, 3, 16)
    optimizer = torch.optim.Adam(model.parameters(), lr=0.001)
    observations = torch.randn(32, 6)
    masks = torch.tensor([[True, False, True]] * 31 + [[False, True, False]])
    with torch.no_grad():
        distribution, values = model.distribution(observations, masks)
        actions = distribution.sample()
        logp = distribution.log_prob(actions)
    old_logp = logp.clone()
    old_masks = masks.clone()
    advantages = torch.randn(32)
    data = observations, masks, actions, logp, values, advantages, values + advantages
    metrics = ppo_update(model, optimizer, data, device=torch.device("cpu"), epochs=2, minibatch=7)
    assert torch.equal(logp, old_logp) and torch.equal(masks, old_masks)
    assert all(torch.isfinite(torch.tensor(value)) for value in metrics.values())
    assert all(parameter.grad is None or torch.isfinite(parameter.grad).all() for parameter in model.parameters())


def checkpoint_fixture(tmp_path):
    model, opponent = Policy(6, 3, 8), Policy(6, 3, 8)
    optimizer = torch.optim.Adam(model.parameters())
    config = {**DEFAULTS, "hidden": 8, "observation_size": 6, "action_size": 3}
    path = tmp_path / "latest.pt"
    save_checkpoint(path, model=model, opponent=opponent, optimizer=optimizer, config=config,
                    steps=128, updates=2, engine_hash="abc")
    return path, model, opponent


def test_checkpoint_portable_roundtrip_and_frozen_opponent(tmp_path):
    path, model, opponent = checkpoint_fixture(tmp_path)
    checkpoint = load_checkpoint(path)
    assert checkpoint["steps"] == 128 and checkpoint["updates"] == 2
    assert all(torch.equal(value, checkpoint["model"][name]) for name, value in model.state_dict().items())
    assert all(torch.equal(value, checkpoint["opponent"][name]) for name, value in opponent.state_dict().items())
    assert not (tmp_path / "latest.pt.tmp").exists()
    restore_random_states(checkpoint, torch.device("cpu"))
    expected = torch.rand(4)
    restore_random_states(checkpoint, torch.device("cpu"))
    assert torch.equal(expected, torch.rand(4))


def test_engine_fingerprint_and_dimensions_are_checked(tmp_path):
    checkpoint = load_checkpoint(checkpoint_fixture(tmp_path)[0])
    verify_engine(checkpoint, {"engineHash": "abc", "observationSize": 6, "actionSize": 3})
    with pytest.raises(ValueError, match="fingerprint"):
        verify_engine(checkpoint, {"engineHash": "changed", "observationSize": 6, "actionSize": 3})
    with pytest.raises(ValueError, match="dimensions"):
        verify_engine(checkpoint, {"engineHash": "abc", "observationSize": 7, "actionSize": 3})


def test_resume_keeps_saved_configuration_and_rejects_conflicts(tmp_path):
    checkpoint = load_checkpoint(checkpoint_fixture(tmp_path)[0])
    assert resolve_config(parser().parse_args([]), checkpoint)["hidden"] == 8
    with pytest.raises(ValueError, match="conflicts"):
        resolve_config(parser().parse_args(["--hidden", "64"]), checkpoint)
    with pytest.raises(ValueError, match="conflicts"):
        resolve_config(parser().parse_args(["--rule", "yaoming-4p"]), checkpoint)


@pytest.mark.parametrize("arguments", [["--num-envs", "0"], ["--steps", "-1"], ["--epochs", "0"], ["--learning-rate", "nan"], ["--gamma", "inf"]])
def test_argparse_rejects_invalid_boundaries(arguments):
    with pytest.raises(SystemExit):
        parser().parse_args(arguments)


@pytest.mark.parametrize("arguments", [["--gamma", "1.1"], ["--gae-lambda", "-0.1"], ["--clip", "0"], ["--learning-rate", "0"], ["--entropy-coef", "-1"]])
def test_config_rejects_invalid_ranges(arguments):
    with pytest.raises(ValueError):
        resolve_config(parser().parse_args(arguments))


def test_cuda_request_never_silently_falls_back(monkeypatch):
    monkeypatch.setattr(torch.cuda, "is_available", lambda: False)
    with pytest.raises(RuntimeError, match="CUDA was requested"):
        resolve_device("cuda")
    assert resolve_device("cpu").type == "cpu"


def test_imitation_checkpoint_requires_init_not_resume(tmp_path):
    checkpoint = load_checkpoint(checkpoint_fixture(tmp_path)[0])
    checkpoint["config"]["training_stage"] = "imitation"
    with pytest.raises(ValueError, match="--init"):
        resolve_config(parser().parse_args([]), checkpoint)


def test_first_interrupt_requests_boundary_and_second_interrupts():
    interrupt = SafeInterrupt()
    interrupt._handle(None, None)
    assert interrupt.requested
    with pytest.raises(KeyboardInterrupt):
        interrupt._handle(None, None)
