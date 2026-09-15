package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mahjong.domain.Tile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.junit.jupiter.api.Assertions.*;

class YmReactionRestoreTest {
    private static final String RON_B3 = "B1 B2 B1 B2 B3 D1 D1 D1 W1 W1 W1 D3 D3";
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-09T08:00:00Z"), ZoneOffset.UTC);
    private final List<YmService> opened = new ArrayList<>();
    private record Setup(YmService service, Identity[] ids, String roomId, int winnerSeat) {}

    @AfterEach void close() { opened.forEach(YmService::closeStreams); }
    private Path file() { return directory.resolve("rooms.json"); }
    private YmService service() {
        YmService service = new YmService(mapper, file(), new Random(31), clock); opened.add(service); return service;
    }
    private Identity seat(Setup setup, int seat) {
        String playerId = setup.service().state(setup.roomId()).seat(seat).id;
        return Arrays.stream(setup.ids()).filter(id -> id.playerId().equals(playerId)).findFirst().orElseThrow();
    }
    private RoomView command(YmService service, Identity id, String type, List<String> ids) {
        return service.action(id.roomId(), id.playerId(), id.token(), service.state(id.roomId()).version,
                UUID.randomUUID().toString(), type, ids);
    }
    private Tile take(YmRoom room, String code) {
        Tile tile = room.wall.stream().filter(value -> YmTiles.code(value).equals(code)).findFirst().orElseThrow();
        room.wall.remove(tile); return tile;
    }
    private Setup window(boolean winnerIsSecondOpponent, String winnerHand) {
        YmService service = service(); Identity first = service.create("响应升级测试", "甲");
        Identity[] ids = {first, service.join(first.roomId(), "乙"), service.join(first.roomId(), "丙")};
        for (Identity id : ids) command(service, id, "READY", List.of());
        YmRoom room = service.state(first.roomId()); int winnerSeat = winnerIsSecondOpponent ? 2 : 1;
        room.wall = YmTiles.deck(); room.window = null; room.lastDiscard = null; room.activeReplay = null;
        room.phase = NEED_DISCARD; room.currentSeat = 0; room.deadlineKind = "DISCARD"; room.deadlineAt = clock.millis() + 30_000;
        for (YmRoom.Player player : room.players) {
            player.hand.clear(); player.discards.clear(); player.discardKinds.clear(); player.melds.clear();
            player.discardedCodes.clear(); player.passedCodes.clear(); player.lastDrawnId = null;
        }
        for (int seat = 0; seat < 3; seat++) {
            String hand = seat == 0 ? "B3" : seat == winnerSeat ? winnerHand : "B3 B3";
            for (String code : hand.split(" ")) room.seat(seat).hand.add(take(room, code));
        }
        room.seat(winnerSeat).discardedCodes.add("B3"); room.seat(winnerSeat).passedCodes.add("B3");
        Setup setup = new Setup(service, ids, room.id, winnerSeat);
        command(service, seat(setup, 0), "DISCARD", List.of(room.seat(0).hand.getFirst().id()));
        assertEquals(REACTION, room.phase); return setup;
    }
    private ObjectNode oldSnapshot(Setup setup, boolean removeSeat, boolean removePass) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(file().toFile());
        ObjectNode offered = (ObjectNode) root.path("rooms").get(0).path("window").path("offered");
        String key = String.valueOf(setup.winnerSeat());
        if (removeSeat) offered.remove(key);
        else {
            ArrayNode actions = mapper.createArrayNode();
            for (var action : offered.path(key))
                if (!action.path("type").asText().equals("WIN") && !(removePass && action.path("type").asText().equals("PASS"))) actions.add(action);
            offered.set(key, actions);
        }
        return root;
    }
    private void writeSnapshot(ObjectNode root, boolean array) throws Exception {
        mapper.writeValue(file().toFile(), array ? root.path("rooms") : root);
    }
    private void conserved(YmRoom room) {
        List<Tile> tiles = new ArrayList<>(room.wall);
        room.players.forEach(p -> { tiles.addAll(p.hand); tiles.addAll(p.discards); p.melds.forEach(m -> tiles.addAll(m.tiles())); });
        assertEquals(108, tiles.size()); assertEquals(108, tiles.stream().map(Tile::id).distinct().count());
        assertEquals(30, room.players.stream().mapToInt(p -> p.score).sum());
    }

    @ParameterizedTest @CsvSource({"false,EXISTING", "true,EXISTING", "false,NO_PASS", "true,NO_PASS", "false,MISSING", "true,MISSING"})
    void restoresMissingRonAndPassOnceWithoutChangingDeadlinesThenAllowsSettlement(boolean array, String priorMode) throws Exception {
        boolean missing = priorMode.equals("MISSING"); Setup setup = window(missing, RON_B3);
        YmRoom prior = setup.service().state(setup.roomId()); long version = prior.version, deadline = prior.deadlineAt, nextBot = prior.nextBotAt;
        writeSnapshot(oldSnapshot(setup, missing, priorMode.equals("NO_PASS")), array);
        YmService restored = service(); YmRoom room = restored.state(setup.roomId());
        assertEquals(version + 1, room.version); assertEquals(deadline, room.deadlineAt); assertEquals(deadline, room.window.deadline);
        assertEquals(nextBot, room.nextBotAt); assertTrue(room.window.responses.isEmpty());
        List<Action> offered = room.window.offered.get(setup.winnerSeat());
        assertEquals(1, offered.stream().filter(action -> action.type().equals("WIN")).count());
        assertEquals(1, offered.stream().filter(action -> action.type().equals("PASS")).count());
        if (!missing) assertTrue(offered.stream().anyMatch(action -> action.type().equals("CHI")), "Existing server-approved chi options must remain intact");
        assertFalse(room.window.offered.containsKey(0), "The discarder cannot ron their own tile");
        assertEquals(version + 1, mapper.readTree(file().toFile()).path("rooms").get(0).path("version").asLong());
        Identity winner = seat(setup, setup.winnerSeat());
        assertThrows(IllegalArgumentException.class, () -> restored.action(room.id, winner.playerId(), seat(setup, 0).token(), room.version,
                "wrong-owner", "WIN", List.of()));
        YmService restarted = service(); YmRoom second = restarted.state(setup.roomId());
        assertEquals(room.version, second.version); assertEquals(offered, second.window.offered.get(setup.winnerSeat()));
        assertEquals(deadline, second.deadlineAt); assertEquals(deadline, second.window.deadline);
        int other = setup.winnerSeat() == 1 ? 2 : 1;
        command(restarted, seat(setup, other), "PASS", List.of());
        RoomView result = command(restarted, winner, "WIN", List.of());
        assertEquals(winner.playerId(), result.result().winnerId()); assertTrue(result.result().fan() >= 4);
        assertTrue(result.lastDiscard().claimed()); conserved(second);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void submittedPassRemainsFinalForThatWindowAndDoesNotReceiveNewWin(boolean array) throws Exception {
        Setup setup = window(false, RON_B3); Identity winner = seat(setup, 1);
        command(setup.service(), winner, "PASS", List.of()); YmRoom prior = setup.service().state(setup.roomId());
        long version = prior.version, deadline = prior.deadlineAt; Map<Integer, Action> responses = Map.copyOf(prior.window.responses);
        writeSnapshot(oldSnapshot(setup, false, false), array);
        YmService restored = service(); YmRoom room = restored.state(setup.roomId());
        long restoredVersion = version + (array ? 1 : 0); // Legacy arrays have no rulebook fingerprint.
        assertEquals(restoredVersion, room.version); assertEquals(responses, room.window.responses);
        assertEquals(deadline, room.deadlineAt); assertEquals(deadline, room.window.deadline);
        assertTrue(room.window.offered.get(1).stream().noneMatch(a -> a.type().equals("WIN")));
        assertThrows(IllegalArgumentException.class, () -> command(restored, winner, "WIN", List.of()));
        assertEquals(responses, room.window.responses); assertEquals(restoredVersion, room.version);
        command(restored, seat(setup, 2), "PASS", List.of()); assertEquals(NEED_DRAW, room.phase); conserved(room);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anotherSubmittedPongIsPreservedWhileUnansweredRonIsRestored(boolean array) throws Exception {
        Setup setup = window(false, RON_B3); YmRoom prior = setup.service().state(setup.roomId());
        Action pong = prior.window.offered.get(2).stream().filter(a -> a.type().equals("PONG")).findFirst().orElseThrow();
        command(setup.service(), seat(setup, 2), "PONG", pong.tileIds()); long deadline = prior.deadlineAt;
        writeSnapshot(oldSnapshot(setup, false, false), array);
        YmService restored = service(); YmRoom room = restored.state(setup.roomId());
        assertEquals(Map.of(2, pong), room.window.responses); assertEquals(deadline, room.deadlineAt);
        RoomView result = command(restored, seat(setup, 1), "WIN", List.of());
        assertEquals(seat(setup, 1).playerId(), result.result().winnerId());
        assertTrue(room.seat(2).melds.isEmpty()); assertEquals(2, room.seat(2).hand.size()); conserved(room);
    }

    @ParameterizedTest @CsvSource({"false,0", "false,-1", "true,0", "true,-1"})
    void expiredWindowsNeverRegainRonOrExtraTime(boolean array, int relativeDeadline) throws Exception {
        Setup setup = window(false, RON_B3); long version = setup.service().state(setup.roomId()).version;
        ObjectNode root = oldSnapshot(setup, false, false), stored = (ObjectNode) root.path("rooms").get(0);
        long expired = clock.millis() + relativeDeadline;
        stored.put("deadlineAt", expired); ((ObjectNode) stored.path("window")).put("deadline", expired); writeSnapshot(root, array);
        YmService restored = service(); YmRoom room = restored.state(setup.roomId());
        assertEquals(version + (array ? 1 : 0), room.version); assertEquals(expired, room.deadlineAt); assertEquals(expired, room.window.deadline);
        assertTrue(room.window.offered.get(1).stream().noneMatch(a -> a.type().equals("WIN")));
        Identity player = seat(setup, 1); RoomView current = restored.view(room.id, player.playerId(), player.token());
        assertEquals(NEED_DRAW.name(), current.status()); assertNull(room.window);
        assertTrue(current.actions().stream().noneMatch(a -> a.type().equals("WIN"))); assertNull(current.result()); conserved(room);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void restoreDoesNotGrantRonToAThreeFanStructuralHand(boolean array) throws Exception {
        Setup setup = window(false, "H1 H2 H3 H5 H6 H7 W1 W5 W9 D1 D2 D3 B3");
        YmRoom prior = setup.service().state(setup.roomId()); long version = prior.version;
        assertFalse(prior.window.offered.containsKey(1));
        writeSnapshot(oldSnapshot(setup, true, false), array);
        YmService restored = service(); YmRoom room = restored.state(setup.roomId());
        long restoredVersion = version + (array ? 1 : 0);
        assertEquals(restoredVersion, room.version); assertFalse(room.window.offered.containsKey(1));
        assertThrows(IllegalArgumentException.class, () -> command(restored, seat(setup, 1), "WIN", List.of()));
        assertEquals(restoredVersion, room.version); assertNull(room.result); conserved(room);
    }
}
