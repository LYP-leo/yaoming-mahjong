package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class YmEngineTest {
    private static final String RON_B3 = "B1 B2 B1 B2 B3 D1 D1 D1 W1 W1 W1 D3 D3";
    private static final String RON_B1_OR_B4 = "B2 B3 B2 B3 B4 B6 B6 B6 B9 B9 B9 B5 B5";
    private static final String DRAGON_WAIT = "H1 H2 H3 H5 H6 H7 B2 B3 B4 D4 D5 D6 B8";
    private final YmEngine engine = new YmEngine(new Random(71));
    private long now = 1_000_000;

    private YmRoom emptyRoom() {
        YmRoom room = new YmRoom(); room.id = "test"; room.name = "回归测试房"; room.hostId = "p0";
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player player = new YmRoom.Player(); player.id = "p" + seat; player.name = "玩家" + seat;
            player.seat = seat; player.token = "token" + seat; room.players.add(player);
        }
        return room;
    }

    /** Physical fixtures are drawn from a single legal deck, never duplicate generated tiles. */
    private YmRoom fixture(String first, String second, String third) {
        YmRoom room = emptyRoom(); room.wall = YmTiles.deck(); room.phase = NEED_DISCARD; room.currentSeat = 0;
        String[] hands = { first, second, third };
        for (int seat = 0; seat < 3; seat++) for (String code : hands[seat].split("\\s+"))
            if (!code.isBlank()) room.seat(seat).hand.add(take(room, code));
        room.players.forEach(player -> YmTiles.sort(player.hand));
        assertConservation(room); return room;
    }

    private Tile take(YmRoom room, String code) {
        Tile tile = room.wall.stream().filter(candidate -> YmTiles.code(candidate).equals(code)).findFirst().orElseThrow();
        room.wall.remove(tile); return tile;
    }

    private Tile tile(YmRoom.Player player, String code) {
        return player.hand.stream().filter(candidate -> YmTiles.code(candidate).equals(code)).findFirst().orElseThrow();
    }

    private void act(YmRoom room, int seat, String type) {
        YmViews.Action action = engine.gameActions(room, room.seat(seat)).stream().filter(candidate -> candidate.type().equals(type)).findFirst()
            .orElseThrow(() -> new AssertionError("No " + type + " for seat " + seat + " in " + room.phase + ": " + engine.gameActions(room, room.seat(seat))));
        engine.perform(room, room.seat(seat), action.type(), action.tileIds(), ++now);
    }

    private void discard(YmRoom room, int seat, String code) {
        engine.perform(room, room.seat(seat), "DISCARD", List.of(tile(room.seat(seat), code).id()), ++now);
    }

    private Set<String> actions(YmRoom room, int seat) {
        return engine.gameActions(room, room.seat(seat)).stream().map(YmViews.Action::type).collect(Collectors.toSet());
    }

    private void assertConservation(YmRoom room) {
        List<Tile> physical = new ArrayList<>(room.wall);
        for (YmRoom.Player player : room.players) {
            physical.addAll(player.hand); physical.addAll(player.discards);
            player.melds.forEach(meld -> physical.addAll(meld.tiles()));
        }
        assertEquals(108, physical.size(), "All entities must be owned by exactly one physical location");
        assertEquals(108, physical.stream().map(Tile::id).distinct().count());
        assertEquals(YmTiles.deck().stream().map(Tile::id).collect(Collectors.toSet()), physical.stream().map(Tile::id).collect(Collectors.toSet()));
        assertEquals(30, room.players.stream().mapToInt(player -> player.score).sum());
        assertTrue(room.players.stream().allMatch(player -> player.score >= 0));
    }

    @Test void allThreeMustReadyAndInitialDealHas39TilesAndValidDice() {
        YmRoom room = emptyRoom(); act(room, 0, "READY"); act(room, 1, "READY");
        assertEquals(WAITING, room.phase);
        act(room, 2, "READY");
        assertEquals(NEED_DRAW, room.phase); assertEquals(room.dealerSeat, room.currentSeat);
        assertEquals(69, room.wall.size());
        assertTrue(room.players.stream().allMatch(player -> player.hand.size() == 13 && player.lastDrawnId == null));
        assertEquals(3, room.dice.opening().size()); assertEquals(3, room.dice.breaking().size());
        assertTrue(Stream.concat(room.dice.opening().stream(), room.dice.breaking().stream()).allMatch(value -> value >= 1 && value <= 6));
        assertEquals(room.dice.breaking().stream().mapToInt(Integer::intValue).sum(), room.dice.breakStack());
        assertEquals((room.dealerSeat + (room.dice.opening().stream().mapToInt(Integer::intValue).sum() - 1) % 3) % 3, room.dice.openingSeat());
        assertConservation(room);
    }

    @Test void seededShuffleCutAndFourTileBatchesFollowDocumentedOrder() {
        Random expectedRandom = new Random(71);
        List<String> seating = new ArrayList<>(List.of("p0", "p1", "p2"));
        Collections.swap(seating, 2, expectedRandom.nextInt(3));
        Collections.swap(seating, 1, expectedRandom.nextInt(2));
        int dealer = 0;
        List<Tile> expected = YmTiles.deck();
        for (int index = 107; index > 0; index--) Collections.swap(expected, index, expectedRandom.nextInt(index + 1));
        int openingSum = expectedRandom.nextInt(1, 7) + expectedRandom.nextInt(1, 7) + expectedRandom.nextInt(1, 7);
        int breakingSum = expectedRandom.nextInt(1, 7) + expectedRandom.nextInt(1, 7) + expectedRandom.nextInt(1, 7);
        int openingSeat = (dealer + (openingSum - 1) % 3) % 3;
        Collections.rotate(expected, -(openingSeat * 36 + breakingSum * 2) % 108);
        List<List<Tile>> expectedHands = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int batch = 0; batch < 3; batch++) for (int step = 0; step < 3; step++) for (int tile = 0; tile < 4; tile++)
            expectedHands.get((dealer + step) % 3).add(expected.removeFirst());
        for (int step = 0; step < 3; step++) expectedHands.get((dealer + step) % 3).add(expected.removeFirst());
        expectedHands.forEach(YmTiles::sort);
        YmRoom room = emptyRoom(); for (int seat = 0; seat < 3; seat++) act(room, seat, "READY");
        assertEquals(dealer, room.dealerSeat); assertEquals(expected, room.wall);
        for (int seat = 0; seat < 3; seat++) {
            assertEquals(seating.get(seat), room.seat(seat).id);
            assertEquals(expectedHands.get(seat), room.seat(seat).hand);
        }
    }

    @Test void drawUsesFrontOnlyCurrentPlayerCanDrawAndInvalidCommandsDoNotMutate() {
        YmRoom room = emptyRoom(); for (int seat = 0; seat < 3; seat++) act(room, seat, "READY");
        int current = room.currentSeat; Tile expected = room.wall.getFirst(); long version = room.version;
        assertThrows(IllegalArgumentException.class, () -> engine.perform(room, room.seat((current + 1) % 3), "DRAW", List.of(), ++now));
        assertEquals(version, room.version); assertEquals(69, room.wall.size());
        act(room, current, "DRAW");
        assertEquals(NEED_DISCARD, room.phase); assertEquals(expected.id(), room.seat(current).lastDrawnId);
        assertEquals(14, room.seat(current).hand.size()); assertEquals(68, room.wall.size());
        assertThrows(IllegalArgumentException.class, () -> engine.perform(room, room.seat(current), "DISCARD", List.of("missing-id"), ++now));
        assertConservation(room);
    }

    @Test void onlyDownstreamSeatCanChi159ManAndClaimTransfersPhysicalTile() {
        YmRoom room = fixture("W5", "W1 W9", "W1 W9"); Tile claimed = tile(room.seat(0), "W5");
        discard(room, 0, "W5");
        assertEquals(REACTION, room.phase); assertTrue(actions(room, 1).contains("CHI")); assertFalse(actions(room, 2).contains("CHI"));
        assertEquals(List.of("W1", "W9"), engine.gameActions(room, room.seat(1)).stream().filter(action -> action.type().equals("CHI")).findFirst().orElseThrow().tileIds()
            .stream().map(id -> room.seat(1).hand.stream().filter(tile -> tile.id().equals(id)).findFirst().orElseThrow()).map(YmTiles::code).toList());
        act(room, 1, "CHI");
        assertEquals(NEED_DISCARD, room.phase); assertEquals(1, room.currentSeat); assertTrue(room.seat(0).discards.isEmpty());
        assertEquals(claimed.id(), room.seat(1).melds.getFirst().claimedTileId());
        assertEquals(List.of("W1", "W5", "W9"), room.seat(1).melds.getFirst().tiles().stream().map(YmTiles::code).toList());
        assertTrue(room.seat(0).discardedCodes.contains("W5")); assertConservation(room);
    }

    @ParameterizedTest @ValueSource(booleans = { true, false })
    void pongBeatsChiRegardlessOfResponseOrder(boolean chiFirst) {
        YmRoom room = fixture("B3", "B1 B2", "B3 B3"); discard(room, 0, "B3");
        act(room, chiFirst ? 1 : 2, chiFirst ? "CHI" : "PONG"); assertEquals(REACTION, room.phase);
        assertTrue(engine.gameActions(room, room.seat(chiFirst ? 1 : 2)).isEmpty());
        act(room, chiFirst ? 2 : 1, chiFirst ? "PONG" : "CHI");
        assertEquals(2, room.currentSeat); assertEquals("PONG", room.seat(2).melds.getFirst().type()); assertTrue(room.seat(1).melds.isEmpty());
        assertConservation(room);
    }

    @ParameterizedTest @ValueSource(booleans = { true, false })
    void ronBeatsPongRegardlessOfResponseOrder(boolean pongFirst) {
        YmRoom room = fixture("B3", "B3 B3", RON_B3); discard(room, 0, "B3");
        assertTrue(actions(room, 2).contains("WIN"));
        act(room, pongFirst ? 1 : 2, pongFirst ? "PONG" : "WIN"); assertEquals(REACTION, room.phase);
        act(room, pongFirst ? 2 : 1, pongFirst ? "WIN" : "PONG");
        assertEquals(MATCH_END, room.phase); assertEquals("p2", room.result.winnerId()); assertTrue(room.seat(1).melds.isEmpty());
        assertConservation(room);
    }

    @ParameterizedTest @ValueSource(booleans = { true, false })
    void nearestRonWinsEvenWhenFartherPlayerRespondsFirst(boolean fartherFirst) {
        YmRoom room = fixture("B3", RON_B3, "B3 H1 H1 H1 H2 H2 H2 H5 H5 H5 H6 H6 H6");
        discard(room, 0, "B3"); assertTrue(actions(room, 1).contains("WIN")); assertTrue(actions(room, 2).contains("WIN"));
        act(room, fartherFirst ? 2 : 1, "WIN"); act(room, fartherFirst ? 1 : 2, "WIN");
        assertEquals("p1", room.result.winnerId()); assertEquals(13, room.seat(2).hand.size()); assertEquals(14, room.seat(1).hand.size());
        assertConservation(room);
    }

    @Test void concealedKongAndAddedKongTakeReplacementFromWallTail() {
        YmRoom concealed = fixture("W1 W1 W1 W1 B2 B3 B4 D4 D5 D6 B7 B8 B9 H3", "", "");
        concealed.seat(0).lastDrawnId = concealed.seat(0).hand.getFirst().id();
        Tile tail = take(concealed, "H3"); concealed.wall.add(tail); int before = concealed.wall.size();
        concealed.seat(0).passedCodes.add("B1"); act(concealed, 0, "CONCEALED_KONG");
        assertEquals(tail.id(), concealed.seat(0).lastDrawnId); assertEquals(before - 1, concealed.wall.size());
        assertTrue(concealed.seat(0).melds.getFirst().concealed()); assertTrue(concealed.seat(0).afterKong);
        assertTrue(concealed.seat(0).passedCodes.isEmpty()); assertConservation(concealed);

        YmRoom added = fixture("W1 B2 B3 B4 D4 D5 D6 B7 B8 B9 H3", "", "");
        List<Tile> oldPong = List.of(take(added, "W1"), take(added, "W1"), take(added, "W1"));
        added.seat(0).melds.add(new YmScoring.Meld("PONG", oldPong, 2, oldPong.getFirst().id(), false));
        added.seat(0).lastDrawnId = tile(added.seat(0), "W1").id(); Tile addedTail = added.wall.getLast();
        act(added, 0, "ADDED_KONG");
        assertEquals(addedTail.id(), added.seat(0).lastDrawnId); assertEquals("KONG", added.seat(0).melds.getFirst().type());
        assertTrue(added.seat(0).melds.getFirst().added());
        assertEquals(4, added.seat(0).melds.getFirst().tiles().size()); assertFalse(added.seat(0).melds.getFirst().concealed());
        assertEquals(2, added.seat(0).melds.getFirst().fromSeat()); assertEquals(oldPong.getFirst().id(), added.seat(0).melds.getFirst().claimedTileId());
        assertConservation(added);
    }

    @Test void openKongTransfersDiscardAndDrawsTailButRequiresAvailableReplacement() {
        YmRoom room = fixture("W1", "W1 W1 W1 B2 B3 B4 D4 D5 D6 B7 B8 B9 H3", "");
        Tile expected = room.wall.getLast(); discard(room, 0, "W1"); act(room, 1, "OPEN_KONG");
        assertEquals(expected.id(), room.seat(1).lastDrawnId); assertTrue(room.seat(1).afterKong); assertConservation(room);
        YmRoom emptyWall = fixture("W1", "W1 W1 W1 B2 B3 B4 D4 D5 D6 B7 B8 B9 H3", "");
        emptyWall.wall.clear(); discard(emptyWall, 0, "W1");
        assertFalse(actions(emptyWall, 1).contains("OPEN_KONG")); assertTrue(actions(emptyWall, 1).contains("PONG"));
    }

    @Test void chiAndPongDoNotAllowImmediateKongOrSelfDrawAndDoNotClearPassedCodes() {
        YmRoom chi = fixture("B3", "B1 B2 W1 W1 W1 W1", ""); chi.seat(1).passedCodes.add("D5");
        discard(chi, 0, "B3"); act(chi, 1, "CHI");
        assertFalse(actions(chi, 1).contains("CONCEALED_KONG")); assertFalse(actions(chi, 1).contains("WIN"));
        assertNull(chi.seat(1).lastDrawnId); assertTrue(chi.seat(1).passedCodes.contains("D5"));
        YmRoom pong = fixture("B3", "B3 B3 W1 W1 W1 W1 D1", ""); pong.seat(1).passedCodes.add("B2");
        discard(pong, 0, "B3"); act(pong, 1, "PONG");
        assertFalse(actions(pong, 1).contains("CONCEALED_KONG")); assertFalse(actions(pong, 1).contains("ADDED_KONG"));
        assertTrue(pong.seat(1).passedCodes.contains("B2")); assertConservation(chi); assertConservation(pong);
    }

    @Test void historicalDiscardAndPassCodesRestrictNeitherRonNorSelfDraw() {
        YmRoom room = fixture("", RON_B1_OR_B4, ""); YmRoom.Player player = room.seat(1);
        Tile one = take(room, "B1"), four = take(room, "B4"); room.wall.add(one); room.wall.add(four);
        assertTrue(engine.canRon(room, player, one, false)); assertTrue(engine.canRon(room, player, four, false));
        player.discardedCodes.add("B1"); assertTrue(engine.canRon(room, player, one, false)); assertTrue(engine.canRon(room, player, four, false));
        player.passedCodes.add("B4"); assertTrue(engine.canRon(room, player, four, false));
        room.phase = NEED_DRAW; room.currentSeat = 1; room.wall.remove(one); room.wall.addFirst(one);
        act(room, 1, "DRAW"); assertTrue(player.passedCodes.isEmpty()); assertTrue(player.discardedCodes.contains("B1"));
        assertTrue(actions(room, 1).contains("WIN")); assertConservation(room);
    }

    @Test void passingOrChoosingPongInsteadOfAnOfferedRonCreatesNoRestriction() {
        YmRoom pass = fixture("B1", RON_B1_OR_B4, ""); discard(pass, 0, "B1"); act(pass, 1, "PASS");
        assertTrue(pass.seat(1).passedCodes.isEmpty()); assertEquals(NEED_DRAW, pass.phase);
        act(pass, 1, "DRAW"); assertTrue(pass.seat(1).passedCodes.isEmpty()); assertConservation(pass);
        YmRoom pong = fixture("B1", "B1 B1 B2 B2 B2 B3 B3 B3 B4 B4 B4 B5 B5", "");
        discard(pong, 0, "B1"); assertTrue(actions(pong, 1).contains("WIN")); act(pong, 1, "PONG");
        assertTrue(pong.seat(1).passedCodes.isEmpty()); discard(pong, 1, "B5");
        assertTrue(pong.seat(1).passedCodes.isEmpty()); assertConservation(pong);
    }

    @Test void responseTimeoutPassesOnlyMissingResponsesWithoutCreatingRonRestriction() {
        YmRoom room = fixture("B3", "B3 B3", RON_B3); discard(room, 0, "B3"); act(room, 1, "PONG");
        long version = room.version; long deadline = room.window.deadline;
        assertFalse(engine.expire(room, deadline - 1)); assertEquals(version, room.version);
        assertTrue(engine.expire(room, deadline)); assertEquals(1, room.currentSeat);
        assertTrue(room.seat(2).passedCodes.isEmpty()); assertEquals(version + 1, room.version); assertConservation(room);
    }

    @Test void aRealEarlierDiscardDoesNotPreventWinningAnotherCopyLater() {
        YmRoom room = fixture("B1", "", RON_B1_OR_B4 + " B1"); room.currentSeat = 2;
        Tile ownDiscard = tile(room.seat(2), "B1"); discard(room, 2, "B1");
        assertTrue(room.seat(2).discardedCodes.contains("B1")); assertEquals(0, room.currentSeat);
        act(room, 0, "DRAW"); discard(room, 0, "B1"); Tile winning = room.lastDiscard.tile();
        assertNotEquals(ownDiscard.id(), winning.id()); assertTrue(actions(room, 2).contains("WIN"));
        act(room, 2, "WIN"); assertEquals(room.seat(2).id, room.result.winnerId());
        assertTrue(room.result.fan() >= 4); assertTrue(room.seat(2).discards.contains(ownDiscard));
        assertTrue(room.lastDiscard.claimed()); assertConservation(room);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void passedRonCanWinSameTypeFromNextOpponentBeforeOwnDraw(boolean timedOut) {
        YmRoom room = fixture("B1", "B1", RON_B1_OR_B4);
        List<Tile> originalHand = List.copyOf(room.seat(2).hand);
        discard(room, 0, "B1"); assertTrue(actions(room, 2).contains("WIN"));
        if (timedOut) assertTrue(engine.expire(room, room.window.deadline)); else act(room, 2, "PASS");
        assertTrue(room.seat(2).passedCodes.isEmpty()); assertEquals(NEED_DRAW, room.phase); assertEquals(1, room.currentSeat);
        act(room, 1, "DRAW"); discard(room, 1, "B1");
        assertEquals(originalHand, room.seat(2).hand); assertNull(room.seat(2).lastDrawnId);
        assertTrue(actions(room, 2).contains("WIN")); act(room, 2, "WIN");
        assertEquals(room.seat(2).id, room.result.winnerId()); assertEquals("B1", YmTiles.code(room.result.winningTile()));
        assertTrue(room.result.fan() >= 4); assertConservation(room);
    }

    @Test void threeFanWindDragonRonIsRejectedButSelfDrawAndKongDiscardQualify() {
        YmRoom ron = fixture("B8", DRAGON_WAIT, ""); discard(ron, 0, "B8");
        assertFalse(actions(ron, 1).contains("WIN")); assertThrows(IllegalArgumentException.class, () -> engine.perform(ron, ron.seat(1), "WIN", List.of(), ++now));
        YmRoom self = fixture(DRAGON_WAIT, "", ""); self.phase = NEED_DRAW;
        Tile completion = take(self, "B8"); self.wall.addFirst(completion); act(self, 0, "DRAW"); act(self, 0, "WIN");
        assertEquals(4, self.result.fan()); assertEquals(List.of("FENGLONG", "BUQIUREN"), self.result.items().stream().map(YmScoring.Fan::id).toList());
        assertEquals(HAND_END, self.phase); assertConservation(self);
        YmRoom cannon = fixture("B8", DRAGON_WAIT, ""); cannon.seat(0).afterKong = true;
        discard(cannon, 0, "B8"); assertTrue(cannon.window.kongDiscard); assertFalse(cannon.seat(0).afterKong);
        act(cannon, 1, "WIN"); assertEquals(4, cannon.result.fan());
        assertEquals(List.of("FENGLONG", "GANGSHANGPAO"), cannon.result.items().stream().map(YmScoring.Fan::id).toList());
        assertConservation(cannon);
    }

    @Test void kongDiscardFlagDoesNotLeakThroughAClaimOrLaterDraw() {
        YmRoom room = fixture("B3", "B1 B2 D5", "D5 D5"); room.seat(0).afterKong = true;
        discard(room, 0, "B3"); assertTrue(room.window.kongDiscard); act(room, 1, "CHI");
        assertFalse(room.seat(1).afterKong); discard(room, 1, "D5"); assertFalse(room.window.kongDiscard);
        act(room, 2, "PASS"); act(room, 2, "DRAW"); assertFalse(room.seat(2).afterKong); assertConservation(room);
    }

    @Test void selfDrawPaysEveryOpponentAndAllThreeAcknowledgementsAreRequiredToRotate() {
        YmRoom room = fixture(DRAGON_WAIT, "", ""); room.phase = NEED_DRAW;
        Tile completion = take(room, "B8"); room.wall.addFirst(completion); act(room, 0, "DRAW"); act(room, 0, "WIN");
        assertEquals(List.of(18, 6, 6), room.players.stream().map(player -> player.score).toList());
        assertEquals(2, room.result.payments().size()); assertTrue(room.result.payments().stream().allMatch(payment -> payment.amount() == 4 && payment.requested() == 4));
        assertEquals(3, room.result.hands().size()); assertEquals(HAND_END, room.phase); assertConservation(room);
        act(room, 0, "ACK"); assertEquals(HAND_END, room.phase); assertTrue(engine.gameActions(room, room.seat(0)).isEmpty());
        act(room, 2, "ACK"); assertEquals(HAND_END, room.phase); act(room, 1, "ACK");
        assertEquals(2, room.round); assertEquals(1, room.dealerSeat); assertEquals(NEED_DRAW, room.phase);
        assertNull(room.result); assertTrue(room.players.stream().noneMatch(player -> player.acknowledged)); assertConservation(room);
    }

    @Test void ronUsesThreeTimesCappedFanAndClampsPaymentAtZero() {
        YmRoom room = fixture("H6", "H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6", "");
        discard(room, 0, "H6"); act(room, 1, "WIN");
        assertEquals(8, room.result.fan()); assertEquals(8, room.result.rawFan());
        assertEquals(1, room.result.payments().size()); assertEquals(24, room.result.payments().getFirst().requested());
        assertEquals(10, room.result.payments().getFirst().amount()); assertEquals(List.of(0, 20, 10), room.players.stream().map(player -> player.score).toList());
        assertEquals(MATCH_END, room.phase); assertTrue(room.result.matchOver()); assertTrue(actions(room, 1).isEmpty());
        assertConservation(room);
    }

    @Test void cappedSelfDrawClampsEachOpponentIndependentlyAndPreservesThirtyPoints() {
        YmRoom room = fixture("H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6 H6", "", "");
        room.seat(0).score = 20; room.seat(1).score = 3; room.seat(2).score = 7;
        room.seat(0).lastDrawnId = tile(room.seat(0), "H6").id(); act(room, 0, "WIN");
        assertEquals(8, room.result.fan()); assertEquals(List.of(3, 7), room.result.payments().stream().map(YmViews.Payment::amount).toList());
        assertTrue(room.result.payments().stream().allMatch(payment -> payment.requested() == 8));
        assertEquals(List.of(30, 0, 0), room.players.stream().map(player -> player.score).toList()); assertConservation(room);
    }

    @Test void sixDrawnHandsRotateOncePerHandAndEndAfterSouthThreeWithoutScoreLoss() {
        YmRoom room = emptyRoom(); for (int seat = 0; seat < 3; seat++) act(room, seat, "READY");
        int initial = room.dealerSeat;
        for (int round = 1; round <= 6; round++) {
            assertEquals(round, room.round); assertEquals((initial + round - 1) % 3, room.dealerSeat);
            assertEquals(List.of("东1局", "东2局", "东3局", "南1局", "南2局", "南3局").get(round - 1), YmEngine.roundLabel(room));
            // Exhausted physical tiles remain on the table; the next front draw ends the hand.
            room.seat(0).discards.addAll(room.wall); room.wall.clear(); act(room, room.currentSeat, "DRAW");
            assertTrue(room.result.draw()); assertTrue(room.result.payments().isEmpty()); assertEquals(List.of(10, 10, 10), room.players.stream().map(player -> player.score).toList());
            assertConservation(room);
            if (round < 6) for (int seat = 0; seat < 3; seat++) act(room, seat, "ACK");
        }
        assertEquals(MATCH_END, room.phase); assertTrue(room.result.matchOver()); engine.startAcknowledgedHand(room, ++now); assertEquals(6, room.round);
    }

    @ParameterizedTest @ValueSource(ints = { 1, 7, 31, 71, 2026, 260907 })
    void botOnlyMatchAlwaysFinishesWithLegalActionsAndPhysicalConservation(int seed) {
        YmEngine bots = new YmEngine(new Random(seed)); YmRoom room = emptyRoom(); room.players.forEach(player -> player.bot = true);
        for (int seat = 0; seat < 3; seat++) bots.perform(room, room.seat(seat), "READY", List.of(), ++now);
        int steps = 0;
        while (room.phase != MATCH_END && steps++ < 3000) {
            assertConservation(room);
            if (room.phase == HAND_END) { bots.startAcknowledgedHand(room, ++now); continue; }
            boolean advanced = false;
            for (YmRoom.Player player : room.players) {
                List<YmViews.Action> legal = bots.gameActions(room, player);
                YmViews.Action chosen = legal.isEmpty() ? null : YmBots.choose(YmBotObservation.capture(room, player), legal);
                if (chosen == null) continue;
                assertTrue(legal.contains(chosen)); bots.perform(room, player, chosen.type(), chosen.tileIds(), ++now); advanced = true; break;
            }
            assertTrue(advanced, "No legal progress at " + room.phase + " round " + room.round);
        }
        assertEquals(MATCH_END, room.phase, "Bot match stalled after " + steps + " actions");
        assertTrue(room.result.matchOver()); assertTrue(room.round >= 1 && room.round <= 6); assertConservation(room);
    }

}
