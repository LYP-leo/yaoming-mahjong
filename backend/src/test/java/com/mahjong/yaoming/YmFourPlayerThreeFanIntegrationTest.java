package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mahjong.domain.Tile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmRules.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.junit.jupiter.api.Assertions.*;

/** Rule-specific minimum fan must agree across commands, public views, hints and durable recovery. */
class YmFourPlayerThreeFanIntegrationTest {
    private static final long NOW = Instant.parse("2026-09-12T08:00:00Z").toEpochMilli();
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
    private static final String FLAT = "W1 W2 W3 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2";
    private static final String SPECIAL = "W1 W4 W7 B2 B5 B8 D3 H1 H2 H3 H4 H5 H6 H7";
    private static final String CLOSED_RON = "H1 H1 H1 W1 W2 W3 B4 B5 B6 D7 D8 D9 H3 H3";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final YmEngine engine = new YmEngine(new Random(123));
    private final List<YmService> services = new ArrayList<>();
    @TempDir Path directory;
    @AfterEach void close() { services.forEach(YmService::closeStreams); }

    private YmRoom fixture(YmRules rules, String hand) { return fixture(rules, hand, null); }
    private YmRoom fixture(YmRules rules, String hand, String openPung) {
        YmRoom room = new YmRoom(); room.id = "three-fan-" + rules.id(); room.name = "起和门槛隔离回归";
        room.ruleId = rules.id(); room.hostId = "p0"; room.currentSeat = 0; room.phase = NEED_DISCARD;
        room.deadlineKind = "DISCARD"; room.deadlineAt = NOW + 30_000; room.lastActivity = NOW; room.nextBotAt = NOW + 650;
        room.wall = new ArrayList<>(YmTiles.deck(rules));
        for (int seat = 0; seat < rules.playerCount(); seat++) {
            YmRoom.Player player = new YmRoom.Player(); player.id = "p" + seat; player.name = "玩家" + seat;
            player.seat = seat; player.token = "three-fan-test-seat-" + seat; player.lastSeen = NOW; room.players.add(player);
        }
        for (String code : hand.split("\\s+")) if (!code.isBlank()) room.seat(0).hand.add(take(room, code));
        if (openPung != null) {
            List<Tile> tiles = List.of(take(room, openPung), take(room, openPung), take(room, openPung));
            room.seat(0).melds.add(new YmScoring.Meld("PONG", tiles, rules.playerCount() - 1, tiles.getFirst().id(), false));
        }
        for (int seat = 1; seat < rules.playerCount(); seat++) for (int count = 0; count < 13; count++)
            room.seat(seat).hand.add(room.wall.removeFirst());
        room.players.forEach(player -> YmTiles.sort(player.hand)); room.seat(0).lastDrawnId = room.seat(0).hand.getLast().id();
        conservation(room); return room;
    }
    private Tile take(YmRoom room, String code) {
        Tile tile = room.wall.stream().filter(candidate -> YmTiles.code(candidate).equals(code)).findFirst().orElseThrow();
        room.wall.remove(tile); return tile;
    }
    private Tile removeWinningTile(YmRoom room, String code) {
        Tile tile = room.seat(0).hand.stream().filter(candidate -> YmTiles.code(candidate).equals(code)).findFirst().orElseThrow();
        room.seat(0).hand.remove(tile); room.seat(0).lastDrawnId = null; return tile;
    }
    private Tile offer(YmRoom room, String code) {
        Tile tile = removeWinningTile(room, code); YmRoom.Player from = room.seat(room.rules().playerCount() - 1);
        from.hand.add(tile); from.lastDrawnId = tile.id(); room.currentSeat = from.seat;
        engine.perform(room, from, "DISCARD", List.of(tile.id()), NOW + 1); conservation(room); return tile;
    }
    private boolean winOffered(YmRoom room) {
        return engine.gameActions(room, room.seat(0)).stream().anyMatch(action -> action.type().equals("WIN"));
    }
    private void finish(YmRoom room) {
        assertTrue(winOffered(room)); engine.perform(room, room.seat(0), "WIN", List.of(), NOW + 2);
        for (int seat = 1; seat < room.rules().playerCount() && room.phase == REACTION; seat++)
            if (engine.gameActions(room, room.seat(seat)).stream().anyMatch(action -> action.type().equals("PASS")))
                engine.perform(room, room.seat(seat), "PASS", List.of(), NOW + 3);
        assertNotNull(room.result); assertEquals(room.result, room.replayHands.getLast().result()); conservation(room);
    }
    private Map<String, Integer> fans(Result result) {
        return result.items().stream().collect(Collectors.toMap(YmScoring.Fan::id, YmScoring.Fan::fan));
    }
    private void conservation(YmRoom room) {
        List<Tile> all = new ArrayList<>(room.wall);
        room.players.forEach(player -> { all.addAll(player.hand); all.addAll(player.discards); player.melds.forEach(meld -> all.addAll(meld.tiles())); });
        assertEquals(room.rules().tileCount(), all.size()); assertEquals(all.size(), all.stream().map(Tile::id).distinct().count());
        assertEquals(YmTiles.deck(room.rules()).stream().map(Tile::id).collect(Collectors.toSet()), all.stream().map(Tile::id).collect(Collectors.toSet()));
        assertEquals(room.rules().playerCount() * 10, room.players.stream().mapToInt(player -> player.score).sum());
        assertTrue(room.players.stream().allMatch(player -> player.score >= 0));
    }
    private Path snapshot(YmRoom room, String name) throws Exception {
        ObjectNode saved = mapper.createObjectNode(); saved.set("rooms", mapper.valueToTree(List.of(room)));
        saved.putArray("leaveReceipts"); saved.putArray("replayArchives"); saved.put("rulebookSha256", "four-player-before-three-fan-test");
        Path file = directory.resolve(name + ".json"); mapper.writeValue(file.toFile(), saved); return file;
    }
    private YmService service(Path file) {
        YmService service = new YmService(mapper, file, new Random(123), CLOCK); services.add(service); return service;
    }
    private RoomView view(YmService service, YmRoom room) { return service.view(room.id, "p0", room.seat(0).token); }
    private YmHints.Wait waitFor(YmHints.Analysis analysis, String code, boolean discardMode) {
        List<YmHints.Wait> waits = discardMode ? analysis.discards().stream().filter(discard -> YmTiles.code(discard.tile()).equals(code))
            .findFirst().orElseThrow().waits() : analysis.waits();
        return waits.stream().filter(wait -> YmTiles.code(wait.tile()).equals(code)).findFirst().orElseThrow();
    }

