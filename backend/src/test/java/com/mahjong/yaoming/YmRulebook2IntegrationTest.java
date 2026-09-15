package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mahjong.domain.Tile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** New-rule legality, hints, bots, payments, and durable migration share one evaluator. */
class YmRulebook2IntegrationTest {
    private static final long NOW = Instant.parse("2026-09-11T08:00:00Z").toEpochMilli();
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    private static final String CLOSED_FLAT = "W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2";
    private static final String OPEN_FLUSH = "B2 B3 B4 B5 B6 B7 B7 B8 B9 B5 B5";
    private static final String OPEN_OUTSIDE = "B1 B2 B3 D7 D8 D9 B9 B9 B9 D1 D1";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final YmEngine engine = new YmEngine(new Random(23));
    private final List<YmService> services = new ArrayList<>();
    @TempDir Path directory;
    @AfterEach void close() { services.forEach(YmService::closeStreams); }

    private YmService service(Path path) {
        YmService service = new YmService(mapper, path, new Random(23), CLOCK);
        services.add(service); return service;
    }
    private YmRoom fixture(String hand, String fixedCodes, String fixedType) {
        YmRoom r = new YmRoom(); r.id = "rulebook2"; r.name = "新版规则隔离测试";
        r.currentSeat = 0; r.phase = NEED_DISCARD; r.deadlineKind = "DISCARD";
        r.deadlineAt = NOW + 30_000; r.nextBotAt = NOW + 650; r.lastActivity = NOW;
        r.wall.addAll(YmTiles.deck());
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player p = new YmRoom.Player(); p.id = "p" + seat; p.name = "玩家" + seat;
            p.token = "test-rulebook2-" + seat; p.seat = seat; p.lastSeen = NOW; r.players.add(p);
        }
        for (String code : hand.split(" ")) r.seat(0).hand.add(take(r, code));
        if (fixedCodes != null) {
            List<Tile> meld = Arrays.stream(fixedCodes.split(" ")).map(code -> take(r, code)).toList();
            r.seat(0).melds.add(new YmScoring.Meld(fixedType, meld, 2, meld.getFirst().id(), false));
        }
        for (int seat = 1; seat < 3; seat++) for (int i = 0; i < 13; i++) r.seat(seat).hand.add(r.wall.removeFirst());
        r.players.forEach(p -> YmTiles.sort(p.hand)); r.seat(0).lastDrawnId = r.seat(0).hand.getLast().id();
        conservation(r); return r;
    }
    private Tile take(YmRoom r, String code) {
        Tile t = r.wall.stream().filter(tile -> YmTiles.code(tile).equals(code)).findFirst().orElseThrow();
        r.wall.remove(t); return t;
    }
    private Tile discardTo(YmRoom r, String code) {
        YmRoom.Player receiver = r.seat(0), from = r.seat(2);
        Tile t = receiver.hand.stream().filter(tile -> YmTiles.code(tile).equals(code)).findFirst().orElseThrow();
        receiver.hand.remove(t); receiver.lastDrawnId = null; from.hand.add(t); from.lastDrawnId = t.id();
        r.currentSeat = 2; engine.perform(r, from, "DISCARD", List.of(t.id()), NOW); conservation(r); return t;
    }
    private void finish(YmRoom r) {
        List<Action> legal = engine.gameActions(r, r.seat(0));
        assertTrue(legal.stream().anyMatch(a -> a.type().equals("WIN")));
        Action bot = YmBots.choose(YmBotObservation.capture(r, r.seat(0)), legal);
        assertEquals("WIN", bot.type()); engine.perform(r, r.seat(0), "WIN", List.of(), NOW + 1);
        for (int seat = 1; seat < 3 && r.phase == REACTION; seat++)
            if (engine.gameActions(r, r.seat(seat)).stream().anyMatch(a -> a.type().equals("PASS")))
                engine.perform(r, r.seat(seat), "PASS", List.of(), NOW + 2);
        assertNotNull(r.result); assertEquals(r.result, r.replayHands.getLast().result()); conservation(r);
    }
    private YmHints.Wait hint(YmRoom r, String code, boolean discardMode) {
        YmHints.Analysis analysis = YmHints.analyze(r, r.seat(0));
        List<YmHints.Wait> waits = discardMode ? analysis.discards().stream()
                .filter(d -> YmTiles.code(d.tile()).equals(code)).findFirst().orElseThrow().waits() : analysis.waits();
        return waits.stream().filter(w -> YmTiles.code(w.tile()).equals(code)).findFirst().orElseThrow();
    }
    private Map<String, Integer> fans(Result result) {
        Map<String, Integer> map = new HashMap<>(); result.items().forEach(f -> map.put(f.id(), f.fan())); return map;
    }
    private void conservation(YmRoom r) {
        List<Tile> all = new ArrayList<>(r.wall);
        r.players.forEach(p -> { all.addAll(p.hand); all.addAll(p.discards); p.melds.forEach(m -> all.addAll(m.tiles())); });
        assertEquals(108, all.size()); assertEquals(108, all.stream().map(Tile::id).distinct().count());
        assertEquals(30, r.players.stream().mapToInt(p -> p.score).sum());
        assertTrue(r.players.stream().allMatch(p -> p.score >= 0));
    }

    @Test void rulesApiPublishesTheActualRevisedCatalogWithoutInventingAPrintedVersion() throws Exception {
        YmService s = service(directory.resolve("api.json"));
        String json = MockMvcBuilders.standaloneSetup(new YmController(s)).build().perform(get("/api/yaoming/rules"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var body = mapper.readTree(json); assertEquals("26.9 LTS", body.path("version").asText());
        Map<String, Integer> actual = new HashMap<>(); body.path("fans").forEach(f -> actual.put(f.path("id").asText(), f.path("fan").asInt()));
        assertEquals(20, actual.size()); assertEquals(1, actual.get("PINGHE")); assertEquals(2, actual.get("MENQING"));
        assertEquals(3, actual.get("QINGQUANDAIYAO")); assertFalse(actual.containsKey("DUANYAO"));
        assertTrue(json.contains("规则集(2)")); assertFalse(json.contains("按附录B计4番")); assertFalse(json.contains("仍加清一色"));
    }

    @Test void closedFlatSelfDrawReachesExactlyFourAndPaysFourPerOpponent() {
        YmRoom r = fixture(CLOSED_FLAT, null, null); YmHints.Wait wait = hint(r, "D2", true);
        assertEquals(4, wait.tsumoFan()); assertTrue(wait.canTsumo()); assertEquals(3, wait.ronFan()); assertFalse(wait.canRon());
        finish(r); assertEquals(Map.of("PINGHE", 1, "MENQING", 2, "BUQIUREN", 1), fans(r.result));
        assertEquals(4, r.result.fan()); assertEquals(List.of(18, 6, 6), r.players.stream().map(p -> p.score).toList());
        assertTrue(r.result.payments().stream().allMatch(p -> p.amount() == 4 && p.requested() == 4));
    }

    @Test void openFlatFlushRonReachesFourAndCapsPaymentAtTheDiscardersBalance() {
        YmRoom r = fixture(OPEN_FLUSH, "B1 B2 B3", "CHI"); discardTo(r, "B5");
        YmHints.Wait wait = hint(r, "B5", false); assertEquals(4, wait.ronFan()); assertTrue(wait.canRon());
        finish(r); assertEquals(Map.of("PINGHE", 1, "QINGYISE", 3), fans(r.result));
        assertEquals(4, r.result.fan()); assertEquals(List.of(20, 10, 0), r.players.stream().map(p -> p.score).toList());
        assertEquals(12, r.result.payments().getFirst().requested()); assertEquals(10, r.result.payments().getFirst().amount());
        assertTrue(r.result.matchOver());
    }

    @Test void removedAllSimplesBonusCannotOfferOrExecuteAnUnderFourSelfDraw() throws Exception {
        YmRoom r = fixture("B2 B3 B4 D3 D4 D5 B6 B7 B8 D6 D6 D6 B5 B5", null, null);
        YmHints.Wait wait = hint(r, "B5", true); assertEquals(3, wait.tsumoFan()); assertEquals(2, wait.ronFan());
        assertFalse(wait.canTsumo()); assertFalse(wait.canRon());
        List<Action> legal = engine.gameActions(r, r.seat(0)); assertFalse(legal.stream().anyMatch(a -> a.type().equals("WIN")));
        assertEquals("DISCARD", YmBots.choose(YmBotObservation.capture(r, r.seat(0)), legal).type());
        String before = mapper.writeValueAsString(r);
        assertThrows(IllegalArgumentException.class, () -> engine.perform(r, r.seat(0), "WIN", List.of(), NOW));
        assertEquals(before, mapper.writeValueAsString(r)); conservation(r);
    }

    @Test void openPureOutsideAloneIsOnlyThreeAndCannotWin() {
        YmRoom r = fixture(OPEN_OUTSIDE, "W1 W1 W1", "PONG");
        YmHints.Wait wait = hint(r, "D1", true); assertEquals(3, wait.tsumoFan()); assertEquals(3, wait.ronFan());
        assertFalse(wait.canTsumo()); assertFalse(wait.canRon());
        assertFalse(engine.gameActions(r, r.seat(0)).stream().anyMatch(a -> a.type().equals("WIN")));
    }

    @Test void openAllHonorsUsesSixNotTheOldCappedEightAndReplayKeepsTheExactResult() {
        YmRoom r = fixture("H2 H2 H2 H3 H3 H3 H5 H5 H5 H6 H6", "H1 H1 H1", "PONG");
        finish(r); assertEquals(Map.of("ZIYISE", 6), fans(r.result)); assertEquals(6, r.result.rawFan());
        assertEquals(6, r.result.fan()); assertEquals(List.of(22, 4, 4), r.players.stream().map(p -> p.score).toList());
    }

    @Test void legacyNonReactionRoomsGetOneDurableVersionBumpWithoutChangingTheHandOrDeadline() throws Exception {
        YmRoom r = fixture(CLOSED_FLAT, null, null); long version = r.version, deadline = r.deadlineAt;
        Path file = directory.resolve("legacy.json"); mapper.writeValue(file.toFile(), List.of(r));
        YmService first = service(file); YmRoom restored = first.state(r.id);
        assertEquals(version + 1, restored.version); assertEquals(deadline, restored.deadlineAt);
        assertEquals(r.wall, restored.wall); assertEquals(r.seat(0).hand, restored.seat(0).hand);
        assertEquals(YmService.RULEBOOK_SHA256, mapper.readTree(file.toFile()).path("rulebookSha256").asText());
        assertThrows(YmService.VersionConflict.class, () -> first.action(r.id, "p0", "test-rulebook2-0", version, "old-client", "WIN", List.of()));
        YmService second = service(file); assertEquals(version + 1, second.state(r.id).version);
        assertTrue(second.view(r.id, "p0", "test-rulebook2-0").actions().stream().anyMatch(a -> a.type().equals("WIN")));
        conservation(second.state(r.id));
    }

    @Test void changedFingerprintAlsoInvalidatesASnapshotAlreadyUsingTheModernEnvelope() throws Exception {
        YmService original = service(directory.resolve("modern.json")); Identity id = original.create("版本戳", "玩家");
        long version = original.state(id.roomId()).version;
        Path file = directory.resolve("modern.json"); ObjectNode saved = (ObjectNode) mapper.readTree(file.toFile());
        saved.put("rulebookSha256", "old-document"); mapper.writeValue(file.toFile(), saved);
        assertEquals(version + 1, service(file).state(id.roomId()).version);
    }

    @Test void completedLegacySettlementAndReplayKeepTheirHistoricalFansInsteadOfBeingRescored() throws Exception {
        YmRoom r = fixture("B2 B3 B4 D3 D4 D5 B6 B7 B8 D6 D6 D6 B5 B5", null, null);
        YmScoring.Evaluation current = engine.evaluate(r, r.seat(0), null, true, false);
        assertTrue(current.validStructure()); assertFalse(current.eligible()); assertEquals(3, current.rawFan());

        // This hand was a legal four-fan self-draw under the old book. Build an honest legacy
        // snapshot with old payments, rather than asking the revised engine to approve it now.
        assertTrue(YmReplay.ensureActive(r, NOW - 2));
        List<YmScoring.Fan> historicalItems = List.of(
                new YmScoring.Fan("MENQING", "门清", 1, "没有吃、碰、明杠或加杠；允许暗杠。"),
                new YmScoring.Fan("DUANYAO", "断幺", 2, "不含 1、9 或字牌。"),
                new YmScoring.Fan("BUQIUREN", "不求人", 1, "门清自摸成和。"));
        int historicalFan = historicalItems.stream().mapToInt(YmScoring.Fan::fan).sum();
        assertEquals(4, historicalFan);
        Tile winningTile = r.seat(0).hand.stream().filter(t -> t.id().equals(r.seat(0).lastDrawnId)).findFirst().orElseThrow();
        List<Payment> payments = List.of(new Payment("p1", "p0", 4, 4), new Payment("p2", "p0", 4, 4));
        for (int seat = 1; seat < 3; seat++) {
            r.seat(seat).score -= historicalFan; r.seat(0).score += historicalFan;
        }
        Result historical = new Result(false, false, "玩家0 自摸", "p0", winningTile,
                historicalFan, historicalFan, historicalItems, payments,
                List.of(new Score("p0", "玩家0", 18, 8, 1), new Score("p1", "玩家1", 6, -4, 2),
                        new Score("p2", "玩家2", 6, -4, 3)),
                r.players.stream().map(p -> new Hand(p.id, List.copyOf(p.hand), List.copyOf(p.melds))).toList(),
                "本局结束，全部确认后轮庄");
        r.result = historical; r.phase = HAND_END; r.currentSeat = -1;
        r.deadlineKind = "SETTLEMENT"; r.deadlineAt = NOW + 30_000;
        r.players.forEach(p -> p.lastDrawnId = null);
        YmReplay.append(r, "WIN", 0, "玩家0 自摸 · 4番", NOW - 1);
        YmReplay.HandRecord historicalReplay = r.replayHands.getLast();
        assertTrue(historicalReplay.complete()); assertEquals(historical, historicalReplay.result());
        conservation(r);

        Path file = directory.resolve("historical-settlement.json");
        mapper.writeValue(file.toFile(), List.of(r));
        YmService restored = service(file);
        assertEquals(r.version + 1, restored.state(r.id).version);
        assertEquals(historical, restored.state(r.id).result);
        YmReplay.HandRecord published = restored.replay(r.id, r.round, "p0", "test-rulebook2-0");
        assertEquals(historicalReplay, published);
        assertEquals(Map.of("MENQING", 1, "DUANYAO", 2, "BUQIUREN", 1), fans(published.result()));
        assertEquals(4, published.result().rawFan()); assertEquals(4, published.result().fan());
        assertEquals(historical, published.frames().getLast().result());
        assertEquals(List.of(18, 6, 6), published.frames().getLast().players().stream().map(YmReplay.ReplayPlayer::score).toList());
        conservation(restored.state(r.id));
        assertEquals(YmService.RULEBOOK_SHA256, mapper.readTree(file.toFile()).path("rulebookSha256").asText());
        YmService restarted = service(file);
        assertEquals(historical, restarted.state(r.id).result);
        assertEquals(historicalReplay, restarted.replay(r.id, r.round, "p0", "test-rulebook2-0"));
    }

    @Test void restoreAddsNewlyEligibleRonWithoutResettingTimeOrReversingSubmittedPasses() throws Exception {
        for (boolean passed : List.of(false, true)) {
            YmRoom r = fixture(OPEN_FLUSH, "B1 B2 B3", "CHI"); discardTo(r, "B5");
            assertNotNull(r.window); r.window.offered.put(0, new ArrayList<>(List.of(Action.of("PASS", "过"))));
            if (passed) r.window.responses.put(0, Action.of("PASS", "过"));
            long deadline = r.deadlineAt, version = r.version;
            Path file = directory.resolve("new-ron-" + passed + ".json"); mapper.writeValue(file.toFile(), List.of(r));
            YmRoom restored = service(file).state(r.id);
            assertEquals(!passed, restored.window.offered.get(0).stream().anyMatch(a -> a.type().equals("WIN")));
            assertEquals(deadline, restored.deadlineAt); assertEquals(version + 1, restored.version); conservation(restored);
        }
    }
}
