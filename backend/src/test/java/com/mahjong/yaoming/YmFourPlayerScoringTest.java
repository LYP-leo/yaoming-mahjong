package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.mahjong.yaoming.YmRules.*;
import static org.junit.jupiter.api.Assertions.*;

class YmFourPlayerScoringTest {
    private static final String FLAT = "W1 W2 W3 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2";
    private static final String DRAGON = "H1 H2 H3 H5 H6 H7 B2 B3 B4 D4 D5 D6 B8 B8";
    private static List<Tile> tiles(String text) {
        return Arrays.stream(text.split("\\s+")).filter(code -> !code.isBlank()).map(YmTiles::of).toList();
    }
    private static YmScoring.Meld meld(String type, boolean concealed, String text) {
        List<Tile> tiles = tiles(text);
        return new YmScoring.Meld(type, tiles, 3, concealed ? null : tiles.getFirst().id(), concealed);
    }
    private static YmScoring.Evaluation score(String hand, List<YmScoring.Meld> melds, boolean self, boolean kong) {
        return YmScoring.evaluate(tiles(hand), melds, 1, 1, self, kong, FOUR_PLAYER);
    }
    private static Map<String, Integer> fans(YmScoring.Evaluation score) {
        Map<String, Integer> result = new HashMap<>();
        score.items().forEach(fan -> assertNull(result.put(fan.id(), fan.fan()), "Duplicate fan ID"));
        return result;
    }
    private record Example(String id, String hand, List<YmScoring.Meld> melds, boolean self, boolean kong) {}
    private static Example example(String id, String hand) { return new Example(id, hand, List.of(), false, false); }
    private static Stream<Example> everyFan() {
        return Stream.of(
            example("PINGHE", FLAT),
            example("FANPAI", "W1 W2 W3 B2 B3 B4 D6 D7 D8 H5 H5 H5 H3 H3"),
            new Example("KONG", "W1 W2 W3 B1 B2 B3 D4 D5 D6 H3 H3", List.of(meld("KONG", true, "B9 B9 B9 B9")), false, false),
            example("MENQING", FLAT),
            example("PENGPENG", "W1 W1 W1 B2 B2 B2 D6 D6 D6 H5 H5 H5 H3 H3"),
            new Example("SHIERLUOTAI", "H3 H3", List.of(meld("CHI", false, "W1 W2 W3"), meld("CHI", false, "B1 B2 B3"), meld("CHI", false, "D4 D5 D6"), meld("CHI", false, "B7 B8 B9")), false, false),
            new Example("BUQIUREN", FLAT, List.of(), true, false),
            new Example("GANGSHANGPAO", FLAT, List.of(), false, true),
            example("WUMENQI", "W1 W2 W3 B2 B3 B4 D7 D8 D9 H5 H5 H5 H4 H4"),
            example("HUNYISE", "B2 B3 B4 B4 B5 B6 B7 B8 B9 H5 H5 H5 H2 H2"),
            example("HUNQUANDAIYAO", "W1 W2 W3 B1 B2 B3 D7 D8 D9 H5 H5 H5 H1 H1"),
            example("QINGYISE", "B1 B2 B3 B2 B3 B4 B6 B7 B8 B7 B8 B9 B5 B5"),
            example("QINGQUANDAIYAO", "W1 W2 W3 B1 B2 B3 D7 D8 D9 W7 W8 W9 D1 D1"),
            example("HUNSILIAN", "B3 B3 B3 D4 D4 D4 W5 W5 W5 H4 H4 H4 D3 D3"),
            example("QUANBUKAO", "W1 W4 W7 B2 B5 B8 D3 D6 D9 H1 H2 H3 H4 H5"),
            example("QINGSILIAN", "B2 B2 B2 D3 D3 D3 W4 W4 W4 B5 B5 B5 D2 D2"),
            example("QUANDAIWU", "W3 W4 W5 B3 B4 B5 B4 B5 B6 D5 D6 D7 D5 D5"),
            example("JIUSHUQI", "W1 W2 W3 B4 B5 B6 D7 D7 D7 W8 W8 W8 B9 B9"),
            example("ZIYISE", "H1 H1 H1 H2 H2 H2 H3 H3 H3 H4 H4 H4 H5 H5"),
            example("QINGSANLIAN", "B3 B3 B3 D4 D4 D4 W5 W5 W5 B4 B4 B4 D3 D3")
        );
    }

