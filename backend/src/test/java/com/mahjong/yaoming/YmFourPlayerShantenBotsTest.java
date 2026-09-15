package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.mahjong.yaoming.YmRules.*;
import static org.junit.jupiter.api.Assertions.*;

class YmFourPlayerShantenBotsTest {
    private static List<Tile> tiles(String codes) {
        return new ArrayList<>(Arrays.stream(codes.split("\\s+")).filter(code -> !code.isBlank()).map(YmTiles::of).toList());
    }
    private static int[] counts(List<Tile> hand, YmRules rules) {
        int[] counts = new int[rules.codes().size()];
        hand.forEach(tile -> counts[rules.codes().indexOf(YmTiles.code(tile))]++);
        return counts;
    }
    private static int[] counts(String hand) { return counts(tiles(hand), FOUR_PLAYER); }
    private static YmRoom room(String hand) {
        YmRoom room = new YmRoom(); room.id = "four-player-bot-local"; room.ruleId = FOUR_PLAYER.id();
        room.round = 1; room.phase = YmRoom.Phase.NEED_DISCARD; room.currentSeat = 0;
        room.wall = new ArrayList<>(YmTiles.deck(FOUR_PLAYER));
        for (int seat = 0; seat < 4; seat++) {
            YmRoom.Player player = new YmRoom.Player(); player.id = "p" + seat; player.seat = seat; player.name = "玩家" + seat;
            room.players.add(player);
        }
        room.seat(0).hand = tiles(hand); return room;
    }
    private static List<YmViews.Action> discards(YmRoom room) {
        return room.seat(0).hand.stream().map(tile -> new YmViews.Action("DISCARD", "出牌", List.of(tile.id()))).toList();
    }
    private static String chosenCode(YmRoom room, List<YmViews.Action> actions) {
        YmViews.Action selected = YmBots.choose(YmBotObservation.capture(room, room.seat(0)), actions);
        assertNotNull(selected); assertTrue(actions.contains(selected)); assertEquals("DISCARD", selected.type());
        return YmTiles.code(room.seat(0).hand.stream().filter(tile -> selected.tileIds().contains(tile.id())).findFirst().orElseThrow());
    }

    @Test void normalManAndNorthUse34KindStructureWithoutImporting159OrWindDragon() {
        YmShanten engine = new YmShanten(FOUR_PLAYER);
        assertEquals(0, engine.calculate(counts("W1 W2 B1 B2 B3 D4 D5 D6 B7 B8 B9 H4 H4"), 0));
        assertEquals(-1, engine.calculate(counts("W1 W2 W3 B1 B2 B3 D4 D5 D6 B7 B8 B9 H4 H4"), 0));
        assertNotEquals(-1, engine.calculate(counts("W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 H4 H4"), 0));
        assertNotEquals(-1, engine.calculate(counts("H1 H2 H3 H5 H6 H7 B2 B3 B4 D4 D5 D6 B8 B8"), 0));
        assertThrows(IllegalArgumentException.class, () -> new YmShanten().calculate(counts("H4"), 0));
        assertThrows(IllegalArgumentException.class, () -> YmShanten.distance(tiles("H4"), 0));
        assertDoesNotThrow(() -> YmShanten.distance(tiles("H4"), 0, FOUR_PLAYER));
    }

    @Test void all720SpecialCompletionsAndTheir13TileWaitsHaveExactStructuralDistanceAndThreeFanSelfDraw() {
        YmShanten engine = new YmShanten(FOUR_PLAYER);
        int checked = 0;
        for (Set<String> template : YmScoring.unconnectedTemplates()) {
            List<String> codes = template.stream().sorted().toList();
            for (int first = 0; first < 16; first++) for (int second = first + 1; second < 16; second++) {
                List<Tile> hand = new ArrayList<>();
                for (int index = 0; index < 16; index++) if (index != first && index != second) hand.add(YmTiles.of(codes.get(index)));
                int[] counts = counts(hand, FOUR_PLAYER), before = counts.clone();
                assertEquals(-1, engine.calculate(counts, 0));
                assertTrue(engine.calculateStandard(counts, 0) > 0, "These singleton patterns have no standard winning route");
                assertTrue(YmScoring.evaluate(hand, List.of(), 4, 2, true, false, FOUR_PLAYER).eligible());
                assertFalse(YmScoring.evaluate(hand, List.of(), 4, 2, false, false, FOUR_PLAYER).eligible());
                for (int index = 0; index < counts.length; index++) if (counts[index] > 0) {
                    counts[index]--; assertEquals(0, engine.calculate(counts, 0)); counts[index]++;
                }
                assertArrayEquals(before, counts); checked++;
            }
        }
        assertEquals(720, checked);
    }

