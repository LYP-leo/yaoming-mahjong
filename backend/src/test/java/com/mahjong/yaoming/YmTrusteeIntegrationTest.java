package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mahjong.domain.Tile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real service/timeout/restore wiring with one physical deck and isolated durable files. */
class YmTrusteeIntegrationTest {
    private static final String WINNING = "H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6 H6";
    private static final String RON = "H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6";
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock();
    private final List<YmService> opened = new ArrayList<>();
    private YmService service;
    private Identity[] identities;

    private static final class MutableClock extends Clock {
        long now = Instant.parse("2026-09-09T00:00:00Z").toEpochMilli();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    @AfterEach void close() { opened.forEach(YmService::closeStreams); }
    private YmService service() {
        YmService value = new YmService(mapper, directory.resolve("rooms.json"), new Random(31), clock);
        opened.add(value); return value;
    }
    private void start() {
        service = service(); Identity a = service.create("纯摸切测试", "甲");
        identities = new Identity[]{a, service.join(a.roomId(), "乙"), service.join(a.roomId(), "丙")};
        for (Identity id : identities) command(id, "READY", List.of());
    }
    private YmRoom room() { return service.state(identities[0].roomId()); }
    private Identity seat(int seat) {
        String playerId = room().seat(seat).id;
        return Arrays.stream(identities).filter(id -> id.playerId().equals(playerId)).findFirst().orElseThrow();
    }
    private RoomView view(Identity id) { return service.view(id.roomId(), id.playerId(), id.token()); }
    private RoomView command(Identity id, String type, List<String> tiles) {
        return service.action(id.roomId(), id.playerId(), id.token(), room().version, UUID.randomUUID().toString(), type, tiles);
    }
    private RoomView choose(int seat, String type) {
        Action action = legal(seat).stream().filter(a -> a.type().equals(type)).findFirst().orElseThrow();
        return command(seat(seat), type, action.tileIds());
    }
    private List<Action> legal(int seat) { return new YmEngine(new Random(1)).gameActions(room(), room().seat(seat)); }
    private Tile take(String code) {
        Tile value = room().wall.stream().filter(t -> YmTiles.code(t).equals(code)).findFirst().orElseThrow();
        room().wall.remove(value); return value;
    }
    private void hands(String first, String second, String third) {
        YmRoom room = room(); room.wall = YmTiles.deck(); room.phase = NEED_DISCARD; room.currentSeat = 0;
        room.window = null; room.lastDiscard = null; room.result = null; room.activeReplay = null;
        room.deadlineKind = "DISCARD"; room.deadlineAt = clock.millis() + YmEngine.DISCARD_TIMEOUT_MS;
        room.nextBotAt = clock.millis() + 650;
        for (YmRoom.Player p : room.players) {
            p.hand.clear(); p.discards.clear(); p.discardKinds.clear(); p.melds.clear(); p.passedCodes.clear(); p.discardedCodes.clear();
            p.lastDrawnId = null; p.afterKong = false; p.bot = false; p.left = false;
            p.trustee = false; p.autoTrustee = false; p.trusteeReason = null; p.lastSeen = clock.millis();
        }
        String[] values = {first, second, third};
        for (int seat = 0; seat < 3; seat++) for (String code : values[seat].split("\\s+"))
            if (!code.isBlank()) room.seat(seat).hand.add(take(code));
    }
    private void automatic(String reason, int seat) {
        YmRoom.Player actor = room().seat(seat);
        switch (reason) {
            case "MANUAL" -> { command(seat(seat), "TRUSTEE", List.of()); clock.now = room().nextBotAt; }
            case "TIMEOUT" -> clock.now = room().deadlineAt;
            case "OFFLINE" -> {
                clock.now += 61_000; room().deadlineAt = clock.millis() + YmEngine.DISCARD_TIMEOUT_MS;
                room().players.forEach(p -> p.lastSeen = clock.millis()); actor.lastSeen = clock.millis() - 61_000;
                room().nextBotAt = clock.millis();
            }
            default -> fail("unexpected reason");
        }
        service.tick(); assertEquals(reason, actor.trusteeReason);
    }
    private void conserved() {
        List<Tile> physical = new ArrayList<>(room().wall);
        room().players.forEach(p -> { physical.addAll(p.hand); physical.addAll(p.discards); p.melds.forEach(m -> physical.addAll(m.tiles())); });
        assertEquals(108, physical.size()); assertEquals(108, physical.stream().map(Tile::id).distinct().count());
        assertEquals(30, room().players.stream().mapToInt(p -> p.score).sum());
    }
    private void tsumogiri(int actorSeat, String drawnId, List<Tile> beforeWall) {
        assertNotNull(room().lastDiscard); assertEquals(drawnId, room().lastDiscard.tile().id());
        assertEquals("TSUMOGIRI", room().lastDiscard.kind()); assertEquals(actorSeat, room().lastDiscard.fromSeat());
        assertEquals("TSUMOGIRI", room().seat(actorSeat).discardKinds.get(drawnId)); assertNull(room().seat(actorSeat).lastDrawnId);
        assertEquals(beforeWall, room().wall); assertNull(room().result); conserved();
    }