    @Test void exactThreeFanClosedTsumoPaysThreeOtherPlayersAndRecordsTheWinningHand() {
        YmRoom room = fixture(FOUR_PLAYER, FLAT);
        YmScoring.Evaluation evaluated = engine.evaluate(room, room.seat(0), null, true, false);
        assertTrue(evaluated.validStructure()); assertTrue(evaluated.eligible()); assertEquals(3, evaluated.rawFan());
        finish(room);
        assertEquals(Map.of("PINGHE", 1, "MENQING", 1, "BUQIUREN", 1), fans(room.result));
        assertEquals(List.of(19, 7, 7, 7), room.players.stream().map(player -> player.score).toList());
        assertEquals(3, room.result.payments().size());
        assertTrue(room.result.payments().stream().allMatch(payment -> payment.amount() == 3 && payment.requested() == 3));
        assertEquals(14, room.result.hands().getFirst().hand().size()); assertEquals(HAND_END, room.phase);
        assertEquals("yaoming-4p", room.replayHands.getLast().ruleId());
    }

    @Test void exactThreeFanOrdinaryRonUsesFourTimesFanAndClampsPaymentWithoutChangingTheScoring() {
        YmRoom room = fixture(FOUR_PLAYER, "B1 B2 B3 D7 D8 D9 B9 B9 B9 D1 D1", "W1");
        Tile winning = offer(room, "D1");
        assertTrue(engine.canRon(room, room.seat(0), winning, false)); finish(room);
        assertEquals(Map.of("QINGQUANDAIYAO", 3), fans(room.result));
        assertEquals(3, room.result.rawFan()); assertEquals(3, room.result.fan());
        assertEquals(List.of(new Payment("p3", "p0", 10, 12)), room.result.payments());
        assertEquals(List.of(20, 10, 10, 0), room.players.stream().map(player -> player.score).toList());
        assertTrue(room.result.matchOver()); assertTrue(room.lastDiscard.claimed());
        assertTrue(room.seat(3).discards.stream().noneMatch(tile -> tile.id().equals(winning.id())));
    }

