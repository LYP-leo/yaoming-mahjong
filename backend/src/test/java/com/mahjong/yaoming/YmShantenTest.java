package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class YmShantenTest {
    private static int[] counts(String codes) {
        int[] result = new int[27];
        for (String code : codes.split("\\s+")) if (!code.isBlank()) result[YmTiles.CODES.indexOf(code)]++;
        return result;
    }
    private static List<Tile> tiles(int[] counts) {
        List<Tile> result = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) for (int copy = 0; copy < counts[i]; copy++) result.add(YmTiles.of(YmTiles.CODES.get(i)));
        return result;
    }
    private static List<int[]> sequences() {
        List<int[]> runs = new ArrayList<>();
        for (String code : List.of("W1 W5 W9", "B1 B2 B3", "B2 B3 B4", "B3 B4 B5", "B4 B5 B6", "B5 B6 B7", "B6 B7 B8", "B7 B8 B9",
            "D1 D2 D3", "D2 D3 D4", "D3 D4 D5", "D4 D5 D6", "D5 D6 D7", "D6 D7 D8", "D7 D8 D9")) runs.add(counts(code));
        return runs;
    }

    @Test void standardHandWaitAndCompleteHaveTheConventionalDistances() {
        YmShanten engine = new YmShanten();
        assertEquals(0, engine.calculate(counts("W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 H3"), 0));
        assertEquals(-1, engine.calculate(counts("W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3"), 0));
        assertEquals(1, engine.calculate(counts("W1 W5 B1 B2 B3 D4 D5 D6 B7 B8 H3 H3 H5"), 0));
        assertEquals(8, engine.calculate(new int[27], 0));
    }

    @ParameterizedTest @ValueSource(strings = { "W1 W5", "W1 W9", "W5 W9" })
    void everyDistinctTwoManCombinationIsAValid159Tatsu(String man) {
        assertEquals(0, YmShanten.distance(tiles(counts(man + " B1 B2 B3 D4 D5 D6 H1 H1 H1 H5 H5")), 0));
    }

    @Test void honorsArePairsOrTripletsAndNeverOrdinarySequences() {
        assertEquals(0, new YmShanten().calculate(counts("B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3 H5 H5"), 0));
        assertEquals(1, new YmShanten().calculate(counts("B1 B2 B3 D4 D5 D6 B7 B8 B9 H1 H2 H5 H5"), 0));
    }

    @ParameterizedTest @ValueSource(ints = { 0, 1, 2, 3, 4 })
    void declaredGroupsCountAsThreeEquivalentTilesAndNeedNoRegrouping(int meldCount) {
        String[] groups = { "W1 W5 W9", "B1 B2 B3", "D4 D5 D6", "B7 B8 B9" };
        int[] hand = counts(String.join(" ", Arrays.copyOfRange(groups, meldCount, groups.length)) + " H3");
        YmShanten engine = new YmShanten();
        assertEquals(0, engine.calculate(hand, meldCount));
        hand[YmTiles.CODES.indexOf("H3")]++;
        assertEquals(-1, engine.calculate(hand, meldCount));
    }

    @Test void windDragonAcceptsAHonorHeadWithoutSpendingItsRequiredSingletonTwice() {
        YmShanten engine = new YmShanten();
        assertEquals(0, engine.calculate(counts("H1 H2 H3 H5 H6 H7 W1 W5 W9 B1 B2 B3 H3"), 0));
        assertEquals(-1, engine.calculate(counts("H1 H2 H3 H5 H6 H7 W1 W5 W9 B1 B2 B3 H3 H3"), 0));
        assertEquals(1, engine.calculate(counts("H1 H2 H3 H5 H6 H7 W1 W5 W9 B1 B2 H3 D8"), 0));
    }

    @Test void windDragonAllowsTwoIdenticalSequencesAndANumberedHeadUsingFourCopies() {
        int[] hand = counts("H1 H2 H3 H5 H6 H7 B1 B2 B3 B1 B2 B3 B2 B2");
        assertEquals(4, hand[YmTiles.CODES.indexOf("B2")]);
        assertEquals(-1, new YmShanten().calculate(hand, 0));
        assertTrue(YmScoring.evaluate(tiles(hand), List.of(), 1, 1, true, false).eligible());
    }

    @Test void anyDeclaredMeldIncludingAConcealedKongDisablesWindDragon() {
        int[] incomplete = counts("H1 H2 H3 H5 H6 H7 B1 B2 D4 D5");
        YmShanten engine = new YmShanten();
        assertEquals(3, engine.calculate(incomplete, 0));
        assertEquals(4, engine.calculate(incomplete, 1));
    }

    @Test void everyOneOfThe3240WindDragonTargetsAgreesWithTheOfficialStructureEvaluator() {
        List<int[]> runs = sequences();
        YmShanten engine = new YmShanten();
        int checked = 0;
        for (int first = 0; first < runs.size(); first++) for (int second = first; second < runs.size(); second++)
            for (int pair = 0; pair < 27; pair++) {
                int[] hand = counts("H1 H2 H3 H5 H6 H7");
                for (int i = 0; i < hand.length; i++) hand[i] += runs.get(first)[i] + runs.get(second)[i];
                hand[pair] += 2;
                assertEquals(-1, engine.calculate(hand, 0), "Wind target " + first + "/" + second + "/" + pair);
                assertTrue(YmScoring.evaluate(tiles(hand), List.of(), 1, 1, true, false).eligible());
                hand[pair]--;
                assertEquals(0, engine.calculate(hand, 0));
                checked++;
            }
        assertEquals(3240, checked);
    }

    @Test void sevenPairsHasNoSpecialBranchButAnOverlappingStandardHandStillWins() {
        YmShanten engine = new YmShanten();
        assertEquals(3, engine.calculate(counts("B1 B1 B3 B3 B5 B5 B7 B7 B9 B9 D2 D2 H1 H1"), 0));
        assertEquals(1, engine.calculate(counts("W1 W1 W5 W5 W9 W9 B2 B2 B5 B5 D2 D2 H1 H1"), 0));
        assertEquals(-1, engine.calculate(counts("B1 B1 B2 B2 B3 B3 B4 B4 B5 B5 B6 B6 B7 B7"), 0));
    }

    @Test void structureDistanceDoesNotPretendTheFourFanThresholdWasMet() {
        int[] hand = counts("W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3");
        assertEquals(-1, new YmShanten().calculate(hand, 0));
        assertFalse(YmScoring.evaluate(tiles(hand), List.of(), 1, 1, true, false).eligible());
    }

    @Test void fifthCopiesIllegalKindsAndMalformedCountArraysAreRejected() {
        YmShanten engine = new YmShanten();
        int[] impossible = counts("B1 B1 B1 B1 B1 B2 B3 D4 D5 D6 H3 H3 H3 H5");
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(impossible, 0));
        int[] negative = new int[27]; negative[0] = -1;
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(negative, 0));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(null, 0));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(new int[26], 0));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(new int[28], 0));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(new int[27], -1));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(new int[27], 5));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(counts("B1 B2 B3"), 4));
        assertThrows(IllegalArgumentException.class, () -> YmShanten.distance(null, 0));
        assertThrows(IllegalArgumentException.class, () -> YmShanten.distance(List.of(new Tile("north", "HONORS", 4, "北")), 0));
    }

    @Test void inputCountsAndTilesAreUnchangedAndCacheKeysIncludeDeclaredMeldCount() {
        YmShanten engine = new YmShanten();
        int[] hand = counts("H1 H2 H3 H5 H6 H7 B1 B2 D4 D5");
        int[] before = hand.clone();
        assertEquals(3, engine.calculate(hand, 0));
        assertEquals(4, engine.calculate(hand, 1));
        assertArrayEquals(before, hand);
        List<Tile> input = new ArrayList<>(tiles(hand));
        List<Tile> copy = List.copyOf(input);
        assertEquals(3, YmShanten.distance(input, 0));
        assertEquals(copy, input);
        hand[0]++;
        int changed = engine.calculate(hand, 0);
        assertEquals(new YmShanten().calculate(hand, 0), changed);
        hand[0]--;
        assertEquals(3, engine.calculate(hand, 0));
    }

    @Test void normalGeneratedCompletionsAndTheirThirteenTileWaitsAgreeWithScoring() {
        List<int[]> possible = sequences();
        for (int i = 0; i < 27; i++) { int[] pung = new int[27]; pung[i] = 3; possible.add(pung); }
        Random random = new Random(260909);
        YmShanten engine = new YmShanten();
        int checked = 0;
        while (checked < 150) {
            int[] hand = new int[27];
            for (int group = 0; group < 4; group++) {
                int[] chosen = possible.get(random.nextInt(possible.size()));
                for (int i = 0; i < 27; i++) hand[i] += chosen[i];
            }
            hand[random.nextInt(27)] += 2;
            if (Arrays.stream(hand).anyMatch(count -> count > 4)) continue;
            assertTrue(YmScoring.evaluate(tiles(hand), List.of(), 1, 1, false, false).validStructure());
            assertEquals(-1, engine.calculate(hand, 0));
            for (int i = 0; i < 27; i++) if (hand[i] > 0) {
                hand[i]--;
                assertEquals(0, engine.calculate(hand, 0));
                hand[i]++;
            }
            checked++;
        }
    }

    @Test void all27CandidatesAcrossEveryDiscardFitAConservativeDecisionBudget() {
        int[] hand = counts("W1 W5 B2 B3 B4 B5 D3 D4 D5 H1 H2 H5 H6 H7");
        int[] before = hand.clone();
        YmShanten engine = new YmShanten();
        long started = System.nanoTime();
        int[] evaluated = { 0 };
        assertTimeout(Duration.ofSeconds(3), () -> {
            for (int discard = 0; discard < 27; discard++) if (hand[discard] > 0) {
                hand[discard]--;
                int distance = engine.calculate(hand, 0);
                for (int draw = 0; draw < 27; draw++) if (hand[draw] < 4) {
                    hand[draw]++;
                    int after = engine.calculate(hand, 0);
                    assertTrue(after >= distance - 1 && after <= distance);
                    hand[draw]--; evaluated[0]++;
                }
                hand[discard]++;
            }
        });
        assertEquals(378, evaluated[0]);
        assertArrayEquals(before, hand);
        System.out.printf("YmShanten 378 candidate draws: %.2f ms%n", (System.nanoTime() - started) / 1_000_000.0);
    }

    @Test void cachesArePerInstanceBoundedAndCanEvictWithoutChangingAnswers() throws ReflectiveOperationException {
        YmShanten engine = new YmShanten();
        Random random = new Random(909);
        for (int sample = 0; sample < 4500; sample++) {
            int[] hand = new int[27];
            for (int tile = 0; tile < 13; tile++) {
                int index;
                do { index = random.nextInt(27); } while (hand[index] == 4);
                hand[index]++;
            }
            engine.calculate(hand, 0);
        }
        for (String name : List.of("hands", "groups")) {
            Field field = YmShanten.class.getDeclaredField(name);
            assertFalse(Modifier.isStatic(field.getModifiers()));
            field.setAccessible(true);
            int size = ((Map<?, ?>) field.get(engine)).size();
            assertTrue(size <= (name.equals("hands") ? 4096 : 32768));
            assertTrue(((Map<?, ?>) field.get(new YmShanten())).isEmpty());
        }
        int[] probe = counts("W1 W9 B1 B2 B3 D4 D5 D6 H1 H1 H1 H5 H5");
        assertEquals(new YmShanten().calculate(probe, 0), engine.calculate(probe, 0));
    }
}