    @ParameterizedTest @MethodSource("everyFan")
    void eachPublishedFourPlayerFanHasAnActualPositiveExample(Example example) {
        YmScoring.Evaluation score = score(example.hand(), example.melds(), example.self(), example.kong());
        assertTrue(score.validStructure(), example.id());
        assertTrue(fans(score).containsKey(example.id()), example.id() + " " + score.items());
        assertEquals(score.rawFan(), score.items().stream().mapToInt(YmScoring.Fan::fan).sum());
        assertEquals(score.rawFan() >= 3, score.eligible());
        assertEquals(Math.min(8, score.rawFan()), score.fan());
    }

    @Test void catalogsAreRuleScopedImmutableAndKeepTheCompleteThreePlayerCatalog() {
        assertEquals(YmScoring.catalog(), YmScoring.catalog(THREE_PLAYER));
        assertEquals(20, YmScoring.catalog(THREE_PLAYER).size());
        assertEquals(20, YmScoring.catalog(FOUR_PLAYER).size());
        Map<String, Integer> three = new HashMap<>(), four = new HashMap<>();
        YmScoring.catalog(THREE_PLAYER).forEach(fan -> three.put(fan.id(), fan.fan()));
        YmScoring.catalog(FOUR_PLAYER).forEach(fan -> four.put(fan.id(), fan.fan()));
        assertEquals(2, three.get("MENQING")); assertEquals(1, four.get("MENQING"));
        assertEquals(3, three.remove("FENGLONG")); assertNull(four.get("FENGLONG"));
        assertEquals(2, four.remove("QUANBUKAO")); assertNull(three.get("QUANBUKAO"));
        three.remove("MENQING"); four.remove("MENQING"); assertEquals(three, four);
        assertThrows(UnsupportedOperationException.class, () -> YmScoring.catalog(FOUR_PLAYER).clear());
    }

    @Test void twoFanIsInsufficientWhileOrdinaryThreeFanSelfDrawAndKongDiscardAreEligible() {
        YmScoring.Evaluation ron = score(FLAT, List.of(), false, false);
        assertEquals(Map.of("PINGHE", 1, "MENQING", 1), fans(ron));
        assertFalse(ron.eligible()); assertEquals(2, ron.fan());
        YmScoring.Evaluation self = score(FLAT, List.of(), true, false);
        assertEquals(Map.of("PINGHE", 1, "MENQING", 1, "BUQIUREN", 1), fans(self));
        assertTrue(self.eligible()); assertEquals(3, self.fan());
        YmScoring.Evaluation kongRon = score(FLAT, List.of(), false, true);
        assertEquals(Map.of("PINGHE", 1, "MENQING", 1, "GANGSHANGPAO", 1), fans(kongRon));
        assertTrue(kongRon.eligible()); assertEquals(3, kongRon.fan());
        YmScoring.Evaluation legal = score("W1 W2 W3 B1 B2 B3 D4 D5 D6 H5 H5 H5 D2 D2", List.of(), true, false);
        assertEquals(Map.of("MENQING", 1, "BUQIUREN", 1, "FANPAI", 1), fans(legal));
        assertTrue(legal.eligible(), "An honor pung replaces pinghe; its three fan now meets the minimum");
        legal = score("W1 W2 W3 B1 B2 B3 D4 D5 D6 H1 H1 H1 D2 D2", List.of(), true, false);
        assertEquals(Map.of("MENQING", 1, "BUQIUREN", 1, "FANPAI", 2), fans(legal));
        assertEquals(4, legal.rawFan()); assertTrue(legal.eligible());
    }

