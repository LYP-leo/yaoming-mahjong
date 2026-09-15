package com.mahjong.yaoming;

import com.mahjong.domain.Tile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.mahjong.yaoming.YmRoom.Phase.*;

/** Immutable post-action snapshots. Authentication and completed-hand publication belong to the service. */
public final class YmReplay {
    public static final int MAX_FRAMES = 1024;
    private YmReplay() {}

    public record ReplayPlayer(String id, String name, int seat, int score, List<Tile> hand,
                               List<Tile> discards, List<YmScoring.Meld> melds, String drawnTileId,
                               Map<String, String> discardKinds) {
        public ReplayPlayer {
            hand = List.copyOf(hand); discards = List.copyOf(discards); melds = List.copyOf(melds);
            discardKinds = discardKinds == null ? Map.of() : Map.copyOf(discardKinds);
        }
    }
    public record Frame(int index, long timestamp, String type, int actorSeat, String message,
                        String status, int currentSeat, int wallCount, int dealerSeat,
                        List<ReplayPlayer> players, YmViews.LastDiscard lastDiscard,
                        YmViews.Dice dice, YmViews.Result result) {
        public Frame {
            players = List.copyOf(players); dice = copyDice(dice); result = copyResult(result);
        }
    }
    public record HandRecord(String roomId, String roomName, int round, String roundLabel,
                             long startedAt, Long completedAt, boolean complete, boolean incomplete,
                             List<Frame> frames, YmViews.Result result, String ruleId, String ruleName, int capacity) {
        public HandRecord {
            frames = List.copyOf(frames); result = copyResult(result);
            YmRules rules = YmRules.fromId(ruleId);
            ruleId = rules.id(); ruleName = rules.displayName(); capacity = rules.playerCount();
        }
        public HandRecord(String roomId, String roomName, int round, String roundLabel,
                          long startedAt, Long completedAt, boolean complete, boolean incomplete,
                          List<Frame> frames, YmViews.Result result) {
            this(roomId, roomName, round, roundLabel, startedAt, completedAt, complete, incomplete, frames, result, null, null, 3);
        }
    }

    /** Call only after a real fresh deal. No old terminal hand is fabricated into a replay. */
    public static void start(YmRoom room, long now) {
        room.activeReplay = new HandRecord(room.id, room.name, room.round, YmEngine.roundLabel(room),
                now, null, false, false, List.of(frame(room, 0, "START", -1, room.message, now)), null,
                room.rules().id(), room.rules().displayName(), room.rules().playerCount());
    }

    /** A legacy running snapshot can be resumed, but cannot honestly claim to contain its earlier actions. */
    public static boolean ensureActive(YmRoom room, long now) {
        if (room.phase != NEED_DRAW && room.phase != NEED_DISCARD && room.phase != REACTION) return false;
        if (room.activeReplay != null && room.activeReplay.round() == room.round) return false;
        room.activeReplay = new HandRecord(room.id, room.name, room.round, YmEngine.roundLabel(room),
                now, null, false, true, List.of(frame(room, 0, "RESUME", -1,
                "从本次更新后的牌桌状态开始记录；本局此前过程缺失", now)), null,
                room.rules().id(), room.rules().displayName(), room.rules().playerCount());
        return true;
    }

    /** Must run once after an accepted gameplay command or automatic reaction resolution. */
    public static void append(YmRoom room, String type, int actorSeat, String message, long now) {
        HandRecord prior = room.activeReplay;
        if (prior == null || prior.round() != room.round || prior.complete()) return;
        boolean finished = (room.phase == HAND_END || room.phase == MATCH_END) && room.result != null;
        List<Frame> frames = new ArrayList<>(prior.frames());
        boolean incomplete = prior.incomplete();
        // Reserve the final slot for the settlement, even when a corrupt/abnormal hand exceeds the limit.
        if (frames.size() < MAX_FRAMES - 1 || finished && frames.size() < MAX_FRAMES) {
            frames.add(frame(room, frames.size(), type, actorSeat, message, now));
        } else {
            incomplete = true;
            if (finished) frames.set(frames.size() - 1, frame(room, frames.size() - 1, type, actorSeat, message, now));
        }
        HandRecord updated = new HandRecord(prior.roomId(), prior.roomName(), prior.round(), prior.roundLabel(),
                prior.startedAt(), finished ? now : null, finished, incomplete, frames, finished ? room.result : null,
                prior.ruleId(), prior.ruleName(), prior.capacity());
        if (finished) {
            if (room.replayHands == null) room.replayHands = new ArrayList<>();
            // All transitions are serialized; this guard also protects restored snapshots from duplicate publication.
            if (room.replayHands.stream().noneMatch(hand -> hand.round() == updated.round())) room.replayHands.add(updated);
            room.activeReplay = null;
        } else room.activeReplay = updated;
    }

    private static Frame frame(YmRoom room, int index, String type, int actorSeat, String message, long now) {
        List<ReplayPlayer> players = room.players.stream().sorted(Comparator.comparingInt(p -> p.seat))
                .map(p -> new ReplayPlayer(p.id, p.name, p.seat, p.score, p.hand, p.discards, p.melds, p.lastDrawnId, p.discardKinds)).toList();
        return new Frame(index, now, type, actorSeat, message, room.phase.name(), room.currentSeat,
                room.wall.size(), room.dealerSeat, players, room.lastDiscard, room.dice, room.result);
    }

    private static YmViews.Dice copyDice(YmViews.Dice dice) {
        return dice == null ? null : new YmViews.Dice(List.copyOf(dice.opening()), List.copyOf(dice.breaking()), dice.openingSeat(), dice.breakStack());
    }
    private static YmViews.Result copyResult(YmViews.Result result) {
        if (result == null) return null;
        List<YmViews.Hand> hands = result.hands().stream()
                .map(hand -> new YmViews.Hand(hand.playerId(), List.copyOf(hand.hand()), List.copyOf(hand.melds()))).toList();
        return new YmViews.Result(result.draw(), result.matchOver(), result.title(), result.winnerId(), result.winningTile(),
                result.rawFan(), result.fan(), List.copyOf(result.items()), List.copyOf(result.payments()),
                List.copyOf(result.scores()), hands, result.reason());
    }
}
