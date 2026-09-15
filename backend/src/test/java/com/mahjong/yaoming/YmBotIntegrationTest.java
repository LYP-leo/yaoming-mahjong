package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mahjong.domain.Tile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real scheduler/control/timeout wiring, not a direct call to the strategy.
 * Each room and snapshot is local to @TempDir; no Spring scheduler or network is started.
 */
class YmBotIntegrationTest {
    private static final String LIVE_159_WAIT = "W1 W9 B1 B2 B3 D4 D5 D6 H1 H1 H1 H5 H5 B9";
    private static final String WIND_DRAGON_KONG_TRAP = "H1 H1 H1 H1 H2 H3 H5 H6 H7 B1 B2 B3 W1 W5";
    private static final Set<String> DECK_IDS = YmTiles.deck().stream().map(Tile::id).collect(Collectors.toSet());
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock();
    private final List<YmService> openedServices = new ArrayList<>();
    private YmService service;
    private Identity host;

    private static final class MutableClock extends Clock {
        long now = Instant.parse("2026-09-09T00:00:00Z").toEpochMilli();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    @AfterEach void closeLocalServices() { openedServices.forEach(YmService::closeStreams); }

    @Test void scheduledBotPreserves159WaitDiscardsPhysicalB9AndReloadsExactlyOnce() {
        startServiceRoom();
        YmRoom room = room();
        String actorId = room.players.stream().filter(player -> player.bot).findFirst().orElseThrow().id;
        configureTurn(room, actorId, LIVE_159_WAIT);
        Before before = before(room, actorId);
        long due = room.nextBotAt;
        clock.now = due - 1; service.advance(clock.millis());
        assertEquals(before.version(), room.version); assertTrue(player(room, actorId).discards.isEmpty());
        clock.now = due; service.advance(clock.millis());
        assertExpectedDiscard(room, actorId, "B9", before);
        assertTrue(player(room, actorId).bot);
        assertFalse(player(room, actorId).trustee);
        assertPersisted(room, actorId, "B9", before);
    }

    @Test void scheduledSmartBotTakesLegalRonDespiteLegacyDiscardAndPassHistory() {
        startServiceRoom();
        YmRoom room = room();
        String actorId = room.players.stream().filter(player -> player.bot).findFirst().orElseThrow().id;
        configureTurn(room, actorId, "H1 H1 H1 B2 B3 B4 B4 B5 B6 B7 B8 B9 H3 D9");
        YmRoom.Player actor = player(room, actorId), from = room.seat(1);
        Tile excess = actor.hand.stream().filter(tile -> YmTiles.code(tile).equals("D9")).findFirst().orElseThrow();
        actor.hand.remove(excess); room.wall.add(excess); actor.lastDrawnId = null;
        actor.discardedCodes.add("H3"); actor.passedCodes.add("H3");
        Tile winning = room.wall.stream().filter(tile -> YmTiles.code(tile).equals("H3")).findFirst().orElseThrow();
        room.wall.remove(winning); from.hand.add(winning); YmTiles.sort(from.hand);
        from.lastDrawnId = winning.id(); room.currentSeat = from.seat;
        assertConservation(room);

        YmEngine engine = new YmEngine(new Random(17));
        engine.perform(room, from, "DISCARD", List.of(winning.id()), clock.millis());
        assertEquals(REACTION, room.phase);
        assertTrue(engine.gameActions(room, actor).stream().anyMatch(action -> action.type().equals("WIN")));
        clock.now = room.nextBotAt; service.tick();

        assertNotNull(room.result); assertFalse(room.result.draw()); assertEquals(actorId, room.result.winnerId());
        assertEquals(winning.id(), room.result.winningTile().id()); assertTrue(room.result.fan() >= 4);
        assertTrue(room.lastDiscard.claimed()); assertEquals("TSUMOGIRI", room.lastDiscard.kind());
        assertTrue(actor.bot); assertFalse(actor.trustee);
        assertConservation(room);
    }

    @Test void explicitTrusteeTsumogirisAfter650msAndKeepsManualReasonAcrossReload() {
        startServiceRoom();
        YmRoom room = room();
        configureTurn(room, host.playerId(), LIVE_159_WAIT);
        command(host, "TRUSTEE");
        YmRoom.Player actor = player(room, host.playerId());
        assertFalse(actor.bot); assertTrue(actor.trustee); assertFalse(actor.autoTrustee);
        assertEquals("MANUAL", actor.trusteeReason);
        Before before = before(room, actor.id);
        String drawnId = actor.lastDrawnId;
        clock.now = room.nextBotAt - 1; service.tick();
        assertEquals(before.version(), room.version); assertTrue(actor.discards.isEmpty());
        clock.now++; service.tick();
        assertExpectedDiscard(room, actor.id, "H5", before);
        assertEquals(drawnId, room.lastDiscard.tile().id()); assertEquals("TSUMOGIRI", room.lastDiscard.kind());
        YmRoom restored = assertPersisted(room, actor.id, "H5", before);
        assertTrue(player(restored, actor.id).trustee);
        assertEquals("MANUAL", player(restored, actor.id).trusteeReason);
        assertFalse(player(restored, actor.id).autoTrustee);
    }

