package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class YmBotObservationTest {
    private static List<Tile> tiles(String codes) {
        return new ArrayList<>(Arrays.stream(codes.split("\\s+")).filter(s -> !s.isBlank()).map(YmTiles::of).toList());
    }

    private static YmRoom room() {
        YmRoom room = new YmRoom();
        room.id = "bot-observation";
        room.phase = NEED_DISCARD;
        room.dealerSeat = 1;
        room.currentSeat = 0;
        room.round = 5;
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player player = new YmRoom.Player();
            player.id = "player-" + seat;
            player.name = "玩家" + seat;
            player.token = "private-test-token-" + seat;
            player.seat = seat;
            room.players.add(player);
        }
        room.seat(0).hand = tiles("W1 W5 W9 B2 B3 B4 D4 D5 D6 H5 H5 H5 H2 H2");
        room.wall = tiles("B1 B9 D1 D9");
        return room;
    }

    private static YmBotObservation capture(YmRoom room) { return YmBotObservation.capture(room, room.seat(0)); }

    private static YmScoring.Meld kong(String code, boolean concealed) {
        List<Tile> tiles = tiles(String.join(" ", List.of(code, code, code, code)));
        return new YmScoring.Meld("KONG", tiles, 0, concealed ? null : tiles.getFirst().id(), concealed);
    }

    @Test void capturesOwnStatePublicScoresAndDealerRelativeWinds() {
        YmRoom room = room(); YmRoom.Player me = room.seat(0);
        me.ready = true; me.score = 8;
        me.discardedCodes.add("B1"); me.passedCodes.add("D3");
        room.seat(1).score = 12;
        YmBotObservation observed = capture(room);
        assertEquals(me.hand, observed.hand()); assertEquals(me.melds, observed.melds());
        assertEquals(Set.of("B1"), observed.discardedCodes()); assertEquals(Set.of("D3"), observed.passedCodes());
        assertTrue(observed.ready()); assertEquals(0, observed.seat()); assertEquals(8, observed.score());
        assertEquals(3, observed.seatWind()); assertEquals(2, observed.roundWind()); assertEquals(4, observed.wallCount());
        assertEquals(List.of(1, 2), observed.opponents().stream().map(YmBotObservation.Opponent::seat).toList());
        assertEquals(List.of(12, 10), observed.opponents().stream().map(YmBotObservation.Opponent::score).toList());
        assertNull(observed.claimTile()); assertEquals(-1, observed.claimFromSeat());
        room.round = 3; room.dealerSeat = 0;
        assertEquals(1, capture(room).roundWind()); assertEquals(1, capture(room).seatWind());
        assertEquals(2, YmBotObservation.capture(room, room.seat(1)).seatWind());
    }

    @Test void requiresActualPlayerMembershipNotMerelyMatchingIdentityOrSeat() {
        YmRoom room = room(); YmRoom.Player outsider = room().seat(0);
        assertEquals(room.seat(0).id, outsider.id);
        assertThrows(IllegalArgumentException.class, () -> YmBotObservation.capture(room, outsider));
        assertThrows(IllegalArgumentException.class, () -> YmBotObservation.capture(room, null));
        assertThrows(IllegalArgumentException.class, () -> YmBotObservation.capture(null, room.seat(0)));
    }

    @Test void knownCountsDeduplicatePhysicalIdsAcrossOwnHandRiversMeldsAndClaim() {
        YmRoom room = room(); room.seat(0).hand = tiles("W5");
        Tile handTile = room.seat(0).hand.getFirst();
        List<Tile> pong = tiles("B2 B2 B2"); Tile claim = YmTiles.of("D9");
        room.seat(0).discards.add(handTile);
        room.seat(1).discards.addAll(List.of(pong.getFirst(), claim, claim));
        room.seat(2).melds.add(new YmScoring.Meld("PONG", pong, 1, pong.getFirst().id(), false));
        room.phase = REACTION; room.window = new YmRoom.Window(); room.window.tile = claim; room.window.fromSeat = 1;
        assertEquals(Map.of("W5", 1, "B2", 3, "D9", 1), capture(room).knownCounts());
        room.seat(1).discards.clear();
        assertEquals(Map.of("W5", 1, "B2", 3, "D9", 1), capture(room).knownCounts(), "An active claim remains visible even if absent from the river");
        Tile otherCopy = YmTiles.of("D9"); room.seat(2).discards.add(otherCopy);
        assertEquals(2, capture(room).knownCounts().get("D9"));
    }

    @Test void exposesClaimOnlyInsideAnActiveReactionAndNeverReadsOtherResponses() {
        YmRoom room = room(); room.window = new YmRoom.Window();
        room.window.tile = YmTiles.of("B8"); room.window.fromSeat = 2;
        room.window.offered = unreadableMap(); room.window.responses = unreadableMap();
        for (YmRoom.Phase phase : YmRoom.Phase.values()) {
            room.phase = phase; YmBotObservation observed = capture(room);
            if (phase == REACTION) {
                assertSame(room.window.tile, observed.claimTile()); assertEquals(2, observed.claimFromSeat());
                assertEquals(1, observed.knownCounts().get("B8"));
            } else {
                assertNull(observed.claimTile()); assertEquals(-1, observed.claimFromSeat());
                assertFalse(observed.knownCounts().containsKey("B8"));
            }
        }
        room.phase = REACTION; room.window = null;
        assertNull(capture(room).claimTile()); assertEquals(-1, capture(room).claimFromSeat());
    }

    @Test void countsAllFourPublicConcealedKongTilesButDoesNotTreatItAsAnOpenMeld() {
        YmRoom room = room();
        YmScoring.Meld own = kong("H3", true), concealed = kong("B7", true), open = kong("D7", false);
        room.seat(0).melds.add(own); room.seat(1).melds.add(concealed); room.seat(2).melds.add(open);
        YmBotObservation observed = capture(room);
        assertEquals(List.of(own), observed.melds()); assertTrue(observed.melds().getFirst().concealed());
        assertEquals(4, observed.knownCounts().get("H3")); assertEquals(4, observed.knownCounts().get("B7"));
        assertEquals(4, observed.knownCounts().get("D7"));
        assertEquals(List.of(0, 1), observed.opponents().stream().map(YmBotObservation.Opponent::openMeldCount).toList());
    }

    @Test void opponentDiscardsContainOnlyPublicCurrentRiverCodesNotPrivateHistory() {
        YmRoom room = room(); room.seat(1).discards = tiles("B7 B7 D9");
        room.seat(1).discardedCodes.add("H1"); room.seat(1).passedCodes.add("W5");
        assertEquals(Set.of("B7", "D9"), capture(room).opponents().getFirst().discards());
        room.seat(1).discards.clear();
        assertTrue(capture(room).opponents().getFirst().discards().isEmpty());
    }

    @Test void hiddenHandsPrivateHistoriesAndWallContentsCannotEvenBeRead() {
        YmRoom room = room(); YmBotObservation before = capture(room);
        for (YmRoom.Player other : room.players) if (other != room.seat(0)) {
            other.hand = unreadableList(); other.discardedCodes = unreadableSet(); other.passedCodes = unreadableSet();
            other.token = null; other.name = "changed-hidden-metadata"; other.lastDrawnId = "hidden-draw";
            other.lastSeen = -1; other.trusteeReason = "hidden-reason";
        }
        room.wall = sizeOnlyWall(4);
        room.processed = new LinkedHashMap<>(); room.events = unreadableList(); room.replayHands = unreadableList();
        assertEquals(before, assertDoesNotThrow(() -> capture(room)));
    }

    @Test void changingHiddenTilesAndReorderingSameLengthWallCannotChangeSnapshot() {
        YmRoom room = room(); YmBotObservation before = capture(room);
        room.seat(1).hand = tiles("H1 H1 H1 H2 H2 H2"); room.seat(2).hand = tiles("W1 W5 W9 B7");
        room.wall = tiles("H7 H6 H5 H3");
        assertEquals(before, capture(room));
        java.util.Collections.reverse(room.wall);
        assertEquals(before, capture(room));
        room.wall.add(YmTiles.of("H1"));
        assertEquals(before.wallCount() + 1, capture(room).wallCount(), "Only the public wall count may change");
    }

    @Test void snapshotIsReadOnlyAndDetachedFromLaterRoomMutations() {
        YmRoom room = room(); YmRoom.Player me = room.seat(0);
        me.melds.add(kong("H3", true)); me.discardedCodes.add("W5"); me.passedCodes.add("B9");
        room.seat(1).discards = tiles("D9"); room.seat(1).melds.add(kong("B7", false));
        List<Tile> originalHand = List.copyOf(me.hand); List<Tile> originalWall = List.copyOf(room.wall);
        List<YmScoring.Meld> originalMelds = List.copyOf(me.melds);
        YmBotObservation observed = capture(room); Map<String, Integer> counts = observed.knownCounts();
        assertEquals(originalHand, me.hand); assertEquals(originalWall, room.wall); assertEquals(originalMelds, me.melds);
        assertEquals(1, room.version); assertEquals(NEED_DISCARD, room.phase);
        me.hand.clear(); me.melds.clear(); me.discardedCodes.clear(); me.passedCodes.clear();
        me.ready = true; me.score = 0; room.wall.clear(); room.seat(1).discards.clear(); room.seat(1).melds.clear();
        assertEquals(originalHand, observed.hand()); assertEquals(originalMelds, observed.melds());
        assertEquals(Set.of("W5"), observed.discardedCodes()); assertEquals(Set.of("B9"), observed.passedCodes());
        assertFalse(observed.ready()); assertEquals(10, observed.score()); assertEquals(4, observed.wallCount());
        assertEquals(Set.of("D9"), observed.opponents().getFirst().discards());
        assertEquals(1, observed.opponents().getFirst().openMeldCount()); assertEquals(counts, observed.knownCounts());
    }

    @Test void everyExposedCollectionIncludingNestedMeldTilesIsImmutable() {
        YmRoom room = room(); room.seat(0).melds.add(kong("H3", true));
        YmBotObservation observed = capture(room);
        assertThrows(UnsupportedOperationException.class, () -> observed.hand().add(YmTiles.of("W5")));
        assertThrows(UnsupportedOperationException.class, () -> observed.melds().clear());
        assertThrows(UnsupportedOperationException.class, () -> observed.melds().getFirst().tiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> observed.discardedCodes().add("W5"));
        assertThrows(UnsupportedOperationException.class, () -> observed.passedCodes().add("W5"));
        assertThrows(UnsupportedOperationException.class, () -> observed.knownCounts().put("W5", 9));
        assertThrows(UnsupportedOperationException.class, () -> observed.opponents().clear());
        assertThrows(UnsupportedOperationException.class, () -> observed.opponents().getFirst().discards().add("W5"));
    }

    @Test void directRecordConstructionAlsoDefensivelyCopiesCollections() {
        List<Tile> hand = tiles("W5"); List<YmScoring.Meld> melds = new ArrayList<>();
        Set<String> discarded = new LinkedHashSet<>(Set.of("B1")), passed = new LinkedHashSet<>(Set.of("D1"));
        Map<String, Integer> known = new LinkedHashMap<>(Map.of("W5", 1));
        Set<String> river = new LinkedHashSet<>(Set.of("H3"));
        YmBotObservation.Opponent opponent = new YmBotObservation.Opponent(1, 10, 0, river);
        List<YmBotObservation.Opponent> opponents = new ArrayList<>(List.of(opponent));
        YmBotObservation observed = new YmBotObservation(hand, melds, discarded, passed, false, 0, 1, 1, 10, 60, null, -1, known, opponents);
        hand.clear(); melds.add(kong("H3", true)); discarded.clear(); passed.clear(); known.clear(); river.clear(); opponents.clear();
        assertEquals(1, observed.hand().size()); assertTrue(observed.melds().isEmpty());
        assertEquals(Set.of("B1"), observed.discardedCodes()); assertEquals(Set.of("D1"), observed.passedCodes());
        assertEquals(Map.of("W5", 1), observed.knownCounts()); assertEquals(List.of(opponent), observed.opponents());
        assertEquals(Set.of("H3"), opponent.discards());
    }

    private static <T> List<T> unreadableList() {
        return new AbstractList<>() {
            @Override public T get(int index) { throw new AssertionError("Private list contents read"); }
            @Override public int size() { throw new AssertionError("Private list size read"); }
        };
    }

    private static <T> Set<T> unreadableSet() {
        return new AbstractSet<>() {
            @Override public Iterator<T> iterator() { throw new AssertionError("Private set read"); }
            @Override public int size() { throw new AssertionError("Private set size read"); }
        };
    }

    private static <K, V> Map<K, V> unreadableMap() {
        return new AbstractMap<>() {
            @Override public Set<Entry<K, V>> entrySet() { throw new AssertionError("Other players' responses read"); }
        };
    }

    private static List<Tile> sizeOnlyWall(int size) {
        return new AbstractList<>() {
            @Override public Tile get(int index) { throw new AssertionError("Wall contents read"); }
            @Override public int size() { return size; }
        };
    }
}
