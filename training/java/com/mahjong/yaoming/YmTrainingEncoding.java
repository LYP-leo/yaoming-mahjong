package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import java.util.*;
import static com.mahjong.yaoming.YmViews.Action;

/** Fixed protocol-v1 representation. Never encode server-private identities or state. */
public final class YmTrainingEncoding {
    public static final int ACTION_SIZE = 194;
    public static final int OBSERVATION_SIZE = 1152;
    private YmTrainingEncoding() {}

    /** Tile indices: W1..9, B1..9, D1..9, H1..7 (H5=red dragon). */
    public static int tileIndex(Tile tile) { return tileIndex(YmTiles.code(tile)); }
    public static int tileIndex(String code) {
        if (code == null || code.length() != 2) throw new IllegalArgumentException("Invalid tile code");
        int start = switch (code.charAt(0)) { case 'W' -> 0; case 'B' -> 9; case 'D' -> 18; case 'H' -> 27; default -> -100; };
        int rank = code.charAt(1) - '0';
        if (start < 0 || rank < 1 || rank > (start == 27 ? 7 : 9)) throw new IllegalArgumentException("Invalid tile code");
        return start + rank - 1;
    }

    /**
     * 0..33 discard, 34 win, 35 pass, 36..69 pong, 70..103 open kong,
     * 104..137 concealed kong, 138..171 added kong, 172..192 ordinary chi
     * (suit W/B/D then starting rank1..7), 193 three-player W159 chi.
     * Same-faced physical discards collapse, choosing the just-drawn ID when present.
     */
    public static LinkedHashMap<Integer, Action> actions(YmRoom room, YmRoom.Player player, List<Action> legal) {
        LinkedHashMap<Integer, Action> result = new LinkedHashMap<>();
        for (Action action : legal) {
            int code = actionIndex(room, player, action);
            Action previous = result.putIfAbsent(code, action);
            if (previous != null && !previous.type().equals(action.type())) throw new IllegalStateException("Action-code collision");
            if (action.type().equals("DISCARD") && action.tileIds().getFirst().equals(player.lastDrawnId)) result.put(code, action);
        }
        if (result.isEmpty()) throw new IllegalStateException("Decision has no encodable legal actions");
        return result;
    }

    static int actionIndex(YmRoom room, YmRoom.Player player, Action action) {
        if (action.type().equals("WIN")) return 34;
        if (action.type().equals("PASS")) return 35;
        List<Tile> own = action.tileIds().stream().map(id -> player.hand.stream().filter(t -> t.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalStateException("Legal action references non-owned tile"))).toList();
        if (own.isEmpty()) throw new IllegalStateException("Unexpected mechanical/empty action " + action.type());
        int tile = tileIndex(own.getFirst());
        return switch (action.type()) {
            case "DISCARD" -> tile;
            case "PONG" -> 36 + tile;
            case "OPEN_KONG" -> 70 + tile;
            case "CONCEALED_KONG" -> 104 + tile;
            case "ADDED_KONG" -> 138 + tile;
            case "CHI" -> {
                if (room.window == null || own.size() != 2) throw new IllegalStateException("CHI requires a response tile");
                List<String> codes = new ArrayList<>(own.stream().map(YmTiles::code).toList());
                codes.add(YmTiles.code(room.window.tile));
                if (!YmTiles.sequence(codes, room.rules())) throw new IllegalStateException("Engine exposed an invalid sequence");
                if (new HashSet<>(codes).equals(Set.of("W1", "W5", "W9"))) yield 193;
                int first = codes.stream().mapToInt(YmTrainingEncoding::tileIndex).min().orElseThrow();
                if (first >= 27 || first % 9 > 6) throw new IllegalStateException("Unencodable CHI");
                yield 172 + (first / 9) * 7 + first % 9;
            }
            default -> throw new IllegalStateException("Unencodable legal action " + action.type());
        };
    }