    @Test void declaredMeldsExcludeSpecialRouteAndTheCacheSeparatesStandardFromAllStructures() {
        YmShanten engine = new YmShanten(FOUR_PLAYER);
        int[] hand = counts("W1 W4 W7 B2 B5 B8 D3 D6 D9 H1 H2 H3 H4 H5");
        int standard = engine.calculateStandard(hand, 0);
        for (int repeat = 0; repeat < 5; repeat++) {
            assertEquals(-1, engine.calculate(hand, 0)); assertEquals(standard, engine.calculateStandard(hand, 0));
        }
        hand[0]--; hand[3]--; hand[6]--;
        assertEquals(engine.calculateStandard(hand, 1), engine.calculate(hand, 1));
        assertNotEquals(-1, engine.calculate(hand, 1));
    }

    @Test void all34DigitsAreExactlyRecoverableFromTheCacheKeyWithoutOverflow() throws Exception {
        YmShanten engine = new YmShanten(FOUR_PLAYER);
        int[] hand = counts("W1 W1 W1 W1 W2 W2 W2 W2 D9 D9 H4 H4 H7 H7");
        engine.calculate(hand, 0);
        Field cacheField = YmShanten.class.getDeclaredField("hands"); cacheField.setAccessible(true);
        Object key = ((Map<?, ?>) cacheField.get(engine)).keySet().iterator().next();
        Field firstField = key.getClass().getDeclaredField("first"), secondField = key.getClass().getDeclaredField("second");
        firstField.setAccessible(true); secondField.setAccessible(true);
        long first = firstField.getLong(key), second = secondField.getLong(key);
        assertTrue(first >= 0); assertTrue(second >= 0);
        int[] restored = new int[34];
        for (int index = 16; index >= 0; index--) { restored[index] = (int) (first % 5); first /= 5; }
        for (int index = 33; index >= 17; index--) { restored[index] = (int) (second % 5); second /= 5; }
        assertEquals(0, first); assertEquals(0, second); assertArrayEquals(hand, restored);
    }

    @Test void generatedNormalHandsAndTheirWaitsAgreeWithScoringAcrossBothRuleScopedCaches() {
        Random random = new Random(120934);
        YmShanten three = new YmShanten(), four = new YmShanten(FOUR_PLAYER);
        List<List<String>> groups = new ArrayList<>();
        for (String code : FOUR_PLAYER.codes()) groups.add(List.of(code, code, code));
        for (char suit : new char[] {'W', 'B', 'D'}) for (int rank = 1; rank <= 7; rank++)
            groups.add(List.of("" + suit + rank, "" + suit + (rank + 1), "" + suit + (rank + 2)));
        int checked = 0;
        while (checked < 200) {
            List<Tile> hand = new ArrayList<>();
            for (int group = 0; group < 4; group++) groups.get(random.nextInt(groups.size())).forEach(code -> hand.add(YmTiles.of(code)));
            String pair = FOUR_PLAYER.codes().get(random.nextInt(34)); hand.add(YmTiles.of(pair)); hand.add(YmTiles.of(pair));
            int[] counts = counts(hand, FOUR_PLAYER);
            if (Arrays.stream(counts).anyMatch(count -> count > 4)) continue;
            assertTrue(YmScoring.evaluate(hand, List.of(), 4, 2, false, false, FOUR_PLAYER).validStructure());
            assertEquals(-1, four.calculate(counts, 0));
            for (int index = 0; index < 34; index++) if (counts[index] > 0) {
                counts[index]--; assertEquals(0, four.calculate(counts, 0)); counts[index]++;
            }
            int[] oldHand = counts(tiles("H1 H2 H3 H5 H6 H7 W1 W5 W9 B1 B2 B3 H3 H3"), THREE_PLAYER);
            assertEquals(-1, three.calculate(oldHand, 0)); checked++;
        }
    }

