package com.mahjong.yaoming;

import com.mahjong.domain.Tile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Private, read-only wait analysis. Never inspect another player's concealed hand or the wall. */
public final class YmHints {
    private YmHints() {}

    public record Analysis(String mode, List<Wait> waits, List<Discard> discards, String note) {
        public Analysis { waits = List.copyOf(waits); discards = List.copyOf(discards); }
    }
    public record Wait(Tile tile, int unseenCount, boolean canTsumo, int tsumoFan,
                       boolean canRon, int ronFan, String ronReason) {}
    public record Discard(Tile tile, List<Wait> waits) {
        public Discard { waits = List.copyOf(waits); }
    }

    private static String note(YmRules rules) {
        return "未知张数仅扣除你的手牌和公开牌，并非牌山剩余张数；0 表示四张均已可见。"
            + rules.minimumFan() + " 番起和，番数为普通自摸/点和并按 8 番封顶；杠上炮等条件性加番另判。"
            + "本规则无振听：打过或放过同种牌仍可点和，不必等待自己摸牌。";
    }
    private static final Map<String, Tile> CANDIDATES = candidates();

    public static Analysis analyze(YmRoom room, YmRoom.Player me) {
        if (room == null || me == null || !room.players.contains(me) || me.left
            || room.phase == null || room.phase == YmRoom.Phase.WAITING
            || room.phase == YmRoom.Phase.HAND_END || room.phase == YmRoom.Phase.MATCH_END)
            return unavailable("本局进行中才可分析听牌；结算后请查看牌谱。");

        int effectiveSize = me.hand.size() + 3 * me.melds.size();
        if (effectiveSize != 13 && !(effectiveSize == 14
            && room.phase == YmRoom.Phase.NEED_DISCARD && room.currentSeat == me.seat))
            return unavailable("当前手牌不是待摸的 13 张等效牌或本人待出的 14 张等效牌。");

        Map<String, Integer> known = knownCounts(room, me);
        if (effectiveSize == 13)
            return new Analysis("WAIT", waits(room, me, me.hand, known), List.of(), note(room.rules()));

        // The rules currently allow every concealed tile to be discarded. Keep an actual legal
        // physical ID for each type, even when several identical copies have the same analysis.
        List<Tile> sorted = new ArrayList<>(me.hand);
        YmTiles.sort(sorted);
        Set<String> seen = new HashSet<>();
        List<Discard> discards = new ArrayList<>();
        for (Tile discard : sorted) {
            if (!seen.add(YmTiles.code(discard))) continue;
            List<Tile> after = new ArrayList<>(me.hand);
            after.remove(discard);
            // A hypothetical discard is still publicly known, so do not remove it from known.
            discards.add(new Discard(discard, waits(room, me, after, known)));
        }
        return new Analysis("DISCARD", List.of(), discards, note(room.rules()));
    }

    private static Analysis unavailable(String note) {
        return new Analysis("UNAVAILABLE", List.of(), List.of(), note);
    }

    private static List<Wait> waits(YmRoom room, YmRoom.Player me, List<Tile> concealed,
                                    Map<String, Integer> known) {
        YmRules rules = room.rules();
        int capacity = rules.playerCount();
        int seatWind = (me.seat - room.dealerSeat + capacity) % capacity + 1;
        int roundWind = room.round <= capacity ? 1 : 2;
        Set<String> ownIds = new HashSet<>();
        concealed.forEach(tile -> ownIds.add(tile.id()));
        me.melds.forEach(meld -> meld.tiles().forEach(tile -> ownIds.add(tile.id())));
        List<Wait> waits = new ArrayList<>();
        for (String code : rules.codes()) {
            Tile candidate = CANDIDATES.get(code);
            // Usually the reserved hint ID cannot occur in a real deck. Still avoid a collision
            // in imported/test state without reading any hidden tile IDs.
            String id = candidate.id();
            while (ownIds.contains(id)) id += "-hint";
            if (!id.equals(candidate.id()))
                candidate = new Tile(id, candidate.suit(), candidate.rank(), candidate.label(), false);
            List<Tile> completed = new ArrayList<>(concealed);
            completed.add(candidate);
            YmScoring.Evaluation tsumo = YmScoring.evaluate(completed, me.melds, seatWind, roundWind, true, false, rules);
            if (!tsumo.validStructure()) continue;
            YmScoring.Evaluation ron = YmScoring.evaluate(completed, me.melds, seatWind, roundWind, false, false, rules);
            waits.add(new Wait(candidate, Math.max(0, 4 - known.getOrDefault(code, 0)),
                tsumo.eligible(), tsumo.fan(), ron.eligible(), ron.fan(),
                ron.eligible() ? "" : "不足 " + rules.minimumFan() + " 番（当前 " + ron.fan() + " 番）"));
        }
        return List.copyOf(waits);
    }

    private static Map<String, Integer> knownCounts(YmRoom room, YmRoom.Player me) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> countedIds = new HashSet<>();
        me.hand.forEach(tile -> count(tile, countedIds, counts));
        for (YmRoom.Player player : room.players) {
            player.discards.forEach(tile -> count(tile, countedIds, counts));
            player.melds.forEach(meld -> meld.tiles().forEach(tile -> count(tile, countedIds, counts)));
        }
        return counts;
    }

    private static void count(Tile tile, Set<String> countedIds, Map<String, Integer> counts) {
        if (countedIds.add(tile.id())) counts.merge(YmTiles.code(tile), 1, Integer::sum);
    }

    private static Map<String, Tile> candidates() {
        Map<String, Tile> candidates = new LinkedHashMap<>();
        for (String code : YmRules.FOUR_PLAYER.codes()) {
            Tile tile = YmTiles.of(code);
            candidates.put(code, new Tile("ym-hint-" + code, tile.suit(), tile.rank(), tile.label(), false));
        }
        return Map.copyOf(candidates);
    }
}