    @Test void leaveHandsSeatToSmartBotWithoutRemovingEntitiesAndPersistsTakeover() {
        startServiceRoom();
        YmRoom room = room();
        configureTurn(room, host.playerId(), LIVE_159_WAIT);
        assertNull(command(host, "LEAVE"));
        YmRoom.Player actor = player(room, host.playerId());
        assertTrue(actor.left); assertTrue(actor.trustee); assertFalse(actor.bot);
        assertEquals(3, room.players.size());
        Before before = before(room, actor.id);
        clock.now = room.nextBotAt; service.tick();
        assertExpectedDiscard(room, actor.id, "B9", before);
        YmRoom restored = assertPersisted(room, actor.id, "B9", before);
        assertTrue(player(restored, actor.id).left); assertTrue(player(restored, actor.id).trustee);
        assertEquals("MANUAL", player(restored, actor.id).trusteeReason);
        assertEquals(3, restored.players.size(), "Another local human remains, so the departed seat must not delete this playing room");
    }

    @Test void engineTimeoutDeclinesWindDragonBreakingKongAndDiscardsOneLegalHonorInstead() {
        YmRoom room = new YmRoom(); room.id = "local-timeout-integration";
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player player = new YmRoom.Player(); player.id = "p" + seat; player.name = "测试玩家" + seat;
            player.seat = seat; room.players.add(player);
        }
        configureTurn(room, "p0", WIND_DRAGON_KONG_TRAP);
        YmRoom.Player actor = player(room, "p0");
        actor.lastDrawnId = actor.hand.stream().filter(tile -> YmTiles.code(tile).equals("H1")).findFirst().orElseThrow().id();
        YmEngine engine = new YmEngine(new Random(9));
        Before before = before(room, actor.id);
        assertTrue(before.legal().stream().anyMatch(action -> action.type().equals("CONCEALED_KONG")), "The old unconditional-kong policy must have a real legal kong to choose");
        long deadline = room.deadlineAt;
        assertFalse(engine.expire(room, deadline - 1)); assertEquals(before.version(), room.version);
        assertTrue(engine.expire(room, deadline));
        assertExpectedDiscard(room, actor.id, "H1", before);
        assertTrue(actor.trustee); assertFalse(actor.autoTrustee); assertEquals("TIMEOUT", actor.trusteeReason);
        assertTrue(actor.melds.isEmpty(), "Timeout must use the new policy rather than automatically breaking wind-dragon structure with a kong");
        assertEquals(deadline + YmEngine.DRAW_TIMEOUT_MS, room.deadlineAt);
        long after = room.version;
        assertFalse(engine.expire(room, deadline));
        assertEquals(after, room.version); assertEquals(1, actor.discards.size());
        assertConservation(room);
    }

    private YmService createService() {
        YmService created = new YmService(mapper, directory.resolve("rooms.json"), new Random(42), clock);
        openedServices.add(created); return created;
    }

    private void startServiceRoom() {
        service = createService(); host = service.create("机器人接线隔离测试", "测试甲");
        Identity other = service.join(host.roomId(), "测试乙");
        command(host, "ADD_BOT"); command(host, "READY"); command(other, "READY");
        assertEquals(NEED_DRAW, room().phase);
        assertEquals(1, room().players.stream().filter(player -> player.bot).count());
    }

    private YmRoom room() { return service.state(host.roomId()); }
    private RoomView command(Identity identity, String type) {
        return service.action(identity.roomId(), identity.playerId(), identity.token(), service.state(identity.roomId()).version,
                UUID.randomUUID().toString(), type, List.of());
    }
    private static YmRoom.Player player(YmRoom room, String id) {
        return room.players.stream().filter(player -> player.id.equals(id)).findFirst().orElseThrow();
    }

