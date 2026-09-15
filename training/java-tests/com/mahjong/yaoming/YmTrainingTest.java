package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static com.mahjong.yaoming.YmViews.Action;
import static com.mahjong.yaoming.YmRoom.Phase.*;

/** Standalone assertion tests; no test-framework dependency. Throws => non-zero exit. */
public final class YmTrainingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static int checks;

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        encodingAllActions();
        actualEngineMeldActions();
        reactionPriorityAndPrivacy();
        for (YmRules rule : YmRules.values()) {
            reproducibilityAndInvalid(rule);
            for (int seed = 0; seed < 3; seed++) completeMatch(rule, seed + 300, "random");
            completeMatch(rule, 777, "tsumogiri");
            completeMatch(rule, 1001, "heuristic");
        }
        System.out.println("PASS YmTrainingTest checks=" + checks + " elapsedSeconds=" + (System.nanoTime() - start) / 1e9);
    }

    private static void completeMatch(YmRules rule, long seed, String policy) {
        YmTrainingEnvironment environment = new YmTrainingEnvironment(0, rule.id(), seed);
        Map<String, Object> state = environment.state();
        assertState(environment, state);
        int steps = 0;
        while (!(boolean) state.get("done")) {
            int action = environment.baseline(policy);
            check(((boolean[]) state.get("mask"))[action], "baseline legal");
            state = environment.step(action);
            assertState(environment, state);
            if (++steps > 20_000) throw new AssertionError("Match failed to terminate");
        }
        check((int) state.get("actor") == -1, "terminal actor");
        check(Arrays.equals((float[]) state.get("obs"), new float[1152]), "terminal observation empty");
        check(Arrays.equals((boolean[]) state.get("mask"), new boolean[194]), "terminal mask empty");
        check((int) state.get("handsCompleted") > 0 && (int) state.get("handsCompleted") <= rule.totalRounds(), "completed hands bounds");
        check(Arrays.stream((int[]) state.get("wins")).sum() <= (int) state.get("handsCompleted"), "winner count bounded");
        int[] scores = (int[]) state.get("scores");
        for (int seat = 0; seat < scores.length; seat++) check(scores[seat] == environment.room.seat(seat).score, "final score seat mapping");
        if (policy.equals("tsumogiri")) {
            check(Arrays.stream((int[]) state.get("wins")).sum() == 0, "tsumogiri never auto-wins");
            check((int) state.get("handsCompleted") == rule.totalRounds(), "all drawn rounds completed");
        }
        System.out.println("completeMatch rule=" + rule.id() + " policy=" + policy + " seed=" + seed + " steps=" + steps
            + " hands=" + state.get("handsCompleted") + " scores=" + Arrays.toString(scores));
    }

    private static void assertState(YmTrainingEnvironment environment, Map<String, Object> state) {
        environment.verifyConservation();
        float[] observation = (float[]) state.get("obs");
        check(observation.length == 1152 && ((boolean[]) state.get("mask")).length == 194, "protocol dimensions");
        for (float value : observation) check(Float.isFinite(value), "finite observation");
        if (!(boolean) state.get("done")) {
            check(environment.room.phase == NEED_DISCARD || environment.room.phase == REACTION, "only genuine policy decisions");
            check(environment.actor() >= 0 && environment.actor() < environment.room.rules().playerCount(), "actor seat range");
            check(any((boolean[]) state.get("mask")), "nonempty legal mask");
            if (environment.room.rules().playerCount() == 3) for (int index = 904; index < 1152; index++) check(observation[index] == 0, "missing fourth seat zero");
        }
    }

    private static void reproducibilityAndInvalid(YmRules rule) throws Exception {
        YmTrainingEnvironment first = new YmTrainingEnvironment(0, rule.id(), 987654321);
        YmTrainingEnvironment second = new YmTrainingEnvironment(0, rule.id(), 987654321);
        check(serial(first.state()).equals(serial(second.state())), "seed reproducible reset");
        String before = serial(first.state());
        try { first.step(999); throw new AssertionError("Illegal action accepted"); } catch (IllegalArgumentException expected) {}
        check(before.equals(serial(first.state())), "illegal action leaves state unchanged");
        for (int step = 0; step < 80 && !(boolean) first.state().get("done"); step++) {
            int one = first.baseline("random"), two = second.baseline("random");
            check(one == two, "policy RNG repeatable");
            check(serial(first.step(one)).equals(serial(second.step(two))), "seed reproducible transition");
        }
        YmTrainingEnvironment privacy = new YmTrainingEnvironment(0, rule.id(), 71);
        int actor = privacy.actor();
        float[] original = YmTrainingEncoding.observation(privacy.room, actor);
        YmRoom.Player other = privacy.room.seat((actor + 1) % rule.playerCount());
        Tile concealed = other.hand.set(0, privacy.room.wall.getFirst());
        privacy.room.wall.set(0, concealed);
        Collections.reverse(privacy.room.wall);
        check(Arrays.equals(original, YmTrainingEncoding.observation(privacy.room, actor)), "opponent concealed faces and wall order private");
        privacy.verifyConservation();
    }

    private static void encodingAllActions() {
        for (YmRules rule : YmRules.values()) {
            for (String code : rule.codes()) {
                YmRoom room = fixture(rule, Map.of(0, List.of(code, code, code, code)));
                YmRoom.Player player = room.seat(0);
                List<Tile> four = player.hand.stream().filter(t -> YmTiles.code(t).equals(code)).toList();
                player.lastDrawnId = four.getLast().id();
                List<Action> discards = four.stream().map(t -> new Action("DISCARD", "", List.of(t.id()))).toList();
                var encoded = YmTrainingEncoding.actions(room, player, discards);
                check(encoded.size() == 1 && encoded.get(YmTrainingEncoding.tileIndex(code)).tileIds().getFirst().equals(player.lastDrawnId), "duplicate discard prefers drawn entity");
                for (String kind : List.of("PONG", "OPEN_KONG", "CONCEALED_KONG", "ADDED_KONG")) {
                    int size = switch (kind) { case "PONG" -> 2; case "OPEN_KONG" -> 3; case "CONCEALED_KONG" -> 4; default -> 1; };
                    int base = switch (kind) { case "PONG" -> 36; case "OPEN_KONG" -> 70; case "CONCEALED_KONG" -> 104; default -> 138; };
                    Action action = new Action(kind, "", four.stream().limit(size).map(Tile::id).toList());
                    check(YmTrainingEncoding.actionIndex(room, player, action) == base + YmTrainingEncoding.tileIndex(code), "meld action maps every tile");
                }
            }
            List<List<String>> sequences = new ArrayList<>();
            for (char suit : new char[] {'W', 'B', 'D'}) {
                if (rule == YmRules.THREE_PLAYER && suit == 'W') { sequences.add(List.of("W1", "W5", "W9")); continue; }
                for (int rank = 1; rank <= 7; rank++) sequences.add(List.of("" + suit + rank, "" + suit + (rank + 1), "" + suit + (rank + 2)));
            }
            Set<Integer> actualCodes = new HashSet<>();
            for (List<String> sequence : sequences) for (int missing = 0; missing < 3; missing++) {
                List<String> own = new ArrayList<>(sequence); String claim = own.remove(missing);
                YmRoom room = fixture(rule, Map.of(0, List.of(claim), 1, own));
                room.window = new YmRoom.Window(); room.window.tile = room.seat(0).hand.stream().filter(t -> YmTiles.code(t).equals(claim)).findFirst().orElseThrow(); room.window.fromSeat = 0;
                List<String> ids = new ArrayList<>();
                for (String code : own) ids.add(room.seat(1).hand.stream().filter(t -> YmTiles.code(t).equals(code)).findFirst().orElseThrow().id());
                int mapped = YmTrainingEncoding.actionIndex(room, room.seat(1), new Action("CHI", "", ids));
                int start = YmTrainingEncoding.tileIndex(sequence.getFirst());
                int expected = rule == YmRules.THREE_PLAYER && claim.charAt(0) == 'W' ? 193 : 172 + start / 9 * 7 + start % 9;
                check(mapped == expected, "chi independent of claimed position"); actualCodes.add(mapped);
            }
            check(actualCodes.size() == (rule == YmRules.THREE_PLAYER ? 15 : 21), "all sequence actions covered");
        }
        YmTrainingEnvironment drawn = new YmTrainingEnvironment(0, "yaoming-3p", 55);
        YmRoom.Player player = drawn.room.seat(drawn.actor());
        String drawId = player.lastDrawnId;
        check(drawId != null, "reset stops after mechanical draw");
        int expected = YmTrainingEncoding.tileIndex(player.hand.stream().filter(t -> t.id().equals(drawId)).findFirst().orElseThrow());
        drawn.step(expected);
        check("TSUMOGIRI".equals(player.discardKinds.get(drawId)), "discard type marker preserved");
    }

    private static void reactionPriorityAndPrivacy() {
        // Seat1 can pong; seat2 can win a pure-bamboo closed hand. Resolution must wait
        // for seat2 and apply win priority, independent of first submission.
        List<String> winner = List.of("B1", "B1", "B1", "B2", "B2", "B2", "B3", "B3", "B3", "B4", "B6", "B7", "B7");
        YmRoom room = fixture(YmRules.FOUR_PLAYER, Map.of(0, List.of("B5"), 1, List.of("B5", "B5"), 2, winner));
        YmEngine engine = new YmEngine(new Random(2));
        Tile tile = room.seat(0).hand.stream().filter(t -> YmTiles.code(t).equals("B5")).findFirst().orElseThrow();
        engine.perform(room, room.seat(0), "DISCARD", List.of(tile.id()), 1);
        check(room.phase == REACTION, "actual engine opens response");
        Action pong = engine.gameActions(room, room.seat(1)).stream().filter(a -> a.type().equals("PONG")).findFirst().orElseThrow();
        Action win = engine.gameActions(room, room.seat(2)).stream().filter(a -> a.type().equals("WIN")).findFirst().orElseThrow();
        float[] observation = YmTrainingEncoding.observation(room, 2);
        engine.perform(room, room.seat(1), pong.type(), pong.tileIds(), 2);
        check(room.phase == REACTION, "pong does not prematurely resolve pending win");
        check(Arrays.equals(observation, YmTrainingEncoding.observation(room, 2)), "pending other's claim private");
        for (int seat : List.copyOf(room.window.offered.keySet())) if (seat != 2 && !room.window.responses.containsKey(seat)) engine.perform(room, room.seat(seat), "PASS", List.of(), 3);
        engine.perform(room, room.seat(2), win.type(), win.tileIds(), 4);
        check(room.result != null && room.result.winnerId().equals(room.seat(2).id), "winner beats earlier pong");
        check(room.seat(1).melds.isEmpty(), "losing pong never applied");
        check(room.players.stream().mapToInt(p -> p.score).sum() == 40, "priority fixture score conservation");
        check(room.seat(2).score > 10 && room.seat(0).score < 10, "real settlement transferred score");
    }

    private static void actualEngineMeldActions() {
        for (YmRules rule : YmRules.values()) {
            // One discard simultaneously offers every kind of ordinary chi around5,
            // pong and open kong. All options must survive the policy action encoding.
            YmRoom room = fixture(rule, Map.of(0, List.of("B5"), 1, List.of("B3", "B4", "B6", "B7", "B5", "B5", "B5")));
            YmEngine engine = new YmEngine(new Random(5));
            Tile discard = room.seat(0).hand.stream().filter(t -> YmTiles.code(t).equals("B5")).findFirst().orElseThrow();
            engine.perform(room, room.seat(0), "DISCARD", List.of(discard.id()), 1);
            List<Action> legal = engine.gameActions(room, room.seat(1));
            var encoded = YmTrainingEncoding.actions(room, room.seat(1), legal);
            check(encoded.containsKey(36 + 13) && encoded.containsKey(70 + 13), "real pong/open-kong offered");
            for (int start = 2; start <= 4; start++) check(encoded.containsKey(172 + 7 + start), "all simultaneous chi options encoded");
            check(encoded.containsKey(35), "pass remains a policy decision");
            Action open = encoded.get(70 + 13);
            engine.perform(room, room.seat(1), open.type(), open.tileIds(), 2);
            if (room.phase == REACTION) for (int seat : List.copyOf(room.window.offered.keySet()))
                if (!room.window.responses.containsKey(seat)) engine.perform(room, room.seat(seat), "PASS", List.of(), 3);
            check(room.seat(1).melds.size() == 1 && room.seat(1).melds.getFirst().type().equals("KONG"), "real open kong applied");
            check(room.seat(1).lastDrawnId != null && room.seat(1).afterKong, "kong replacement draw automatic inside real engine");

            for (String type : List.of("CONCEALED_KONG", "ADDED_KONG")) {
                YmRoom kong = fixture(rule, Map.of(0, List.of("D9", "D9", "D9", "D9")));
                YmRoom.Player player = kong.seat(0);
                List<Tile> four = player.hand.stream().filter(t -> YmTiles.code(t).equals("D9")).toList();
                if (type.equals("ADDED_KONG")) {
                    List<Tile> three = four.subList(0, 3); player.hand.removeAll(three);
                    player.melds.add(new YmScoring.Meld("PONG", three, 1, three.getFirst().id(), false));
                }
                player.lastDrawnId = four.getLast().id();
                var actions = YmTrainingEncoding.actions(kong, player, engine.gameActions(kong, player));
                int code = (type.equals("CONCEALED_KONG") ? 104 : 138) + 26;
                check(actions.containsKey(code), "real self kong offered " + type);
                Action action = actions.get(code); int wallBefore = kong.wall.size();
                engine.perform(kong, player, action.type(), action.tileIds(), 4);
                check(player.melds.getFirst().type().equals("KONG") && player.melds.getFirst().tiles().size() == 4, "real self kong four tiles");
                check(kong.wall.size() == wallBefore - 1 && player.afterKong && player.lastDrawnId != null, "self kong replacement draw");
                check(player.melds.getFirst().concealed() == type.equals("CONCEALED_KONG"), "concealed/open meld preserved");
            }
        }
    }

    /** Valid physical deck partition. Requested faces allocated before filling other seats. */
    private static YmRoom fixture(YmRules rule, Map<Integer, List<String>> requests) {
        YmRoom room = new YmRoom(); room.ruleId = rule.id(); room.phase = NEED_DISCARD; room.currentSeat = 0;
        List<Tile> remaining = new ArrayList<>(YmTiles.deck(rule));
        for (int seat = 0; seat < rule.playerCount(); seat++) {
            YmRoom.Player player = new YmRoom.Player(); player.seat = seat; player.id = "p" + seat; player.name = player.id; room.players.add(player);
            for (String code : requests.getOrDefault(seat, List.of())) {
                Tile tile = remaining.stream().filter(t -> YmTiles.code(t).equals(code)).findFirst().orElseThrow();
                remaining.remove(tile); player.hand.add(tile);
            }
        }
        for (YmRoom.Player player : room.players) while (player.hand.size() < (player.seat == 0 ? 14 : 13)) player.hand.add(remaining.removeLast());
        room.wall = remaining;
        return room;
    }

    private static String serial(Object object) throws Exception { return JSON.writeValueAsString(object); }
    private static boolean any(boolean[] values) { for (boolean value : values) if (value) return true; return false; }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
