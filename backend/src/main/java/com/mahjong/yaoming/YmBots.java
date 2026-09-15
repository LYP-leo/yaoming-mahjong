package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import java.util.*;
import static com.mahjong.yaoming.YmViews.*;

/** Bounded, deterministic heuristic opponent. Receives only its own and public information. */
final class YmBots {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(YmBots.class);
    private static final Map<YmRules, Map<String, Integer>> FAN_VALUES = Arrays.stream(YmRules.values()).collect(
        java.util.stream.Collectors.toUnmodifiableMap(rule -> rule, rule -> YmScoring.catalog(rule).stream().collect(
            java.util.stream.Collectors.toUnmodifiableMap(YmScoring.Fan::id, YmScoring.Fan::fan))));
    private YmBots() {}

    static Action choose(YmBotObservation view, List<Action> actions) {
        if (actions.isEmpty()) return null;
        for (String type : List.of("WIN", "ACK", "READY", "DRAW"))
            for (Action action : actions)
                if (action.type().equals(type) && !(type.equals("READY") && view.ready())) return action;
        try {
            return new Decision(view).choose(actions);
        } catch (RuntimeException failure) {
            // An evaluation failure must not strand this or other rooms on the service lock.
            // Do not log hands, tokens or exception payloads; keep every fallback server-approved.
            LOG.warn("Robot heuristic failed ({}); using a legal conservative fallback", failure.getClass().getSimpleName());
            return actions.stream().filter(action -> action.type().equals("PASS")).findFirst()
                .orElseGet(() -> actions.stream().filter(action -> action.type().equals("DISCARD"))
                    .min(Comparator.comparing(action -> String.join(":", action.tileIds()))).orElse(null));
        }
    }

    private record Quality(int distance, int tsumoOuts, int ronOuts, int improvements, int fanWeight, double potential) {
        double value() {
            // Distance dominates ordinary attack decisions; the lower terms break close choices.
            return -1000.0 * distance + Math.min(600, tsumoOuts * 12 + ronOuts * 6 + improvements * 4 + fanWeight)
                + Math.min(8, potential) * 12;
        }
        boolean liveWait() { return tsumoOuts + ronOuts > 0; }
    }
    private record Candidate(Action action, Quality quality, double value) {}

    private static final class Decision {
        private final YmBotObservation view;
        private final YmShanten shanten;
        private final Map<String, Quality> cache = new HashMap<>();
        private final Map<String, Tile> own = new HashMap<>();
        private final Map<String, Integer> known;
        private final Tile[] probes;

        Decision(YmBotObservation view) {
            this.view = view;
            this.shanten = new YmShanten(view.rules());
            this.probes = new Tile[view.rules().codes().size()];
            this.known = view.knownCounts();
            view.hand().forEach(tile -> own.put(tile.id(), tile));
            Set<String> used = new HashSet<>(own.keySet());
            view.melds().forEach(meld -> meld.tiles().forEach(tile -> used.add(tile.id())));
            if (view.claimTile() != null) used.add(view.claimTile().id());
            for (int index = 0; index < probes.length; index++) {
                Tile value = YmTiles.of(view.rules().codes().get(index), view.rules());
                String id = "bot-probe-" + view.rules().codes().get(index);
                while (used.contains(id)) id += "-probe";
                probes[index] = new Tile(id, value.suit(), value.rank(), value.label(), false);
            }
        }

