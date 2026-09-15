package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class YmHintsTest {
    private static final String HONORS = "H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6";
    private static final String MAN = "W1 W5 B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3";
    private static final String DRAGON = "H1 H2 H3 H5 H6 H7 W1 W5 W9 B1 B2 B3 H3";
    // No B5 in this hand: three public copies leave one unseen, four leave zero.
    // Completing B345 gives four sequences + a numeric pair: pinghe 1 + menqing 2 + self-draw 1.
    private static final String BAMBOO_FIVE_WAIT = "B3 B4 D2 D3 D4 B6 B7 B8 D6 D7 D8 B2 B2";

    private static List<Tile> tiles(String codes) {
        return Arrays.stream(codes.split("\\s+")).filter(code -> !code.isBlank()).map(YmTiles::of).toList();
    }
    private static YmRoom room(String hand) {
        YmRoom room = new YmRoom();
        room.id = "hints-test";
        room.phase = NEED_DRAW;
        room.currentSeat = 0;
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player player = new YmRoom.Player();
            player.id = "player-" + seat;
            player.name = "玩家" + seat;
            player.seat = seat;
            room.players.add(player);
        }
        room.seat(0).hand = new ArrayList<>(tiles(hand));
        return room;
    }
    private static YmHints.Analysis analyze(YmRoom room) { return YmHints.analyze(room, room.seat(0)); }
    private static YmHints.Wait wait(YmHints.Analysis analysis, String code) {
        return analysis.waits().stream().filter(wait -> YmTiles.code(wait.tile()).equals(code)).findFirst().orElseThrow();
    }
    private static YmHints.Discard discard(YmHints.Analysis analysis, String code) {
        return analysis.discards().stream().filter(discard -> YmTiles.code(discard.tile()).equals(code)).findFirst().orElseThrow();
    }

    @Test void thirteenEquivalentTilesUseWaitModeAndSupport159Man() {
        YmHints.Analysis hints = analyze(room(MAN));
        assertEquals("WAIT", hints.mode());
        assertTrue(hints.discards().isEmpty());
        assertEquals(List.of("W9"), hints.waits().stream().map(wait -> YmTiles.code(wait.tile())).toList());
        assertEquals(4, wait(hints, "W9").unseenCount());
        assertTrue(hints.waits().stream().allMatch(wait -> !wait.tile().red()));
        assertFalse(hints.waits().stream().anyMatch(wait -> YmTiles.code(wait.tile()).equals("W2") || YmTiles.code(wait.tile()).equals("H4")));
    }

    @Test void structuralWaitBelowFourFanStillAppearsWithExplanation() {
        YmHints.Wait candidate = wait(analyze(room(MAN)), "W9");
        assertFalse(candidate.canRon());
        assertFalse(candidate.canTsumo());
        assertEquals(2, candidate.ronFan());
        assertEquals(3, candidate.tsumoFan());
        assertTrue(candidate.ronReason().contains("不足 4 番"));
    }

    @Test void removedDuanyaoDoesNotLeaveAnOldFourFanSelfDrawLegal() {
        YmHints.Wait candidate = wait(analyze(room("B2 B3 B4 D3 D4 D5 B6 B7 B8 D6 D6 D6 B5")), "B5");
        assertFalse(candidate.canTsumo());
        assertEquals(3, candidate.tsumoFan());
        assertFalse(candidate.canRon());
        assertEquals(2, candidate.ronFan());
        assertTrue(candidate.ronReason().contains("不足 4 番"));
    }

    @Test void pingheMakesFourFanSelfDrawLegalWhileOrdinaryRonHasOnlyThree() {
        YmHints.Wait candidate = wait(analyze(room(BAMBOO_FIVE_WAIT)), "B5");
        assertTrue(candidate.canTsumo());
        assertEquals(4, candidate.tsumoFan());
        assertFalse(candidate.canRon());
        assertEquals(3, candidate.ronFan());
    }

    @Test void windDragonWaitUsesRealSpecialEvaluatorWithoutPredictingKongDiscard() {
        YmRoom room = room(DRAGON);
        room.seat(0).afterKong = true;
        room.window = new YmRoom.Window();
        room.window.kongDiscard = true;
        YmHints.Wait candidate = wait(analyze(room), "H3");
        assertTrue(candidate.canTsumo());
        assertEquals(4, candidate.tsumoFan());
        assertFalse(candidate.canRon());
        assertEquals(3, candidate.ronFan());
        assertTrue(candidate.ronReason().contains("不足 4 番"));
    }

    @Test void discardedSameTypeRestrictsNeitherRonNorSelfDraw() {
        YmRoom room = room(HONORS);
        room.seat(0).discardedCodes.add("H6");
        YmHints.Wait candidate = wait(analyze(room), "H6");
        assertTrue(candidate.canTsumo());
        assertTrue(candidate.canRon());
        assertEquals("", candidate.ronReason());
    }

    @Test void historicalPassDoesNotRequireDrawingBeforeRon() {
        YmRoom room = room(HONORS);
        room.seat(0).passedCodes.add("H6");
        YmHints.Wait candidate = wait(analyze(room), "H6");
        assertTrue(candidate.canTsumo());
        assertTrue(candidate.canRon());
        assertEquals("", candidate.ronReason());
        room.seat(0).passedCodes.clear();
        assertTrue(wait(analyze(room), "H6").canRon());
    }

    @Test void unrelatedDiscardAndPassDoNotCreateWholeHandFuriten() {
        YmRoom room = room(HONORS);
        room.seat(0).discardedCodes.add("B1");
        room.seat(0).passedCodes.add("D1");
        YmHints.Wait candidate = wait(analyze(room), "H6");
        assertTrue(candidate.canRon());
        assertEquals("", candidate.ronReason());
    }

    @Test void insufficientFanIsTheOnlyRonReasonDespiteBothHistoricalSets() {
        YmRoom room = room(MAN);
        room.seat(0).discardedCodes.add("W9");
        room.seat(0).passedCodes.add("W9");
        String reason = wait(analyze(room), "W9").ronReason();
        assertTrue(reason.contains("不足 4 番"));
        assertFalse(reason.contains("曾打过"));
        assertFalse(reason.contains("本巡曾放过"));
    }

    @Test void honorFanScoresRespectEightFanCap() {
        YmHints.Wait candidate = wait(analyze(room(HONORS)), "H6");
        assertTrue(candidate.canRon());
        assertTrue(candidate.canTsumo());
        assertEquals(8, candidate.ronFan());
        assertEquals(8, candidate.tsumoFan());
    }

    @Test void declaredMeldsCountAsThreeEquivalentTilesButRetainTheirActualScoring() {
        YmRoom room = room("B5");
        room.seat(0).melds.addAll(List.of(
            new YmScoring.Meld("CHI", tiles("B2 B3 B4"), 2, null, false),
            new YmScoring.Meld("CHI", tiles("B2 B3 B4"), 2, null, false),
            new YmScoring.Meld("CHI", tiles("B4 B5 B6"), 2, null, false),
            new YmScoring.Meld("CHI", tiles("B6 B7 B8"), 2, null, false)));
        YmHints.Analysis hints = analyze(room);
        assertEquals("WAIT", hints.mode());
        YmHints.Wait candidate = wait(hints, "B5");
        assertEquals(2, candidate.unseenCount());
        // Four declared sequences: full flush 3 + pinghe 1 + twelve exposed 1; no closed-hand bonus.
        assertEquals(5, candidate.tsumoFan());
        assertEquals(5, candidate.ronFan());
        assertTrue(candidate.canRon());
    }

    @Test void concealedKongKeepsClosedSelfDrawBonusAndCountsFourKnownPhysicalTiles() {
        YmRoom room = room("B1 B2 B3 B4 B5 B6 B6 B7 B8 H3");
        room.seat(0).melds.add(new YmScoring.Meld("KONG", tiles("H5 H5 H5 H5"), 0, null, true));
        YmHints.Wait candidate = wait(analyze(room), "H3");
        assertEquals(6, candidate.ronFan());
        assertEquals(7, candidate.tsumoFan());
        assertEquals(3, candidate.unseenCount());
        assertFalse(analyze(room).waits().stream().anyMatch(wait -> YmTiles.code(wait.tile()).equals("H5")));
    }

    @Test void declaredSequenceCannotBeRearrangedToCreateWindDragon() {
        YmRoom room = room("H1 H2 H3 H5 H6 H7 B1 B2 B3 H3");
        room.seat(0).melds.add(new YmScoring.Meld("CHI", tiles("W1 W5 W9"), 2, null, false));
        assertTrue(analyze(room).waits().isEmpty());
    }

    @Test void unknownCountSubtractsOnlyOwnHandAndAllPublicDiscardsAndMelds() {
        YmRoom room = room(HONORS);
        room.seat(1).discards.add(YmTiles.of("H6"));
        room.seat(2).discards.add(YmTiles.of("H6"));
        room.seat(1).hand = new ArrayList<>(tiles("H6 H6 H6"));
        room.wall = new ArrayList<>(tiles("H6 H6 H6"));
        assertEquals(1, wait(analyze(room), "H6").unseenCount());
        room.seat(1).discards.clear();
        room.seat(2).discards.clear();
        room.seat(2).melds.add(new YmScoring.Meld("PONG", tiles("H6 H6 H6"), 1, null, false));
        assertEquals(0, wait(analyze(room), "H6").unseenCount());
    }

    @Test void fourKnownCopiesKeepStructuralWaitWithZeroUnknownCount() {
        YmRoom room = room(HONORS);
        room.seat(1).discards.addAll(tiles("H6 H6 H6"));
        YmHints.Wait candidate = wait(analyze(room), "H6");
        assertEquals(0, candidate.unseenCount());
        assertTrue(candidate.canTsumo());
        assertTrue(candidate.canRon());
        assertTrue(analyze(room).note().contains("0 表示四张均已可见"));
    }

    @ParameterizedTest(name = "{0}: {1} river copies of five bamboo leave {2} unseen")
    @CsvSource({ "WAIT, 3, 1", "WAIT, 4, 0", "DISCARD, 3, 1", "DISCARD, 4, 0" })
    void bambooFiveCountsRemainVisibleInWaitAndDiscardResults(String mode, int riverCopies, int remaining) {
        YmRoom room = room(BAMBOO_FIVE_WAIT + (mode.equals("DISCARD") ? " H7" : ""));
        if (mode.equals("DISCARD")) room.phase = NEED_DISCARD;
        assertTrue(room.seat(0).hand.stream().noneMatch(tile -> YmTiles.code(tile).equals("B5")));
        for (int copy = 0; copy < riverCopies; copy++) room.seat(1 + copy % 2).discards.add(YmTiles.of("B5"));
        List<Tile> originalHand = List.copyOf(room.seat(0).hand);
        List<List<Tile>> originalRivers = room.players.stream().map(player -> List.copyOf(player.discards)).toList();
        YmHints.Analysis hints = analyze(room);
        assertEquals(mode, hints.mode());
        List<YmHints.Wait> waits = mode.equals("WAIT") ? hints.waits() : discard(hints, "H7").waits();
        YmHints.Wait candidate = waits.stream().filter(wait -> YmTiles.code(wait.tile()).equals("B5")).findFirst().orElseThrow();
        assertEquals(remaining, candidate.unseenCount());
        assertTrue(candidate.canTsumo());
        assertEquals(4, candidate.tsumoFan());
        assertFalse(candidate.canRon());
        assertEquals(3, candidate.ronFan());
        assertTrue(candidate.ronReason().contains("不足 4 番"));
        assertTrue(hints.note().contains("并非牌山剩余张数"));
        if (remaining == 0) assertTrue(hints.note().contains("0 表示四张均已可见"));
        assertEquals(originalHand, room.seat(0).hand);
        assertEquals(originalRivers, room.players.stream().map(player -> List.copyOf(player.discards)).toList());
    }

    @Test void claimedBambooFiveInBothRiverAndPongCountsAsOnePhysicalTileNotTwo() {
        YmRoom room = room(BAMBOO_FIVE_WAIT);
        Tile claimed = YmTiles.of("B5");
        room.seat(1).discards.add(claimed);
        room.seat(2).melds.add(new YmScoring.Meld("PONG",
            List.of(claimed, YmTiles.of("B5"), YmTiles.of("B5")), 1, claimed.id(), false));
        assertEquals(1, wait(analyze(room), "B5").unseenCount());
        room.seat(1).discards.clear();
        assertEquals(1, wait(analyze(room), "B5").unseenCount());
        room.seat(1).discards.add(YmTiles.of("B5"));
        assertEquals(0, wait(analyze(room), "B5").unseenCount());
    }

    @Test void bambooFiveCountCannotReadHiddenHandsOrWallEvenForTheirSize() {
        YmRoom room = room(BAMBOO_FIVE_WAIT);
        room.seat(1).discards.addAll(tiles("B5 B5 B5"));
        YmHints.Analysis before = analyze(room);
        assertEquals(1, wait(before, "B5").unseenCount());
        List<Tile> forbidden = new AbstractList<>() {
            @Override public Tile get(int index) { throw new AssertionError("Hints must not inspect hidden tiles"); }
            @Override public int size() { throw new AssertionError("Hints must not inspect hidden tile counts"); }
        };
        room.seat(1).hand = forbidden;
        room.seat(2).hand = forbidden;
        room.wall = forbidden;
        assertEquals(before, analyze(room));
        room.seat(2).discards.add(YmTiles.of("B5"));
        assertEquals(0, wait(analyze(room), "B5").unseenCount());
    }

    @Test void hypotheticallyDiscardedFourthBambooFiveStaysKnownAndDoesNotInventAnUnseenCopy() {
        YmRoom room = room(BAMBOO_FIVE_WAIT + " B5");
        room.phase = NEED_DISCARD;
        room.seat(1).discards.addAll(tiles("B5 B5 B5"));
        YmHints.Discard option = discard(analyze(room), "B5");
        YmHints.Wait candidate = option.waits().stream().filter(wait -> YmTiles.code(wait.tile()).equals("B5")).findFirst().orElseThrow();
        assertEquals(0, candidate.unseenCount());
        assertTrue(candidate.canTsumo());
        assertFalse(candidate.canRon());
        assertFalse(candidate.ronReason().contains("曾打过同种牌"));
        assertTrue(candidate.ronReason().contains("不足 4 番"));
        assertTrue(room.seat(0).hand.stream().anyMatch(tile -> tile.id().equals(option.tile().id())));
        assertTrue(room.seat(0).discardedCodes.isEmpty());
        assertTrue(room.seat(0).discards.isEmpty());
    }

    @Test void impossibleFifthOwnCopyIsNeverListed() {
        YmRoom room = room("B1 B2 B3 B4 B5 B6 B5 B5 B5 D1 D2 D3 B5");
        assertFalse(analyze(room).waits().stream().anyMatch(wait -> YmTiles.code(wait.tile()).equals("B5")));
    }

    @Test void duplicatePublicTileIdIsCountedOnlyOnce() {
        YmRoom room = room(HONORS);
        Tile claimed = YmTiles.of("H6");
        room.seat(1).discards.add(claimed);
        room.seat(2).discards.add(claimed);
        room.seat(2).melds.add(new YmScoring.Meld("PONG", List.of(claimed, YmTiles.of("H6"), YmTiles.of("H6")), 1, claimed.id(), false));
        assertEquals(0, wait(analyze(room), "H6").unseenCount());
        room.seat(2).melds.clear();
        assertEquals(2, wait(analyze(room), "H6").unseenCount());
    }

    @Test void changingHiddenTilesIncludingTheirCountAndOrderCannotChangeAnalysis() {
        YmRoom room = room(HONORS);
        YmHints.Analysis before = analyze(room);
        room.seat(1).hand.addAll(tiles("H6 H6 H6 H6"));
        room.seat(2).hand.addAll(tiles("B1 B2 B3"));
        room.wall.addAll(tiles("H6 H6 H6 H6 B1"));
        assertEquals(before, analyze(room));
        room.seat(1).hand = null;
        room.seat(2).hand = null;
        room.wall = null;
        assertEquals(before, analyze(room));
    }

    @Test void fourteenEquivalentTilesOfferUniqueLegalPhysicalDiscardIds() {
        YmRoom room = room(HONORS + " H7");
        room.phase = NEED_DISCARD;
        YmHints.Analysis hints = analyze(room);
        assertEquals("DISCARD", hints.mode());
        assertTrue(hints.waits().isEmpty());
        assertEquals(room.seat(0).hand.stream().map(YmTiles::code).distinct().count(), hints.discards().size());
        Set<String> handIds = new HashSet<>(room.seat(0).hand.stream().map(Tile::id).toList());
        assertTrue(hints.discards().stream().allMatch(discard -> handIds.contains(discard.tile().id())));
        assertTrue(discard(hints, "H7").waits().stream().anyMatch(wait -> YmTiles.code(wait.tile()).equals("H6")));
    }

    @Test void hypotheticalDiscardRemainsKnownButDoesNotRestrictSameTileRon() {
        YmRoom room = room(HONORS + " H6");
        room.phase = NEED_DISCARD;
        YmHints.Discard discard = discard(analyze(room), "H6");
        YmHints.Wait candidate = discard.waits().stream().filter(wait -> YmTiles.code(wait.tile()).equals("H6")).findFirst().orElseThrow();
        assertEquals(2, candidate.unseenCount());
        assertTrue(candidate.canTsumo());
        assertTrue(candidate.canRon());
        assertEquals("", candidate.ronReason());
        assertTrue(room.seat(0).discardedCodes.isEmpty());
        assertTrue(room.seat(0).discards.isEmpty());
    }

    @Test void thirteenTileWaitsRemainAvailableDuringOthersTurnAndReaction() {
        YmRoom room = room(HONORS);
        room.currentSeat = 1;
        room.phase = NEED_DISCARD;
        assertEquals("WAIT", analyze(room).mode());
        room.phase = REACTION;
        room.currentSeat = -1;
        assertEquals("WAIT", analyze(room).mode());
    }

    @Test void fourteenTilesAreUnavailableOutsideTheOwnersDiscardPhase() {
        YmRoom room = room(HONORS + " H7");
        assertEquals("UNAVAILABLE", analyze(room).mode());
        room.phase = NEED_DISCARD;
        room.currentSeat = 1;
        assertEquals("UNAVAILABLE", analyze(room).mode());
        room.currentSeat = 0;
        assertEquals("DISCARD", analyze(room).mode());
    }

    @Test void inactiveOrMalformedHandsAndOutsidersAreUnavailable() {
        YmRoom room = room(HONORS);
        for (YmRoom.Phase phase : List.of(WAITING, HAND_END, MATCH_END)) {
            room.phase = phase;
            assertEquals("UNAVAILABLE", analyze(room).mode());
        }
        room.phase = NEED_DRAW;
        room.seat(0).hand.removeLast();
        assertEquals("UNAVAILABLE", analyze(room).mode());
        assertEquals("UNAVAILABLE", YmHints.analyze(room, new YmRoom.Player()).mode());
        assertEquals("UNAVAILABLE", YmHints.analyze(null, room.seat(0)).mode());
        assertEquals("UNAVAILABLE", YmHints.analyze(room, null).mode());
        room.seat(0).hand.add(YmTiles.of("H6"));
        room.seat(0).left = true;
        assertEquals("UNAVAILABLE", analyze(room).mode());
    }

    @Test void analysisDoesNotMutateRoomOrPlayerAndReturnsImmutableCollections() {
        YmRoom room = room(HONORS + " H7");
        room.phase = NEED_DISCARD;
        room.seat(0).passedCodes.add("H6");
        room.seat(0).discardedCodes.add("D1");
        List<Tile> hand = List.copyOf(room.seat(0).hand);
        Set<String> passed = Set.copyOf(room.seat(0).passedCodes);
        Set<String> discarded = Set.copyOf(room.seat(0).discardedCodes);
        long version = room.version;
        long deadline = room.deadlineAt;
        YmHints.Analysis hints = analyze(room);
        assertEquals(hand, room.seat(0).hand);
        assertEquals(passed, room.seat(0).passedCodes);
        assertEquals(discarded, room.seat(0).discardedCodes);
        assertEquals(version, room.version);
        assertEquals(deadline, room.deadlineAt);
        assertTrue(room.seat(0).melds.isEmpty());
        assertTrue(room.seat(0).discards.isEmpty());
        assertTrue(room.events.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> hints.discards().clear());
        assertThrows(UnsupportedOperationException.class, () -> hints.waits().clear());
        assertThrows(UnsupportedOperationException.class, () -> hints.discards().getFirst().waits().clear());
        assertEquals(hints, analyze(room));
    }

    @Test void everyCandidateAgreesWithActualEvaluatorAtCurrentSeatAndRoundWinds() {
        YmRoom room = room("B1 B2 B3 D4 D5 D6 H1 H1 H1 H5 H5 H5 W9");
        room.round = 4;
        room.dealerSeat = 2;
        for (YmHints.Wait candidate : analyze(room).waits()) {
            List<Tile> completed = new ArrayList<>(room.seat(0).hand);
            completed.add(candidate.tile());
            YmScoring.Evaluation ron = YmScoring.evaluate(completed, room.seat(0).melds, 2, 2, false, false);
            YmScoring.Evaluation tsumo = YmScoring.evaluate(completed, room.seat(0).melds, 2, 2, true, false);
            assertEquals(ron.eligible(), candidate.canRon());
            assertEquals(ron.fan(), candidate.ronFan());
            assertEquals(tsumo.eligible(), candidate.canTsumo());
            assertEquals(tsumo.fan(), candidate.tsumoFan());
        }
        assertFalse(analyze(room).waits().isEmpty());
    }

    @Test void reservedHintIdCannotInvalidateOtherwiseLegalHand() {
        YmRoom room = room(HONORS);
        Tile original = room.seat(0).hand.getLast();
        room.seat(0).hand.set(room.seat(0).hand.size() - 1,
            new Tile("ym-hint-H6", original.suit(), original.rank(), original.label(), false));
        YmHints.Wait candidate = wait(analyze(room), "H6");
        assertTrue(candidate.canRon());
        assertNotEquals("ym-hint-H6", candidate.tile().id());
    }
}