    @Test void candidateSearchFitsTheExistingDecisionBudgetAndLeavesInputUntouched() {
        YmShanten engine = new YmShanten(FOUR_PLAYER);
        int[] hand = counts("W1 W2 W4 W5 B2 B3 B4 B5 D3 D4 D5 H4 H6 H7"), before = hand.clone();
        int[] checked = {0};
        assertTimeout(Duration.ofSeconds(3), () -> {
            for (int discard = 0; discard < 34; discard++) if (hand[discard] > 0) {
                hand[discard]--; int distance = engine.calculate(hand, 0);
                for (int draw = 0; draw < 34; draw++) {
                    hand[draw]++; int next = engine.calculate(hand, 0);
                    assertTrue(next >= distance - 1 && next <= distance); hand[draw]--; checked[0]++;
                }
                hand[discard]++;
            }
        });
        assertEquals(476, checked[0]); assertArrayEquals(before, hand);
    }

    @Test void fourPlayerCachesRemainBoundedAndMalformedPhysicalArraysFailClosed() throws Exception {
        YmShanten engine = new YmShanten(FOUR_PLAYER);
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(new int[27], 0));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(new int[33], 0));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(new int[35], 0));
        int[] fifth = new int[34]; fifth[33] = 5;
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(fifth, 0));
        int[] negative = new int[34]; negative[27] = -1;
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(negative, 0));
        int[] tooMany = counts("W1 W1 W1 W1 W2 W2 W2 W2 W3 W3 W3 W3 H4 H4 H4");
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(tooMany, 0));
        Random random = new Random(341209);
        for (int sample = 0; sample < 4200; sample++) {
            int[] hand = new int[34];
            for (int tile = 0; tile < 13; tile++) {
                int index;
                do { index = random.nextInt(34); } while (hand[index] == 4);
                hand[index]++;
            }
            engine.calculate(hand, 0);
        }
        for (String name : List.of("hands", "groups")) {
            Field field = YmShanten.class.getDeclaredField(name); field.setAccessible(true);
            int size = ((Map<?, ?>) field.get(engine)).size();
            assertTrue(size <= (name.equals("hands") ? 4096 : 32768));
            assertTrue(((Map<?, ?>) field.get(new YmShanten(FOUR_PLAYER))).isEmpty());
        }
        int[] probe = counts("W1 W2 W3 B1 B2 B3 D4 D5 D6 B7 B8 B9 H4 H4");
        assertEquals(new YmShanten(FOUR_PLAYER).calculate(probe, 0), engine.calculate(probe, 0));
    }

    @Test void fourthSeatObservationIncludesOnlyPublicInformationAndUsesTheFourPlayerRoundBoundary() {
        YmRoom room = room("W2 H4"); room.round = 4;
        assertEquals(1, YmBotObservation.capture(room, room.seat(3)).roundWind());
        room.round = 5;
        YmBotObservation north = YmBotObservation.capture(room, room.seat(3));
        assertEquals(FOUR_PLAYER, north.rules()); assertEquals(4, north.seatWind()); assertEquals(2, north.roundWind());
        assertEquals(3, north.opponents().size()); assertEquals(136, north.wallCount());
        YmBotObservation before = YmBotObservation.capture(room, room.seat(0));
        for (YmRoom.Player other : room.players) if (other.seat != 0) other.hand = new AbstractList<>() {
            @Override public Tile get(int index) { throw new AssertionError("Private hand read"); }
            @Override public int size() { throw new AssertionError("Private hand size read"); }
        };
        room.wall = new AbstractList<>() {
            @Override public Tile get(int index) { throw new AssertionError("Wall read"); }
            @Override public int size() { return 136; }
        };
        assertEquals(before, YmBotObservation.capture(room, room.seat(0)));
    }

    @Test void botsKeepOrdinaryManWaitsAndUseFourPlayerMenqingValues() {
        YmRoom room = room("W1 W2 B1 B2 B3 D4 D5 D6 H1 H1 H1 H5 H5 B9");
        assertEquals("B9", chosenCode(room, discards(room)));
        List<Tile> after = room.seat(0).hand.stream().filter(tile -> !YmTiles.code(tile).equals("B9")).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        after.add(YmTiles.of("W3"));
        YmScoring.Evaluation win = YmScoring.evaluate(after, List.of(), 1, 1, true, false, FOUR_PLAYER);
        assertEquals(6, win.rawFan());
        assertEquals(Map.of("MENQING", 1, "BUQIUREN", 1, "FANPAI", 2, "WUMENQI", 2),
            win.items().stream().collect(java.util.stream.Collectors.toMap(YmScoring.Fan::id, YmScoring.Fan::fan)));
        YmRoom flat = room("B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D3 D4 B5 B5");
        assertEquals(3, YmBots.fanPotential(YmBotObservation.capture(flat, flat.seat(0)), flat.seat(0).hand, List.of()), .000001);
        flat.ruleId = THREE_PLAYER.id();
        assertEquals(4, YmBots.fanPotential(YmBotObservation.capture(flat, flat.seat(0)), flat.seat(0).hand, List.of()), .000001);
    }

    @Test void botPursuesAZeroShantenUnconnectedRouteWithThreeFanSelfDrawButNotTwoFanRon() {
        YmRoom room = room("W1 W4 W7 B2 B5 B8 D3 D6 D9 H1 H2 H3 H4 W2");
        List<Tile> afterDiscardingW2 = room.seat(0).hand.stream().filter(tile -> !YmTiles.code(tile).equals("W2")).toList();
        YmShanten shanten = new YmShanten(FOUR_PLAYER);
        assertEquals(0, shanten.calculate(counts(afterDiscardingW2, FOUR_PLAYER), 0));
        assertTrue(shanten.calculateStandard(counts(afterDiscardingW2, FOUR_PLAYER), 0) > 0);
        List<String> selfWaits = new ArrayList<>();
        for (String code : FOUR_PLAYER.codes()) {
            List<Tile> completed = new ArrayList<>(afterDiscardingW2); completed.add(YmTiles.of(code));
            YmScoring.Evaluation self = YmScoring.evaluate(completed, List.of(), 1, 1, true, false, FOUR_PLAYER);
            if (self.eligible()) { selfWaits.add(code); assertEquals(3, self.rawFan()); }
            assertFalse(YmScoring.evaluate(completed, List.of(), 1, 1, false, false, FOUR_PLAYER).eligible());
        }
        assertEquals(List.of("H5", "H6", "H7"), selfWaits);
        List<YmViews.Action> actions = discards(room).stream().filter(action -> room.seat(0).hand.stream()
            .anyMatch(tile -> action.tileIds().contains(tile.id()) && Set.of("W2", "H1").contains(YmTiles.code(tile)))).toList();
        assertEquals("W2", chosenCode(room, actions), "Keep the now-playable special wait instead of ordinary W1-W2 progress");
        assertEquals("W2", chosenCode(room, discards(room)));
    }

    @Test void botKeepsAnOrdinaryThreeFanSelfDrawWaitInsteadOfATwoFanHonorPair() {
        YmRoom room = room("W1 W2 W3 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 H7");
        assertEquals("H7", chosenCode(room, discards(room)));
        List<Tile> hand = new ArrayList<>(room.seat(0).hand.stream()
            .filter(tile -> !YmTiles.code(tile).equals("H7")).toList());
        hand.add(YmTiles.of("D2"));
        YmScoring.Evaluation self = YmScoring.evaluate(hand, List.of(), 1, 1, true, false, FOUR_PLAYER);
        assertTrue(self.eligible()); assertEquals(3, self.rawFan());
        assertFalse(YmScoring.evaluate(hand, List.of(), 1, 1, false, false, FOUR_PLAYER).eligible());
    }

    @Test void fourthOpponentsHiddenStateAndActionOrderCannotChangeTheBotsDecisionOrMutateTheRoom() {
        YmRoom room = room("W1 W2 B1 B2 B3 D4 D5 D6 H1 H1 H1 H5 H5 B9");
        List<YmViews.Action> legal = new ArrayList<>(discards(room));
        String before = chosenCode(room, legal);
        List<Tile> original = List.copyOf(room.seat(0).hand);
        room.seat(3).hand = tiles("W3 W3 W3 H4 H4 H4"); room.seat(3).token = "not-visible-to-bots";
        Collections.reverse(room.wall); Collections.reverse(legal);
        assertEquals(before, chosenCode(room, legal)); assertEquals(original, room.seat(0).hand);
        assertEquals(1, room.version); assertEquals(YmRoom.Phase.NEED_DISCARD, room.phase);
    }
}
