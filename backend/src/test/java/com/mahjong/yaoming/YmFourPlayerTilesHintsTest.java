package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class YmFourPlayerTilesHintsTest {
    private static List<Tile> tiles(String codes) {
        return Arrays.stream(codes.split(" ")).map(YmTiles::of).toList();
    }

    private static YmRoom room(YmRules rules, String hand) {
        YmRoom room = new YmRoom();
        room.ruleId = rules.id();
        room.phase = YmRoom.Phase.NEED_DRAW;
        room.currentSeat = 0;
        for (int seat = 0; seat < rules.playerCount(); seat++) {
            YmRoom.Player player = new YmRoom.Player();
            player.id = "p" + seat; player.name = "玩家" + seat; player.seat = seat;
            room.players.add(player);
        }
        room.seat(0).hand = new ArrayList<>(tiles(hand));
        return room;
    }

    private static YmHints.Wait wait(YmRoom room, String code) {
        return YmHints.analyze(room, room.seat(0)).waits().stream()
            .filter(candidate -> YmTiles.code(candidate.tile()).equals(code)).findFirst().orElseThrow();
    }

    @ParameterizedTest @EnumSource(YmRules.class)
    void decksHaveExactlyFourUniquePhysicalCopiesOfEachRuleTile(YmRules rules) {
        List<Tile> deck = YmTiles.deck(rules);
        assertEquals(rules.tileCount(), deck.size());
        assertEquals(deck.size(), new HashSet<>(deck.stream().map(Tile::id).toList()).size());
        assertEquals(new HashSet<>(rules.codes()), deck.stream().map(YmTiles::code).collect(Collectors.toSet()));
        assertTrue(deck.stream().collect(Collectors.groupingBy(YmTiles::code, Collectors.counting())).values().stream().allMatch(n -> n == 4));
        assertTrue(deck.stream().noneMatch(Tile::red));
        assertThrows(UnsupportedOperationException.class, () -> rules.codes().clear());
        assertEquals(rules.playerCount() * 2, rules.totalRounds());
        assertEquals(rules == YmRules.FOUR_PLAYER ? 3 : 4, rules.minimumFan());
    }

    @Test void legacyDefaultsAndUnknownRulesCannotSilentlySelectFourPlayers() {
        assertSame(YmRules.THREE_PLAYER, YmRules.fromId(null));
        assertSame(YmRules.THREE_PLAYER, YmRules.fromId(""));
        assertSame(YmRules.THREE_PLAYER, new YmRoom().rules());
        assertSame(YmRules.FOUR_PLAYER, YmRules.fromId("yaoming-4p"));
        assertThrows(IllegalArgumentException.class, () -> YmRules.fromId("yaoming-5p"));
        assertEquals(108, YmTiles.deck().size());
        assertEquals(136, YmTiles.deck(YmRules.FOUR_PLAYER).size());
    }

    @Test void ordinaryWanSequencesAndLegacy159RemainRuleSpecific() {
        for (int first = 1; first <= 7; first++) {
            List<String> codes = List.of("W" + first, "W" + (first + 1), "W" + (first + 2));
            assertTrue(YmTiles.sequence(codes, YmRules.FOUR_PLAYER));
            assertFalse(YmTiles.sequence(codes, YmRules.THREE_PLAYER));
        }
        assertTrue(YmTiles.sequence(List.of("W1", "W5", "W9")));
        assertFalse(YmTiles.sequence(List.of("W1", "W5", "W9"), YmRules.FOUR_PLAYER));
        assertFalse(YmTiles.sequence(List.of("H2", "H3", "H4"), YmRules.FOUR_PLAYER));
        assertFalse(YmTiles.sequence(List.of("W8", "W9", "W1"), YmRules.FOUR_PLAYER));
        assertFalse(YmTiles.sequence(List.of("W1", "B2", "D3"), YmRules.FOUR_PLAYER));
    }

    @Test void chiRetainsPhysicalChoicesAndRejectsTheOtherRulesWanRun() {
        List<Tile> normal = tiles("W2 W2 W3 W4");
        assertEquals(2, YmTiles.chiOptions(normal, YmTiles.of("W1"), YmRules.FOUR_PLAYER).size());
        assertTrue(YmTiles.chiOptions(normal, YmTiles.of("W1")).isEmpty());
        List<Tile> legacy = tiles("W1 W9");
        assertEquals(1, YmTiles.chiOptions(legacy, YmTiles.of("W5")).size());
        assertTrue(YmTiles.chiOptions(legacy, YmTiles.of("W5"), YmRules.FOUR_PLAYER).isEmpty());
        assertEquals(2, normal.stream().filter(tile -> tile.rank() == 2).count());
    }

    @Test void fourPlayerHintsIncludeNewWanTilesAndKeepThreePlayer159Waits() {
        YmRoom four = room(YmRules.FOUR_PLAYER, "W1 W2 W3 W4 W5 W6 W7 W8 W9 W5 W5 W5 W2");
        YmRoom three = room(YmRules.THREE_PLAYER, "W1 W5 B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3");
        for (int repeat = 0; repeat < 3; repeat++) {
            YmHints.Wait normal = wait(four, "W2");
            assertTrue(normal.canRon()); assertEquals(4, normal.ronFan());
            assertTrue(normal.canTsumo()); assertEquals(5, normal.tsumoFan());
            assertEquals(2, normal.unseenCount());
            assertEquals(List.of("W9"), YmHints.analyze(three, three.seat(0)).waits().stream().map(w -> YmTiles.code(w.tile())).toList());
        }
    }

    @Test void northWaitCountsOnlyOwnAndPublicTilesAndUsesNorthSeatWind() {
        YmRoom four = room(YmRules.FOUR_PLAYER, "H4 H4 W5 W5 W5 B5 B5 B5 D5 D5 D5 H7 H7");
        four.dealerSeat = 1; // Seat 0 is North, not a dragon wind and not an invalid seat.
        four.seat(1).hand = new ArrayList<>(tiles("H4"));
        assertEquals(2, wait(four, "H4").unseenCount());
        // North completes the seat-wind pung and all five categories (W/B/D/wind/dragon).
        assertEquals(5, wait(four, "H4").ronFan());
        assertEquals(6, wait(four, "H4").tsumoFan());
        List<Tile> completed = new ArrayList<>(four.seat(0).hand);
        completed.add(YmTiles.of("H4"));
        YmScoring.Evaluation score = YmScoring.evaluate(completed, List.of(), 4, 1, false, false, YmRules.FOUR_PLAYER);
        assertEquals(1, score.items().stream().filter(f -> f.id().equals("FANPAI")).findFirst().orElseThrow().fan());
        assertEquals(2, score.items().stream().filter(f -> f.id().equals("WUMENQI")).findFirst().orElseThrow().fan());
        four.seat(1).hand.clear();
        four.seat(1).discards.add(YmTiles.of("H4"));
        assertEquals(1, wait(four, "H4").unseenCount());
        four.seat(2).discards.add(YmTiles.of("H4"));
        assertEquals(0, wait(four, "H4").unseenCount());
        four.wall = new ArrayList<>(tiles("H4 H4 H4 H4"));
        assertEquals(0, wait(four, "H4").unseenCount());
    }

    @Test void allUnconnectedWaitsAllowThreeFanSelfDrawButRejectTwoFanRon() {
        YmRoom four = room(YmRules.FOUR_PLAYER, "W1 W4 W7 B2 B5 B8 D3 H1 H2 H3 H4 H5 H6");
        YmHints.Wait white = wait(four, "H7");
        assertFalse(white.canRon()); assertTrue(white.canTsumo());
        assertEquals(2, white.ronFan()); assertEquals(3, white.tsumoFan());
        assertTrue(white.ronReason().contains("不足 3 番"));
        assertTrue(YmHints.analyze(four, four.seat(0)).note().contains("3 番起和"));
    }

    @Test void fourteenTileAnalysisAndNorthDiscardCandidatesRemainPrivate() {
        YmRoom four = room(YmRules.FOUR_PLAYER, "W1 W2 W3 W4 W5 W6 W7 W8 W9 W5 W5 W5 W2 H4");
        four.phase = YmRoom.Phase.NEED_DISCARD;
        List<Tile> before = List.copyOf(four.seat(0).hand);
        YmHints.Analysis analysis = YmHints.analyze(four, four.seat(0));
        assertEquals("DISCARD", analysis.mode());
        YmHints.Discard north = analysis.discards().stream().filter(d -> YmTiles.code(d.tile()).equals("H4")).findFirst().orElseThrow();
        assertTrue(north.waits().stream().anyMatch(w -> YmTiles.code(w.tile()).equals("W2") && w.canRon()));
        assertEquals(before, four.seat(0).hand);
        four.seat(0).left = true;
        assertEquals("UNAVAILABLE", YmHints.analyze(four, four.seat(0)).mode());
    }
}