    @ParameterizedTest @ValueSource(strings = {"MANUAL", "OFFLINE", "TIMEOUT"})
    void humanAutopilotDiscardsTheWinningDrawInsteadOfWinning(String reason) throws Exception {
        start(); hands(WINNING, "", ""); YmRoom.Player actor = room().seat(0);
        // Two identical green dragons remain distinguishable: discard the first entity, not a rank-based substitute.
        Tile drawn = actor.hand.stream().filter(t -> YmTiles.code(t).equals("H6")).findFirst().orElseThrow();
        Tile retained = actor.hand.getLast(); assertNotEquals(drawn.id(), retained.id()); actor.lastDrawnId = drawn.id();
        assertTrue(legal(0).stream().anyMatch(a -> a.type().equals("WIN"))); List<Tile> wall = List.copyOf(room().wall);
        automatic(reason, 0); tsumogiri(0, drawn.id(), wall); assertTrue(actor.hand.contains(retained));
        String observer = mapper.writeValueAsString(view(seat(1)));
        assertFalse(observer.contains(retained.id())); for (Identity id : identities) assertFalse(observer.contains(id.token()));
        YmReplay.Frame frame = room().activeReplay.frames().getLast(); assertEquals("DISCARD", frame.type());
        assertEquals("TSUMOGIRI", frame.lastDiscard().kind());
        long version = room().version; service.tick(); assertEquals(version, room().version, "650 ms throttle must prevent a second action");
    }

    @ParameterizedTest @ValueSource(strings = {"CONCEALED_KONG", "ADDED_KONG"})
    void humanAutopilotDoesNotTakeALegalSelfKong(String type) {
        start(); hands(type.equals("CONCEALED_KONG") ? "W1 W1 W1 W1 B1 B2 B3 D4 D5 D6 B7 B8 B9 H5" : "W1 B1 B2 B3 D4 D5 D6 B7 B8 B9 H5", "", "");
        YmRoom.Player actor = room().seat(0); Tile drawn = actor.hand.getFirst(); actor.lastDrawnId = drawn.id();
        if (type.equals("ADDED_KONG")) {
            List<Tile> tiles = List.of(take("W1"), take("W1"), take("W1"));
            actor.melds.add(new YmScoring.Meld("PONG", tiles, 1, tiles.getFirst().id(), false));
        }
        assertTrue(legal(0).stream().anyMatch(a -> a.type().equals(type))); List<YmScoring.Meld> melds = List.copyOf(actor.melds);
        List<Tile> wall = List.copyOf(room().wall); automatic("MANUAL", 0);
        tsumogiri(0, drawn.id(), wall); assertEquals(melds, actor.melds);
    }

    @ParameterizedTest @ValueSource(strings = {"WIN", "CHI", "PONG", "OPEN_KONG"})
    void reactionAutopilotOnlyPassesIncludingAValidRon(String offered) {
        start(); hands(offered.equals("WIN") ? "H6" : "B3", switch (offered) {
            case "WIN" -> RON;
            case "CHI" -> "B1 B2 H7";
            case "PONG" -> "B3 B3 H7";
            default -> "B3 B3 B3 H7";
        }, "");
        choose(0, "DISCARD"); assertEquals(REACTION, room().phase);
        assertTrue(legal(1).stream().anyMatch(a -> a.type().equals(offered)));
        List<Tile> before = List.copyOf(room().seat(1).hand); List<Tile> wall = List.copyOf(room().wall);
        automatic("MANUAL", 1);
        assertEquals(NEED_DRAW, room().phase); assertEquals(1, room().currentSeat); assertNull(room().result);
        assertEquals(before, room().seat(1).hand); assertTrue(room().seat(1).melds.isEmpty()); assertEquals(wall, room().wall);
        assertFalse(room().lastDiscard.claimed()); assertEquals("PASS", room().activeReplay.frames().getLast().type());
        assertTrue(room().seat(1).passedCodes.isEmpty(), "Passing, including a legal ron, never creates a Yaoming restriction"); conserved();
    }