        Action choose(List<Action> actions) {
            List<Action> discards = actions.stream().filter(action -> action.type().equals("DISCARD"))
                .sorted(Comparator.comparing(this::actionKey)).toList();
            Candidate bestDiscard = null;
            for (Action action : discards) {
                if (action.tileIds().size() != 1 || !own.containsKey(action.tileIds().getFirst())) continue;
                Tile discard = own.get(action.tileIds().getFirst());
                List<Tile> after = without(view.hand(), action.tileIds());
                Quality quality = assess(after, view.melds());
                Candidate candidate = new Candidate(action, quality, discardValue(quality, discard));
                bestDiscard = better(bestDiscard, candidate);
            }
            Action pass = actions.stream().filter(action -> action.type().equals("PASS")).findFirst().orElse(null);
            Quality baseline = bestDiscard != null ? bestDiscard.quality() : assess(view.hand(), view.melds());
            Candidate best = bestDiscard;
            Set<String> simulated = new HashSet<>();
            for (Action action : actions.stream().sorted(Comparator.comparing(this::actionKey)).toList()) {
                if (!Set.of("CHI", "PONG", "OPEN_KONG", "CONCEALED_KONG", "ADDED_KONG").contains(action.type())) continue;
                String semantic = action.type() + action.tileIds().stream().map(own::get).filter(Objects::nonNull).map(YmTiles::code).sorted().toList();
                if (!simulated.add(semantic)) continue;
                Candidate candidate = claim(action, baseline);
                if (candidate == null) continue;
                // Prefer passing over a merely cosmetic exchange of equivalent closed-hand tiles.
                if (bestDiscard == null && candidate.value() <= baseline.value() + 12) continue;
                best = better(best, candidate);
            }
            return best != null ? best.action() : pass;
        }

        private Candidate claim(Action action, Quality baseline) {
            if (action.tileIds().isEmpty() || action.tileIds().stream().anyMatch(id -> !own.containsKey(id))) return null;
            List<Tile> hand = without(view.hand(), action.tileIds());
            List<YmScoring.Meld> melds = new ArrayList<>(view.melds());
            List<Tile> group = new ArrayList<>(action.tileIds().stream().map(own::get).toList());
            boolean kong = action.type().contains("KONG");
            if (kong && view.wallCount() <= 0) return null;
            if (action.type().equals("ADDED_KONG")) {
                if (group.size() != 1) return null;
                int index = -1;
                for (int i = 0; i < melds.size(); i++) if (melds.get(i).type().equals("PONG")
                    && YmTiles.same(melds.get(i).tiles().getFirst(), group.getFirst())) { index = i; break; }
                if (index < 0) return null;
                YmScoring.Meld old = melds.get(index);
                group.addAll(old.tiles());
                melds.set(index, new YmScoring.Meld("KONG", group, old.fromSeat(), old.claimedTileId(), false, true));
            } else {
                boolean concealed = action.type().equals("CONCEALED_KONG");
                if (!concealed) {
                    if (view.claimTile() == null) return null;
                    group.add(view.claimTile());
                }
                melds.add(new YmScoring.Meld(kong ? "KONG" : action.type(), group,
                    concealed ? view.seat() : view.claimFromSeat(), concealed ? "" : view.claimTile().id(), concealed));
            }
            Quality quality;
            double value;
            if (kong) {
                // Evaluate the 13-equivalent pre-replacement hand. Never peek at the wall's tail.
                quality = assess(hand, melds);
                value = quality.value() + 8;
            } else {
                Candidate next = bestHypotheticalDiscard(hand, melds);
                if (next == null) return null;
                quality = next.quality(); value = next.value();
            }
            if (quality.distance() > baseline.distance()) return null;
            if (baseline.liveWait() && !quality.liveWait()) return null;
            boolean breaksClosed = view.melds().stream().allMatch(YmScoring.Meld::concealed)
                && melds.stream().anyMatch(meld -> !meld.concealed());
            // A speculative open hand needs both progress and a route near this room's minimum.
            if (breaksClosed && !quality.liveWait()
                && (quality.distance() >= baseline.distance() || quality.distance() > 1
                    || quality.potential() < view.rules().minimumFan() - .2)) return null;
            if (!breaksClosed && !quality.liveWait() && quality.potential() < view.rules().minimumFan() - .8) return null;
            return new Candidate(action, quality, value);
        }

        private Candidate bestHypotheticalDiscard(List<Tile> hand, List<YmScoring.Meld> melds) {
            Candidate best = null;
            Set<String> seen = new HashSet<>();
            List<Tile> sorted = new ArrayList<>(hand); YmTiles.sort(sorted);
            for (Tile tile : sorted) {
                if (!seen.add(YmTiles.code(tile))) continue;
                Quality quality = assess(without(hand, List.of(tile.id())), melds);
                best = better(best, new Candidate(null, quality, discardValue(quality, tile)));
            }
            return best;
        }

