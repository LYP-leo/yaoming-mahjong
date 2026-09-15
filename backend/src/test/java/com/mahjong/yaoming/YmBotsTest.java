package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.junit.jupiter.api.Assertions.*;

class YmBotsTest {
    private final YmEngine engine = new YmEngine(new Random(42));
    private List<Tile> tiles(String text) {
        return text.isBlank() ? new ArrayList<>() : new ArrayList<>(Arrays.stream(text.split(" ")).map(YmTiles::of).toList());
    }
    private YmRoom room(String hand) {
        YmRoom room = new YmRoom(); room.id = "bot-test"; room.round = 1; room.dealerSeat = 0; room.currentSeat = 0;
        room.phase = YmRoom.Phase.NEED_DISCARD; room.wall = new ArrayList<>(YmTiles.deck());
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player player = new YmRoom.Player(); player.id = "p" + seat; player.seat = seat; player.name = "player" + seat;
            room.players.add(player);
        }
        room.seat(0).hand = tiles(hand);
        return room;
    }
    private Action choose(YmRoom room) {
        List<Action> legal = engine.gameActions(room, room.seat(0));
        Action action = YmBots.choose(YmBotObservation.capture(room, room.seat(0)), legal);
        assertNotNull(action); assertTrue(legal.contains(action)); return action;
    }
    private String discarded(YmRoom room, Action action) {
        assertEquals("DISCARD", action.type());
        return YmTiles.code(room.seat(0).hand.stream().filter(tile -> tile.id().equals(action.tileIds().getFirst())).findFirst().orElseThrow());
    }
    private YmRoom reaction(String hand, String code) {
        YmRoom room = room(hand); room.phase = YmRoom.Phase.REACTION; room.currentSeat = -1;
        room.window = new YmRoom.Window(); room.window.fromSeat = 2; room.window.tile = YmTiles.of(code);
        room.seat(2).discards.add(room.window.tile);
        List<Action> legal = new ArrayList<>();
        List<Tile> same = room.seat(0).hand.stream().filter(tile -> YmTiles.same(tile, room.window.tile)).toList();
        if (same.size() >= 2) legal.add(new Action("PONG", "碰", same.stream().limit(2).map(Tile::id).toList()));
        if (same.size() >= 3) legal.add(new Action("OPEN_KONG", "杠", same.stream().limit(3).map(Tile::id).toList()));
        for (List<String> ids : YmTiles.chiOptions(room.seat(0).hand, room.window.tile)) legal.add(new Action("CHI", "吃", ids));
        if (engine.canRon(room, room.seat(0), room.window.tile, false)) legal.add(Action.of("WIN", "和"));
        legal.add(Action.of("PASS", "过")); room.window.offered.put(0, legal); return room;
    }

    @Test void preserves159SequenceAndChoosesLiveFourFanWait() {
        YmRoom room = room("W1 W9 B1 B2 B3 D4 D5 D6 H1 H1 H1 H5 H5 B9");
        assertEquals("B9", discarded(room, choose(room)));
    }
    @Test void pursuesWindDragonInsteadOfDiscardingItsSingletonHonors() {
        YmRoom room = room("H1 H2 H3 H5 H6 H7 W1 W5 W9 B1 B2 B3 H3 D9");
        // Keeping singleton D9 as the pair wait leaves three unseen copies; H3 only leaves two.
        Action action = choose(room); assertEquals("H3", discarded(room, action));
        List<Tile> after = new ArrayList<>(room.seat(0).hand);
        after.removeIf(tile -> action.tileIds().contains(tile.id()));
        for (String honor : List.of("H1", "H2", "H3", "H5", "H6", "H7"))
            assertTrue(after.stream().anyMatch(tile -> YmTiles.code(tile).equals(honor)));
        after.add(YmTiles.of("D9"));
        assertEquals(4, YmScoring.evaluate(after, List.of(), 1, 1, true, false).fan());
    }
    @Test void choosesPingheNumericPairOverABelowThresholdHonorPair() {
        YmRoom room = room("B2 B3 B4 D2 D3 D4 B6 B7 B8 D6 D7 D8 H7 B5");
        assertEquals("H7", discarded(room, choose(room)));
    }
    @Test void fourPublicCopiesMakeTheOtherwiseWideWaitUnattractive() {
        YmRoom room = room("H1 H1 H1 B2 B3 B4 D4 D5 D6 B5 B6 B7 B8 H7");
        room.seat(1).discards.addAll(tiles("B5 B5 B5 B8 B8 B8"));
        List<Action> legal = engine.gameActions(room, room.seat(0)).stream().filter(action -> action.type().equals("DISCARD")
            && Set.of("H7", "B8").contains(YmTiles.code(room.seat(0).hand.stream().filter(tile -> tile.id().equals(action.tileIds().getFirst())).findFirst().orElseThrow()))).toList();
        assertEquals("B8", discarded(room, YmBots.choose(YmBotObservation.capture(room, room.seat(0)), legal)));
    }
    @Test void opensForAValueHonorPongThatCreatesARealWinningWait() {
        YmRoom room = reaction("H1 H1 H5 H5 H5 B2 B3 B4 B6 B7 B8 B5 D9", "H1");
        assertEquals("PONG", choose(room).type());
    }
    @Test void opensForAChiWithEnoughFanAndSelectsItsPhysicalChoice() {
        YmRoom room = reaction("H1 H1 H1 H5 H5 H5 B2 B3 B6 B7 B8 B5 D9", "B4");
        Action action = choose(room); assertEquals("CHI", action.type());
        assertEquals(Set.of("B2", "B3"), new HashSet<>(room.seat(0).hand.stream().filter(tile -> action.tileIds().contains(tile.id())).map(YmTiles::code).toList()));
    }
    @Test void passesWhenOpeningWouldLeaveOnlyThreeFan() {
        YmRoom room = reaction("H1 H1 H1 H5 H5 H5 B2 B3 D6 D7 D8 B5 D9", "B4");
        assertEquals("PASS", choose(room).type());
    }
    @Test void declinesConcealedKongThatDestroysWindDragonTenpai() {
        YmRoom room = room("H1 H1 H1 H1 H2 H3 H5 H6 H7 B1 B2 B3 W1 W5");
        room.seat(0).lastDrawnId = room.seat(0).hand.getFirst().id();
        assertTrue(engine.gameActions(room, room.seat(0)).stream().anyMatch(action -> action.type().equals("CONCEALED_KONG")));
        assertEquals("H1", discarded(room, choose(room)));
    }
    @Test void declaresConcealedKongWhenItKeepsTheWaitAndImprovesFan() {
        YmRoom room = room("H1 H1 H1 H1 B2 B3 B4 D4 D5 D6 B7 B8 B9 H3");
        room.seat(0).lastDrawnId = room.seat(0).hand.getFirst().id();
        assertEquals("CONCEALED_KONG", choose(room).type());
    }
    @Test void legalTsumoAndRonAlwaysTakePriorityOverEveryOtherAction() {
        YmRoom self = room("H1 H1 H1 B2 B3 B4 D4 D5 D6 B7 B8 B9 H3 H3");
        self.seat(0).lastDrawnId = self.seat(0).hand.getLast().id(); assertEquals("WIN", choose(self).type());
        YmRoom ron = reaction("H1 H1 H1 B2 B3 B4 B4 B5 B6 B7 B8 B9 H3", "H3");
        assertTrue(engine.canRon(ron, ron.seat(0), ron.window.tile, false));
        assertEquals("WIN", choose(ron).type());
    }
    @Test void legacyHistoryCannotInventAWinThatTheEngineDidNotOffer() {
        YmRoom room = reaction("H1 H1 H1 B2 B3 B4 B4 B5 B6 B7 B8 B9 H3", "H3");
        room.seat(0).discardedCodes.add("H3"); room.seat(0).passedCodes.add("H3");
        room.window.offered.get(0).removeIf(action -> action.type().equals("WIN"));
        assertEquals("PASS", choose(room).type());
    }
    @Test void oldDiscardedCodesCannotSuppressAnEligibleRonWaitInDiscardEvaluation() {
        YmRoom room = room("H1 H1 H1 B2 B3 B4 B4 B5 B6 B7 B8 B9 H3 H5");
        assertEquals("H3", discarded(room, choose(room)), "Keep the live value-dragon pair wait");
        room.seat(0).discardedCodes.add("H5");
        assertEquals("H3", discarded(room, choose(room)), "A historic discard is not a ron prohibition");
    }
    @Test void oldPassedCodesCannotSuppressAnEligibleRonWaitInDiscardEvaluation() {
        YmRoom room = room("H1 H1 H1 B2 B3 B4 B4 B5 B6 B7 B8 B9 H3 H5");
        assertEquals("H3", discarded(room, choose(room)));
        room.seat(0).passedCodes.add("H5");
        assertEquals("H3", discarded(room, choose(room)), "Passing earlier does not forbid a later same-type ron");
    }
    @Test void hypotheticallyDiscardedTileTypeStillCountsAsAnEligibleRonOut() {
        YmRoom room = room("H1 H1 H1 B2 B3 B4 B4 B5 B6 B7 B8 B9 B1 B1");
        // Exhaust the other waits using public physical tiles. Discard B1 leaves
        // two unseen B1; discard B9 leaves two unseen B6. Both have the same fan.
        // The deterministic B1 tie-break must not lose ron outs merely because
        // this very candidate discarded a different physical copy of B1.
        room.seat(1).discards.addAll(tiles("B3 B3 B3 B4 B4 B7 B7 B7 B9 B9 B9 B6"));
        List<Action> legal = engine.gameActions(room, room.seat(0)).stream().filter(action -> action.type().equals("DISCARD")
            && Set.of("B1", "B9").contains(YmTiles.code(room.seat(0).hand.stream().filter(tile -> tile.id().equals(action.tileIds().getFirst())).findFirst().orElseThrow()))).toList();
        YmBotObservation observation = YmBotObservation.capture(room, room.seat(0));
        // Explicitly prove the twin-candidate premise: the complete B1/B4/B7
        // and B3/B6/B9 waits must have only the following unseen copies left.
        // This catches accidental extra routes rather than changing the policy's
        // expected choice to accommodate an invalid fixture.
        for (Action action : legal) {
            List<Tile> after = room.seat(0).hand.stream().filter(tile -> !action.tileIds().contains(tile.id())).toList();
            Map<String, Integer> liveRon = new LinkedHashMap<>();
            for (String code : YmTiles.CODES) {
                int unseen = Math.max(0, 4 - observation.knownCounts().getOrDefault(code, 0));
                if (unseen == 0) continue;
                List<Tile> completed = new ArrayList<>(after); completed.add(YmTiles.of(code));
                YmScoring.Evaluation ron = YmScoring.evaluate(completed, List.of(), 1, 1, false, false);
                if (ron.eligible()) { assertEquals(6, ron.fan()); liveRon.put(code, unseen); }
            }
            assertEquals(discarded(room, action).equals("B1") ? Map.of("B1", 2) : Map.of("B6", 2), liveRon);
        }
        assertEquals("B1", discarded(room, YmBots.choose(observation, legal)));
    }
    @Test void duplicateCopiesAndActionOrderDoNotChangeTheDecision() {
        YmRoom room = room("W1 W9 B1 B2 B3 D4 D5 D6 H1 H1 H1 H5 H5 B9");
        List<Action> legal = new ArrayList<>(engine.gameActions(room, room.seat(0)));
        YmBotObservation view = YmBotObservation.capture(room, room.seat(0)); Action expected = YmBots.choose(view, legal);
        Collections.reverse(legal); assertEquals(expected, YmBots.choose(view, legal));
        Collections.reverse(room.seat(0).hand); assertEquals(expected, YmBots.choose(YmBotObservation.capture(room, room.seat(0)), legal));
    }
    @Test void changingHiddenHandsWallOrderAndPrivateStateCannotChangeActions() throws Exception {
        YmRoom room = reaction("H1 H1 H1 H5 H5 H5 B2 B3 B6 B7 B8 B5 D9", "B4");
        Action expected = choose(room);
        Collections.reverse(room.wall); room.seat(1).hand = tiles("B5 B5 B5 B5"); room.seat(2).hand = tiles("H1 H1 H1 H1");
        room.seat(1).token = "not-an-input"; room.seat(1).discardedCodes.add("B5"); room.seat(1).passedCodes.add("B5");
        assertEquals(expected, choose(room));
        ObjectMapper mapper = new ObjectMapper(); String before = mapper.writeValueAsString(room);
        choose(room); assertEquals(before, mapper.writeValueAsString(room));
    }
    @Test void readyBotDoesNotToggleAndNoActionsReturnsNull() {
        YmRoom room = room(""); room.seat(0).ready = true;
        YmBotObservation view = YmBotObservation.capture(room, room.seat(0));
        assertNull(YmBots.choose(view, List.of())); assertNull(YmBots.choose(view, List.of(Action.of("READY", "取消准备"))));
        assertEquals("ACK", YmBots.choose(view, List.of(Action.of("ACK", "确认"))).type());
        assertEquals("DRAW", YmBots.choose(view, List.of(Action.of("DRAW", "摸牌"))).type());
    }
    @Test void lowScoreFarHandDoesNotTreatAnOpponentsRiverAsAbsolutelySafe() {
        YmRoom room = room("W1 W5 B1 B3 B5 B7 B9 D1 D3 D5 D7 D9 H2 H6");
        room.seat(0).score = 3;
        room.seat(1).melds.add(new YmScoring.Meld("PONG", tiles("H5 H5 H5"), 2, "", false));
        room.seat(1).melds.add(new YmScoring.Meld("PONG", tiles("D2 D2 D2"), 2, "", false));
        room.seat(1).discards.add(YmTiles.of("D9"));
        List<Action> legal = engine.gameActions(room, room.seat(0)).stream().filter(action -> action.type().equals("DISCARD")
            && Set.of("H6", "D9").contains(YmTiles.code(room.seat(0).hand.stream().filter(tile -> tile.id().equals(action.tileIds().getFirst())).findFirst().orElseThrow()))).toList();
        Action selected = YmBots.choose(YmBotObservation.capture(room, room.seat(0)), legal);
        assertEquals("H6", discarded(room, selected), "Opponent's earlier D9 can still be won on; it is not a safe tile");
        // Moving the same public physical tile between rivers preserves known
        // counts. Its former owner must not change whether it can deal in.
        room.seat(2).discards.add(room.seat(1).discards.removeFirst());
        assertEquals(selected, YmBots.choose(YmBotObservation.capture(room, room.seat(0)), legal));
    }
    @Test void addedKongKeepsThePongMetadataAndDoesNotReadReplacement() {
        YmRoom room = room("H1 B2 B3 B4 B4 B5 B6 B7 B8 B9 H3");
        List<Tile> pong = tiles("H1 H1 H1");
        room.seat(0).melds.add(new YmScoring.Meld("PONG", pong, 2, pong.getFirst().id(), false));
        room.seat(0).lastDrawnId = room.seat(0).hand.getFirst().id();
        assertEquals("ADDED_KONG", choose(room).type());
        Collections.reverse(room.wall); assertEquals("ADDED_KONG", choose(room).type());
        assertEquals("PONG", room.seat(0).melds.getFirst().type()); assertEquals(3, room.seat(0).melds.getFirst().tiles().size());
    }
    @Test void declinesAddedKongThatBreaksAPromisingSequence() {
        YmRoom room = room("B2 B3 B4 B5 B6 B7 H1 H1 H1 H5 D9");
        List<Tile> pong = tiles("B2 B2 B2"); room.seat(0).melds.add(new YmScoring.Meld("PONG", pong, 2, pong.getFirst().id(), false));
        room.seat(0).lastDrawnId = room.seat(0).hand.getLast().id();
        assertEquals("D9", discarded(room, choose(room)));
    }
    @Test void invalidStrategyInputFallsBackToAnOfferedDiscardWithoutMutatingIt() {
        YmRoom room = room("H1 H1 H1 H1 H1 B1 B2 B3 D4 D5 D6 H3 H5 H7");
        List<Action> legal = engine.gameActions(room, room.seat(0)); List<Tile> before = List.copyOf(room.seat(0).hand);
        Action action = assertDoesNotThrow(() -> YmBots.choose(YmBotObservation.capture(room, room.seat(0)), legal));
        assertNotNull(action); assertEquals("DISCARD", action.type()); assertTrue(legal.contains(action));
        assertEquals(before, room.seat(0).hand);
    }
}
