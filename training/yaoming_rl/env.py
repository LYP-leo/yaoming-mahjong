"""Transitions always connect decisions of the same learning seat.

Opponent turns, automatic draws, acknowledgements and intervening settlements
belong to that transition. Terminal states are never fed to a policy.
"""

from __future__ import annotations

from typing import Callable


class LearnerVecEnv:
    def __init__(self, simulator, *, rule: str, num_envs: int, seed: int,
                 opponent: str = "heuristic", opponent_actions: Callable | None = None,
                 reward_scale: float = 10.0, initial_seats: list[int] | None = None):
        if rule not in ("yaoming-3p", "yaoming-4p") or num_envs < 1 or reward_scale <= 0:
            raise ValueError("invalid rule, number of environments or reward scale")
        if opponent not in ("selfplay", "heuristic", "random", "tsumogiri"):
            raise ValueError("unknown opponent")
        if opponent == "selfplay" and opponent_actions is None:
            raise ValueError("selfplay needs a frozen opponent policy")
        self.simulator, self.rule, self.num_envs, self.seed = simulator, rule, num_envs, seed
        self.player_count = 3 if rule == "yaoming-3p" else 4
        self.seats = list(initial_seats) if initial_seats is not None else [i % self.player_count for i in range(num_envs)]
        if len(self.seats) != num_envs or any(not 0 <= seat < self.player_count for seat in self.seats):
            raise ValueError("invalid learning seats")
        self.opponent, self.opponent_actions = opponent, opponent_actions
        self.reward_scale = reward_scale
        self.episodes = [0] * num_envs
        self.states: list[dict] = []
        self.episode_seeds = [0] * num_envs
        self.skipped_zero_decision_episodes = 0

    def _reset_requests(self, indexes):
        requests = []
        for i in indexes:
            value = self.seed + self.episodes[i] * self.num_envs + i
            self.episode_seeds[i] = value
            requests.append({"cmd": "reset", "env": i, "rule": self.rule, "seed": value})
        return requests

    def reset(self) -> list[dict]:
        self.states = self.simulator.batch(self._reset_requests(range(self.num_envs)))
        self._advance_opponents(range(self.num_envs))
        self._skip_zero_decision_episodes(range(self.num_envs))
        return self.states

    def _skip_zero_decision_episodes(self, indexes):
        # A match can end before this seat first acts. There is no learning
        # transition for it: record its count, rotate seats, and do not attach
        # its score change to the previous episode's final transition.
        for _ in range(1000):
            finished = [i for i in indexes if self.states[i]["done"]]
            if not finished:
                return
            self.skipped_zero_decision_episodes += len(finished)
            for i in finished:
                self.episodes[i] += 1
                self.seats[i] = (self.seats[i] + 1) % self.player_count
            for i, state in zip(finished, self.simulator.batch(self._reset_requests(finished))):
                self.states[i] = state
            self._advance_opponents(finished)
        raise RuntimeError("1000 consecutive zero-decision episodes; check simulator progress")

    def _advance_opponents(self, indexes):
        # Safety guard catches simulator/protocol stalls instead of hanging training.
        for _ in range(10000):
            active = [i for i in indexes if not self.states[i]["done"] and self.states[i]["actor"] != self.seats[i]]
            if not active:
                return
            if self.opponent == "selfplay":
                actions = self.opponent_actions([self.states[i] for i in active])
                requests = [{"cmd": "step", "env": i, "action": action} for i, action in zip(active, actions)]
                if len(actions) != len(active):
                    raise RuntimeError("opponent returned the wrong action count")
            else:
                requests = [{"cmd": "step", "env": i, "policy": self.opponent} for i in active]
            for i, state in zip(active, self.simulator.batch(requests)):
                self.states[i] = state
        raise RuntimeError("opponent advancement exceeded 10000 decisions; check simulator progress")

    def step(self, actions: list[int]):
        if len(actions) != self.num_envs:
            raise ValueError("one action per environment is required")
        previous_scores = [state["scores"][self.seats[i]] for i, state in enumerate(self.states)]
        for i, action in enumerate(actions):
            state = self.states[i]
            if state["done"] or state["actor"] != self.seats[i] or not 0 <= action < len(state["mask"]) or not state["mask"][action]:
                raise ValueError("learning action is illegal or belongs to another seat")
        self.states = self.simulator.batch([{"cmd": "step", "env": i, "action": action} for i, action in enumerate(actions)])
        self._advance_opponents(range(self.num_envs))
        rewards, dones, infos, reset_indexes = [], [], [], []
        for i, state in enumerate(self.states):
            seat = self.seats[i]
            rewards.append((state["scores"][seat] - previous_scores[i]) / self.reward_scale)
            dones.append(bool(state["done"]))
            info = None
            if state["done"]:
                scores = state["scores"]
                # Ties receive their average occupied rank; no seat-order tie advantage.
                rank = 1 + sum(score > scores[seat] for score in scores) + 0.5 * (sum(score == scores[seat] for score in scores) - 1)
                info = {"seat": seat, "seed": self.episode_seeds[i], "score": scores[seat], "scores": list(scores),
                        "rank": rank, "wins": state.get("wins", [0] * self.player_count)[seat],
                        "hands": state.get("handsCompleted", 0), "terminal_state": state}
                self.episodes[i] += 1
                self.seats[i] = (seat + 1) % self.player_count
                reset_indexes.append(i)
            infos.append(info)
        if reset_indexes:
            for i, state in zip(reset_indexes, self.simulator.batch(self._reset_requests(reset_indexes))):
                self.states[i] = state
            self._advance_opponents(reset_indexes)
            self._skip_zero_decision_episodes(reset_indexes)
        return self.states, rewards, dones, infos
