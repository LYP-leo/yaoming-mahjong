package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import com.mahjong.yaoming.YmScoring.Evaluation;
import com.mahjong.yaoming.YmScoring.Meld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Regression examples for the user's second rulebook, not Japanese riichi scoring. */
class YmRulebook2ScoringTest {
    private static final String NUMERIC_SEQUENCES = "W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2";
    private static final String ALL_HONORS = "H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6 H6";

    private static List<Tile> tiles(String codes) {
        return Arrays.stream(codes.split("\\s+")).map(YmTiles::of).toList();
    }

    private static Meld meld(String type, boolean concealed, boolean added, String codes) {
        return new Meld(type, tiles(codes), concealed ? -1 : 2, null, concealed, added);
    }

    private static Evaluation evaluate(String hand, List<Meld> melds, boolean selfDraw, boolean kongDiscard) {
        return YmScoring.evaluate(tiles(hand), melds, 1, 1, selfDraw, kongDiscard);
    }

    private static void assertFans(Evaluation result, Map<String, Integer> expected) {
        assertTrue(result.validStructure());
        assertEquals(expected, result.items().stream().collect(Collectors.toMap(YmScoring.Fan::id, YmScoring.Fan::fan)));
        int rawFan = expected.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(rawFan, result.rawFan());
        assertEquals(Math.min(8, rawFan), result.fan());
        assertEquals(rawFan >= 4, result.eligible());
    }

    @Test void updatedCatalogHasExactlyTheNewTwentyFansAndValues() {
        assertEquals(Map.ofEntries(
            Map.entry("PINGHE", 1), Map.entry("FANPAI", 1), Map.entry("KONG", 1),
            Map.entry("PENGPENG", 1), Map.entry("SHIERLUOTAI", 1), Map.entry("BUQIUREN", 1),
            Map.entry("GANGSHANGPAO", 1), Map.entry("MENQING", 2), Map.entry("WUMENQI", 2),
            Map.entry("HUNYISE", 2), Map.entry("HUNQUANDAIYAO", 2), Map.entry("QINGYISE", 3),
            Map.entry("QINGQUANDAIYAO", 3), Map.entry("HUNSILIAN", 3), Map.entry("FENGLONG", 3),
            Map.entry("QINGSILIAN", 4), Map.entry("QUANDAIWU", 4), Map.entry("JIUSHUQI", 4),
            Map.entry("ZIYISE", 6), Map.entry("QINGSANLIAN", 6)),
            YmScoring.catalog().stream().collect(Collectors.toMap(YmScoring.Fan::id, YmScoring.Fan::fan)));
        assertTrue(YmScoring.catalog().stream().noneMatch(fan -> fan.id().equals("DUANYAO") || fan.name().equals("断幺")));
    }

    @Test void closedPingheAllows159ManAndStacksWithMenqingAndSelfDrawToReachFour() {
        assertFans(evaluate(NUMERIC_SEQUENCES, List.of(), false, false), Map.of("PINGHE", 1, "MENQING", 2));
        assertFans(evaluate(NUMERIC_SEQUENCES, List.of(), true, false),
            Map.of("PINGHE", 1, "MENQING", 2, "BUQIUREN", 1));
        assertFans(evaluate(NUMERIC_SEQUENCES, List.of(), false, true),
            Map.of("PINGHE", 1, "MENQING", 2, "GANGSHANGPAO", 1));
    }

    @ParameterizedTest @ValueSource(strings = {"W1", "W5", "W9", "B4", "D1", "D2", "D9"})
    void pingheAcceptsTerminalOrMiddleNumericPairsWithoutWaitShapeRestrictions(String pair) {
        // Present the last tile as a pair completion; there is no riichi-style two-sided-wait requirement.
        assertFans(evaluate("W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 " + pair + " " + pair,
            List.of(), false, false), Map.of("PINGHE", 1, "MENQING", 2));
    }

    @ParameterizedTest @ValueSource(strings = {"H1", "H2", "H3", "H5", "H6", "H7"})
    void everyHonorPairExcludesPingheEvenIfItIsNotAValuePair(String honor) {
        assertFans(evaluate("W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 " + honor + " " + honor,
            List.of(), false, false), Map.of("MENQING", 2));
    }

    @Test void exposed159ManAndOtherExposedSequencesStillCountForPinghe() {
        assertFans(evaluate("B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2",
            List.of(meld("CHI", false, false, "W1 W5 W9")), true, false), Map.of("PINGHE", 1));
        assertFans(evaluate("D2 D2", List.of(
            meld("CHI", false, false, "W1 W5 W9"), meld("CHI", false, false, "B1 B2 B3"),
            meld("CHI", false, false, "D4 D5 D6"), meld("CHI", false, false, "B7 B8 B9")),
            false, false), Map.of("PINGHE", 1, "SHIERLUOTAI", 1));
    }