    /**
     * 1152 floats, no recurrent history. All seat-dependent blocks are relative to actor:
     * next seat is (actor+1)%playerCount; the absent fourth block is zero in 3p.
     * [0,34) own concealed counts/4; [34,68) own last draw one-hot;
     * [68,102) current response tile one-hot; [102,136) rule tile availability.
     * [136,160) globals: discard phase, response phase, 3p,4p, round/8,wall/136,
     * playerCount/4, own afterKong, relative dealer[4], own wind[4], round wind[2],
     * relative claim-from[4], response kong-discard, own score/40.
     * Then 4 blocks of248. Per block [0,8): present,score/40,handSize/14,
     * seatWind/4,openMeldCount/4,meldCount/4,isDealer,isLastDiscarder;
     * [8,42) current river counts/4; [42,76) current river tsumogiri counts/4;
     * four43-wide declared-meld slots: counts/4[34],chi,pong,kong,concealed,added,
     * relative source one-hot[4]. Declared concealed kongs are public in Yaoming.
     * Opponents' concealed faces, wall order, pending responses/offers, IDs, timing,
     * result hands and winner-private metadata are deliberately never read here.
     */
    public static float[] observation(YmRoom room, int actor) {
        float[] out = new float[OBSERVATION_SIZE];
        if (actor < 0) return out;
        YmRoom.Player me = room.seat(actor);
        int count = room.rules().playerCount();
        me.hand.forEach(tile -> out[tileIndex(tile)] += .25f);
        me.hand.stream().filter(tile -> tile.id().equals(me.lastDrawnId)).findFirst().ifPresent(tile -> out[34 + tileIndex(tile)] = 1);
        boolean reaction = room.phase == YmRoom.Phase.REACTION && room.window != null;
        if (reaction) out[68 + tileIndex(room.window.tile)] = 1;
        room.rules().codes().forEach(code -> out[102 + tileIndex(code)] = 1);
        int global = 136;
        out[global] = room.phase == YmRoom.Phase.NEED_DISCARD ? 1 : 0;
        out[global + 1] = reaction ? 1 : 0;
        out[global + 2] = count == 3 ? 1 : 0; out[global + 3] = count == 4 ? 1 : 0;
        out[global + 4] = room.round / 8f; out[global + 5] = room.wall.size() / 136f;
        out[global + 6] = count / 4f; out[global + 7] = me.afterKong ? 1 : 0;
        out[global + 8 + Math.floorMod(room.dealerSeat - actor, count)] = 1;
        out[global + 12 + Math.floorMod(actor - room.dealerSeat, count)] = 1;
        out[global + 16 + (room.round <= count ? 0 : 1)] = 1;
        if (reaction) {
            out[global + 18 + Math.floorMod(room.window.fromSeat - actor, count)] = 1;
            out[global + 22] = room.window.kongDiscard ? 1 : 0;
        }
        out[global + 23] = me.score / 40f;
        for (int relative = 0; relative < count; relative++) {
            YmRoom.Player player = room.seat((actor + relative) % count);
            int base = 160 + relative * 248;
            out[base] = 1; out[base + 1] = player.score / 40f; out[base + 2] = player.hand.size() / 14f;
            out[base + 3] = (Math.floorMod(player.seat - room.dealerSeat, count) + 1) / 4f;
            out[base + 4] = player.melds.stream().filter(meld -> !meld.concealed()).count() / 4f;
            out[base + 5] = player.melds.size() / 4f;
            out[base + 6] = player.seat == room.dealerSeat ? 1 : 0;
            out[base + 7] = room.lastDiscard != null && player.seat == room.lastDiscard.fromSeat() ? 1 : 0;
            for (Tile tile : player.discards) {
                out[base + 8 + tileIndex(tile)] += .25f;
                if ("TSUMOGIRI".equals(player.discardKinds.get(tile.id()))) out[base + 42 + tileIndex(tile)] += .25f;
            }
            if (player.melds.size() > 4) throw new IllegalStateException("More than four melds");
            for (int slot = 0; slot < player.melds.size(); slot++) {
                YmScoring.Meld meld = player.melds.get(slot);
                int start = base + 76 + slot * 43;
                for (Tile tile : meld.tiles()) out[start + tileIndex(tile)] += .25f;
                int kind = switch (meld.type()) { case "CHI" -> 34; case "PONG" -> 35; case "KONG" -> 36; default -> throw new IllegalStateException("Unknown meld type"); };
                out[start + kind] = 1; out[start + 37] = meld.concealed() ? 1 : 0; out[start + 38] = meld.added() ? 1 : 0;
                out[start + 39 + Math.floorMod(meld.fromSeat() - actor, count)] = 1;
            }
        }
        return out;
    }
}