    @Test void twoFanStructuredHandsCannotOfferOrExecuteTsumoOrOrdinaryRon() throws Exception {
        YmRoom self = fixture(FOUR_PLAYER, "W1 W2 W3 B1 B2 B3 D4 D5 D6 B7 B7 B7 D2 D2");
        YmScoring.Evaluation evaluated = engine.evaluate(self, self.seat(0), null, true, false);
        assertTrue(evaluated.validStructure()); assertEquals(2, evaluated.rawFan()); assertFalse(evaluated.eligible());
        assertFalse(winOffered(self)); String before = mapper.writeValueAsString(self);
        assertThrows(IllegalArgumentException.class, () -> engine.perform(self, self.seat(0), "WIN", List.of(), NOW));
        assertEquals(before, mapper.writeValueAsString(self)); conservation(self);
        YmRoom ron = fixture(FOUR_PLAYER, FLAT); Tile winning = offer(ron, "D2");
        evaluated = engine.evaluate(ron, ron.seat(0), winning, false, false);
        assertTrue(evaluated.validStructure()); assertEquals(2, evaluated.rawFan()); assertFalse(evaluated.eligible());
        assertFalse(engine.canRon(ron, ron.seat(0), winning, false)); assertFalse(winOffered(ron));
        before = mapper.writeValueAsString(ron);
        assertThrows(IllegalArgumentException.class, () -> engine.perform(ron, ron.seat(0), "WIN", List.of(), NOW + 2));
        assertEquals(before, mapper.writeValueAsString(ron)); conservation(ron);
    }

    @Test void allUnconnectedPlusSelfDrawReachesThreeWithoutAlsoAddingMenqingOrFiveCategories() {
        YmRoom room = fixture(FOUR_PLAYER, SPECIAL); finish(room);
        assertEquals(Map.of("QUANBUKAO", 2, "BUQIUREN", 1), fans(room.result));
        assertEquals(3, room.result.rawFan()); assertEquals(3, room.result.fan());
        assertEquals(3, room.result.payments().size()); assertEquals(List.of(19, 7, 7, 7), room.players.stream().map(player -> player.score).toList());
    }

    @Test void allUnconnectedOrdinaryRonIsTwoButActualKongReplacementDiscardAddsOnlyTheOneAllowedFan() {
        YmRoom ordinary = fixture(FOUR_PLAYER, SPECIAL); Tile ordinaryWin = offer(ordinary, "H7");
        assertFalse(engine.canRon(ordinary, ordinary.seat(0), ordinaryWin, false)); assertFalse(winOffered(ordinary));
        assertEquals(2, engine.evaluate(ordinary, ordinary.seat(0), ordinaryWin, false, false).rawFan());

        YmRoom kong = fixture(FOUR_PLAYER, SPECIAL); Tile winning = removeWinningTile(kong, "H7");
        YmRoom.Player source = kong.seat(3); kong.wall.addAll(source.hand); source.hand.clear();
        for (int count = 0; count < 4; count++) source.hand.add(take(kong, "D9"));
        while (source.hand.size() < 14) source.hand.add(kong.wall.removeFirst());
        kong.wall.add(winning); source.lastDrawnId = source.hand.getFirst().id(); kong.currentSeat = 3;
        conservation(kong);
        Action concealedKong = engine.gameActions(kong, source).stream().filter(action -> action.type().equals("CONCEALED_KONG"))
            .filter(action -> source.hand.stream().filter(tile -> action.tileIds().contains(tile.id())).allMatch(tile -> YmTiles.code(tile).equals("D9")))
            .findFirst().orElseThrow();
        engine.perform(kong, source, concealedKong.type(), concealedKong.tileIds(), NOW);
        assertTrue(source.afterKong); assertEquals(winning.id(), source.lastDrawnId); assertEquals(1, source.melds.size()); conservation(kong);
        engine.perform(kong, source, "DISCARD", List.of(winning.id()), NOW + 1);
        assertTrue(kong.window.kongDiscard); assertTrue(engine.canRon(kong, kong.seat(0), winning, true));
        finish(kong);
        assertEquals(Map.of("QUANBUKAO", 2, "GANGSHANGPAO", 1), fans(kong.result));
        assertEquals(3, kong.result.rawFan()); assertEquals(List.of(new Payment("p3", "p0", 10, 12)), kong.result.payments());
    }