        private Quality assess(List<Tile> hand, List<YmScoring.Meld> melds) {
            int[] counts = counts(hand, view.rules());
            String key = Arrays.toString(counts) + melds.stream().map(meld -> meld.type() + ":" + meld.concealed() + ":"
                + meld.tiles().stream().map(YmTiles::code).sorted().toList()).toList();
            Quality found = cache.get(key);
            if (found != null) return found;
            int structural = structuralDistance(counts, melds.size());
            int improvements = 0, tsumo = 0, ron = 0, fanWeight = 0;
            if (hand.size() + melds.size() * 3 == 13) {
                for (int i = 0; i < counts.length; i++) {
                    String code = view.rules().codes().get(i);
                    int unseen = Math.max(0, 4 - known.getOrDefault(code, 0));
                    if (unseen == 0 || counts[i] >= 4) continue;
                    counts[i]++;
                    int next = structuralDistance(counts, melds.size());
                    counts[i]--;
                    if (next < structural) improvements += unseen;
                    if (next != -1) continue;
                    List<Tile> completed = new ArrayList<>(hand); completed.add(probes[i]);
                    YmScoring.Evaluation self = YmScoring.evaluate(completed, melds, view.seatWind(), view.roundWind(), true, false, view.rules());
                    YmScoring.Evaluation other = YmScoring.evaluate(completed, melds, view.seatWind(), view.roundWind(), false, false, view.rules());
                    if (self.eligible()) { tsumo += unseen; fanWeight += unseen * self.fan(); }
                    // This ruleset has no furiten: historical passes/discards and
                    // the hypothetical discard itself never prohibit same-type ron.
                    if (other.eligible()) ron += unseen;
                }
            }
            // Structural tenpai with no live, eligible win is not playable tenpai.
            int distance = structural == 0 && tsumo + ron == 0 ? 1 : Math.max(0, structural);
            if (structural == 0 && tsumo + ron == 0) improvements = 0;
            Quality result = new Quality(distance, tsumo, ron, improvements, fanWeight, fanPotential(view, hand, melds));
            cache.put(key, result);
            return result;
        }

        private int structuralDistance(int[] counts, int meldCount) {
            // Four-player full-unconnected plus self draw now reaches three fan.
            // Keep its structural route; actual self-draw/ron eligibility is checked above.
            return shanten.calculate(counts, meldCount);
        }

        private double discardValue(Quality quality, Tile tile) {
            double risk = 0;
            boolean threatened = false;
            for (YmBotObservation.Opponent opponent : view.opponents()) {
                if (opponent.openMeldCount() < 2) continue;
                threatened = true;
                // An opponent may ron a type already in their own river. Public
                // discards affect unseen counts, but confer no automatic safety.
                double typeRisk = tile.suit().equals("HONORS") ? .7 : tile.rank() == 1 || tile.rank() == 9 ? .85 : 1;
                risk += (opponent.openMeldCount() >= 3 ? 1.4 : 1) * typeRisk;
            }
            boolean fold = threatened && view.score() <= 4 && quality.distance() >= 2;
            return quality.value() - risk * (fold ? 900 : 9);
        }

        private String actionKey(Action action) {
            return action.type() + ":" + action.tileIds().stream().map(own::get).filter(Objects::nonNull)
                .map(YmTiles::code).sorted().toList() + ":" + action.tileIds().stream().sorted().toList();
        }
        private Candidate better(Candidate first, Candidate next) {
            return first == null || next.value() > first.value() + .000001 ? next : first;
        }
    }