    @Test void concealedTripletOrExposedPongCannotCountAsPinghe() {
        assertFans(evaluate("W1 W1 W1 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2", List.of(), false, false),
            Map.of("MENQING", 2));
        assertFans(evaluate("B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2",
            List.of(meld("PONG", false, false, "W1 W1 W1")), false, false), Map.of());
    }

    @ParameterizedTest @ValueSource(strings = {"CONCEALED", "OPEN", "ADDED"})
    void noKongCountsAsASequenceAndOnlyConcealedKongPreservesMenqingAndBuqiuren(String kind) {
        boolean concealed = kind.equals("CONCEALED");
        assertFans(evaluate("B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2",
            List.of(meld("KONG", concealed, kind.equals("ADDED"), "W1 W1 W1 W1")), true, false),
            concealed ? Map.of("KONG", 1, "MENQING", 2, "BUQIUREN", 1) : Map.of("KONG", 1));
    }

    @Test void bestDecompositionCanReplaceThreeTripletsWithThreeSequencesToFindPinghe() {
        String hand = "B1 B1 B1 B2 B2 B2 B3 B3 B3 D4 D5 D6 D8 D8";
        // The splitter explores triplets first. Scoring must choose the higher-valued all-sequence split.
        assertFans(evaluate(hand, List.of(), false, false), Map.of("MENQING", 2, "PINGHE", 1));
        assertFans(evaluate("B1 B1 B1 B2 B2 B2 B3 B3 B3 D8 D8",
            List.of(meld("CHI", false, false, "D4 D5 D6")), false, false), Map.of("PINGHE", 1));
        // Once one of those triplets is actually declared, its tiles may not be regrouped as sequences.
        assertFans(evaluate("B2 B2 B2 B3 B3 B3 D4 D5 D6 D8 D8",
            List.of(meld("PONG", false, false, "B1 B1 B1")), false, false), Map.of());
    }

    @Test void removingDuanyaoMakesAnOldFourFanSelfDrawIneligibleWithoutAnotherValidFan() {
        String oldDuanyao = "B2 B3 B4 D3 D4 D5 B6 B7 B8 D6 D6 D6 B5 B5";
        assertFans(evaluate(oldDuanyao, List.of(), false, false), Map.of("MENQING", 2));
        assertFans(evaluate(oldDuanyao, List.of(), true, false), Map.of("MENQING", 2, "BUQIUREN", 1));
        String allSequences = "B2 B3 B4 D3 D4 D5 B6 B7 B8 D6 D7 D8 B5 B5";
        assertFans(evaluate(allSequences, List.of(), false, false), Map.of("MENQING", 2, "PINGHE", 1));
        assertFans(evaluate(allSequences, List.of(), true, false), Map.of("MENQING", 2, "PINGHE", 1, "BUQIUREN", 1));
    }

    @Test void openPureOutsideIsThreeUntilPingheOrKongDiscardSuppliesTheFourthFan() {
        List<Meld> pong = List.of(meld("PONG", false, false, "W1 W1 W1"));
        String hand = "B1 B2 B3 D7 D8 D9 B9 B9 B9 D1 D1";
        assertFans(evaluate(hand, pong, false, false), Map.of("QINGQUANDAIYAO", 3));
        assertFans(evaluate(hand, pong, false, true), Map.of("QINGQUANDAIYAO", 3, "GANGSHANGPAO", 1));
        assertFans(evaluate("B1 B2 B3 D7 D8 D9 W1 W5 W9 D1 D1",
            List.of(meld("CHI", false, false, "W1 W5 W9")), false, false),
            Map.of("QINGQUANDAIYAO", 3, "PINGHE", 1));
    }

    @Test void numericFullFlushRemainsThreeAndStillStacksWithPingheAndMenqing() {
        assertFans(evaluate("B1 B2 B3 B2 B3 B4 B5 B6 B7 B7 B8 B9 B5 B5", List.of(), false, false),
            Map.of("QINGYISE", 3, "PINGHE", 1, "MENQING", 2));
    }

    @Test void allHonorsNoLongerAddsFullFlushButPreservesTheRawTotalAndEightFanCap() {
        assertFans(evaluate(ALL_HONORS, List.of(), false, false), Map.of("ZIYISE", 6, "MENQING", 2));
        assertFans(evaluate(ALL_HONORS, List.of(), true, false), Map.of("ZIYISE", 6, "MENQING", 2, "BUQIUREN", 1));
        assertFans(evaluate("H2 H2 H2 H3 H3 H3 H5 H5 H5 H6 H6",
            List.of(meld("PONG", false, false, "H1 H1 H1")), false, false), Map.of("ZIYISE", 6));
        assertFans(evaluate("H6 H6", List.of(
            meld("KONG", true, false, "H1 H1 H1 H1"), meld("KONG", true, false, "H2 H2 H2 H2"),
            meld("KONG", true, false, "H3 H3 H3 H3"), meld("KONG", true, false, "H5 H5 H5 H5")),
            true, false), Map.of("ZIYISE", 6, "KONG", 4, "MENQING", 2, "BUQIUREN", 1, "SHIERLUOTAI", 1));
    }
}
