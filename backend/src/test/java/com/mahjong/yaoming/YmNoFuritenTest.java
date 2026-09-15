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
import java.time.ZoneOffset;
import java.util.*;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.junit.jupiter.api.Assertions.*;

class YmNoFuritenTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-09T08:00:00Z"), ZoneOffset.UTC);
    private final List<YmService> opened = new ArrayList<>();

    @AfterEach void closeServices() { opened.forEach(YmService::closeStreams); }
    private YmService service() {
        YmService service = new YmService(mapper, directory.resolve("rooms.json"), new Random(31), clock);
        opened.add(service); return service;
    }
    private RoomView command(YmService service, Identity id, String type, List<String> tiles) {
        return service.action(id.roomId(), id.playerId(), id.token(), service.state(id.roomId()).version,
                UUID.randomUUID().toString(), type, tiles);
    }
    private Identity seat(YmRoom room, Identity[] ids, int seat) {
        return Arrays.stream(ids).filter(id -> id.playerId().equals(room.seat(seat).id)).findFirst().orElseThrow();
    }
    private Tile take(YmRoom room, String code) {
        Tile tile = room.wall.stream().filter(value -> YmTiles.code(value).equals(code)).findFirst().orElseThrow();
        room.wall.remove(tile); return tile;
    }
    private void fixture(YmRoom room, String first, String second, String third) {
        room.wall = YmTiles.deck(); room.phase = NEED_DISCARD; room.currentSeat = 0;
        room.window = null; room.lastDiscard = null; room.activeReplay = null; room.result = null;
        room.deadlineKind = "DISCARD"; room.deadlineAt = clock.millis() + 30_000;
        room.nextBotAt = clock.millis() + 650;
        for (YmRoom.Player p : room.players) {
            p.hand.clear(); p.discards.clear(); p.discardKinds.clear(); p.melds.clear();
            p.discardedCodes.clear(); p.passedCodes.clear(); p.lastDrawnId = null; p.afterKong = false;
        }
        String[] hands = {first, second, third};
        for (int seat = 0; seat < 3; seat++) for (String code : hands[seat].split("\\s+"))
            if (!code.isBlank()) room.seat(seat).hand.add(take(room, code));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void restoredNonemptyHistoricalSetsDoNotPreventARealRonAndSettlement(boolean arraySnapshot) throws Exception {
        YmService original = service(); Identity a = original.create("兼容存档", "甲");
        Identity[] ids = {a, original.join(a.roomId(), "乙"), original.join(a.roomId(), "丙")};
        for (Identity id : ids) command(original, id, "READY", List.of());
        YmRoom room = original.state(a.roomId());
        fixture(room, "H6", "H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6", "");
        YmRoom.Player winner = room.seat(1); winner.discardedCodes.add("H6"); winner.passedCodes.add("H6");
        // An actual prior river entity backs the historical discard code; it remains public.
        winner.discards.add(take(room, "H6"));
        command(original, seat(room, ids, 2), "TRUSTEE", List.of()); // Persist the fixture without playing a tile.
        if (arraySnapshot) {
            var stored = mapper.readTree(directory.resolve("rooms.json").toFile());
            mapper.writeValue(directory.resolve("rooms.json").toFile(), stored.path("rooms"));
        }
        YmService restored = service(); room = restored.state(a.roomId());
        Identity from = seat(room, ids, 0), winnerId = seat(room, ids, 1);
        assertEquals(Set.of("H6"), room.seat(1).discardedCodes); assertEquals(Set.of("H6"), room.seat(1).passedCodes);
        YmHints.Wait hint = restored.hints(room.id, winnerId.playerId(), winnerId.token()).analysis().waits().stream()
                .filter(wait -> YmTiles.code(wait.tile()).equals("H6")).findFirst().orElseThrow();
        assertTrue(hint.canRon()); assertEquals("", hint.ronReason()); assertEquals(2, hint.unseenCount());
        Tile offered = room.seat(0).hand.getFirst(); command(restored, from, "DISCARD", List.of(offered.id()));
        RoomView offeredView = restored.view(room.id, winnerId.playerId(), winnerId.token());
        assertTrue(offeredView.actions().stream().anyMatch(action -> action.type().equals("WIN")));
        assertEquals(Set.of("H6"), room.seat(1).passedCodes, "Old state is ignored, not required to clear before a win");
        RoomView result = command(restored, winnerId, "WIN", List.of());
        assertEquals("MATCH_END", result.status()); assertEquals(winnerId.playerId(), result.result().winnerId());
        assertEquals(8, result.result().fan()); assertEquals(10, result.result().payments().getFirst().amount());
        assertTrue(result.lastDiscard().claimed());
        assertEquals(30, result.players().stream().mapToInt(PlayerView::score).sum());
        var replay = restored.replay(room.id, room.round, winnerId.playerId(), winnerId.token());
        assertEquals(result.result(), replay.result()); assertEquals(result.result(), replay.frames().getLast().result());
        assertTrue(replay.frames().getLast().lastDiscard().claimed());
        String json = mapper.writeValueAsString(result);
        for (Identity secret : ids) assertFalse(json.contains(secret.token()));
        List<Tile> physical = new ArrayList<>(room.wall);
        room.players.forEach(p -> { physical.addAll(p.hand); physical.addAll(p.discards); p.melds.forEach(m -> physical.addAll(m.tiles())); });
        assertEquals(108, physical.size()); assertEquals(108, physical.stream().map(Tile::id).distinct().count());
    }

    @Test void rulesAndDefaultHintStateTheConfirmedRuleWithoutOldRestrictionText() {
        YmService service = service(); Identity owner = service.create("提示", "甲");
        String rules = String.join(" ", service.rules().notes());
        assertTrue(rules.contains("无振听")); assertTrue(rules.contains("打过或放过同种牌仍可点和"));
        assertFalse(rules.contains("不能再点和")); assertFalse(rules.contains("下一次摸牌前不能"));
        String hint = service.view(owner.roomId(), owner.playerId(), owner.token()).winHint();
        assertTrue(hint.contains("4番起和")); assertTrue(hint.contains("无振听")); assertTrue(hint.contains("仍可点和"));
    }
}