    @ParameterizedTest @ValueSource(strings = {"CONCEALED_KONG", "ADDED_KONG", "OPEN_KONG"})
    void enablingAfterAManualKongDiscardsTheActualReplacement(String type) {
        start(); int actorSeat;
        if (type.equals("OPEN_KONG")) {
            hands("W1", "W1 W1 W1 B9", ""); actorSeat = 1; choose(0, "DISCARD");
        } else {
            hands(type.equals("CONCEALED_KONG") ? "W1 W1 W1 W1 B9" : "W1 B9", "", ""); actorSeat = 0;
            room().seat(0).lastDrawnId = room().seat(0).hand.getFirst().id();
            if (type.equals("ADDED_KONG")) {
                List<Tile> tiles = List.of(take("W1"), take("W1"), take("W1"));
                room().seat(0).melds.add(new YmScoring.Meld("PONG", tiles, 1, tiles.getFirst().id(), false));
            }
        }
        String replacement = room().wall.getLast().id(); choose(actorSeat, type);
        assertEquals(replacement, room().seat(actorSeat).lastDrawnId); assertTrue(room().seat(actorSeat).afterKong);
        List<Tile> wall = List.copyOf(room().wall); automatic("MANUAL", actorSeat); tsumogiri(actorSeat, replacement, wall);
    }

    @ParameterizedTest @ValueSource(strings = {"CHI", "PONG"})
    void enablingAfterManualClaimUsesMechanicalRightmostTedashi(String type) {
        start(); hands("B3", type.equals("CHI") ? "B1 B2 H7 W9 D8" : "B3 B3 H7 W9 D8", "");
        choose(0, "DISCARD"); choose(1, type); assertNull(room().seat(1).lastDrawnId);
        String expected = room().seat(1).hand.stream().filter(t -> YmTiles.code(t).equals("H7")).findFirst().orElseThrow().id();
        List<Tile> wall = List.copyOf(room().wall); automatic("MANUAL", 1);
        assertEquals(expected, room().lastDiscard.tile().id()); assertEquals("TEDASHI", room().lastDiscard.kind());
        assertEquals(Map.of(expected, "TEDASHI"), room().seat(1).discardKinds); assertEquals(wall, room().wall); conserved();
    }

    @Test void autoDrawIsFollowedByExactDrawDiscardAtSeparateThrottleTicks() {
        start(); hands("B1 B2 B3 D4 D5 D6 W1 W5 W9 H1 H1 H5 H5", "", "");
        room().phase = NEED_DRAW; room().deadlineKind = "DRAW"; room().deadlineAt = clock.millis() + YmEngine.DRAW_TIMEOUT_MS;
        Tile drawn = room().wall.getFirst(); automatic("MANUAL", 0);
        assertEquals(NEED_DISCARD, room().phase); assertEquals(drawn.id(), room().seat(0).lastDrawnId);
        assertTrue(room().seat(0).discards.isEmpty()); long version = room().version;
        clock.now = room().nextBotAt - 1; service.tick(); assertEquals(version, room().version);
        clock.now++; List<Tile> wall = List.copyOf(room().wall); service.tick(); tsumogiri(0, drawn.id(), wall);
    }

