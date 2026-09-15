package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import java.util.List;
import java.util.Map;

public final class YmViews {
    private YmViews() {}
    public record Identity(String roomId, String playerId, String token) {}
    public record Summary(String id, String name, String status, int players, int capacity, String ruleId, String ruleName) {
        public Summary {
            YmRules rules = YmRules.fromId(ruleId);
            ruleId = rules.id(); ruleName = rules.displayName(); capacity = rules.playerCount();
        }
        public Summary(String id, String name, String status, int players, int capacity) {
            this(id, name, status, players, capacity, null, null);
        }
    }
    public record Action(String type, String label, List<String> tileIds) {
        public Action { tileIds = List.copyOf(tileIds); }
        public static Action of(String type, String label) { return new Action(type, label, List.of()); }
    }
    public record Event(long sequence, String text) {}
    public record Dice(List<Integer> opening, List<Integer> breaking, int openingSeat, int breakStack) {}
    public record LastDiscard(Tile tile, int fromSeat, boolean claimed, String kind) {
        /** Historical snapshots have no reliable discard provenance. */
        public LastDiscard(Tile tile, int fromSeat, boolean claimed) { this(tile, fromSeat, claimed, null); }
    }
    public record Payment(String fromId, String toId, int amount, int requested) {}
    public record Score(String playerId, String name, int score, int delta, int rank) {}
    public record Hand(String playerId, List<Tile> hand, List<YmScoring.Meld> melds) {}
    public record Result(boolean draw, boolean matchOver, String title, String winnerId, Tile winningTile,
                         int rawFan, int fan, List<YmScoring.Fan> items, List<Payment> payments,
                         List<Score> scores, List<Hand> hands, String reason) {}
    public record PlayerView(String id, String name, int seat, String wind, int score, boolean bot,
                             boolean ready, boolean online, boolean acknowledged, boolean trustee,
                             List<Tile> hand, int handSize, List<Tile> discards, List<YmScoring.Meld> melds,
                             List<String> discardedCodes, List<String> passedCodes, String drawnTileId, String trusteeReason,
                             Map<String, String> discardKinds) {
        public PlayerView { discardKinds = discardKinds == null ? Map.of() : Map.copyOf(discardKinds); }
    }
    public record RoomView(String id, String name, long version, String status, int round, String roundLabel,
                           int dealerSeat, int currentSeat, int wallCount, String message, String meId,
                           List<PlayerView> players, List<Action> actions, Result result, List<Event> events,
                           Dice dice, String deadlineAt, String winHint, String serverTime, String deadlineKind, LastDiscard lastDiscard,
                           String ruleId, String ruleName, int capacity) {
        public RoomView {
            YmRules rules = YmRules.fromId(ruleId);
            ruleId = rules.id(); ruleName = rules.displayName(); capacity = rules.playerCount();
        }
        public RoomView(String id, String name, long version, String status, int round, String roundLabel,
                        int dealerSeat, int currentSeat, int wallCount, String message, String meId,
                        List<PlayerView> players, List<Action> actions, Result result, List<Event> events,
                        Dice dice, String deadlineAt, String winHint, String serverTime, String deadlineKind, LastDiscard lastDiscard) {
            this(id, name, version, status, round, roundLabel, dealerSeat, currentSeat, wallCount, message, meId,
                    players, actions, result, events, dice, deadlineAt, winHint, serverTime, deadlineKind, lastDiscard, null, null, 3);
        }
    }
    public record Rules(String name, String version, String description, List<String> notes,
                        List<YmScoring.Fan> fans, List<Tile> tiles, String id, int playerCount, int tileCount, int totalRounds) {
        public Rules {
            YmRules rules = YmRules.fromId(id);
            id = rules.id(); playerCount = rules.playerCount(); tileCount = rules.tileCount(); totalRounds = rules.totalRounds();
        }
        public Rules(String name, String version, String description, List<String> notes, List<YmScoring.Fan> fans, List<Tile> tiles) {
            this(name, version, description, notes, fans, tiles, null, 3, 108, 6);
        }
    }
}
