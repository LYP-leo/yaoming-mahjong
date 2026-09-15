package com.mahjong.yaoming;

import com.mahjong.domain.Tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A decision's own tiles and public table information, detached from durable room state.
 * Capture while holding the service's room-state lock; strategies must receive only this
 * value and their own server-approved actions, never the room, wall or other players.
 */
record YmBotObservation(List<Tile> hand, List<YmScoring.Meld> melds,
                        Set<String> discardedCodes, Set<String> passedCodes,
                        boolean ready, int seat, int seatWind, int roundWind, int score,
                        int wallCount, Tile claimTile, int claimFromSeat,
                        Map<String, Integer> knownCounts, List<Opponent> opponents, YmRules rules) {
    YmBotObservation {
        // Tile is an immutable record; Meld defensively copies its immutable Tile list.
        hand = List.copyOf(hand);
        melds = List.copyOf(melds);
        discardedCodes = immutableSet(discardedCodes);
        passedCodes = immutableSet(passedCodes);
        knownCounts = Collections.unmodifiableMap(new LinkedHashMap<>(knownCounts));
        opponents = List.copyOf(opponents);
        rules = java.util.Objects.requireNonNull(rules);
    }

    YmBotObservation(List<Tile> hand, List<YmScoring.Meld> melds,
                     Set<String> discardedCodes, Set<String> passedCodes,
                     boolean ready, int seat, int seatWind, int roundWind, int score,
                     int wallCount, Tile claimTile, int claimFromSeat,
                     Map<String, Integer> knownCounts, List<Opponent> opponents) {
        this(hand, melds, discardedCodes, passedCodes, ready, seat, seatWind, roundWind, score,
            wallCount, claimTile, claimFromSeat, knownCounts, opponents, YmRules.THREE_PLAYER);
    }

    record Opponent(int seat, int score, int openMeldCount, Set<String> discards) {
        Opponent { discards = immutableSet(discards); }
    }

    static YmBotObservation capture(YmRoom room, YmRoom.Player me) {
        if (room == null || me == null || room.players.stream().noneMatch(player -> player == me)) {
            throw new IllegalArgumentException("机器人决策玩家不属于此房间");
        }
        List<Tile> hand = List.copyOf(me.hand);
        List<YmScoring.Meld> melds = List.copyOf(me.melds);
        Map<String, Integer> knownCounts = new LinkedHashMap<>();
        Set<String> knownIds = new HashSet<>();
        hand.forEach(tile -> count(tile, knownIds, knownCounts));
        List<Opponent> opponents = new ArrayList<>();
        for (YmRoom.Player player : room.players) {
            Set<String> river = new LinkedHashSet<>();
            for (Tile tile : player.discards) {
                count(tile, knownIds, knownCounts);
                river.add(YmTiles.code(tile));
            }
            int openMeldCount = 0;
            for (YmScoring.Meld meld : player.melds) {
                // In this ruleset an ankan exposes its two middle faces and announces its
                // type publicly. Thus all four copies are known even to opponents. This
                // is not permission to reveal concealed hands or reuse this rule in a
                // variant that displays all four kong tiles face down.
                meld.tiles().forEach(tile -> count(tile, knownIds, knownCounts));
                if (!meld.concealed()) openMeldCount++;
            }
            if (player != me) opponents.add(new Opponent(player.seat, player.score, openMeldCount, river));
        }
        Tile claimTile = room.phase == YmRoom.Phase.REACTION && room.window != null ? room.window.tile : null;
        int claimFromSeat = claimTile == null ? -1 : room.window.fromSeat;
        if (claimTile != null) count(claimTile, knownIds, knownCounts);
        // Only size is public: never iterate, peek at or copy the remaining wall.
        YmRules rules = room.rules();
        return new YmBotObservation(hand, melds, me.discardedCodes, me.passedCodes, me.ready,
                me.seat, Math.floorMod(me.seat - room.dealerSeat, rules.playerCount()) + 1, room.round <= rules.playerCount() ? 1 : 2,
                me.score, room.wall.size(), claimTile, claimFromSeat, knownCounts, opponents, rules);
    }

    private static void count(Tile tile, Set<String> knownIds, Map<String, Integer> knownCounts) {
        if (knownIds.add(tile.id())) knownCounts.merge(YmTiles.code(tile), 1, Integer::sum);
    }

    private static <T> Set<T> immutableSet(Set<T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
