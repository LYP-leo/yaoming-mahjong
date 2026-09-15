package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import com.mahjong.yaoming.YmScoring.Evaluation;
import com.mahjong.yaoming.YmScoring.Meld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class YmScoringTest {
    private static final String BASE = "W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3";
    private record Example(String fan, String concealed, List<Meld> melds, boolean selfDraw, boolean kongDiscard) {
        Evaluation evaluate() { return YmScoring.evaluate(tiles(concealed), melds, 1, 1, selfDraw, kongDiscard); }
    }
    private static List<Tile> tiles(String codes) {
        return Arrays.stream(codes.split("\\s+")).filter(code -> !code.isBlank()).map(YmTiles::of).toList();
    }
    private static Meld meld(String type, boolean closed, String codes) {
        return new Meld(type, tiles(codes), closed ? -1 : 2, null, closed);
    }
    private static Example sample(String id, String hand) { return new Example(id, hand, List.of(), false, false); }
    private static boolean has(Evaluation result, String id) { return result.items().stream().anyMatch(fan -> fan.id().equals(id)); }
    private static int value(Evaluation result, String id) {
        return result.items().stream().filter(fan -> fan.id().equals(id)).mapToInt(YmScoring.Fan::fan).sum();
    }
    private static Evaluation evaluate(String hand) { return YmScoring.evaluate(tiles(hand), List.of(), 1, 1, false, false); }

    static Stream<Example> allPositiveFans() {
        return Stream.of(
            sample("FANPAI", "W1 W5 W9 B2 B3 B4 D6 D7 D8 H5 H5 H5 H3 H3"),
            new Example("KONG", "W1 W5 W9 B1 B2 B3 D4 D5 D6 H3 H3", List.of(meld("KONG", true, "B9 B9 B9 B9")), false, false),
            sample("MENQING", BASE),
            sample("PENGPENG", "W1 W1 W1 B2 B2 B2 D3 D3 D3 B9 B9 B9 H2 H2"),
            new Example("SHIERLUOTAI", "H3 H3", List.of(meld("CHI", false, "W1 W5 W9"), meld("CHI", false, "B1 B2 B3"), meld("CHI", false, "D4 D5 D6"), meld("CHI", false, "B7 B8 B9")), false, false),
            new Example("BUQIUREN", BASE, List.of(), true, false),
            new Example("GANGSHANGPAO", BASE, List.of(), false, true),
            sample("PINGHE", "W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2"),
            sample("WUMENQI", "W1 W5 W9 B2 B3 B4 D7 D8 D9 H5 H5 H5 H1 H1"),
            sample("HUNYISE", "B1 B2 B3 B4 B5 B6 B7 B8 B9 H5 H5 H5 H3 H3"),
            sample("HUNQUANDAIYAO", "W1 W5 W9 B1 B2 B3 D7 D8 D9 H5 H5 H5 H1 H1"),
            sample("QINGYISE", "B1 B2 B3 B2 B3 B4 B5 B6 B7 B7 B8 B9 B5 B5"),
            sample("QINGQUANDAIYAO", "W1 W1 W1 B1 B2 B3 D7 D8 D9 B9 B9 B9 D1 D1"),
            sample("HUNSILIAN", "B1 B1 B1 D1 D2 D3 W1 W1 W1 H3 H3 H3 D2 D2"),
            new Example("FENGLONG", "H1 H2 H3 H5 H6 H7 B2 B3 B4 D4 D5 D6 B8 B8", List.of(), true, false),
            sample("QINGSILIAN", "B2 B3 B4 B2 B3 B4 D1 D1 D1 W1 W1 W1 D3 D3"),
            sample("QUANDAIWU", "W1 W5 W9 B3 B4 B5 B4 B5 B6 D5 D6 D7 D5 D5"),
            sample("JIUSHUQI", "W1 W1 W1 D9 D9 D9 B2 B2 B3 B4 B5 B6 B7 B8"),
            sample("ZIYISE", "H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6 H6"),
            sample("QINGSANLIAN", "B1 B2 B3 B1 B2 B3 D1 D1 D1 W1 W1 W1 D3 D3")
        );
    }

    @ParameterizedTest(name = "positive fan {0}")
    @MethodSource("allPositiveFans")
    void recognizesEveryDocumentedFan(Example example) {
        Evaluation result = example.evaluate();
        assertTrue(result.validStructure(), example.toString());
        assertTrue(has(result, example.fan()), () -> "Expected " + example.fan() + " but got " + result.items());
    }

    static Stream<Example> allNegativeFans() {
        return YmScoring.catalog().stream().map(fan -> fan.id().equals("MENQING")
            ? new Example(fan.id(), "B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3", List.of(meld("CHI", false, "W1 W5 W9")), true, false)
            : sample(fan.id(), BASE));
    }

    @ParameterizedTest(name = "negative fan {0}")
    @MethodSource("allNegativeFans")
    void rejectsEachFanWhenItsConditionIsAbsent(Example example) {
        Evaluation result = example.evaluate();
        assertTrue(result.validStructure());
        assertFalse(has(result, example.fan()), () -> "Unexpected " + example.fan() + " in " + result.items());
    }

    @Test void catalogHasExactlyTheTwentyDocumentedFans() {
        assertEquals(20, YmScoring.catalog().size());
        assertEquals(20, YmScoring.catalog().stream().map(YmScoring.Fan::id).distinct().count());
        assertEquals(YmScoring.catalog().stream().map(YmScoring.Fan::id).collect(Collectors.toSet()),
            allPositiveFans().map(Example::fan).collect(Collectors.toSet()));
    }

    @Test void physicalDeckHas108UniqueTilesAndFourOfEachLegalType() {
        List<Tile> deck = YmTiles.deck();
        assertEquals(108, deck.size());
        assertEquals(108, deck.stream().map(Tile::id).distinct().count());
        Map<String, Long> counts = deck.stream().map(YmTiles::code).collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertEquals(27, counts.size());
        assertTrue(counts.values().stream().allMatch(count -> count == 4));
        assertFalse(counts.containsKey("H4"));
        assertEquals(List.of("W1", "W5", "W9"), counts.keySet().stream().filter(code -> code.startsWith("W")).sorted().toList());
        assertTrue(deck.stream().noneMatch(Tile::red));
    }

    @Test void chiIncludes159ManAndEveryPhysicalChoiceButNeverMixedSuitsOrHonors() {
        List<Tile> hand = tiles("W1 W1 W9 B2 B3 B4 H1 H2");
        List<List<String>> man = YmTiles.chiOptions(hand, YmTiles.of("W5"));
        assertEquals(2, man.size());
        assertTrue(man.stream().allMatch(ids -> ids.contains(hand.get(2).id())));
        assertEquals(2, YmTiles.chiOptions(hand, YmTiles.of("B2")).getFirst().size());
        assertTrue(YmTiles.chiOptions(hand, YmTiles.of("H3")).isEmpty());
        assertTrue(YmTiles.chiOptions(tiles("W1 B5"), YmTiles.of("W9")).isEmpty());
        assertTrue(YmTiles.chiOptions(tiles("B1 B2"), YmTiles.of("B9")).isEmpty());
    }

    @Test void specialManSequenceFormsAStandardWinningHand() {
        assertTrue(evaluate(BASE).validStructure());
        assertFalse(evaluate("W1 W1 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3").validStructure());
    }

    @Test void windDragonNeedsSelfDrawOrKongDiscardToReachFourAndAddsNoOtherFans() {
        List<Tile> hand = tiles("H1 H2 H3 H5 H6 H7 W1 W5 W9 B1 B2 B3 H3 H3");
        Evaluation ron = YmScoring.evaluate(hand, List.of(), 3, 1, false, false);
        assertTrue(ron.validStructure());
        assertFalse(ron.eligible());
        assertEquals(3, ron.rawFan());
        assertEquals(List.of("FENGLONG"), ron.items().stream().map(YmScoring.Fan::id).toList());
        Evaluation self = YmScoring.evaluate(hand, List.of(), 3, 1, true, true);
        assertTrue(self.eligible());
        assertEquals(4, self.rawFan());
        assertEquals(List.of("FENGLONG", "BUQIUREN"), self.items().stream().map(YmScoring.Fan::id).toList());
        Evaluation kong = YmScoring.evaluate(hand, List.of(), 3, 1, false, true);
        assertEquals(4, kong.rawFan());
        assertEquals(List.of("FENGLONG", "GANGSHANGPAO"), kong.items().stream().map(YmScoring.Fan::id).toList());
    }

    @Test void windDragonCannotUseAnExposedSequenceAnAnkanOrTripletsInsteadOfSequences() {
        Evaluation open = YmScoring.evaluate(tiles("H1 H2 H3 H5 H6 H7 B1 B2 B3 H3 H3"),
            List.of(meld("CHI", false, "W1 W5 W9")), 1, 1, true, false);
        assertFalse(open.validStructure());
        Evaluation ankan = YmScoring.evaluate(tiles("H1 H2 H3 H5 H6 H7 B1 B2 B3 H3 H3"),
            List.of(meld("KONG", true, "W1 W1 W1 W1")), 1, 1, true, false);
        assertFalse(ankan.validStructure());
        assertFalse(evaluate("H1 H2 H3 H5 H6 H7 B1 B1 B1 D4 D5 D6 H3 H3").validStructure());
    }

    @Test void sevenPairsIsNotAWinningStructure() {
        assertFalse(evaluate("W1 W1 W5 W5 W9 W9 B2 B2 B5 B5 D2 D2 H1 H1").validStructure());
    }

    @Test void fourFanThresholdAndEightFanCapAreIndependentOfStructure() {
        Evaluation weak = evaluate(BASE);
        assertTrue(weak.validStructure());
        assertEquals(2, weak.rawFan());
        assertFalse(weak.eligible());
        Evaluation capped = YmScoring.evaluate(tiles("H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6 H6"),
            List.of(), 1, 1, true, false);
        assertEquals(9, capped.rawFan());
        assertEquals(8, capped.fan());
        assertTrue(capped.eligible());
    }

    @Test void allHonorsExcludesHonorTripletAllPungsMixedOutsideAndFullFlush() {
        Evaluation result = evaluate("H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6 H6");
        assertTrue(has(result, "ZIYISE"));
        for (String excluded : List.of("FANPAI", "PENGPENG", "HUNQUANDAIYAO", "HUNYISE", "QINGYISE")) assertFalse(has(result, excluded));
        assertEquals(8, result.rawFan());
    }

    @Test void pureOutsideUsesThreeAndExcludesMixedOutside() {
        Evaluation result = evaluate("W1 W1 W1 B1 B2 B3 D7 D8 D9 B9 B9 B9 D1 D1");
        assertEquals(3, value(result, "QINGQUANDAIYAO"));
        assertFalse(has(result, "HUNQUANDAIYAO"));
    }

    @Test void identicalSeatAndRoundWindsAddTwiceAndEachDragonAddsOnce() {
        Evaluation result = evaluate("B1 B2 B3 D4 D5 D6 H1 H1 H1 H5 H5 H5 W9 W9");
        assertEquals(3, value(result, "FANPAI"));
        Evaluation otherSeat = YmScoring.evaluate(tiles("B1 B2 B3 D4 D5 D6 H1 H1 H1 H5 H5 H5 W9 W9"), List.of(), 2, 1, false, false);
        assertEquals(2, value(otherSeat, "FANPAI"));
    }

    @Test void eachKongAddsOneAndConcealedKongsPreserveClosedSelfDraw() {
        Evaluation result = YmScoring.evaluate(tiles("D2 D3 D4 B6 B7 B8 D5 D5"),
            List.of(meld("KONG", true, "W1 W1 W1 W1"), meld("KONG", true, "H5 H5 H5 H5")), 1, 1, true, false);
        assertTrue(result.validStructure());
        assertEquals(2, value(result, "KONG"));
        assertTrue(has(result, "MENQING"));
        assertTrue(has(result, "BUQIUREN"));
    }

    @Test void concealedKongCountsTowardFourDeclaredMeldsAsWritten() {
        Evaluation result = YmScoring.evaluate(tiles("D5 D5"), List.of(
            meld("KONG", true, "W1 W1 W1 W1"), meld("CHI", false, "B1 B2 B3"),
            meld("CHI", false, "D1 D2 D3"), meld("CHI", false, "B7 B8 B9")), 1, 1, true, false);
        assertTrue(result.validStructure());
        assertTrue(has(result, "SHIERLUOTAI"));
        assertFalse(has(result, "BUQIUREN"));
    }

    @Test void bestSingleDecompositionWinsAndDeclaredSequencesCannotBeRearranged() {
        Evaluation concealed = evaluate("B1 B1 B1 B2 B2 B2 B3 B3 B3 B4 B4 B4 B5 B5");
        assertEquals(6, concealed.rawFan());
        assertTrue(has(concealed, "PENGPENG"));
        Evaluation fixed = YmScoring.evaluate(tiles("B1 B1 B2 B2 B3 B3 B4 B4 B4 B5 B5"),
            List.of(meld("CHI", false, "B1 B2 B3")), 1, 1, false, false);
        assertTrue(fixed.validStructure());
        assertFalse(has(fixed, "PENGPENG"));
        assertEquals(3, fixed.rawFan());
    }

    @Test void nineRanksRequiresEveryRankToBelongToExactlyOneGroup() {
        // Keep this all-numeric: the rank-ownership condition must fail independently of the honor guard.
        Evaluation overlap = evaluate("W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2");
        assertTrue(overlap.validStructure());
        assertFalse(has(overlap, "JIUSHUQI"));
        Evaluation unique = evaluate("W1 W1 W1 D9 D9 D9 B2 B2 B3 B4 B5 B6 B7 B8");
        assertTrue(has(unique, "JIUSHUQI"));
        assertEquals(4, value(unique, "JIUSHUQI"));
        Evaluation missing = evaluate("W1 W1 W1 D9 D9 D9 B2 B2 B3 B4 B5 B6 B6 B6");
        assertTrue(missing.validStructure()); assertFalse(has(missing, "JIUSHUQI"));
    }

    private record NineRanksExample(String honor, String placement, List<Tile> hand, List<Meld> melds,
                                    Map<String, Integer> remainingFans) {
        @Override public String toString() { return honor + " / " + placement; }
    }

    private static List<Tile> honorTiles(String honor, int count) {
        List<Tile> result = new ArrayList<>();
        for (int index = 0; index < count; index++) result.add(honor.equals("H4")
                ? new Tile("nine-ranks-north-" + index, "HONORS", 4, "北") : YmTiles.of(honor));
        return result;
    }

    /** Three disjoint numeric sequences satisfy the old buggy rank check even with two honor groups. */
    private static NineRanksExample honorExample(String honor, String placement) {
        String otherHonor = honor.equals("H7") ? "H5" : "H7";
        List<Tile> hand = new ArrayList<>(tiles("B1 B2 B3 D4 D5 D6 B7 B8 B9"));
        List<Meld> melds = new ArrayList<>();
        boolean pair = placement.equals("PAIR"), triplet = placement.equals("TRIPLET");
        boolean concealedKong = placement.equals("CONCEALED_KONG");
        boolean kong = placement.endsWith("KONG");
        String valueHonor = pair ? otherHonor : honor;
        hand.addAll(honorTiles(pair ? honor : otherHonor, 2));
        if (pair || triplet) hand.addAll(honorTiles(valueHonor, 3));
        else melds.add(new Meld(kong ? "KONG" : "PONG", honorTiles(honor, kong ? 4 : 3),
                concealedKong ? -1 : 2, null, concealedKong, placement.equals("ADDED_KONG")));
        Map<String, Integer> expected = new LinkedHashMap<>();
        int honorFan = valueHonor.equals("H1") ? 2 : valueHonor.charAt(1) >= '5' ? 1 : 0;
        if (honorFan > 0) expected.put("FANPAI", honorFan);
        if (kong) expected.put("KONG", 1);
        if (pair || triplet || concealedKong) expected.put("MENQING", 2);
        return new NineRanksExample(honor, placement, List.copyOf(hand), List.copyOf(melds), Map.copyOf(expected));
    }

    static Stream<NineRanksExample> honorNineRanksExamples() {
        return Stream.of("H1", "H2", "H3", "H5", "H6", "H7").flatMap(honor ->
                Stream.of("PAIR", "TRIPLET", "PONG", "OPEN_KONG", "CONCEALED_KONG", "ADDED_KONG")
                        .map(placement -> honorExample(honor, placement)));
    }

    @ParameterizedTest(name = "nine ranks excludes {0}") @MethodSource("honorNineRanksExamples")
    void everyLegalHonorInPairTripletOrAnyDeclaredSetExcludesOnlyNineRanks(NineRanksExample example) {
        Evaluation result = YmScoring.evaluate(example.hand(), example.melds(), 1, 1, false, false);
        assertTrue(result.validStructure()); assertFalse(has(result, "JIUSHUQI"));
        Map<String, Integer> actual = result.items().stream().collect(Collectors.toMap(YmScoring.Fan::id, YmScoring.Fan::fan));
        assertEquals(example.remainingFans(), actual, "Honor, kong and closed-hand fans must retain their independent values");
        int remaining = example.remainingFans().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(remaining, result.rawFan()); assertEquals(Math.min(8, remaining), result.fan());
        assertEquals(remaining >= 4, result.eligible(), "A hand with enough other fans must remain eligible");
    }

    @ParameterizedTest @ValueSource(strings = {"PAIR", "TRIPLET", "PONG", "OPEN_KONG", "CONCEALED_KONG", "ADDED_KONG"})
    void northIsStillOutsideTheDeckRatherThanJustExcludedFromNineRanks(String placement) {
        NineRanksExample example = honorExample("H4", placement);
        Evaluation result = YmScoring.evaluate(example.hand(), example.melds(), 1, 1, false, false);
        assertFalse(result.validStructure()); assertFalse(result.eligible()); assertEquals(0, result.rawFan()); assertTrue(result.items().isEmpty());
        assertFalse(YmTiles.CODES.contains("H4"));
    }

    @ParameterizedTest @ValueSource(strings = {"CHI", "PONG", "OPEN_KONG", "CONCEALED_KONG", "ADDED_KONG"})
    void allNumericNineRanksStillScoresFourWithEveryLegalMeldKind(String placement) {
        boolean chi = placement.equals("CHI"), kong = placement.endsWith("KONG");
        List<Tile> hand = tiles(chi ? "W1 W1 W1 D9 D9 D9 B2 B2 B6 B7 B8" : "D9 D9 D9 B2 B2 B3 B4 B5 B6 B7 B8");
        Meld fixed = new Meld(chi ? "CHI" : kong ? "KONG" : "PONG",
                tiles(chi ? "B3 B4 B5" : kong ? "W1 W1 W1 W1" : "W1 W1 W1"), 2, null,
                placement.equals("CONCEALED_KONG"), placement.equals("ADDED_KONG"));
        Evaluation result = YmScoring.evaluate(hand, List.of(fixed), 1, 1, false, false);
        assertTrue(result.validStructure()); assertTrue(result.eligible()); assertEquals(4, value(result, "JIUSHUQI"));
    }

    @Test void nineRanksCatalogExplicitlyIncludesPairAndMeldsInTheNoHonorCondition() {
        YmScoring.Fan fan = YmScoring.catalog().stream().filter(item -> item.id().equals("JIUSHUQI")).findFirst().orElseThrow();
        assertEquals(4, fan.fan()); assertTrue(fan.description().contains("不得含字牌"));
        assertTrue(fan.description().contains("雀头")); assertTrue(fan.description().contains("所有副露"));
        assertTrue(fan.description().contains("每种数值只能出现在一个"));
    }

    @Test void consecutiveRanksWorkAcrossSuitsAndWith159ManButDoNotWrapOrSkip() {
        Evaluation man = evaluate("W1 W5 W9 W1 W5 W9 B1 B1 B1 D9 D9 D9 B5 B5");
        assertTrue(has(man, "QINGSANLIAN"));
        Evaluation skip = evaluate("B1 B1 B1 D3 D3 D3 W5 W5 W5 B3 B3 B3 D1 D1");
        assertFalse(has(skip, "QINGSANLIAN"));
        Evaluation wrap = evaluate("B1 B1 B1 D8 D8 D8 W9 W9 W9 B8 B8 B8 D1 D1");
        assertFalse(has(wrap, "QINGSANLIAN"));
        Evaluation manNotOrdinaryFive = evaluate("B3 B3 B3 D4 D4 D4 W5 W5 W5 B4 B4 B4 D3 D3");
        assertFalse(has(manNotOrdinaryFive, "QINGSANLIAN"));
    }

    @Test void mixedFourRequiresExactlyThreeRanksAndOnlyOneHonorCategory() {
        Evaluation bothHonorCategories = evaluate("B1 B2 B3 W1 W1 W1 H1 H1 H1 H5 H5 H5 D2 D2");
        assertFalse(has(bothHonorCategories, "HUNSILIAN"));
        Evaluation onlyTwoRanks = evaluate("B1 B1 B1 D2 D2 D2 W1 W1 W1 H3 H3 H3 D2 D2");
        assertFalse(onlyTwoRanks.validStructure()); // Five copies of D2 are physically impossible.
        Evaluation two = evaluate("B1 B1 B1 D2 D2 D2 W1 W1 W1 H3 H3 H3 B2 B2");
        assertTrue(two.validStructure());
        assertFalse(has(two, "HUNSILIAN"));
    }

    @Test void malformedOrImpossibleHandsAndMeldsAreRejected() {
        assertFalse(evaluate("W1 W1 W1 W1 W1 B1 B2 B3 D4 D5 D6 H3 H3 H3").validStructure());
        List<Tile> duplicate = new ArrayList<>(tiles(BASE));
        duplicate.set(13, duplicate.get(12));
        assertFalse(YmScoring.evaluate(duplicate, List.of(), 1, 1, false, false).validStructure());
        List<Tile> illegal = new ArrayList<>(tiles(BASE));
        illegal.set(0, new Tile("north", "HONORS", 4, "北"));
        assertFalse(YmScoring.evaluate(illegal, List.of(), 1, 1, false, false).validStructure());
        illegal.set(0, new Tile("red", "CHARACTERS", 1, "一万", true));
        assertFalse(YmScoring.evaluate(illegal, List.of(), 1, 1, false, false).validStructure());
        assertFalse(YmScoring.evaluate(tiles(BASE).subList(0, 13), List.of(), 1, 1, false, false).validStructure());
        assertFalse(YmScoring.evaluate(tiles("B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3"),
            List.of(meld("PONG", false, "W1 W5 W9")), 1, 1, false, false).validStructure());
        assertFalse(YmScoring.evaluate(tiles("B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3"),
            List.of(meld("CHI", true, "W1 W5 W9")), 1, 1, false, false).validStructure());
        assertThrows(IllegalArgumentException.class, () -> YmTiles.of("W2", YmRules.THREE_PLAYER));
        assertThrows(IllegalArgumentException.class, () -> YmTiles.of("H4", YmRules.THREE_PLAYER));
    }

    @Test void evaluationDoesNotMutateInputOrExposeMutableOutput() {
        List<Tile> hand = new ArrayList<>(tiles(BASE));
        List<Tile> before = List.copyOf(hand);
        Evaluation result = YmScoring.evaluate(hand, List.of(), 1, 1, false, false);
        assertEquals(before, hand);
        assertThrows(UnsupportedOperationException.class, () -> result.items().clear());
        assertThrows(UnsupportedOperationException.class, () -> YmScoring.catalog().clear());
        List<Tile> unsorted = new ArrayList<>(tiles("H5 W9 B2 W1 D3"));
        YmTiles.sort(unsorted);
        assertEquals(List.of("W1", "W9", "B2", "D3", "H5"), unsorted.stream().map(YmTiles::code).toList());
        assertTrue(YmTiles.same(YmTiles.of("W1"), YmTiles.of("W1")));
        assertFalse(YmTiles.same(YmTiles.of("W1"), YmTiles.of("B1")));
        assertEquals(5, new HashSet<>(unsorted.stream().map(Tile::id).toList()).size());
    }
}