    @Test void cancellationBeforeDueTickDiscardsNoCachedActionAndManualControlWorks() {
        start(); hands("D5 D5 H7", "", ""); room().seat(0).lastDrawnId = room().seat(0).hand.getFirst().id();
        command(seat(0), "TRUSTEE", List.of()); long originallyDue = room().nextBotAt;
        clock.now += 100; command(seat(0), "TRUSTEE", List.of()); long cancelledVersion = room().version;
        assertFalse(room().seat(0).trustee); assertNull(room().seat(0).trusteeReason);
        clock.now = Math.max(originallyDue, room().nextBotAt); service.tick();
        assertEquals(cancelledVersion, room().version); assertTrue(room().seat(0).discards.isEmpty()); assertNull(room().lastDiscard);
        Tile chosen = room().seat(0).hand.getLast(); command(seat(0), "DISCARD", List.of(chosen.id()));
        assertEquals(chosen.id(), room().lastDiscard.tile().id()); assertEquals("TEDASHI", room().lastDiscard.kind()); conserved();
    }

    @Test void reconnectCancelsOfflineAutopilotBeforeItsDueTick() {
        start(); hands("D5 H7", "", ""); room().seat(0).lastDrawnId = room().seat(0).hand.getFirst().id();
        clock.now += 61_000; room().deadlineAt = clock.millis() + 30_000; room().nextBotAt = clock.millis() + 650;
        room().players.forEach(p -> p.lastSeen = clock.millis()); room().seat(0).lastSeen = clock.millis() - 61_000;
        service.tick(); assertEquals("OFFLINE", room().seat(0).trusteeReason); assertTrue(room().seat(0).discards.isEmpty());
        view(seat(0)); assertFalse(room().seat(0).trustee); assertFalse(room().seat(0).autoTrustee);
        long version = room().version; clock.now = room().nextBotAt; service.tick();
        assertEquals(version, room().version); assertTrue(room().seat(0).discards.isEmpty()); conserved();
    }

    @ParameterizedTest @ValueSource(strings = {"MANUAL", "OFFLINE", "TIMEOUT"})
    void restartedTrusteeKeepsReasonAndStillSkipsWinningDraw(String reason) {
        start(); hands(WINNING, "", ""); YmRoom.Player actor = room().seat(0); String drawn = actor.hand.getLast().id();
        actor.lastDrawnId = drawn; actor.trustee = true; actor.autoTrustee = reason.equals("OFFLINE"); actor.trusteeReason = reason;
        command(seat(2), "TRUSTEE", List.of()); // Commit deterministic state using a normal accepted command.
        service = service(); actor = room().seat(0); assertEquals(drawn, actor.lastDrawnId); assertEquals(reason, actor.trusteeReason);
        assertTrue(legal(0).stream().anyMatch(a -> a.type().equals("WIN")));
        clock.now = room().nextBotAt; List<Tile> wall = List.copyOf(room().wall); service.tick(); tsumogiri(0, drawn, wall);
        assertEquals(reason, actor.trusteeReason);
        service = service(); assertEquals("TSUMOGIRI", room().seat(0).discardKinds.get(drawn)); assertNull(room().result); conserved();
    }

    @ParameterizedTest @ValueSource(strings = {"BOT", "LEFT", "BOT_TIMEOUT", "LEFT_TIMEOUT"})
    void botAndDepartedSeatStillTakeASmartWinEvenWhenTrusteeFlagIsSet(String mode) {
        start(); hands(WINNING, "", ""); YmRoom.Player actor = room().seat(0);
        actor.lastDrawnId = actor.hand.getLast().id(); actor.trustee = true; actor.trusteeReason = "MANUAL";
        actor.bot = mode.startsWith("BOT"); actor.left = mode.startsWith("LEFT");
        clock.now = mode.endsWith("_TIMEOUT") ? room().deadlineAt : room().nextBotAt; service.tick();
        assertNotNull(room().result); assertFalse(room().result.draw()); assertEquals(actor.id, room().result.winnerId());
        assertTrue(actor.discards.isEmpty()); assertTrue(actor.discardKinds.isEmpty()); conserved();
    }

    @Test void waitingTrusteePreservesReadyOnceWithoutRepeatedToggle() {
        service = service(); Identity host = service.create("等待托管", "甲"); identities = new Identity[]{host};
        command(host, "TRUSTEE", List.of()); clock.now = room().nextBotAt; service.tick();
        assertEquals(WAITING, room().phase); assertTrue(room().seat(0).ready); long version = room().version;
        clock.now = room().nextBotAt; service.tick(); service.tick();
        assertEquals(version, room().version); assertTrue(room().seat(0).ready);
    }
}
