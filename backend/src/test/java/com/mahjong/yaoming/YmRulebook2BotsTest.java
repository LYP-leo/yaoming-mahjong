package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.mahjong.yaoming.YmViews.Action;
import static org.junit.jupiter.api.Assertions.*;

/** The heuristic must pursue current scoring routes without inventing legal win actions. */
class YmRulebook2BotsTest {
    private static final String PINGHE = "W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2";
    private final YmEngine engine = new YmEngine(new Random(29));

    private static List<Tile> tiles(String codes) {
        return new ArrayList<>(Arrays.stream(codes.split(" ")).map(YmTiles::of).toList());
    }

    private static YmRoom room(String hand) {
        YmRoom room = new YmRoom(); room.id = "rulebook-2-bot"; room.round = 1;
        room.phase = YmRoom.Phase.NEED_DISCARD; room.currentSeat = 0;
        room.wall = new ArrayList<>(YmTiles.deck());
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player player = new YmRoom.Player(); player.id = "p" + seat;
            player.name = "测试" + seat; player.seat = seat; room.players.add(player);
        }
        room.seat(0).hand = tiles(hand); room.seat(0).lastDrawnId = room.seat(0).hand.getLast().id();
        return room;
    }

    private double potential(YmRoom room) {
        return YmBots.fanPotential(YmBotObservation.capture(room, room.seat(0)), room.seat(0).hand, room.seat(0).melds);
    }

    private Action choose(YmRoom room) {
        List<Action> legal = engine.gameActions(room, room.seat(0));
        Action selected = YmBots.choose(YmBotObservation.capture(room, room.seat(0)), legal);
        assertNotNull(selected); assertTrue(legal.contains(selected)); return selected;
    }

    @Test void closedPingheWithTerminalsAnd159ManIsARealFourFanSelfDraw() {
        YmRoom room = room(PINGHE);
        YmScoring.Evaluation score = engine.evaluate(room, room.seat(0), null, true, false);
        assertTrue(score.eligible()); assertEquals(4, score.rawFan());
        assertEquals(List.of("PINGHE", "MENQING", "BUQIUREN").stream().sorted().toList(),
            score.items().stream().map(YmScoring.Fan::id).sorted().toList());
        assertEquals("WIN", choose(room).type()); assertEquals(4, potential(room), .000001);
    }

    @Test void closedNumericTripletHandDoesNotKeepTheRemovedTanyaoWin() {
        YmRoom room = room("B2 B3 B4 D3 D4 D5 B6 B7 B8 D6 D6 D6 B5 B5");
        YmScoring.Evaluation score = engine.evaluate(room, room.seat(0), null, true, false);
        assertFalse(score.eligible()); assertEquals(3, score.rawFan());
        assertTrue(score.items().stream().noneMatch(item -> item.id().equals("DUANYAO") || item.id().equals("PINGHE")));
        assertEquals("DISCARD", choose(room).type());
    }

    @Test void openMixedSuitedPongHasNoPhantomTwoFanTanyaoRoute() {
        YmRoom room = room("D2 D3 D4 B6 B7 B8 D6 D7 B5 B5");
        room.seat(0).melds.add(new YmScoring.Meld("PONG", tiles("B2 B2 B2"), 2, "", false));
        assertEquals(0, potential(room), .000001,
            "An open pong excludes pinghe; a mixed simple-tile hand no longer earns tanyao");
    }

    @Test void openPureSequenceRouteCombinesPingheAndPureSuit() {
        YmRoom room = room("B2");
        for (String sequence : List.of("B1 B2 B3", "B4 B5 B6", "B6 B7 B8", "B7 B8 B9"))
            room.seat(0).melds.add(new YmScoring.Meld("CHI", tiles(sequence), 2, "", false));
        assertEquals(4, potential(room), .000001);
        List<Tile> completed = new ArrayList<>(room.seat(0).hand); completed.add(YmTiles.of("B2"));
        YmScoring.Evaluation score = YmScoring.evaluate(completed, room.seat(0).melds, 1, 1, false, false);
        assertTrue(score.eligible()); assertEquals(5, score.rawFan());
        assertTrue(score.items().stream().anyMatch(item -> item.id().equals("PINGHE")));
    }

    @Test void fullyOpenHonorsEstimateDoesNotStackPureSuitOrValueHonors() {
        YmRoom room = room("H6");
        for (String code : List.of("H1", "H2", "H3", "H5"))
            room.seat(0).melds.add(new YmScoring.Meld("PONG", tiles(code + " " + code + " " + code), 2, "", false));
        assertEquals(7, potential(room), .000001, "Zi yi se 6 plus four exposed melds 1, not the old 9-fan route");
        List<Tile> completed = new ArrayList<>(room.seat(0).hand); completed.add(YmTiles.of("H6"));
        YmScoring.Evaluation score = YmScoring.evaluate(completed, room.seat(0).melds, 1, 1, false, false);
        assertEquals(7, score.rawFan());
        assertTrue(score.items().stream().noneMatch(item -> List.of("QINGYISE", "FANPAI", "PENGPENG").contains(item.id())));
    }
}
