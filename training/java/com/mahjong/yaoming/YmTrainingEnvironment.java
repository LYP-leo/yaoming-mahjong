package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import java.util.*;
import static com.mahjong.yaoming.YmViews.Action;
import static com.mahjong.yaoming.YmRoom.Phase.*;

/** One seedable complete match. No timers, web server, storage, or automatic strategy moves. */
public final class YmTrainingEnvironment {
    private static final int MAX_ENGINE_STEPS = 100_000;
    final YmRoom room = new YmRoom();
    final YmEngine engine;
    private final Random policyRandom;
    private final int env;
    private final int[] wins;
    private long clock;
    private int actor = -1, engineSteps, decisions, handsCompleted, countedRound;
    private LinkedHashMap<Integer, Action> choices = new LinkedHashMap<>();

    public YmTrainingEnvironment(int env, String ruleId, long seed) {
        if (env < 0) throw new IllegalArgumentException("env must be non-negative");
        if (!Set.of("yaoming-3p", "yaoming-4p").contains(ruleId)) throw new IllegalArgumentException("rule must be yaoming-3p or yaoming-4p");
        YmRules rules = YmRules.fromId(ruleId);
        this.env = env; engine = new YmEngine(new Random(seed)); policyRandom = new Random(seed ^ 0x632be59bd9b4e019L);
        wins = new int[rules.playerCount()];
        room.id = "offline-" + env; room.name = "Offline training"; room.ruleId = rules.id(); room.appliedMinimumFan = rules.minimumFan();
        room.lastActivity = 0;
        for (int index = 0; index < rules.playerCount(); index++) {
            YmRoom.Player player = new YmRoom.Player(); player.id = "p" + index; player.name = "Player " + index;
            player.seat = index; player.lastSeen = 0; room.players.add(player);
        }
        advanceMechanical();
    }

    public Map<String, Object> state() {
        boolean done = room.phase == MATCH_END;
        boolean[] mask = new boolean[YmTrainingEncoding.ACTION_SIZE];
        if (!done) choices.keySet().forEach(code -> mask[code] = true);
        int[] scores = new int[room.rules().playerCount()];
        for (int seat = 0; seat < scores.length; seat++) scores[seat] = room.seat(seat).score;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true); result.put("env", env); result.put("done", done); result.put("actor", done ? -1 : actor);
        result.put("obs", YmTrainingEncoding.observation(room, done ? -1 : actor)); result.put("mask", mask);
        result.put("scores", scores); result.put("round", room.round); result.put("playerCount", scores.length);
        result.put("handsCompleted", handsCompleted); result.put("wins", wins.clone());
        return result;
    }

    public Map<String, Object> step(int code) {
        Action action = choices.get(code);
        if (room.phase == MATCH_END) throw new IllegalStateException("Match already ended; reset this env");
        if (action == null) throw new IllegalArgumentException("Illegal action " + code);
        perform(room.seat(actor), action); decisions++;
        advanceMechanical(); return state();
    }

    public Map<String, Object> step(String policy) { return step(baseline(policy)); }

    /** Reads a legal strategy label without playing it; random consumes only its policy RNG. */
    public int baseline(String policy) {
        if (room.phase == MATCH_END || choices.isEmpty()) throw new IllegalStateException("No current decision");
        YmRoom.Player player = room.seat(actor);
        List<Action> legal = List.copyOf(choices.values());
        Action action = switch (policy) {
            case "random" -> legal.get(policyRandom.nextInt(legal.size()));
            case "heuristic" -> YmBots.choose(YmBotObservation.capture(room, player), legal);
            case "tsumogiri" -> YmTrustee.choose(player.hand, player.lastDrawnId, player.ready, legal);
            default -> throw new IllegalArgumentException("Unknown policy " + policy);
        };
        if (action == null) throw new IllegalStateException("Baseline returned no legal action");
        int code = YmTrainingEncoding.actionIndex(room, player, action);
        if (!choices.containsKey(code)) throw new IllegalStateException("Baseline returned illegal action");
        return code;
    }

    private void advanceMechanical() {
        for (int automatic = 0; automatic < MAX_ENGINE_STEPS; automatic++) {
            countSettlement();
            if (room.phase == MATCH_END) { actor = -1; choices.clear(); return; }
            if (room.phase == WAITING) {
                YmRoom.Player player = room.players.stream().filter(p -> !p.ready).findFirst().orElseThrow();
                perform(player, Action.of("READY", "")); continue;
            }
            if (room.phase == NEED_DRAW) { perform(room.seat(room.currentSeat), Action.of("DRAW", "")); continue; }
            if (room.phase == HAND_END) {
                Optional<YmRoom.Player> unacknowledged = room.players.stream().filter(p -> !p.acknowledged).findFirst();
                if (unacknowledged.isPresent()) perform(unacknowledged.get(), Action.of("ACK", ""));
                else engine.startAcknowledgedHand(room, ++clock);
                continue;
            }
            if (room.phase == NEED_DISCARD) actor = room.currentSeat;
            else if (room.phase == REACTION) {
                actor = -1;
                // Sequential submission does not resolve until all claims arrive. The actor's
                // observation never reads offered seats or previous players' pending choices.
                for (int distance = 1; distance < room.rules().playerCount(); distance++) {
                    int seat = (room.window.fromSeat + distance) % room.rules().playerCount();
                    if (room.window.offered.containsKey(seat) && !room.window.responses.containsKey(seat)) { actor = seat; break; }
                }
                if (actor < 0) throw new IllegalStateException("Reaction has no pending player");
            } else throw new IllegalStateException("Unexpected phase " + room.phase);
            choices = YmTrainingEncoding.actions(room, room.seat(actor), engine.gameActions(room, room.seat(actor)));
            return;
        }
        throw new IllegalStateException("Mechanical advance exceeded safety bound");
    }

    private void perform(YmRoom.Player player, Action action) {
        if (engineSteps >= MAX_ENGINE_STEPS) throw new IllegalStateException("Match exceeded safety bound; not a terminal result");
        engine.perform(room, player, action.type(), action.tileIds(), ++clock); engineSteps++;
    }

    private void countSettlement() {
        if ((room.phase == HAND_END || room.phase == MATCH_END) && room.result != null && countedRound != room.round) {
            countedRound = room.round; handsCompleted++;
            if (room.result.winnerId() != null) room.players.stream().filter(p -> p.id.equals(room.result.winnerId()))
                .findFirst().ifPresent(player -> wins[player.seat]++);
        }
    }

    /** Expensive assertions, used by tests rather than every training step. */
    void verifyConservation() {
        if (room.players.stream().mapToInt(p -> p.score).sum() != 10 * room.rules().playerCount()) throw new AssertionError("Score conservation");
        if (room.players.stream().anyMatch(p -> p.score < 0)) throw new AssertionError("Negative score");
        List<Tile> tiles = new ArrayList<>(room.wall);
        for (YmRoom.Player player : room.players) {
            tiles.addAll(player.hand); tiles.addAll(player.discards); player.melds.forEach(meld -> tiles.addAll(meld.tiles()));
        }
        if (tiles.size() != room.rules().tileCount() || tiles.stream().map(Tile::id).distinct().count() != tiles.size()) throw new AssertionError("Physical tile conservation");
        Map<String, Integer> counts = new HashMap<>(); tiles.forEach(t -> counts.merge(YmTiles.code(t), 1, Integer::sum));
        if (!counts.keySet().equals(new HashSet<>(room.rules().codes())) || counts.values().stream().anyMatch(n -> n != 4)) throw new AssertionError("Tile-type conservation");
    }

    int actor() { return actor; }
    int decisions() { return decisions; }
}