    @Test void restoredFourPlayerViewsHintsAndSubmittedWinAllUseThreeFanWithoutChangingTheHandOrDeadline() throws Exception {
        YmRoom original = fixture(FOUR_PLAYER, FLAT); long deadline = original.deadlineAt, version = original.version;
        Path file = snapshot(original, "active-four"); YmService service = service(file);
        YmRoom restored = service.state(original.id); RoomView projected = view(service, original);
        assertEquals(FOUR_PLAYER, restored.rules()); assertEquals("yaoming-4p", projected.ruleId()); assertEquals(4, projected.capacity());
        assertEquals(deadline, restored.deadlineAt); assertEquals(version + 1, restored.version);
        assertEquals(original.seat(0).hand, restored.seat(0).hand); assertEquals(original.wall, restored.wall);
        assertTrue(projected.actions().stream().anyMatch(action -> action.type().equals("WIN")));
        assertTrue(projected.winHint().contains("可自摸：3番"));
        YmService.HintView hints = service.hints(original.id, "p0", original.seat(0).token);
        assertEquals(projected.version(), hints.version());
        YmHints.Wait wait = waitFor(hints.analysis(), "D2", true);
        assertEquals(3, wait.tsumoFan()); assertTrue(wait.canTsumo()); assertEquals(2, wait.ronFan()); assertFalse(wait.canRon());
        assertTrue(wait.ronReason().contains("不足 3 番")); assertTrue(hints.analysis().note().contains("3 番起和"));
        for (PlayerView player : projected.players()) assertEquals(player.id().equals("p0") ? 14 : 0, player.hand().size());
        RoomView completed = service.action(original.id, "p0", original.seat(0).token, projected.version(), UUID.randomUUID().toString(), "WIN", List.of());
        assertEquals(3, completed.result().fan()); conservation(service.state(original.id));
        YmService restarted = service(file);
        assertEquals(completed.result(), view(restarted, original).result());
        assertEquals(completed.result(), restarted.replay(original.id, 1, "p0", original.seat(0).token).result());
    }

    @Test void restoringAnOldFourFanReactionAddsANewlyEligibleThreeFanRonWithoutExtendingItsDeadline() throws Exception {
        YmRoom original = fixture(FOUR_PLAYER, CLOSED_RON); offer(original, "H1");
        assertTrue(original.window.offered.get(0).stream().anyMatch(action -> action.type().equals("PONG")));
        original.window.offered.get(0).removeIf(action -> action.type().equals("WIN"));
        long deadline = original.deadlineAt, version = original.version;
        YmService service = service(snapshot(original, "reaction-four")); YmRoom restored = service.state(original.id);
        assertEquals(deadline, restored.deadlineAt); assertEquals(deadline, restored.window.deadline);
        assertEquals(version + 1, restored.version); assertTrue(winOffered(restored));
        assertTrue(restored.window.offered.get(0).stream().anyMatch(action -> action.type().equals("PONG")));
        assertEquals(3, engine.evaluate(restored, restored.seat(0), restored.window.tile, false, false).rawFan());
        assertTrue(view(service, original).actions().stream().anyMatch(action -> action.type().equals("WIN")));
        conservation(restored);
    }

    @Test void missingMinimumMarkerInModernSnapshotsBumpsOnlyFourPlayerOnceAndNeverRewritesThreePlayerState() throws Exception {
        YmRoom four = fixture(FOUR_PLAYER, FLAT);
        YmRoom three = fixture(THREE_PLAYER, "B2 B3 B4 D3 D4 D5 B6 B7 B8 D6 D6 D6 B5 B5");
        ObjectNode saved = mapper.createObjectNode(); saved.set("rooms", mapper.valueToTree(List.of(four, three)));
        saved.putArray("leaveReceipts"); saved.putArray("replayArchives"); saved.put("rulebookSha256", YmService.RULEBOOK_SHA256);
        saved.withArray("rooms").forEach(node -> ((ObjectNode) node).remove("appliedMinimumFan"));
        Path file = directory.resolve("missing-minimum-marker.json"); mapper.writeValue(file.toFile(), saved);
        YmService first = service(file);
        assertEquals(four.version + 1, first.state(four.id).version); assertEquals(3, first.state(four.id).appliedMinimumFan);
        assertEquals(three.version, first.state(three.id).version); assertEquals(4, first.state(three.id).appliedMinimumFan);
        assertEquals(four.deadlineAt, first.state(four.id).deadlineAt); assertEquals(three.deadlineAt, first.state(three.id).deadlineAt);
        assertEquals(four.seat(0).hand, first.state(four.id).seat(0).hand); assertEquals(three.seat(0).hand, first.state(three.id).seat(0).hand);
        YmService second = service(file);
        assertEquals(four.version + 1, second.state(four.id).version); assertEquals(three.version, second.state(three.id).version);
        assertEquals(3, second.state(four.id).appliedMinimumFan); assertEquals(4, second.state(three.id).appliedMinimumFan);
        assertTrue(view(second, four).actions().stream().anyMatch(action -> action.type().equals("WIN")));
        assertFalse(view(second, three).actions().stream().anyMatch(action -> action.type().equals("WIN")));
        conservation(second.state(four.id)); conservation(second.state(three.id));
    }

