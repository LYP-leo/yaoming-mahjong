package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.junit.jupiter.api.Assertions.*;

/** Shared scoring and restore-boundary regressions; all rooms/data are synthetic and isolated. */
class YmNineRanksIntegrationTest {
    private static final long NOW = Instant.parse("2026-09-09T08:00:00Z").toEpochMilli();
    private static final String BAD_HONORS = "B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3 H3 H5 H5";
    private static final String VALID_HONORS = "B1 B2 B3 B4 B5 B6 B7 B8 B9 H1 H1 H1 H5 H5";
    private static final String PURE_CONCEALED = "D4 D5 D6 B7 B7 B7 D8 D8 W9 W9 W9";
    private final YmEngine engine = new YmEngine(new Random(37));
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @TempDir Path directory;

    private YmRoom fixture(String concealed, boolean openSequence) {
        YmRoom room = new YmRoom(); room.id = "nine-ranks-local"; room.name = "九数齐回归";
        room.round = 1; room.dealerSeat = 0; room.currentSeat = 0; room.phase = NEED_DISCARD;
        room.deadlineKind = "DISCARD"; room.deadlineAt = NOW + 30_000; room.nextBotAt = NOW + 650;
        room.wall = new ArrayList<>(YmTiles.deck());
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player player = new YmRoom.Player(); player.id = "p" + seat; player.token = "test-only-" + seat;
            player.name = "测试" + seat; player.seat = seat; player.lastSeen = NOW; room.players.add(player);
        }
        YmRoom.Player me = room.seat(0);
        for (String code : concealed.split(" ")) me.hand.add(take(room, code));
        if (openSequence) {
            List<Tile> group = List.of(take(room, "B1"), take(room, "B2"), take(room, "B3"));
            me.melds.add(new YmScoring.Meld("CHI", group, 2, group.getFirst().id(), false));
        }
        for (int seat = 1; seat < 3; seat++) for (int index = 0; index < 13; index++) room.seat(seat).hand.add(room.wall.removeFirst());
        room.players.forEach(player -> YmTiles.sort(player.hand));
        me.lastDrawnId = me.hand.getLast().id();
        assertConservation(room); return room;
    }

    private static Tile take(YmRoom room, String code) {
        Tile tile = room.wall.stream().filter(t -> YmTiles.code(t).equals(code)).findFirst().orElseThrow();
        room.wall.remove(tile); return tile;
    }

    /** Move one winning entity out of the receiver's full hand, then really discard it from seat 2. */
    private Tile offer(YmRoom room, String code) {
        YmRoom.Player receiver = room.seat(0), from = room.seat(2);
        Tile tile = receiver.hand.stream().filter(t -> YmTiles.code(t).equals(code)).findFirst().orElseThrow();
        receiver.hand.remove(tile); receiver.lastDrawnId = null;
        from.hand.add(tile); YmTiles.sort(from.hand); from.lastDrawnId = tile.id();
        room.currentSeat = from.seat;
        engine.perform(room, from, "DISCARD", List.of(tile.id()), NOW);
        assertConservation(room); return tile;
    }

    private YmHints.Wait waitFor(YmRoom room, String code, boolean beforeDiscard) {
        YmHints.Analysis analysis = YmHints.analyze(room, room.seat(0));
        List<YmHints.Wait> waits = beforeDiscard ? analysis.discards().stream()
                .filter(option -> YmTiles.code(option.tile()).equals(code)).findFirst().orElseThrow().waits() : analysis.waits();
        assertEquals(beforeDiscard ? "DISCARD" : "WAIT", analysis.mode());
        return waits.stream().filter(wait -> YmTiles.code(wait.tile()).equals(code)).findFirst().orElseThrow();
    }

    private void assertWait(YmHints.Wait wait, int tsumo, int ron) {
        assertEquals(tsumo, wait.tsumoFan()); assertEquals(ron, wait.ronFan());
        assertEquals(tsumo >= 4, wait.canTsumo()); assertEquals(ron >= 4, wait.canRon());
    }

    @Test void honorsDependentNineRanksNoLongerOffersTsumoToHumanOrBot() throws Exception {
        YmRoom room = fixture(BAD_HONORS, false); YmRoom.Player me = room.seat(0);
        YmScoring.Evaluation score = engine.evaluate(room, me, null, true, false);
        assertTrue(score.validStructure()); assertFalse(score.eligible()); assertEquals(3, score.fan());
        assertFalse(score.items().stream().anyMatch(item -> item.id().equals("JIUSHUQI")));
        assertWait(waitFor(room, "H5", true), 3, 2);
        List<Action> legal = engine.gameActions(room, me);
        assertFalse(legal.stream().anyMatch(action -> action.type().equals("WIN")));
        Action bot = YmBots.choose(YmBotObservation.capture(room, me), legal);
        assertEquals("DISCARD", bot.type()); assertTrue(legal.contains(bot));
        String before = mapper.writeValueAsString(room);
        assertThrows(IllegalArgumentException.class, () -> engine.perform(room, me, "WIN", List.of(), NOW));
        assertEquals(before, mapper.writeValueAsString(room), "Rejecting a stale self-win must not mutate state");
    }

    @Test void honorsDependentWaitIsBelowFourInHintsAndActualDiscardResponse() {
        YmRoom room = fixture(BAD_HONORS, false); Tile discarded = offer(room, "H5");
        assertWait(waitFor(room, "H5", false), 3, 2);
        assertFalse(engine.canRon(room, room.seat(0), discarded, false));
        List<Action> legal = engine.gameActions(room, room.seat(0));
        assertFalse(legal.stream().anyMatch(action -> action.type().equals("WIN")));
        Action bot = YmBots.choose(YmBotObservation.capture(room, room.seat(0)), legal);
        assertTrue(bot == null || !bot.type().equals("WIN")); assertNull(room.result);
    }

    @Test void pureNumericOpenNineRanksSelfDrawSettlesExactlyFour() {
        YmRoom room = fixture(PURE_CONCEALED, true);
        assertWait(waitFor(room, "D8", true), 4, 4);
        takeOfferedWin(room);
        assertSettlement(room, 4, true);
    }

    @Test void pureNumericOpenNineRanksRonAgreesAcrossEngineHintsAndSmartBot() {
        YmRoom room = fixture(PURE_CONCEALED, true); Tile discarded = offer(room, "D8");
        assertEquals(REACTION, room.phase); assertTrue(engine.canRon(room, room.seat(0), discarded, false));
        assertWait(waitFor(room, "D8", false), 4, 4);
        takeOfferedWin(room);
        assertSettlement(room, 4, true); assertTrue(room.lastDiscard.claimed());
    }

    @Test void otherSufficientFansStillAllowHonorSelfDrawWithoutNineRanks() {
        YmRoom room = fixture(VALID_HONORS, false);
        assertWait(waitFor(room, "H5", true), 7, 6);
        takeOfferedWin(room); assertSettlement(room, 7, false);
    }

    @Test void otherSufficientFansStillAllowHonorRonWithoutNineRanks() {
        YmRoom room = fixture(VALID_HONORS, false); Tile discarded = offer(room, "H5");
        assertTrue(engine.canRon(room, room.seat(0), discarded, false));
        assertWait(waitFor(room, "H5", false), 7, 6);
        takeOfferedWin(room); assertSettlement(room, 6, false); assertTrue(room.lastDiscard.claimed());
    }

    private void takeOfferedWin(YmRoom room) {
        YmRoom.Player me = room.seat(0); me.bot = true;
        List<Action> legal = engine.gameActions(room, me);
        assertTrue(legal.stream().anyMatch(action -> action.type().equals("WIN")));
        Action selected = engine.automaticAction(room, me, legal);
        assertEquals("WIN", selected.type()); assertTrue(legal.contains(selected));
        engine.perform(room, me, selected.type(), selected.tileIds(), NOW + 1);
    }

    private void assertSettlement(YmRoom room, int fan, boolean nineRanks) {
        assertNotNull(room.result); assertEquals("p0", room.result.winnerId());
        assertEquals(fan, room.result.rawFan()); assertEquals(fan, room.result.fan());
        assertEquals(nineRanks, room.result.items().stream().anyMatch(item -> item.id().equals("JIUSHUQI")));
        if (nineRanks) assertEquals(4, room.result.items().stream().filter(item -> item.id().equals("JIUSHUQI")).findFirst().orElseThrow().fan());
        assertEquals(1, room.seat(0).hand.stream().filter(tile -> tile.id().equals(room.result.winningTile().id())).count());
        assertEquals(room.result, room.replayHands.getLast().result());
        assertEquals(room.result, room.replayHands.getLast().frames().getLast().result());
        assertConservation(room);
    }

    private YmRoom staleWindow(boolean submitted, boolean expired, boolean pong) {
        YmRoom room = fixture(BAD_HONORS, false); Tile tile = offer(room, "H5");
        room.phase = REACTION; room.currentSeat = -1; room.deadlineKind = "REACTION";
        room.deadlineAt = expired ? NOW - 1 : NOW + 20_000;
        room.window = new YmRoom.Window(); room.window.tile = tile; room.window.fromSeat = 2;
        room.window.deadline = room.deadlineAt;
        room.window.offered.put(0, new ArrayList<>(List.of(Action.of("WIN", "旧点和"), Action.of("PASS", "过"))));
        if (submitted) room.window.responses.put(0, Action.of("WIN", "旧点和"));
        if (pong) {
            YmRoom.Player other = room.seat(1);
            room.wall.add(other.hand.removeLast()); room.wall.add(other.hand.removeLast());
            Tile first = take(room, "H5"), second = take(room, "H5");
            other.hand.add(first); other.hand.add(second); YmTiles.sort(other.hand);
            room.window.offered.put(1, new ArrayList<>(List.of(new Action("PONG", "碰", List.of(first.id(), second.id())), Action.of("PASS", "过"))));
        } else room.window.offered.put(1, new ArrayList<>(List.of(Action.of("PASS", "过"))));
        assertConservation(room); return room;
    }

    @Test void staleRonIsHiddenAndRejectedBeforeAnyStateMutation() throws Exception {
        YmRoom room = staleWindow(false, false, false);
        String before = mapper.writeValueAsString(room);
        assertFalse(engine.gameActions(room, room.seat(0)).stream().anyMatch(action -> action.type().equals("WIN")));
        assertThrows(IllegalArgumentException.class, () -> engine.perform(room, room.seat(0), "WIN", List.of(), NOW));
        assertEquals(before, mapper.writeValueAsString(room)); assertNotNull(room.window);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void restorePrunesStaleWinEvenWhenWindowExpiredAndPersistsTheCorrection(boolean expired) throws Exception {
        YmRoom room = staleWindow(false, expired, false); long deadline = room.deadlineAt, version = room.version;
        Path path = directory.resolve("legacy-" + expired + ".json"); mapper.writeValue(path.toFile(), List.of(room));
        try (ServiceHandle holder = new ServiceHandle(path)) {
            YmService service = holder.service; YmRoom restored = service.state(room.id);
            assertEquals(deadline, restored.deadlineAt); assertEquals(deadline, restored.window.deadline);
            assertEquals(version + 1, restored.version);
            assertFalse(restored.window.offered.get(0).stream().anyMatch(action -> action.type().equals("WIN")));
            assertTrue(restored.events.stream().anyMatch(event -> event.text().contains("规则修正")));
            if (expired) {
                assertDoesNotThrow(service::tick); assertEquals(NEED_DRAW, restored.phase); assertNull(restored.window);
            } else {
                assertDoesNotThrow(() -> engine.perform(restored, restored.seat(0), "PASS", List.of(), NOW));
                assertDoesNotThrow(() -> engine.perform(restored, restored.seat(1), "PASS", List.of(), NOW));
                assertEquals(NEED_DRAW, restored.phase); assertNull(restored.window);
            }
            assertNull(restored.result); assertConservation(restored);
        }
        try (ServiceHandle holder = new ServiceHandle(path)) {
            YmRoom restored = holder.service.state(room.id);
            if (restored.window != null) assertFalse(restored.window.offered.get(0).stream().anyMatch(action -> action.type().equals("WIN")));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void submittedInvalidWinBecomesPassAndDoesNotBlockAnotherPassOrPong(boolean pong) {
        YmRoom room = staleWindow(true, false, pong);
        long deadline = room.deadlineAt;
        assertTrue(engine.restoreRonOptions(room, NOW));
        assertEquals("PASS", room.window.responses.get(0).type());
        assertEquals(deadline, room.deadlineAt); assertEquals(deadline, room.window.deadline);
        assertTrue(room.events.stream().anyMatch(event -> event.text().contains("规则修正") && event.text().contains("按过")));
        Action other = room.window.offered.get(1).stream().filter(action -> action.type().equals(pong ? "PONG" : "PASS")).findFirst().orElseThrow();
        assertDoesNotThrow(() -> engine.perform(room, room.seat(1), other.type(), other.tileIds(), NOW));
        assertEquals(pong ? NEED_DISCARD : NEED_DRAW, room.phase); assertNull(room.window); assertNull(room.result);
        if (pong) { assertEquals(1, room.currentSeat); assertEquals("PONG", room.seat(1).melds.getFirst().type()); }
        assertConservation(room);
    }

    @Test void finalArbitrationRechecksSubmittedOldWinWithoutRestoreAndNeverLeavesANullReactionWindow() {
        YmRoom room = staleWindow(true, false, true);
        Action pong = room.window.offered.get(1).stream().filter(action -> action.type().equals("PONG")).findFirst().orElseThrow();
        assertDoesNotThrow(() -> engine.perform(room, room.seat(1), "PONG", pong.tileIds(), NOW));
        assertEquals(NEED_DISCARD, room.phase); assertEquals(1, room.currentSeat); assertNull(room.window);
        assertEquals("PONG", room.seat(1).melds.getFirst().type()); assertNull(room.result);
        assertTrue(room.events.stream().anyMatch(event -> event.text().contains("规则修正")));
        assertDoesNotThrow(() -> engine.gameActions(room, room.seat(1))); assertConservation(room);
    }

    @Test void expiredSubmittedInvalidWinDoesNotChangeAnotherSubmittedPongOrExtendItsDeadline() {
        YmRoom room = staleWindow(true, true, true); long deadline = room.deadlineAt;
        Action pong = room.window.offered.get(1).stream().filter(action -> action.type().equals("PONG")).findFirst().orElseThrow();
        room.window.responses.put(1, pong);
        assertTrue(engine.restoreRonOptions(room, NOW));
        assertEquals("PASS", room.window.responses.get(0).type()); assertEquals(pong, room.window.responses.get(1));
        assertEquals(deadline, room.deadlineAt); assertEquals(deadline, room.window.deadline);
        assertTrue(assertDoesNotThrow(() -> engine.expire(room, NOW)));
        assertEquals(NEED_DISCARD, room.phase); assertEquals(1, room.currentSeat); assertNull(room.window);
        assertEquals("PONG", room.seat(1).melds.getFirst().type()); assertNull(room.result); assertConservation(room);
    }

    @Test void completedResultAndReplayAreNotReevaluatedDuringOptionMigration() throws Exception {
        YmRoom room = fixture(VALID_HONORS, false); takeOfferedWin(room);
        String before = mapper.writeValueAsString(room);
        assertFalse(engine.restoreRonOptions(room, NOW + 60_000));
        assertEquals(before, mapper.writeValueAsString(room));
    }

    private final class ServiceHandle implements AutoCloseable {
        final YmService service;
        ServiceHandle(Path path) { service = new YmService(mapper, path, new Random(7), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)); }
        @Override public void close() { service.closeStreams(); }
    }

    private static void assertConservation(YmRoom room) {
        List<Tile> tiles = new ArrayList<>(room.wall);
        room.players.forEach(player -> { tiles.addAll(player.hand); tiles.addAll(player.discards); player.melds.forEach(meld -> tiles.addAll(meld.tiles())); });
        assertEquals(108, tiles.size()); assertEquals(108, tiles.stream().map(Tile::id).distinct().count());
        assertEquals(30, room.players.stream().mapToInt(player -> player.score).sum());
    }
}
