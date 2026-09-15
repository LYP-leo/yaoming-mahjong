package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mahjong.domain.Tile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.junit.jupiter.api.Assertions.*;

class YmDiscardKindTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final YmEngine engine = new YmEngine(new Random(71));
    private final MutableClock clock = new MutableClock();
    private final List<YmService> opened = new ArrayList<>();

    private static final class MutableClock extends Clock {
        long now = Instant.parse("2026-09-09T00:00:00Z").toEpochMilli();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    @AfterEach void closeServices() { opened.forEach(YmService::closeStreams); }

    private YmRoom fixture(String first, String second, String third) {
        YmRoom room = new YmRoom(); room.id = "discard-kind"; room.name = "弃牌类型"; room.hostId = "p0";
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player player = new YmRoom.Player(); player.id = "p" + seat; player.name = "玩家" + seat;
            player.token = "private-token-" + seat; player.seat = seat; room.players.add(player);
        }
        hands(room, first, second, third); return room;
    }

    private void hands(YmRoom room, String first, String second, String third) {
        room.wall = YmTiles.deck(); room.phase = NEED_DISCARD; room.currentSeat = 0;
        room.window = null; room.lastDiscard = null; room.result = null; room.activeReplay = null;
        room.deadlineKind = "DISCARD"; room.deadlineAt = clock.millis() + YmEngine.DISCARD_TIMEOUT_MS;
        room.nextBotAt = clock.millis();
        for (YmRoom.Player player : room.players) {
            player.hand.clear(); player.discards.clear(); player.discardKinds.clear(); player.melds.clear();
            player.discardedCodes.clear(); player.passedCodes.clear(); player.lastDrawnId = null;
            player.afterKong = false; player.lastSeen = clock.millis();
        }
        String[] values = {first, second, third};
        for (int seat = 0; seat < 3; seat++) for (String code : values[seat].split("\\s+"))
            if (!code.isBlank()) room.seat(seat).hand.add(take(room, code));
    }

    private Tile take(YmRoom room, String code) {
        Tile tile = room.wall.stream().filter(t -> YmTiles.code(t).equals(code)).findFirst().orElseThrow();
        room.wall.remove(tile); return tile;
    }

    private void act(YmRoom room, int seat, String type) {
        Action action = engine.gameActions(room, room.seat(seat)).stream().filter(a -> a.type().equals(type)).findFirst().orElseThrow();
        engine.perform(room, room.seat(seat), type, action.tileIds(), clock.millis());
    }

    private void discard(YmRoom room, int seat, Tile tile) {
        engine.perform(room, room.seat(seat), "DISCARD", List.of(tile.id()), clock.millis());
    }

    private YmReplay.Frame frame(YmRoom room) {
        return (room.activeReplay == null ? room.replayHands.getLast() : room.activeReplay).frames().getLast();
    }

    private YmReplay.ReplayPlayer replayPlayer(YmReplay.Frame frame, int seat) {
        return frame.players().stream().filter(p -> p.seat() == seat).findFirst().orElseThrow();
    }

    private void conserved(YmRoom room) {
        List<Tile> tiles = new ArrayList<>(room.wall);
        room.players.forEach(p -> { tiles.addAll(p.hand); tiles.addAll(p.discards); p.melds.forEach(m -> tiles.addAll(m.tiles())); });
        assertEquals(108, tiles.size()); assertEquals(108, tiles.stream().map(Tile::id).distinct().count());
        assertEquals(30, room.players.stream().mapToInt(p -> p.score).sum());
        room.players.forEach(p -> assertTrue(p.discards.stream().map(Tile::id).toList().containsAll(p.discardKinds.keySet())));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void identicalTileCodesAreClassifiedByEntityNotRank(boolean drawnEntity) {
        YmRoom room = fixture("B5 B5", "", ""); YmRoom.Player me = room.seat(0);
        Tile older = me.hand.getFirst(), drawn = me.hand.getLast(); me.lastDrawnId = drawn.id();
        assertTrue(YmTiles.same(older, drawn)); assertNotEquals(older.id(), drawn.id());
        Tile chosen = drawnEntity ? drawn : older; discard(room, 0, chosen);
        String expected = drawnEntity ? "TSUMOGIRI" : "TEDASHI";
        assertEquals(Map.of(chosen.id(), expected), me.discardKinds);
        assertEquals(new LastDiscard(chosen, 0, false, expected), room.lastDiscard);
        assertNull(me.lastDrawnId); assertEquals(Map.of(chosen.id(), expected), replayPlayer(frame(room), 0).discardKinds());
        conserved(room);
    }

    @Test void rejectedDiscardRecordsNothingAndDoesNotClearPrivateDrawId() {
        YmRoom room = fixture("D5", "", ""); YmRoom.Player me = room.seat(0);
        me.lastDrawnId = me.hand.getFirst().id(); long version = room.version;
        assertThrows(IllegalArgumentException.class, () -> engine.perform(room, me, "DISCARD", List.of("not-owned"), clock.millis()));
        assertTrue(me.discardKinds.isEmpty()); assertTrue(me.discards.isEmpty()); assertNull(room.lastDiscard);
        assertNull(room.activeReplay); assertEquals(version, room.version); assertEquals(me.hand.getFirst().id(), me.lastDrawnId);
        conserved(room);
    }

    @ParameterizedTest @ValueSource(strings = {"CHI", "PONG"})
    void claimingPreservesSourceKindAndNextDiscardIsTedashi(String claim) {
        YmRoom room = fixture("B3", claim.equals("CHI") ? "B1 B2 H3" : "B3 B3 H3", "");
        Tile source = room.seat(0).hand.getFirst(); room.seat(0).lastDrawnId = source.id(); discard(room, 0, source);
        YmReplay.Frame pending = frame(room); act(room, 1, claim);
        assertEquals(new LastDiscard(source, 0, true, "TSUMOGIRI"), room.lastDiscard);
        assertTrue(room.seat(0).discardKinds.isEmpty()); assertTrue(room.seat(0).discards.isEmpty());
        assertEquals(Map.of(source.id(), "TSUMOGIRI"), replayPlayer(pending, 0).discardKinds());
        assertTrue(replayPlayer(frame(room), 0).discardKinds().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> replayPlayer(pending, 0).discardKinds().clear());
        assertNull(room.seat(1).lastDrawnId);
        Tile fromHand = room.seat(1).hand.getFirst(); discard(room, 1, fromHand);
        assertEquals("TEDASHI", room.lastDiscard.kind()); assertEquals(Map.of(fromHand.id(), "TEDASHI"), room.seat(1).discardKinds);
        conserved(room);
    }

    @ParameterizedTest @CsvSource({"CONCEALED_KONG,true", "CONCEALED_KONG,false", "ADDED_KONG,true", "ADDED_KONG,false", "OPEN_KONG,true", "OPEN_KONG,false"})
    void everyKongUsesItsActualReplacementDrawEntity(String type, boolean discardReplacement) {
        YmRoom room; int actor;
        if (type.equals("OPEN_KONG")) {
            room = fixture("W1", "W1 W1 W1 B9", ""); actor = 1;
            Tile source = room.seat(0).hand.getFirst(); room.seat(0).lastDrawnId = source.id(); discard(room, 0, source);
        } else {
            room = fixture(type.equals("CONCEALED_KONG") ? "W1 W1 W1 W1 B9" : "W1 B9", "", ""); actor = 0;
            room.seat(actor).lastDrawnId = room.seat(actor).hand.getFirst().id();
            if (type.equals("ADDED_KONG")) {
                List<Tile> tiles = List.of(take(room, "W1"), take(room, "W1"), take(room, "W1"));
                room.seat(actor).melds.add(new YmScoring.Meld("PONG", tiles, 1, tiles.getFirst().id(), false));
            }
        }
        Tile replacement = room.wall.getLast(); act(room, actor, type);
        assertEquals(replacement.id(), room.seat(actor).lastDrawnId); assertTrue(room.seat(actor).afterKong);
        if (type.equals("OPEN_KONG")) {
            assertEquals("TSUMOGIRI", room.lastDiscard.kind()); assertTrue(room.lastDiscard.claimed());
            assertTrue(room.seat(0).discardKinds.isEmpty());
        }
        Tile chosen = discardReplacement ? replacement : room.seat(actor).hand.stream().filter(t -> YmTiles.code(t).equals("B9")).findFirst().orElseThrow();
        discard(room, actor, chosen);
        assertEquals(discardReplacement ? "TSUMOGIRI" : "TEDASHI", room.lastDiscard.kind());
        assertEquals(room.lastDiscard.kind(), room.seat(actor).discardKinds.get(chosen.id())); conserved(room);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ronKeepsTheClaimedKindInFinalFrame(boolean tsumogiri) {
        YmRoom room = fixture("H6", "H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6", "");
        Tile winning = room.seat(0).hand.getFirst(); room.seat(0).lastDrawnId = tsumogiri ? winning.id() : null;
        discard(room, 0, winning); YmReplay.Frame pending = frame(room); act(room, 1, "WIN");
        String expected = tsumogiri ? "TSUMOGIRI" : "TEDASHI";
        assertEquals(MATCH_END, room.phase); assertEquals(new LastDiscard(winning, 0, true, expected), room.lastDiscard);
        assertEquals(room.lastDiscard, frame(room).lastDiscard()); assertTrue(room.seat(0).discardKinds.isEmpty());
        assertEquals(Map.of(winning.id(), expected), replayPlayer(pending, 0).discardKinds());
        assertEquals(1, room.seat(1).hand.stream().filter(t -> t.id().equals(winning.id())).count()); conserved(room);
    }

    @Test void historicalUnknownClaimRemainsUnknown() {
        YmRoom room = fixture("B3", "B3 B3 H3", ""); Tile source = room.seat(0).hand.getFirst();
        discard(room, 0, source); room.seat(0).discardKinds.clear(); room.lastDiscard = new LastDiscard(source, 0, false);
        act(room, 1, "PONG"); assertNull(room.lastDiscard.kind()); assertTrue(room.lastDiscard.claimed());
        assertNull(frame(room).lastDiscard().kind()); conserved(room);
    }

    @Test void newHandClearsEveryRiverKindWithoutMutatingThePreviousFrame() {
        YmRoom room = fixture("H7", "", ""); Tile source = room.seat(0).hand.getFirst();
        discard(room, 0, source); YmReplay.Frame previous = frame(room);
        room.phase = HAND_END; room.players.forEach(p -> p.acknowledged = true);
        engine.startAcknowledgedHand(room, clock.millis());
        assertEquals(2, room.round); assertNull(room.lastDiscard);
        room.players.forEach(p -> { assertTrue(p.discardKinds.isEmpty()); assertTrue(p.discards.isEmpty()); });
        frame(room).players().forEach(p -> assertTrue(p.discardKinds().isEmpty()));
        assertEquals(Map.of(source.id(), "TEDASHI"), replayPlayer(previous, 0).discardKinds()); conserved(room);
    }

    private YmService service() {
        YmService service = new YmService(mapper, directory.resolve("rooms.json"), new Random(31), clock);
        opened.add(service); return service;
    }

    private Identity[] started(YmService service) {
        Identity a = service.create("弃牌类型", "甲"), b = service.join(a.roomId(), "乙"), c = service.join(a.roomId(), "丙");
        Identity[] ids = {a, b, c}; for (Identity id : ids) command(service, id, "READY", List.of()); return ids;
    }

    private Identity identity(YmRoom room, Identity[] identities, int seat) {
        return Arrays.stream(identities).filter(id -> id.playerId().equals(room.seat(seat).id)).findFirst().orElseThrow();
    }

    private RoomView view(YmService service, Identity id) { return service.view(id.roomId(), id.playerId(), id.token()); }
    private RoomView command(YmService service, Identity id, String type, List<String> ids) {
        return service.action(id.roomId(), id.playerId(), id.token(), view(service, id).version(), UUID.randomUUID().toString(), type, ids);
    }

    @ParameterizedTest @CsvSource({"BOT,true", "BOT,false", "MANUAL,true", "MANUAL,false", "OFFLINE,true", "OFFLINE,false", "TIMEOUT,true", "TIMEOUT,false", "LEFT,true", "LEFT,false"})
    void automatedPathsUseTheSameEntityClassification(String mode, boolean drawn) {
        YmService service = service(); Identity[] ids = started(service); YmRoom room = service.state(ids[0].roomId());
        if (mode.equals("OFFLINE")) clock.now += 61_000;
        hands(room, "H3", "", ""); YmRoom.Player actor = room.seat(0); Tile tile = actor.hand.getFirst();
        actor.lastDrawnId = drawn ? tile.id() : null;
        switch (mode) {
            case "BOT" -> actor.bot = true;
            case "MANUAL" -> { actor.trustee = true; actor.trusteeReason = "MANUAL"; }
            case "OFFLINE" -> actor.lastSeen = clock.millis() - 61_000;
            case "TIMEOUT" -> clock.now = room.deadlineAt;
            case "LEFT" -> actor.left = true;
            default -> fail("unexpected automatic path");
        }
        service.advance(clock.millis());
        assertEquals(new LastDiscard(tile, 0, false, drawn ? "TSUMOGIRI" : "TEDASHI"), room.lastDiscard);
        assertEquals(Map.of(tile.id(), room.lastDiscard.kind()), actor.discardKinds);
        assertEquals(actor.discardKinds, replayPlayer(frame(room), 0).discardKinds());
        if (mode.equals("OFFLINE") || mode.equals("TIMEOUT")) assertEquals(mode, actor.trusteeReason);
        conserved(room);
    }

    @Test void publicMetadataDoesNotRevealRetainedDrawOrOpponentHandAndViewsAreImmutable() throws Exception {
        YmService service = service(); Identity[] ids = started(service); YmRoom room = service.state(ids[0].roomId());
        hands(room, "B5 B5", "H1", "H2"); YmRoom.Player actor = room.seat(0);
        Tile discarded = actor.hand.getFirst(), retained = actor.hand.getLast(); actor.lastDrawnId = retained.id();
        Identity owner = identity(room, ids, 0), observer = identity(room, ids, 1);
        String before = mapper.writeValueAsString(view(service, observer));
        assertFalse(before.contains(discarded.id())); assertFalse(before.contains(retained.id()));
        assertEquals(retained.id(), view(service, owner).players().getFirst().drawnTileId());
        RoomView result = command(service, owner, "DISCARD", List.of(discarded.id()));
        for (Identity id : ids) {
            RoomView visible = view(service, id); PlayerView publicActor = visible.players().getFirst();
            assertEquals(Map.of(discarded.id(), "TEDASHI"), publicActor.discardKinds()); assertNull(publicActor.drawnTileId());
            String json = mapper.writeValueAsString(visible);
            for (Identity secret : ids) assertFalse(json.contains(secret.token()));
            if (!id.equals(owner)) { assertTrue(publicActor.hand().isEmpty()); assertFalse(json.contains(retained.id())); }
        }
        Map<String, String> captured = result.players().getFirst().discardKinds();
        assertThrows(UnsupportedOperationException.class, () -> captured.put("fake", "TSUMOGIRI"));
        actor.discardKinds.put(discarded.id(), "TSUMOGIRI");
        assertEquals("TEDASHI", captured.get(discarded.id()));
        assertEquals("TEDASHI", replayPlayer(frame(room), 0).discardKinds().get(discarded.id()));
    }

    @Test void discardMetadataRoundTripsThroughDurableStateAndReplay() throws Exception {
        YmService service = service(); Identity[] ids = started(service); YmRoom room = service.state(ids[0].roomId());
        hands(room, "H3 H7", "", ""); Tile tile = room.seat(0).hand.getFirst(); room.seat(0).lastDrawnId = tile.id();
        Identity owner = identity(room, ids, 0); command(service, owner, "DISCARD", List.of(tile.id()));
        YmService restored = service(); YmRoom loaded = restored.state(room.id);
        assertEquals(Map.of(tile.id(), "TSUMOGIRI"), loaded.seat(0).discardKinds);
        assertEquals(room.lastDiscard, loaded.lastDiscard);
        assertEquals(replayPlayer(frame(room), 0).discardKinds(), replayPlayer(frame(loaded), 0).discardKinds());
        assertEquals(Map.of(tile.id(), "TSUMOGIRI"), view(restored, owner).players().getFirst().discardKinds());
        JsonNode snapshot = mapper.readTree(Files.readString(directory.resolve("rooms.json")));
        assertEquals("TSUMOGIRI", snapshot.path("rooms").get(0).path("lastDiscard").path("kind").asText());
        assertThrows(UnsupportedOperationException.class, () -> replayPlayer(frame(loaded), 0).discardKinds().clear());
        conserved(loaded);
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void missingOrNullOldMapsAreUnknownForBothSnapshotFormats(boolean arrayFormat, boolean explicitNull) throws Exception {
        YmService service = service(); Identity[] ids = started(service); YmRoom room = service.state(ids[0].roomId());
        hands(room, "H3", "", ""); Tile oldTile = room.seat(0).hand.getFirst();
        command(service, identity(room, ids, 0), "DISCARD", List.of(oldTile.id()));
        ObjectNode stored = (ObjectNode) mapper.readTree(Files.readString(directory.resolve("rooms.json")));
        ObjectNode storedRoom = (ObjectNode) stored.path("rooms").get(0);
        for (JsonNode player : storedRoom.path("players")) eraseMap((ObjectNode) player, explicitNull);
        ((ObjectNode) storedRoom.path("lastDiscard")).remove("kind");
        for (JsonNode frame : storedRoom.path("activeReplay").path("frames")) {
            for (JsonNode player : frame.path("players")) eraseMap((ObjectNode) player, explicitNull);
            if (frame.path("lastDiscard").isObject()) ((ObjectNode) frame.path("lastDiscard")).remove("kind");
        }
        mapper.writeValue(directory.resolve("rooms.json").toFile(), arrayFormat ? stored.path("rooms") : stored);
        YmService restored = service(); YmRoom loaded = restored.state(room.id);
        assertNull(loaded.lastDiscard.kind()); loaded.players.forEach(p -> assertTrue(p.discardKinds.isEmpty()));
        loaded.activeReplay.frames().forEach(f -> f.players().forEach(p -> assertTrue(p.discardKinds().isEmpty())));
        assertTrue(view(restored, ids[0]).players().stream().allMatch(p -> p.discardKinds().isEmpty()));
        Identity next = identity(loaded, ids, 1); command(restored, next, "DRAW", List.of());
        String newId = loaded.seat(1).lastDrawnId; command(restored, next, "DISCARD", List.of(newId));
        assertEquals("TSUMOGIRI", loaded.seat(1).discardKinds.get(newId));
        assertTrue(loaded.seat(0).discardKinds.isEmpty()); assertTrue(loaded.seat(0).discards.contains(oldTile));
        conserved(loaded);
    }

    private void eraseMap(ObjectNode player, boolean explicitNull) {
        if (explicitNull) player.putNull("discardKinds"); else player.remove("discardKinds");
    }

    @Test void nullablePublicAndReplayFieldsNormalizeToImmutableEmptyMaps() throws Exception {
        YmService service = service(); Identity[] ids = started(service);
        ObjectNode publicPlayer = mapper.valueToTree(view(service, ids[0]).players().getFirst());
        publicPlayer.putNull("discardKinds"); PlayerView loaded = mapper.treeToValue(publicPlayer, PlayerView.class);
        assertTrue(loaded.discardKinds().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> loaded.discardKinds().put("id", "TEDASHI"));
        YmReplay.ReplayPlayer replay = new YmReplay.ReplayPlayer("p", "玩家", 0, 10, List.of(), List.of(), List.of(), null, null);
        assertTrue(replay.discardKinds().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> replay.discardKinds().put("id", "TEDASHI"));
        LastDiscard old = mapper.readValue("{\"tile\":{\"id\":\"old\",\"suit\":\"HONORS\",\"rank\":1},\"fromSeat\":0,\"claimed\":false}", LastDiscard.class);
        assertNull(old.kind());
    }

    @Test void failedDurableDiscardRollsBackMetadataAndRetryRecordsExactlyOnce() throws Exception {
        YmService service = service(); Identity[] ids = started(service); YmRoom room = service.state(ids[0].roomId());
        hands(room, "H3", "", ""); Tile tile = room.seat(0).hand.getFirst(); room.seat(0).lastDrawnId = tile.id();
        Identity owner = identity(room, ids, 0);
        command(service, identity(room, ids, 2), "TRUSTEE", List.of()); // Commit the deterministic pre-discard fixture.
        Path file = directory.resolve("rooms.json"), saved = directory.resolve("saved.json");
        Files.move(file, saved); Files.createDirectory(file); Files.writeString(file.resolve("blocker"), "intentional fault");
        long version = room.version;
        try {
            assertThrows(IllegalStateException.class, () -> service.action(owner.roomId(), owner.playerId(), owner.token(), version,
                    "discard-once", "DISCARD", List.of(tile.id())));
            YmRoom rolledBack = service.state(room.id);
            assertEquals(version, rolledBack.version); assertTrue(rolledBack.seat(0).discardKinds.isEmpty());
            assertTrue(rolledBack.seat(0).discards.isEmpty()); assertEquals(tile.id(), rolledBack.seat(0).lastDrawnId);
            assertNull(rolledBack.lastDiscard); assertFalse(rolledBack.processed.containsKey(owner.playerId() + ":discard-once"));
        } finally {
            Files.delete(file.resolve("blocker")); Files.delete(file); Files.move(saved, file);
        }
        RoomView accepted = service.action(owner.roomId(), owner.playerId(), owner.token(), version, "discard-once", "DISCARD", List.of(tile.id()));
        RoomView retry = service.action(owner.roomId(), owner.playerId(), owner.token(), version, "discard-once", "DISCARD", List.of(tile.id()));
        assertEquals(accepted.version(), retry.version()); assertEquals(Map.of(tile.id(), "TSUMOGIRI"), retry.players().getFirst().discardKinds());
        assertEquals(List.of(tile), service.state(room.id).seat(0).discards); conserved(service.state(room.id));
    }
}