    @Test void threePlayerMinimumRemainsFourInActionsHintsAndRestoredServiceViews() throws Exception {
        YmRoom original = fixture(THREE_PLAYER, "B2 B3 B4 D3 D4 D5 B6 B7 B8 D6 D6 D6 B5 B5");
        YmScoring.Evaluation score = engine.evaluate(original, original.seat(0), null, true, false);
        assertTrue(score.validStructure()); assertEquals(3, score.rawFan()); assertFalse(score.eligible()); assertFalse(winOffered(original));
        YmService service = service(snapshot(original, "legacy-three")); RoomView view = view(service, original);
        assertEquals("yaoming-3p", view.ruleId()); assertEquals(3, view.capacity());
        assertTrue(view.winHint().contains("至少需要4番")); assertFalse(view.actions().stream().anyMatch(action -> action.type().equals("WIN")));
        YmHints.Wait wait = waitFor(service.hints(original.id, "p0", original.seat(0).token).analysis(), "B5", true);
        assertEquals(3, wait.tsumoFan()); assertFalse(wait.canTsumo()); assertFalse(wait.canRon());
        assertTrue(wait.ronReason().contains("不足 4 番"));
        YmRoom ron = fixture(THREE_PLAYER, "B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D3 D4 B5 B5"); Tile winning = offer(ron, "B5");
        assertEquals(3, engine.evaluate(ron, ron.seat(0), winning, false, false).rawFan());
        assertFalse(engine.canRon(ron, ron.seat(0), winning, false)); conservation(service.state(original.id));
    }

    @Test void completedFourPlayerPaymentsAndHistoricalFanDescriptionsAreNotRecomputedOnRestore() throws Exception {
        YmRoom room = fixture(FOUR_PLAYER, CLOSED_RON); finish(room);
        assertEquals(4, room.result.fan()); assertEquals(List.of(22, 6, 6, 6), room.players.stream().map(player -> player.score).toList());
        Result current = room.result;
        List<YmScoring.Fan> historicalFans = current.items().stream().map(fan -> new YmScoring.Fan(fan.id(), fan.name(), fan.fan(),
            "四人旧4番门槛时保存的说明：" + fan.description())).toList();
        Result historical = new Result(current.draw(), current.matchOver(), current.title(), current.winnerId(), current.winningTile(),
            current.rawFan(), current.fan(), historicalFans, current.payments(), current.scores(), current.hands(), "原四人4番门槛下已结算，不回溯重算");
        room.result = historical;
        YmReplay.HandRecord prior = room.replayHands.getLast();
        List<YmReplay.Frame> frames = prior.frames().stream().map(frame -> new YmReplay.Frame(frame.index(), frame.timestamp(), frame.type(),
            frame.actorSeat(), frame.message(), frame.status(), frame.currentSeat(), frame.wallCount(), frame.dealerSeat(), frame.players(),
            frame.lastDiscard(), frame.dice(), frame.result() == null ? null : historical)).toList();
        YmReplay.HandRecord replay = new YmReplay.HandRecord(prior.roomId(), prior.roomName(), prior.round(), prior.roundLabel(), prior.startedAt(),
            prior.completedAt(), prior.complete(), prior.incomplete(), frames, historical, prior.ruleId(), prior.ruleName(), prior.capacity());
        room.replayHands.set(room.replayHands.size() - 1, replay); conservation(room);
        Path file = snapshot(room, "history-four"); YmService restored = service(file);
        assertEquals(historical, restored.state(room.id).result); assertEquals(historical, view(restored, room).result());
        assertEquals(replay, restored.replay(room.id, 1, "p0", room.seat(0).token));
        assertEquals(historical, restored.replay(room.id, 1, "p3", room.seat(3).token).frames().getLast().result());
        assertEquals(4, restored.state(room.id).result.fan()); assertEquals(current.payments(), restored.state(room.id).result.payments());
        conservation(restored.state(room.id));
        YmService restarted = service(file); assertEquals(replay, restarted.replay(room.id, 1, "p0", room.seat(0).token));
    }
}
