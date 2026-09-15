package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class YmFourPlayerServiceReplayTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<YmService> services = new ArrayList<>();
    private YmService service() {
        YmService service = new YmService(mapper, directory.resolve("rooms.json"), new Random(79), Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC));
        services.add(service); return service;
    }
    @AfterEach void close() { services.forEach(YmService::closeStreams); }
    private RoomView view(YmService service, Identity id) { return service.view(id.roomId(), id.playerId(), id.token()); }
    private RoomView action(YmService service, Identity id, String type) {
        return service.action(id.roomId(), id.playerId(), id.token(), view(service, id).version(), UUID.randomUUID().toString(), type, List.of());
    }
    private List<Identity> started(YmService service) {
        Identity host = service.create("四人房", "东", "yaoming-4p");
        List<Identity> ids = new ArrayList<>(List.of(host));
        for (String name : List.of("南", "西", "北")) ids.add(service.join(host.roomId(), name));
        ids.forEach(id -> action(service, id, "READY")); return ids;
    }

    @Test void rulesetsExposeDistinctCatalogsAndUnknownIdsDoNotCreateRooms() {
        YmService service = service();
        assertEquals(2, service.rulesets().size()); assertEquals("yaoming-3p", service.rules().id());
        Rules four = service.rules("yaoming-4p");
        assertEquals(4, four.playerCount()); assertEquals(136, four.tileCount()); assertEquals(8, four.totalRounds()); assertEquals(34, four.tiles().size());
        assertEquals(1, four.fans().stream().filter(fan -> fan.id().equals("MENQING")).findFirst().orElseThrow().fan());
        assertTrue(four.fans().stream().noneMatch(fan -> fan.id().equals("FENGLONG")));
        assertEquals(2, four.fans().stream().filter(fan -> fan.name().equals("全不靠")).findFirst().orElseThrow().fan());
        assertTrue(four.description().contains("3番起和"));
        assertTrue(four.notes().stream().anyMatch(note -> note.contains("达到3番后可以和")));
        assertTrue(service.rules().description().contains("4番起和"));
        assertEquals(27, service.rules().tiles().size());
        assertThrows(IllegalArgumentException.class, () -> service.create("非法", "玩家", "future-rules"));
        assertThrows(IllegalArgumentException.class, () -> service.rules("future-rules")); assertTrue(service.list().isEmpty());
    }

    @Test void mixedLobbiesKeepFixedCapacityAndThreeReadyHumansCanAddTheFourthBot() {
        YmService service = service(); Identity legacy = service.create("三人", "旧客户端");
        Identity host = service.create("四人", "房主", "yaoming-4p"), second = service.join(host.roomId(), "玩家二"), third = service.join(host.roomId(), "玩家三");
        for (Identity id : List.of(host, second, third)) action(service, id, "READY");
        assertEquals("WAITING", view(service, host).status()); assertEquals(4, view(service, host).capacity());
        assertThrows(IllegalArgumentException.class, () -> action(service, second, "ADD_BOT"));
        action(service, host, "ADD_BOT"); assertEquals("NEED_DRAW", view(service, host).status()); assertEquals(4, view(service, host).players().size());
        assertThrows(IllegalArgumentException.class, () -> service.join(host.roomId(), "第五位"));
        assertThrows(IllegalArgumentException.class, () -> action(service, host, "ADD_BOT"));
        Summary four = service.list().stream().filter(summary -> summary.id().equals(host.roomId())).findFirst().orElseThrow();
        assertEquals("yaoming-4p", four.ruleId()); assertEquals(4, four.capacity()); assertEquals(YmRules.FOUR_PLAYER.displayName(), four.ruleName());
        assertEquals("WAITING", view(service, legacy).status()); assertEquals(3, view(service, legacy).capacity()); assertEquals("yaoming-3p", view(service, legacy).ruleId());
    }

    @Test void fourPrivateViewsHaveEveryWindButOnlyTheOwnersHandAndRestoreTheSameRuleAndEntities() throws Exception {
        YmService service = service(); List<Identity> ids = started(service);
        Map<String, RoomView> before = new LinkedHashMap<>();
        for (Identity id : ids) {
            RoomView view = view(service, id); before.put(id.playerId(), view);
            assertEquals(Set.of("东", "南", "西", "北"), new HashSet<>(view.players().stream().map(PlayerView::wind).toList()));
            assertEquals(84, view.wallCount()); assertEquals(4, view.capacity());
            for (PlayerView player : view.players()) assertEquals(player.id().equals(id.playerId()) ? 13 : 0, player.hand().size());
            String json = mapper.writeValueAsString(view);
            for (Identity identity : ids) assertFalse(json.contains(identity.token()));
        }
        assertThrows(IllegalArgumentException.class, () -> service.view(ids.getFirst().roomId(), ids.get(1).playerId(), ids.getFirst().token()));
        YmService restored = service();
        for (Identity id : ids) {
            RoomView after = view(restored, id);
            assertEquals("yaoming-4p", after.ruleId()); assertEquals(before.get(id.playerId()).version(), after.version());
            assertEquals(before.get(id.playerId()).players().stream().map(PlayerView::hand).toList(), after.players().stream().map(PlayerView::hand).toList());
            assertEquals(id, restored.resume(id.roomId(), id.token()));
        }
        assertEquals(136, restored.state(ids.getFirst().roomId()).rules().tileCount());
    }

    @Test void explicitNullAndMissingLegacyRoomRulesDefaultToThreeWithoutInferringOccupiedSeats() throws Exception {
        YmService service = service(); Identity first = service.create("旧单人等待房", "甲"), second = service.create("旧空字段房", "乙");
        ObjectNode snapshot = (ObjectNode) mapper.readTree(directory.resolve("rooms.json").toFile());
        ((ObjectNode) snapshot.withArray("rooms").get(0)).remove("ruleId");
        ((ObjectNode) snapshot.withArray("rooms").get(1)).putNull("ruleId");
        mapper.writeValue(directory.resolve("rooms.json").toFile(), snapshot);
        YmService restored = service();
        for (Identity id : List.of(first, second)) {
            assertEquals("yaoming-3p", view(restored, id).ruleId()); assertEquals(3, view(restored, id).capacity());
            assertEquals(1, view(restored, id).players().size()); assertEquals("WAITING", view(restored, id).status());
        }
    }

    @Test void unknownDurableRuleFailsClosedWithoutOverwritingTheOriginalSnapshot() throws Exception {
        YmService service = service(); service.create("未知规则测试", "玩家");
        Path file = directory.resolve("rooms.json"); ObjectNode snapshot = (ObjectNode) mapper.readTree(file.toFile());
        ((ObjectNode) snapshot.withArray("rooms").get(0)).put("ruleId", "unsupported-rules"); mapper.writeValue(file.toFile(), snapshot);
        byte[] original = Files.readAllBytes(file);
        assertThrows(IllegalArgumentException.class, this::service); assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void fourthPlayerCommandsRetainDuplicateAndStaleProtectionAndFailedCommitKeepsTheRule() throws Exception {
        YmService service = service(); Identity id = service.create("持久化测试", "玩家", "yaoming-4p"); long version = view(service, id).version();
        service.action(id.roomId(), id.playerId(), id.token(), version, "ready-once", "READY", List.of());
        RoomView duplicate = service.action(id.roomId(), id.playerId(), id.token(), version, "ready-once", "READY", List.of());
        assertEquals(version + 1, duplicate.version()); assertTrue(duplicate.players().getFirst().ready());
        assertThrows(YmService.VersionConflict.class, () -> service.action(id.roomId(), id.playerId(), id.token(), version, "new-id", "READY", List.of()));
        Path file = directory.resolve("rooms.json"), backup = directory.resolve("saved.json");
        Files.move(file, backup); Files.createDirectory(file); Files.writeString(file.resolve("blocker"), "test");
        assertThrows(IllegalStateException.class, () -> action(service, id, "ADD_BOT"));
        assertEquals("yaoming-4p", service.state(id.roomId()).ruleId); assertEquals(1, service.state(id.roomId()).players.size());
        Files.delete(file.resolve("blocker")); Files.delete(file); Files.move(backup, file);
        action(service, id, "ADD_BOT"); assertEquals(2, view(service, id).players().size()); assertEquals(4, view(service, id).capacity());
    }

    @Test void completedFourPlayerReplayKeepsMetadataAfterAllPlayersLeaveAndRestartWhileLiveHandsStayPrivate() throws Exception {
        YmService service = service(); List<Identity> ids = started(service); String roomId = ids.getFirst().roomId();
        assertEquals(4, service.replays(roomId, ids.getFirst().playerId(), ids.getFirst().token()).capacity());
        assertTrue(service.replays(roomId, ids.getFirst().playerId(), ids.getFirst().token()).hands().isEmpty());
        assertThrows(NoSuchElementException.class, () -> service.replay(roomId, 1, ids.getFirst().playerId(), ids.getFirst().token()));
        YmRoom room = service.state(roomId); room.round = 8; room.dealerSeat = 3; room.currentSeat = 3;
        room.seat(0).discards.addAll(room.wall); room.wall.clear();
        Identity current = ids.stream().filter(id -> id.playerId().equals(room.seat(3).id)).findFirst().orElseThrow();
        action(service, current, "DRAW"); assertEquals(MATCH_END, room.phase);
        YmReplay.HandRecord record = service.replay(roomId, 8, ids.getFirst().playerId(), ids.getFirst().token());
        assertEquals("yaoming-4p", record.ruleId()); assertEquals(4, record.capacity()); assertEquals("南4局", record.roundLabel());
        assertTrue(record.complete()); assertEquals(4, record.frames().getLast().players().size());
        String json = mapper.writeValueAsString(record); for (Identity id : ids) assertFalse(json.contains(id.token()));
        for (Identity id : ids) action(service, id, "LEAVE"); assertEquals(1, service.list().size());
        service.advance(1_000_000 + YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS);
        assertTrue(service.list().isEmpty());
        YmService restored = service();
        for (Identity id : ids) {
            assertEquals(record, restored.replay(roomId, 8, id.playerId(), id.token()));
            YmService.ReplayList list = restored.replays(roomId, id.playerId(), id.token());
            assertEquals(4, list.capacity()); assertEquals("yaoming-4p", list.ruleId());
            assertEquals(4, list.hands().getFirst().capacity()); assertEquals("yaoming-4p", list.hands().getFirst().ruleId());
        }
        assertThrows(IllegalArgumentException.class, () -> restored.replay(roomId, 8, ids.getFirst().playerId(), ids.get(1).token()));
    }

    @Test void missingLegacyReplayMetadataDefaultsToThreeAndNeverRecalculatesStoredResults() throws Exception {
        Result historical = new Result(false, true, "旧版已确认结算", "p0", null, 17, 8, List.of(), List.of(), List.of(), List.of(), "原始理由");
        YmReplay.HandRecord original = new YmReplay.HandRecord("old", "旧房", 6, "南3局", 1L, 2L, true, false, List.of(), historical);
        ObjectNode json = mapper.valueToTree(original); json.remove(List.of("ruleId", "ruleName", "capacity"));
        YmReplay.HandRecord restored = mapper.treeToValue(json, YmReplay.HandRecord.class);
        assertEquals("yaoming-3p", restored.ruleId()); assertEquals(3, restored.capacity()); assertEquals(historical, restored.result());
        assertEquals(17, restored.result().rawFan()); assertEquals("南3局", restored.roundLabel());
    }

    @Test void httpAcceptsOptionalRuleIdListsBothRulesAndRejectsUnknownVariants() throws Exception {
        YmService service = service(); var mvc = MockMvcBuilders.standaloneSetup(new YmController(service)).build();
        mvc.perform(get("/api/yaoming/rules")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("yaoming-3p"));
        mvc.perform(get("/api/yaoming/rules").param("ruleId", "yaoming-4p")).andExpect(status().isOk()).andExpect(jsonPath("$.tileCount").value(136));
        mvc.perform(get("/api/yaoming/rulesets")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(post("/api/yaoming/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"四人HTTP\",\"playerName\":\"玩家\",\"ruleId\":\"yaoming-4p\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/yaoming/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"兼容旧客户端\",\"playerName\":\"旧玩家\"}"))
                .andExpect(status().isCreated());
        assertEquals(Set.of("yaoming-3p", "yaoming-4p"), new HashSet<>(service.list().stream().map(Summary::ruleId).toList()));
        mvc.perform(post("/api/yaoming/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"未知\",\"playerName\":\"玩家\",\"ruleId\":\"unknown\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/yaoming/rules").param("ruleId", "unknown")).andExpect(status().isBadRequest());
        assertEquals(2, service.list().size());
    }
}
