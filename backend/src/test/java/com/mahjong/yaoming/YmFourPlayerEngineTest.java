package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static com.mahjong.yaoming.YmRoom.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class YmFourPlayerEngineTest {
    private final YmEngine engine = new YmEngine(new Random(73));
    private long now = 1_000_000;

    private YmRoom room() {
        YmRoom room = new YmRoom(); room.id = "four"; room.name = "四人回归"; room.hostId = "p0"; room.ruleId = "yaoming-4p";
        for (int seat = 0; seat < 4; seat++) {
            YmRoom.Player player = new YmRoom.Player(); player.id = "p" + seat; player.name = "玩家" + seat;
            player.seat = seat; player.token = "synthetic-" + seat; room.players.add(player);
        }
        return room;
    }
    private YmRoom fixture(String... hands) {
        YmRoom room = room(); room.wall = YmTiles.deck(YmRules.FOUR_PLAYER); room.phase = NEED_DISCARD; room.currentSeat = 0;
        for (int seat = 0; seat < hands.length; seat++) for (String code : hands[seat].split("\\s+")) {
            if (code.isBlank()) continue;
            Tile tile = room.wall.stream().filter(candidate -> YmTiles.code(candidate).equals(code)).findFirst().orElseThrow();
            room.wall.remove(tile); room.seat(seat).hand.add(tile);
        }
        conservation(room); return room;
    }
    private void act(YmRoom room, int seat, String type) {
        YmViews.Action action = engine.gameActions(room, room.seat(seat)).stream().filter(a -> a.type().equals(type)).findFirst().orElseThrow();
        engine.perform(room, room.seat(seat), type, action.tileIds(), ++now);
    }
    private Set<String> actions(YmRoom room, int seat) {
        return engine.gameActions(room, room.seat(seat)).stream().map(YmViews.Action::type).collect(Collectors.toSet());
    }
    private void conservation(YmRoom room) {
        List<Tile> all = new ArrayList<>(room.wall);
        room.players.forEach(player -> { all.addAll(player.hand); all.addAll(player.discards); player.melds.forEach(meld -> all.addAll(meld.tiles())); });
        assertEquals(136, all.size()); assertEquals(136, all.stream().map(Tile::id).distinct().count());
        assertEquals(YmTiles.deck(YmRules.FOUR_PLAYER).stream().map(Tile::id).collect(Collectors.toSet()), all.stream().map(Tile::id).collect(Collectors.toSet()));
        assertEquals(40, room.players.stream().mapToInt(player -> player.score).sum());
        assertTrue(room.players.stream().allMatch(player -> player.score >= 0));
    }

    @Test void waitsForFourthReadyAndDealsFourThirteenTileHandsUsingFourSidedCut() {
        YmRoom room = room();
        for (int seat = 0; seat < 3; seat++) act(room, seat, "READY");
        assertEquals(WAITING, room.phase); assertTrue(room.wall.isEmpty());
        act(room, 3, "READY");
        assertEquals(NEED_DRAW, room.phase); assertEquals(84, room.wall.size());
        assertTrue(room.players.stream().allMatch(player -> player.hand.size() == 13));
        assertEquals(3, room.dice.opening().size()); assertEquals(3, room.dice.breaking().size());
        assertEquals((room.dealerSeat + (room.dice.opening().stream().mapToInt(Integer::intValue).sum() - 1) % 4) % 4, room.dice.openingSeat());
        conservation(room);
        act(room, room.currentSeat, "DRAW");
        assertEquals(83, room.wall.size()); assertEquals(14, room.seat(room.currentSeat).hand.size()); conservation(room);
    }

    @Test void seededFourPlayerDealUsesThirtyFourTilesPerSideAndThreeFourTileBatches() {
        Random random = new Random(73);
        List<String> seating = new ArrayList<>(List.of("p0", "p1", "p2", "p3"));
        for (int index = 3; index > 0; index--) Collections.swap(seating, index, random.nextInt(index + 1));
        List<Tile> wall = YmTiles.deck(YmRules.FOUR_PLAYER);
        for (int index = wall.size() - 1; index > 0; index--) Collections.swap(wall, index, random.nextInt(index + 1));
        int opening = random.nextInt(1, 7) + random.nextInt(1, 7) + random.nextInt(1, 7);
        int breaking = random.nextInt(1, 7) + random.nextInt(1, 7) + random.nextInt(1, 7);
        Collections.rotate(wall, -(((opening - 1) % 4) * 34 + breaking * 2) % 136);
        List<List<Tile>> hands = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int batch = 0; batch < 3; batch++) for (int seat = 0; seat < 4; seat++) for (int tile = 0; tile < 4; tile++) hands.get(seat).add(wall.removeFirst());
        for (int seat = 0; seat < 4; seat++) hands.get(seat).add(wall.removeFirst());
        hands.forEach(YmTiles::sort);
        YmRoom room = room(); for (int seat = 0; seat < 4; seat++) act(room, seat, "READY");
        assertEquals(wall, room.wall);
        for (int seat = 0; seat < 4; seat++) { assertEquals(seating.get(seat), room.seat(seat).id); assertEquals(hands.get(seat), room.seat(seat).hand); }
        conservation(room);
    }

    @Test void northDiscardAllowsOnlyDownstreamEastToChiOrdinaryManAndWrapsToEastAfterPass() {
        YmRoom room = fixture("W1 W3", "W1 W3", "H4", "W2"); room.currentSeat = 3;
        act(room, 3, "DISCARD");
        assertTrue(actions(room, 0).contains("CHI")); assertFalse(actions(room, 1).contains("CHI")); assertFalse(actions(room, 2).contains("CHI"));
        act(room, 0, "PASS"); assertEquals(NEED_DRAW, room.phase); assertEquals(0, room.currentSeat); conservation(room);
        YmRoom claim = fixture("W1 W3", "W1 W3", "H4", "W2"); claim.currentSeat = 3;
        act(claim, 3, "DISCARD"); act(claim, 0, "CHI");
        assertEquals(3, claim.seat(0).melds.getFirst().fromSeat()); assertTrue(claim.lastDiscard.claimed());
        assertTrue(claim.seat(3).discards.isEmpty()); conservation(claim);
    }

    @Test void threeRonRespondersWaitForAllThenNearestWinsWithFourTimesFanClampedAtZero() {
        YmRoom room = fixture("H5", "W1 W1 W1 W2 W2 W2 W3 W3 W3 W4 W4 W4 H5",
                "B1 B1 B1 B2 B2 B2 B3 B3 B3 B4 B4 B4 H5", "D1 D1 D1 D2 D2 D2 D3 D3 D3 D4 D4 D4 H5");
        act(room, 0, "DISCARD");
        assertEquals(Set.of(1, 2, 3), room.window.offered.keySet());
        for (int seat = 1; seat < 4; seat++) assertTrue(actions(room, seat).contains("WIN"));
        act(room, 3, "WIN"); assertEquals(REACTION, room.phase);
        act(room, 2, "WIN"); assertEquals(REACTION, room.phase);
        act(room, 1, "WIN");
        assertEquals("p1", room.result.winnerId()); assertEquals(4, room.result.fan());
        assertEquals(1, room.result.payments().size()); assertEquals(16, room.result.payments().getFirst().requested());
        assertEquals(10, room.result.payments().getFirst().amount()); assertEquals(MATCH_END, room.phase); conservation(room);
    }

    @Test void northSelfDrawGetsCorrectSeatWindPaysThreeOthersAndNeedsFourAcknowledgements() {
        YmRoom room = fixture("", "", "", "W1 W2 W3 B4 B5 B6 H1 H1 H1 H4 H4 H4 D5 D5"); room.currentSeat = 3;
        room.seat(3).lastDrawnId = room.seat(3).hand.getLast().id();
        act(room, 3, "WIN");
        assertEquals(4, room.result.fan()); assertEquals(2, room.result.items().stream().filter(f -> f.id().equals("FANPAI")).findFirst().orElseThrow().fan());
        assertEquals(3, room.result.payments().size()); assertTrue(room.result.payments().stream().allMatch(payment -> payment.requested() == 4 && payment.amount() == 4));
        assertEquals(22, room.seat(3).score); assertEquals(HAND_END, room.phase); conservation(room);
        YmReplay.HandRecord replay = room.replayHands.getFirst();
        assertEquals("yaoming-4p", replay.ruleId()); assertEquals(4, replay.capacity());
        assertTrue(replay.frames().stream().allMatch(frame -> frame.players().size() == 4)); assertEquals(room.result, replay.result());
        for (int seat = 0; seat < 3; seat++) act(room, seat, "ACK");
        assertEquals(HAND_END, room.phase); assertEquals(1, room.round);
        act(room, 3, "ACK"); assertEquals(2, room.round); assertEquals(1, room.dealerSeat); assertEquals(NEED_DRAW, room.phase); conservation(room);
    }

    @Test void selfDrawCapClampsThreeBalancesIndependentlyAndPreservesFortyPoints() {
        YmRoom room = fixture("", "", "", "H1 H1 H1 H2 H2 H2 H3 H3 H3 H4 H4 H4 H5 H5"); room.currentSeat = 3;
        room.seat(0).score = 1; room.seat(1).score = 2; room.seat(2).score = 10; room.seat(3).score = 27;
        room.seat(3).lastDrawnId = room.seat(3).hand.getLast().id(); act(room, 3, "WIN");
        assertEquals(8, room.result.fan()); assertEquals(List.of(1, 2, 8), room.result.payments().stream().map(YmViews.Payment::amount).toList());
        assertTrue(room.result.payments().stream().allMatch(payment -> payment.requested() == 8));
        assertEquals(38, room.seat(3).score); assertEquals(MATCH_END, room.phase); conservation(room);
    }

    @Test void eightDrawnHandsRotateAcrossNorthAndEndOnlyAfterSouthFourWithStableTieOrder() {
        YmRoom room = room(); for (int seat = 0; seat < 4; seat++) act(room, seat, "READY");
        for (int round = 1; round <= 8; round++) {
            assertEquals(round, room.round); assertEquals((round - 1) % 4, room.dealerSeat);
            assertEquals((round <= 4 ? "东" : "南") + ((round - 1) % 4 + 1) + "局", YmEngine.roundLabel(room));
            room.seat(0).discards.addAll(room.wall); room.wall.clear();
            act(room, room.currentSeat, "DRAW"); conservation(room);
            assertEquals(round == 8 ? MATCH_END : HAND_END, room.phase);
            if (round < 8) { room.players.forEach(player -> player.acknowledged = true); engine.startAcknowledgedHand(room, ++now); }
        }
        assertEquals("南四局结束，本场结束", room.result.reason()); assertEquals(8, room.replayHands.size());
        assertEquals(List.of(0, 1, 2, 3), room.result.scores().stream().map(score -> room.players.stream().filter(player -> player.id.equals(score.playerId())).findFirst().orElseThrow().seat).toList());
        engine.startAcknowledgedHand(room, ++now); assertEquals(8, room.round);
    }
}
