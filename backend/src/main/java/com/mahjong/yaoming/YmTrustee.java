package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import java.util.ArrayList;
import java.util.List;

import static com.mahjong.yaoming.YmViews.Action;

/** Human autopilot: no scoring, opponent observation, claims, kongs or automatic wins. */
final class YmTrustee {
    private YmTrustee() {}

    static Action choose(List<Tile> hand, String lastDrawnId, boolean ready, List<Action> legal) {
        // Preserve the existing lobby/settlement lifecycle without toggling a ready seat off.
        for (String type : List.of("ACK", "READY", "DRAW", "PASS")) {
            if (type.equals("READY") && ready) continue;
            for (Action action : legal) if (action.type().equals(type)) return action;
        }
        List<Action> discards = legal.stream()
                .filter(action -> action.type().equals("DISCARD") && action.tileIds().size() == 1).toList();
        if (lastDrawnId != null && hand.stream().anyMatch(tile -> tile.id().equals(lastDrawnId)))
            for (Action action : discards) if (action.tileIds().getFirst().equals(lastDrawnId)) return action;

        // After chi/pong there is no drawn entity. Pick the rightmost legal tile mechanically.
        List<Tile> sorted = new ArrayList<>(hand); YmTiles.sort(sorted);
        for (int index = sorted.size() - 1; index >= 0; index--)
            for (Action action : discards) if (action.tileIds().getFirst().equals(sorted.get(index).id())) return action;
        return null;
    }
}