    @Test void threeFanOpenRonIsLegalOnlyInFourPlayerAndThreePlayerKeepsFourFanMinimum() {
        List<Tile> hand = tiles("B1 B2 B3 B4 B5 B6 B7 B8 B9 B5 B5");
        List<YmScoring.Meld> melds = List.of(meld("PONG", false, "B2 B2 B2"));
        for (YmRules rule : YmRules.values()) {
            YmScoring.Evaluation scored = YmScoring.evaluate(hand, melds, 1, 1, false, false, rule);
            assertTrue(scored.validStructure());
            assertEquals(Map.of("QINGYISE", 3), fans(scored));
            assertEquals(rule == FOUR_PLAYER, scored.eligible());
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
    void everyNormalManSequenceWorksConcealedAndDeclared(int start) {
        String man = "W" + start + " W" + (start + 1) + " W" + (start + 2);
        assertTrue(fans(score(man + " B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2", List.of(), false, false)).containsKey("PINGHE"));
        assertEquals(Map.of("PINGHE", 1), fans(score("B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2", List.of(meld("CHI", false, man)), false, false)));
    }

    @Test void old159SequencesAndWindDragonAreNotRecognizedInFourPlayerButRemainUnchangedInThreePlayer() {
        String man = "W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2";
        assertFalse(score(man, List.of(), true, false).validStructure());
        assertEquals(4, YmScoring.evaluate(tiles(man), List.of(), 1, 1, true, false).rawFan());
        assertFalse(score("B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2", List.of(meld("CHI", false, "W1 W5 W9")), true, false).validStructure());
        assertFalse(score(DRAGON, List.of(), true, false).validStructure());
        assertEquals(Map.of("FENGLONG", 3, "BUQIUREN", 1), fans(YmScoring.evaluate(tiles(DRAGON), List.of(), 1, 1, true, false)));
    }

    @Test void northIsAWindNotADragonAndSupportsTheFourthSeat() {
        String hand = "W1 W2 W3 B1 B2 B3 D4 D5 D6 H4 H4 H4 D2 D2";
        YmScoring.Evaluation north = YmScoring.evaluate(tiles(hand), List.of(), 4, 1, false, false, FOUR_PLAYER);
        assertEquals(Map.of("MENQING", 1, "FANPAI", 1), fans(north));
        assertEquals(2, fans(YmScoring.evaluate(tiles(hand), List.of(), 4, 4, false, false, FOUR_PLAYER)).get("FANPAI"));
        assertFalse(fans(score(hand, List.of(), false, false)).containsKey("FANPAI"));
        assertTrue(fans(score("W1 W2 W3 B2 B3 B4 D7 D8 D9 H5 H5 H5 H4 H4", List.of(), false, false)).containsKey("WUMENQI"));
        assertTrue(fans(score("B3 B3 B3 D4 D4 D4 W5 W5 W5 H4 H4 H4 D3 D3", List.of(), false, false)).containsKey("HUNSILIAN"));
        assertFalse(fans(score("B3 B3 B3 D4 D4 D4 W5 W5 W5 H4 H4 H4 H5 H5", List.of(), false, false)).containsKey("HUNSILIAN"));
        assertFalse(YmScoring.evaluate(tiles(hand), List.of(), 4, 1, false, false).validStructure());
        assertFalse(YmScoring.evaluate(tiles(hand), List.of(), 5, 1, false, false, FOUR_PLAYER).validStructure());
    }

    @Test void normalFiveManBridgesFourAndSixBut159NeverFormsSpecialConsecutiveCategories() {
        String bridge = "B3 B3 B3 D4 D4 D4 W5 W5 W5 B4 B4 B4 D3 D3";
        assertTrue(fans(score(bridge, List.of(), false, false)).containsKey("QINGSANLIAN"));
        assertFalse(fans(YmScoring.evaluate(tiles(bridge), List.of(), 1, 1, false, false)).containsKey("QINGSANLIAN"));
        String skipped = "W1 W1 W1 W5 W5 W5 W9 W9 W9 B1 B1 B1 D9 D9";
        assertFalse(fans(score(skipped, List.of(), false, false)).containsKey("QINGSANLIAN"));
        assertTrue(fans(YmScoring.evaluate(tiles(skipped), List.of(), 1, 1, false, false)).containsKey("QINGSANLIAN"));
    }

    @Test void everyOneOf720UnconnectedTargetsNeedsOnlyItsExplicitThirdFanToWin() {
        int checked = 0;
        for (Set<String> template : YmScoring.unconnectedTemplates()) {
            List<String> codes = template.stream().sorted().toList();
            assertEquals(16, codes.size());
            for (int first = 0; first < 16; first++) for (int second = first + 1; second < 16; second++) {
                List<Tile> hand = new ArrayList<>();
                for (int index = 0; index < 16; index++) if (index != first && index != second) hand.add(YmTiles.of(codes.get(index)));
                for (int mode = 0; mode < 4; mode++) {
                    boolean self = mode == 1 || mode == 3, kong = mode >= 2;
                    YmScoring.Evaluation score = YmScoring.evaluate(hand, List.of(), 4, 2, self, kong, FOUR_PLAYER);
                    assertTrue(score.validStructure()); assertEquals(mode != 0, score.eligible());
                    assertEquals(mode == 0 ? Map.of("QUANBUKAO", 2) : Map.of("QUANBUKAO", 2, self ? "BUQIUREN" : "GANGSHANGPAO", 1), fans(score));
                }
                checked++;
            }
        }
        assertEquals(720, checked);
    }

    @Test void unconnectedRequiresOneResiduePerSuitNoDuplicateAndNoDeclaredMeldEvenAnkan() {
        String base = "W1 W4 W7 B2 B5 B8 D3 D6 D9 H1 H2 H3 H4 H5";
        assertFalse(score(base.replace("H5", "H4"), List.of(), true, false).validStructure());
        assertFalse(score(base.replace("W7", "W8"), List.of(), true, false).validStructure());
        assertFalse(score(base.replace("W7", "B7"), List.of(), true, false).validStructure());
        for (YmScoring.Meld meld : List.of(meld("CHI", false, "W1 W2 W3"), meld("PONG", false, "W9 W9 W9"), meld("KONG", true, "W9 W9 W9 W9")))
            assertFalse(fans(score("W4 W7 B2 B5 B8 D3 D6 H1 H2 H3 H4", List.of(meld), true, false)).containsKey("QUANBUKAO"));
        assertFalse(YmScoring.evaluate(tiles(base), List.of(), 1, 1, true, false).validStructure());
    }

    @Test void bestDecompositionAndDeclaredMeldRestrictionsRemainAuthoritative() {
        String ambiguous = "W1 W1 W1 W2 W2 W2 W3 W3 W3 D4 D5 D6 D8 D8";
        assertEquals(Map.of("PINGHE", 1, "MENQING", 1), fans(score(ambiguous, List.of(), false, false)));
        assertEquals(Map.of("PINGHE", 1), fans(score("W1 W1 W1 W2 W2 W2 W3 W3 W3 D8 D8", List.of(meld("CHI", false, "D4 D5 D6")), false, false)));
        assertFalse(fans(score("W2 W2 W2 W3 W3 W3 D4 D5 D6 D8 D8", List.of(meld("PONG", false, "W1 W1 W1")), false, false)).containsKey("PINGHE"));
    }

    @Test void honorsExclusionsAndConcealedKongStackingKeepRawAndCappedTotals() {
        String hand = "H1 H1 H1 H2 H2 H2 H3 H3 H3 H4 H4 H4 H5 H5";
        assertEquals(Map.of("ZIYISE", 6, "MENQING", 1), fans(score(hand, List.of(), false, false)));
        assertEquals(8, score(hand, List.of(), true, false).rawFan());
        List<YmScoring.Meld> kongs = Stream.of("H1", "H2", "H3", "H4").map(code -> meld("KONG", true, String.join(" ", java.util.Collections.nCopies(4, code)))).toList();
        YmScoring.Evaluation score = score("H5 H5", kongs, true, false);
        assertEquals(Map.of("ZIYISE", 6, "KONG", 4, "MENQING", 1, "BUQIUREN", 1, "SHIERLUOTAI", 1), fans(score));
        assertEquals(13, score.rawFan()); assertEquals(8, score.fan()); assertTrue(score.eligible());
    }

    @Test void physicalValidationStillRejectsDuplicateIdsFifthCopiesAndRedTiles() {
        List<Tile> duplicated = new ArrayList<>(tiles(FLAT)); duplicated.set(1, duplicated.getFirst());
        assertFalse(YmScoring.evaluate(duplicated, List.of(), 1, 1, false, false, FOUR_PLAYER).validStructure());
        List<Tile> red = new ArrayList<>(tiles(FLAT)); Tile first = red.getFirst();
        red.set(0, new Tile(first.id(), first.suit(), first.rank(), first.label(), true));
        assertFalse(YmScoring.evaluate(red, List.of(), 1, 1, false, false, FOUR_PLAYER).validStructure());
        assertFalse(score("W1 W1 W1 W1 W1 B2 B3 B4 D5 D6 D7 H4 H4 H4", List.of(), true, false).validStructure());
    }
}
