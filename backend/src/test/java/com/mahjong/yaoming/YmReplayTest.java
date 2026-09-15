package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static org.assertj.core.api.Assertions.*;

class YmReplayTest {
    private final YmEngine engine = new YmEngine(new Random(71));
    private long now = 1_000_000;

    private YmRoom room() {
        YmRoom r = new YmRoom(); r.id = "replay-test"; r.name = "牌谱测试"; r.hostId = "p0";
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player p = new YmRoom.Player(); p.id = "p" + seat; p.name = "玩家" + seat;
            p.token = "never-export-secret-" + seat; p.seat = seat; r.players.add(p);
        }
        return r;
    }
    private YmRoom start() {
        YmRoom r = room(); for (YmRoom.Player p : r.players) act(r, p.seat, "READY"); return r;
    }
    private YmRoom fixture(String first, String second, String third) {
        YmRoom r = room(); r.wall = YmTiles.deck(); r.phase = NEED_DISCARD; r.currentSeat = 0;
        String[] hands = {first, second, third};
        for (int seat = 0; seat < 3; seat++) for (String code : hands[seat].split("\\s+"))
            if (!code.isBlank()) r.seat(seat).hand.add(take(r, code));
        return r;
    }
    private Tile take(YmRoom r, String code) {
        Tile tile = r.wall.stream().filter(t -> YmTiles.code(t).equals(code)).findFirst().orElseThrow();
        r.wall.remove(tile); return tile;
    }
    private void act(YmRoom r, int seat, String type) {
        YmViews.Action action = engine.gameActions(r, r.seat(seat)).stream()
                .filter(a -> a.type().equals(type)).findFirst().orElseThrow();
        engine.perform(r, r.seat(seat), type, action.tileIds(), ++now);
    }
    private YmReplay.Frame last(YmRoom r) {
        return (r.activeReplay == null ? r.replayHands.getLast() : r.activeReplay).frames().getLast();
    }
    private YmReplay.ReplayPlayer player(YmReplay.Frame frame, int seat) {
        return frame.players().stream().filter(p -> p.seat() == seat).findFirst().orElseThrow();
    }
    private void conservation(YmReplay.Frame frame) {
        List<String> visible = new ArrayList<>();
        for (YmReplay.ReplayPlayer p : frame.players()) {
            p.hand().forEach(t -> visible.add(t.id())); p.discards().forEach(t -> visible.add(t.id()));
            p.melds().forEach(m -> m.tiles().forEach(t -> visible.add(t.id())));
        }
        assertThat(visible).doesNotHaveDuplicates();
        assertThat(visible.size() + frame.wallCount()).isEqualTo(108);
        assertThat(frame.players().stream().mapToInt(YmReplay.ReplayPlayer::score).sum()).isEqualTo(30);
    }

    @Test void onlyFreshDealStartsAndReadyDoesNotAppendToNewHand() {
        YmRoom r = room(); act(r, 0, "READY"); act(r, 1, "READY");
        assertThat(r.activeReplay).isNull(); assertThat(r.replayHands).isEmpty();
        act(r, 2, "READY");
        assertThat(r.activeReplay.frames()).hasSize(1); assertThat(r.activeReplay.incomplete()).isFalse();
        assertThat(r.activeReplay.complete()).isFalse(); assertThat(r.activeReplay.startedAt()).isEqualTo(now);
        YmReplay.Frame initial = last(r);
        assertThat(initial.type()).isEqualTo("START"); assertThat(initial.actorSeat()).isEqualTo(-1);
        assertThat(initial.players()).allMatch(p -> p.hand().size() == 13);
        assertThat(initial.wallCount()).isEqualTo(69); assertThat(initial.dice()).isEqualTo(r.dice);
        conservation(initial);
    }

    @Test void drawsAndDiscardsHaveStableDeepSnapshotsAndInvalidActionsDoNotRecord() {
        YmRoom r = start(); YmReplay.Frame first = last(r); int current = r.currentSeat;
        act(r, current, "DRAW"); YmReplay.Frame drawn = last(r); String drawnId = r.seat(current).lastDrawnId;
        engine.perform(r, r.seat(current), "DISCARD", List.of(drawnId), ++now);
        assertThat(player(first, current).hand()).hasSize(13);
        assertThat(player(drawn, current).hand()).hasSize(14); assertThat(player(drawn, current).drawnTileId()).isEqualTo(drawnId);
        assertThat(player(drawn, current).discards()).isEmpty();
        assertThat(last(r).lastDiscard().tile().id()).isEqualTo(drawnId);
        assertThat(player(last(r), current).drawnTileId()).isNull();
        int size = r.activeReplay.frames().size();
        assertThatThrownBy(() -> engine.perform(r, r.seat(current), "DISCARD", List.of("absent"), ++now)).isInstanceOf(IllegalArgumentException.class);
        assertThat(r.activeReplay.frames()).hasSize(size);
        assertThatThrownBy(() -> drawn.players().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> player(drawn, current).hand().clear()).isInstanceOf(UnsupportedOperationException.class);
        r.activeReplay.frames().forEach(this::conservation);
    }

    @Test void pendingResponseAndArbitrationRecordDifferentPostActionStates() {
        YmRoom r = fixture("B3", "B1 B2", "B3 B3");
        act(r, 0, "DISCARD"); Tile discarded = r.lastDiscard.tile();
        act(r, 1, "CHI"); YmReplay.Frame pending = last(r);
        assertThat(pending.status()).isEqualTo("REACTION"); assertThat(player(pending, 1).melds()).isEmpty();
        assertThat(pending.message()).contains("等待");
        act(r, 2, "PONG"); YmReplay.Frame resolved = last(r);
        assertThat(resolved.type()).isEqualTo("PONG"); assertThat(resolved.actorSeat()).isEqualTo(2);
        assertThat(resolved.status()).isEqualTo("NEED_DISCARD"); assertThat(resolved.currentSeat()).isEqualTo(2);
        assertThat(player(resolved, 0).discards()).isEmpty(); assertThat(player(resolved, 2).melds().getFirst().claimedTileId()).isEqualTo(discarded.id());
        assertThat(resolved.lastDiscard().claimed()).isTrue();
        assertThat(player(pending, 0).discards()).hasSize(1); assertThat(player(pending, 2).melds()).isEmpty();
        assertThatThrownBy(() -> player(resolved, 2).melds().getFirst().tiles().clear()).isInstanceOf(UnsupportedOperationException.class);
        r.activeReplay.frames().forEach(this::conservation);
    }

    @Test void reactionTimeoutRecordsActualResolutionAndAutomaticDrawRecordsOneAction() {
        YmRoom r = fixture("B3", "B1 B2", "B3 B3");
        act(r, 0, "DISCARD"); act(r, 1, "CHI"); int before = r.activeReplay.frames().size();
        now = r.deadlineAt; engine.expire(r, now);
        assertThat(r.activeReplay.frames()).hasSize(before + 1); assertThat(last(r).type()).isEqualTo("REACTION_TIMEOUT");
        assertThat(last(r).actorSeat()).isEqualTo(-1); assertThat(last(r).currentSeat()).isEqualTo(1);
        assertThat(player(last(r), 1).melds().getFirst().type()).isEqualTo("CHI"); conservation(last(r));
        YmRoom fresh = start(); int current = fresh.currentSeat; now = fresh.deadlineAt;
        engine.expire(fresh, now);
        assertThat(fresh.activeReplay.frames()).hasSize(2); assertThat(last(fresh).type()).isEqualTo("DRAW");
        assertThat(last(fresh).message()).contains("超时托管"); assertThat(player(last(fresh), current).hand()).hasSize(14);
    }

    @Test void eachKongFrameIncludesMeldAndTailReplacementWithoutChangingEarlierFrames() {
        YmRoom concealed = fixture("W1 W1 W1 W1 B2 B3 B4 D4 D5 D6 B7 B8 B9 H3", "", "");
        concealed.seat(0).lastDrawnId = concealed.seat(0).hand.getFirst().id(); Tile tail = concealed.wall.getLast();
        act(concealed, 0, "CONCEALED_KONG");
        assertThat(player(last(concealed), 0).drawnTileId()).isEqualTo(tail.id());
        assertThat(player(last(concealed), 0).melds().getFirst().concealed()).isTrue(); conservation(last(concealed));
        YmRoom added = fixture("W1 B2 B3 B4 D4 D5 D6 B7 B8 B9 H3", "", "");
        List<Tile> pong = List.of(take(added, "W1"), take(added, "W1"), take(added, "W1"));
        added.seat(0).melds.add(new YmScoring.Meld("PONG", pong, 2, pong.getFirst().id(), false));
        added.seat(0).lastDrawnId = added.seat(0).hand.getFirst().id();
        act(added, 0, "ADDED_KONG");
        assertThat(player(last(added), 0).melds().getFirst().added()).isTrue();
        assertThat(player(added.activeReplay.frames().getFirst(), 0).melds().getFirst().type()).isEqualTo("PONG"); conservation(last(added));
        YmRoom open = fixture("W1", "W1 W1 W1 B2 B3 B4 D4 D5 D6 B7 B8 B9 H3", "");
        Tile openTail = open.wall.getLast(); act(open, 0, "DISCARD"); act(open, 1, "OPEN_KONG");
        assertThat(last(open).type()).isEqualTo("OPEN_KONG"); assertThat(player(last(open), 1).drawnTileId()).isEqualTo(openTail.id());
        assertThat(player(last(open), 0).discards()).isEmpty(); conservation(last(open));
    }

    @Test void ronArchivesOnlyAfterResolvedWinAndTerminalFrameOwnsWinningTileExactlyOnce() {
        YmRoom r = fixture("H6", "H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6", "");
        act(r, 0, "DISCARD"); assertThat(r.replayHands).isEmpty(); Tile tile = r.lastDiscard.tile();
        act(r, 1, "WIN");
        assertThat(r.activeReplay).isNull(); assertThat(r.replayHands).hasSize(1);
        YmReplay.HandRecord hand = r.replayHands.getFirst();
        assertThat(hand.complete()).isTrue(); assertThat(hand.completedAt()).isEqualTo(now);
        assertThat(hand.incomplete()).isTrue(); assertThat(hand.result()).isEqualTo(r.result);
        assertThat(last(r).status()).isEqualTo("MATCH_END"); assertThat(last(r).result()).isEqualTo(r.result);
        assertThat(player(last(r), 1).hand()).extracting(Tile::id).contains(tile.id());
        assertThat(player(last(r), 0).discards()).isEmpty(); conservation(last(r));
        r.players.forEach(p -> {p.hand.clear(); p.melds.clear(); p.score = 999;});
        assertThat(hand.result().scores()).extracting(YmViews.Score::score).containsExactly(20, 10, 0);
        assertThat(player(hand.frames().getLast(), 1).hand()).hasSize(14);
        assertThatThrownBy(() -> hand.result().hands().getFirst().hand().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void ackAndAutomaticSettlementBeginOneNewHandWithoutAttachingOldAckToIt() {
        YmRoom r = fixture("H1 H2 H3 H5 H6 H7 B2 B3 B4 D4 D5 D6 B8", "", "");
        r.phase = NEED_DRAW; r.wall.addFirst(take(r, "B8"));
        act(r, 0, "DRAW"); act(r, 0, "WIN"); YmReplay.HandRecord first = r.replayHands.getFirst();
        assertThat(r.phase).isEqualTo(HAND_END); assertThat(first.result().scores()).extracting(YmViews.Score::score).containsExactly(18, 6, 6);
        act(r, 0, "ACK"); act(r, 1, "ACK"); assertThat(r.activeReplay).isNull();
        act(r, 2, "ACK");
        assertThat(r.round).isEqualTo(2); assertThat(r.replayHands).containsExactly(first);
        assertThat(r.activeReplay.frames()).hasSize(1); assertThat(last(r).type()).isEqualTo("START");
        assertThat(r.activeReplay.round()).isEqualTo(2); assertThat(r.activeReplay.incomplete()).isFalse();
        assertThat(r.activeReplay.result()).isNull();
        // An old settlement can advance without claiming any historical replay was present.
        YmRoom old = room(); old.phase = HAND_END; old.deadlineKind = "SETTLEMENT"; old.deadlineAt = now;
        engine.expire(old, now);
        assertThat(old.round).isEqualTo(2); assertThat(old.replayHands).isEmpty(); assertThat(old.activeReplay.frames()).hasSize(1);
    }

    @Test void finalEmptyWallDrawIsRecordedAndLegacyEndedRoomsDoNotInventHistory() {
        YmRoom r = fixture("", "", ""); r.phase = NEED_DRAW; r.wall.clear(); r.round = 6;
        act(r, 0, "DRAW");
        assertThat(r.activeReplay).isNull(); assertThat(r.replayHands).hasSize(1);
        assertThat(last(r).type()).isEqualTo("DRAW"); assertThat(last(r).wallCount()).isZero();
        assertThat(last(r).result().draw()).isTrue(); assertThat(last(r).status()).isEqualTo("MATCH_END");
        YmRoom old = room(); old.phase = MATCH_END;
        assertThat(YmReplay.ensureActive(old, now)).isFalse(); assertThat(old.activeReplay).isNull(); assertThat(old.replayHands).isEmpty();
    }

    @Test void legacyRunningRecorderStartsOnceWithExplicitGapAndRejectsInvalidActionBeforeStarting() {
        YmRoom r = fixture("B1", "", "");
        assertThatThrownBy(() -> engine.perform(r, r.seat(0), "DISCARD", List.of("missing"), now)).isInstanceOf(IllegalArgumentException.class);
        assertThat(r.activeReplay).isNull();
        assertThat(YmReplay.ensureActive(r, now)).isTrue(); YmReplay.HandRecord initial = r.activeReplay;
        assertThat(initial.incomplete()).isTrue(); assertThat(last(r).type()).isEqualTo("RESUME"); assertThat(last(r).message()).contains("缺失");
        assertThat(YmReplay.ensureActive(r, now + 100)).isFalse(); assertThat(r.activeReplay).isSameAs(initial);
        act(r, 0, "DISCARD"); assertThat(r.activeReplay.frames()).hasSize(2); assertThat(r.activeReplay.incomplete()).isTrue();
    }

    @Test void jsonRoundTripPreservesImmutableFrameDataAndNeverExportsTokensOrWallOrder() throws Exception {
        YmRoom r = start(); act(r, r.currentSeat, "DRAW");
        ObjectMapper mapper = new ObjectMapper(); String json = mapper.writeValueAsString(r.activeReplay);
        assertThat(json).doesNotContain("never-export-secret", "\"token\"", "\"wall\"");
        JsonNode node = mapper.readTree(json);
        assertThat(node.path("startedAt").isIntegralNumber()).isTrue();
        assertThat(node.path("frames").get(0).path("timestamp").isIntegralNumber()).isTrue();
        YmReplay.HandRecord copy = mapper.readValue(json, YmReplay.HandRecord.class);
        assertThat(copy).isEqualTo(r.activeReplay);
        YmRoom restored = mapper.readValue(mapper.writeValueAsBytes(r), YmRoom.class);
        assertThat(restored.activeReplay).isEqualTo(r.activeReplay);
        JsonNode legacy = mapper.readTree("{\"id\":\"old\",\"name\":\"旧局\"}");
        assertThat(mapper.treeToValue(legacy, YmRoom.class).replayHands).isEmpty();
        assertThatThrownBy(() -> copy.frames().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void frameLimitFlagsOmittedMiddleActionsButAlwaysKeepsFinalResult() {
        YmRoom r = start();
        for (int count = 0; count < YmReplay.MAX_FRAMES + 10; count++) YmReplay.append(r, "TEST", -1, "测试保护", ++now);
        assertThat(r.activeReplay.frames()).hasSize(YmReplay.MAX_FRAMES - 1); assertThat(r.activeReplay.incomplete()).isTrue();
        r.phase = NEED_DRAW; r.currentSeat = 0; r.wall.clear();
        act(r, 0, "DRAW"); YmReplay.HandRecord completed = r.replayHands.getFirst();
        assertThat(completed.frames()).hasSize(YmReplay.MAX_FRAMES); assertThat(completed.incomplete()).isTrue();
        assertThat(completed.complete()).isTrue(); assertThat(completed.frames().getLast().result().draw()).isTrue();
        assertThat(completed.frames().getLast().index()).isEqualTo(YmReplay.MAX_FRAMES - 1);
    }

    @Test void wholeDeterministicBotMatchHasAllHandsAndEveryFrameConservesEntitiesAndPoints() {
        YmRoom r = start(); int commands = 0;
        while (r.phase != MATCH_END && commands++ < 2000) {
            boolean performed = false;
            for (YmRoom.Player p : r.players) {
                YmViews.Action action = YmBots.choose(YmBotObservation.capture(r, p), engine.gameActions(r, p));
                if (action == null) continue;
                engine.perform(r, p, action.type(), action.tileIds(), ++now); performed = true; break;
            }
            assertThat(performed).isTrue();
        }
        assertThat(r.phase).isEqualTo(MATCH_END); assertThat(r.activeReplay).isNull();
        assertThat(r.replayHands).hasSize(r.round);
        assertThat(new HashSet<>(r.replayHands.stream().map(YmReplay.HandRecord::round).toList())).hasSize(r.round);
        for (YmReplay.HandRecord hand : r.replayHands) {
            assertThat(hand.complete()).isTrue(); assertThat(hand.incomplete()).isFalse();
            assertThat(hand.frames().getFirst().type()).isEqualTo("START");
            assertThat(hand.frames().getLast().result()).isEqualTo(hand.result());
            for (int index = 0; index < hand.frames().size(); index++) {
                assertThat(hand.frames().get(index).index()).isEqualTo(index); conservation(hand.frames().get(index));
            }
        }
    }
}