    /** Deal from the real 108-entity deck, so every unseen tile still has one physical owner. */
    private void configureTurn(YmRoom room, String actorId, String hand) {
        room.wall = new ArrayList<>(YmTiles.deck()); room.window = null; room.result = null; room.lastDiscard = null;
        room.activeReplay = null; room.replayHands.clear(); room.round = 1;
        room.phase = NEED_DISCARD; room.currentSeat = 0; room.dealerSeat = 0; room.initialDealer = 0;
        room.deadlineKind = "DISCARD"; room.deadlineAt = clock.millis() + YmEngine.DISCARD_TIMEOUT_MS;
        room.nextBotAt = clock.millis() + 650;
        int nextSeat = 1;
        for (YmRoom.Player player : room.players) {
            player.seat = player.id.equals(actorId) ? 0 : nextSeat++;
            player.hand.clear(); player.discards.clear(); player.melds.clear(); player.discardedCodes.clear(); player.passedCodes.clear();
            player.lastDrawnId = null; player.afterKong = false; player.lastSeen = clock.millis(); player.score = 10;
        }
        YmRoom.Player actor = player(room, actorId);
        for (String code : hand.split(" ")) {
            Tile physical = room.wall.stream().filter(tile -> YmTiles.code(tile).equals(code)).findFirst().orElseThrow();
            room.wall.remove(physical); actor.hand.add(physical);
        }
        // These remaining early-deck hands cannot claim B9 or H1 in the selected fixtures.
        for (YmRoom.Player opponent : room.players) if (opponent != actor)
            for (int count = 0; count < 13; count++) opponent.hand.add(room.wall.removeFirst());
        room.players.forEach(player -> YmTiles.sort(player.hand));
        actor.lastDrawnId = actor.hand.getLast().id();
        assertEquals(14, actor.hand.size()); assertEquals(68, room.wall.size());
        assertConservation(room);
    }

    private record Before(long version, List<Tile> wall, Map<String, List<Tile>> hands, Map<String, List<Tile>> rivers,
                          Map<String, List<YmScoring.Meld>> melds, Map<String, Integer> scores, List<Action> legal) {}
    private static Before before(YmRoom room, String actorId) {
        Map<String, List<Tile>> hands = new LinkedHashMap<>(), rivers = new LinkedHashMap<>();
        Map<String, List<YmScoring.Meld>> melds = new LinkedHashMap<>(); Map<String, Integer> scores = new LinkedHashMap<>();
        for (YmRoom.Player player : room.players) {
            hands.put(player.id, List.copyOf(player.hand)); rivers.put(player.id, List.copyOf(player.discards));
            melds.put(player.id, List.copyOf(player.melds)); scores.put(player.id, player.score);
        }
        return new Before(room.version, List.copyOf(room.wall), hands, rivers, melds, scores,
                List.copyOf(new YmEngine(new Random(1)).gameActions(room, player(room, actorId))));
    }

    private static void assertExpectedDiscard(YmRoom room, String actorId, String expectedCode, Before before) {
        assertEquals(before.version() + 1, room.version, "Exactly one authoritative action must occur");
        assertEquals(NEED_DRAW, room.phase); assertEquals(1, room.currentSeat);
        assertNotNull(room.lastDiscard); assertFalse(room.lastDiscard.claimed()); assertEquals(0, room.lastDiscard.fromSeat());
        Tile discarded = room.lastDiscard.tile(); assertEquals(expectedCode, YmTiles.code(discarded));
        assertTrue(before.legal().stream().anyMatch(action -> action.type().equals("DISCARD") && action.tileIds().equals(List.of(discarded.id()))),
                "Discard must use an originally legal physical entity ID");
        assertEquals(before.wall(), room.wall, "A discard must not consume or reorder the wall as a replacement draw would");
        for (YmRoom.Player player : room.players) {
            List<Tile> expectedHand = new ArrayList<>(before.hands().get(player.id));
            List<Tile> expectedRiver = new ArrayList<>(before.rivers().get(player.id));
            if (player.id.equals(actorId)) { assertTrue(expectedHand.remove(discarded)); expectedRiver.add(discarded); }
            assertEquals(expectedHand, player.hand); assertEquals(expectedRiver, player.discards);
            assertEquals(before.melds().get(player.id), player.melds); assertEquals(before.scores().get(player.id).intValue(), player.score);
        }
        assertNull(player(room, actorId).lastDrawnId); assertConservation(room);
    }

    private YmRoom assertPersisted(YmRoom previous, String actorId, String code, Before before) {
        long version = previous.version;
        // A bare service constructor restores state but does not execute @Scheduled methods.
        // Freeze the clock before nextBotAt and the next deadline to prevent an extra action.
        assertTrue(clock.millis() < previous.nextBotAt); assertTrue(clock.millis() < previous.deadlineAt);
        YmService restoredService = createService(); YmRoom restored = restoredService.state(previous.id);
        assertNotSame(previous, restored); assertEquals(version, restored.version);
        assertExpectedDiscard(restored, actorId, code, before);
        restoredService.tick();
        assertEquals(version, restored.version); assertExpectedDiscard(restored, actorId, code, before);
        return restored;
    }

    private static void assertConservation(YmRoom room) {
        List<Tile> physical = new ArrayList<>(room.wall);
        room.players.forEach(player -> { physical.addAll(player.hand); physical.addAll(player.discards); player.melds.forEach(meld -> physical.addAll(meld.tiles())); });
        assertEquals(108, physical.size());
        Set<String> ids = physical.stream().map(Tile::id).collect(Collectors.toSet());
        assertEquals(108, ids.size()); assertEquals(DECK_IDS, ids);
        assertEquals(30, room.players.stream().mapToInt(player -> player.score).sum());
        assertTrue(room.players.stream().allMatch(player -> player.score >= 0));
    }
}