    /** Approximate route selection only; actual waits and wins always use YmScoring.evaluate. */
    static double fanPotential(YmBotObservation view, List<Tile> hand, List<YmScoring.Meld> melds) {
        Map<String, Integer> fans = FAN_VALUES.get(view.rules());
        List<Tile> all = new ArrayList<>(hand); melds.forEach(meld -> all.addAll(meld.tiles()));
        boolean closed = melds.stream().allMatch(YmScoring.Meld::concealed);
        double base = closed ? fans.get("MENQING") + fans.get("BUQIUREN") : 0;
        base += fans.get("KONG") * melds.stream().filter(meld -> meld.type().equals("KONG")).count();
        if (!all.isEmpty() && all.stream().allMatch(tile -> tile.suit().equals("HONORS")))
            return Math.min(8, base + fans.get("ZIYISE") + (melds.size() == 4 ? fans.get("SHIERLUOTAI") : 0));
        Map<String, Integer> groups = new HashMap<>(); all.forEach(tile -> groups.merge(YmTiles.code(tile), 1, Integer::sum));
        double value = 0;
        for (String code : view.rules().codes()) {
            if (!code.startsWith("H")) continue;
            int rank = code.charAt(1) - '0';
            int fan = (rank >= 5 ? 1 : 0) + (rank == view.seatWind() ? 1 : 0) + (rank == view.roundWind() ? 1 : 0);
            int count = groups.getOrDefault(code, 0);
            value += fan * (count >= 3 ? 1 : count == 2 ? .6 : 0);
        }
        double potential = base + value;
        // Pinghe allows open sequences and terminals, but no honor pair or triplet/kong.
        // In incomplete hands use a small progress estimate, not the removed tanyao bonus.
        double pinghe = melds.stream().allMatch(meld -> meld.type().equals("CHI"))
            ? Math.max(0, fans.get("PINGHE") - hand.stream().filter(tile -> tile.suit().equals("HONORS")).count() * .7
                - groups.entrySet().stream().filter(entry -> entry.getValue() >= 3).count() * .2) : 0;
        potential = Math.max(potential, base + pinghe);
        for (String suit : List.of("CHARACTERS", "BAMBOO", "DOTS", "HONORS")) {
            if (melds.stream().flatMap(meld -> meld.tiles().stream()).allMatch(tile -> tile.suit().equals(suit)))
                // Zi yi se excludes value honors and no longer stacks qing yi se.
                potential = Math.max(potential, base + (suit.equals("HONORS") ? fans.get("ZIYISE") : fans.get("QINGYISE") + pinghe)
                    - hand.stream().filter(tile -> !tile.suit().equals(suit)).count() * (suit.equals("HONORS") ? 1.5 : .8));
            if (!suit.equals("HONORS") && melds.stream().flatMap(meld -> meld.tiles().stream()).allMatch(tile -> tile.suit().equals(suit) || tile.suit().equals("HONORS")))
                potential = Math.max(potential, base + value + fans.get("HUNYISE") - hand.stream().filter(tile -> !tile.suit().equals(suit) && !tile.suit().equals("HONORS")).count() * .8);
        }
        Set<String> categories = new HashSet<>();
        all.forEach(tile -> categories.add(tile.suit().equals("HONORS") ? tile.rank() <= 4 ? "WIND" : "DRAGON" : tile.suit()));
        potential = Math.max(potential, base + value + fans.get("WUMENQI") - (5 - categories.size()) * .9);
        if (melds.stream().allMatch(meld -> meld.tiles().stream().anyMatch(tile -> !tile.suit().equals("HONORS") && tile.rank() == 5))) {
            long incompatible = hand.stream().filter(tile -> tile.suit().equals("HONORS")
                || !(view.rules().legacyWanSequence() && tile.suit().equals("CHARACTERS")) && (tile.rank() < 3 || tile.rank() > 7)).count();
            potential = Math.max(potential, base + value + fans.get("QUANDAIWU") - incompatible * .8);
        }
        return Math.max(0, Math.min(8, potential));
    }

    private static int[] counts(List<Tile> hand, YmRules rules) {
        int[] counts = new int[rules.codes().size()];
        for (Tile tile : hand) {
            int index = rules.codes().indexOf(YmTiles.code(tile));
            if (index < 0) throw new IllegalArgumentException("Tile is not part of the robot's ruleset");
            counts[index]++;
        }
        return counts;
    }
    private static List<Tile> without(List<Tile> hand, List<String> ids) {
        return hand.stream().filter(tile -> !ids.contains(tile.id())).toList();
    }
}
