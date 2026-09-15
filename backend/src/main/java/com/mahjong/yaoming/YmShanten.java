package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structural distance only: rule-specific fan eligibility and publicly exhausted waits are separate checks. */
final class YmShanten {
    private static final int HAND_CACHE_LIMIT = 4096, GROUP_CACHE_LIMIT = 32768;
    private static final int MAN = 0, NUMBERS = 1, HONORS = 2;
    private static final List<SequencePair> WIND_SEQUENCE_PAIRS = windSequencePairs();
    private final YmRules rules;
    private final long[] unconnectedMasks;
    private final Map<HandKey, Integer> hands = boundedMap(HAND_CACHE_LIMIT);
    private final Map<Long, Long> groups = boundedMap(GROUP_CACHE_LIMIT);

    private record HandKey(long first, long second, int meldCount, boolean special) {}
    private record SequencePair(int[] counts, int[] occupied) {}

    YmShanten() { this(YmRules.THREE_PLAYER); }
    YmShanten(YmRules rules) {
        this.rules = java.util.Objects.requireNonNull(rules);
        this.unconnectedMasks = rules == YmRules.FOUR_PLAYER ? YmScoring.unconnectedTemplates().stream().mapToLong(template -> {
            long mask = 0;
            for (String code : template) mask |= 1L << rules.codes().indexOf(code);
            return mask;
        }).toArray() : new long[0];
    }

    /** Reuse one instance for a decision's candidate hands; caches never grow beyond fixed limits. */
    int calculate(int[] counts, int meldCount) {
        return calculate(counts, meldCount, true);
    }

    /** Standard structure only, available separately for diagnostics and route comparisons. */
    int calculateStandard(int[] counts, int meldCount) { return calculate(counts, meldCount, false); }

    private int calculate(int[] counts, int meldCount, boolean special) {
        if (counts == null || counts.length != rules.codes().size() || meldCount < 0 || meldCount > 4)
            throw new IllegalArgumentException("Expected " + rules.codes().size() + " tile counts and zero to four declared melds");
        int size = 3 * meldCount;
        long first = 0, second = 0;
        for (int index = 0; index < counts.length; index++) {
            int count = counts[index];
            if (count < 0 || count > 4) throw new IllegalArgumentException("Each physical tile kind has zero to four copies");
            size += count;
            // Two at-most-17-digit base-five words are exact for both 27 and 34 kinds;
            // a single 34-digit word would overflow and could alias unrelated hands.
            if (index < 17) first = first * 5 + count;
            else second = second * 5 + count;
        }
        if (size > 14) throw new IllegalArgumentException("More than fourteen equivalent tiles");
        HandKey key = new HandKey(first, second, meldCount, special);
        Integer cached = hands.get(key);
        if (cached != null) return cached;

        int best = standard(counts, meldCount);
        // Even an ankan is a declared meld and excludes either ruleset's special structure.
        if (special && meldCount == 0 && best > -1) {
            if (rules == YmRules.THREE_PLAYER) best = windDragon(counts, best);
            else best = Math.min(best, unconnected(counts));
        }
        hands.put(key, best);
        return best;
    }

    static int distance(List<Tile> hand, int meldCount) {
        return distance(hand, meldCount, YmRules.THREE_PLAYER);
    }

    static int distance(List<Tile> hand, int meldCount, YmRules rules) {
        if (hand == null) throw new IllegalArgumentException("Missing hand");
        int[] counts = new int[rules.codes().size()];
        for (Tile tile : hand) {
            int index = rules.codes().indexOf(YmTiles.code(tile));
            if (index < 0) throw new IllegalArgumentException("Tile is not part of the Yaoming deck");
            counts[index]++;
        }
        return new YmShanten(rules).calculate(counts, meldCount);
    }

    private int standard(int[] counts, int declared) {
        long profiles = 1L; // Bit zero: no complete group, no two-tile group, no pair.
        int manEnd = rules.legacyWanSequence() ? 3 : 9;
        profiles = combine(profiles, profiles(Arrays.copyOfRange(counts, 0, manEnd), rules.legacyWanSequence() ? MAN : NUMBERS), declared);
        profiles = combine(profiles, profiles(Arrays.copyOfRange(counts, manEnd, manEnd + 9), NUMBERS), declared);
        profiles = combine(profiles, profiles(Arrays.copyOfRange(counts, manEnd + 9, manEnd + 18), NUMBERS), declared);
        profiles = combine(profiles, profiles(Arrays.copyOfRange(counts, manEnd + 18, counts.length), HONORS), declared);
        int best = 8;
        for (long remaining = profiles; remaining != 0; remaining &= remaining - 1) {
            int index = Long.numberOfTrailingZeros(remaining);
            int melds = index / 10 + declared, incomplete = index % 10 / 2, pair = index & 1;
            best = Math.min(best, 8 - 2 * melds - Math.min(incomplete, 4 - melds) - pair);
        }
        return best;
    }

    /** Each set bit encodes m*10 + t*2 + p. Excess groups can always be skipped. */
    private long profiles(int[] counts, int kind) {
        long key = 0;
        int first = -1;
        for (int i = 0; i < counts.length; i++) {
            key = key * 5 + counts[i];
            if (first < 0 && counts[i] != 0) first = i;
        }
        if (first < 0) return 1L;
        key = key * 3 + kind;
        Long cached = groups.get(key);
        if (cached != null) return cached;

        counts[first]--;
        long options = profiles(counts, kind); // Leave this tile ungrouped.
        counts[first]++;

        if (counts[first] >= 3) {
            counts[first] -= 3;
            options |= add(profiles(counts, kind), 1, 0, 0);
            counts[first] += 3;
        }
        if (counts[first] >= 2) {
            counts[first] -= 2;
            long afterPair = profiles(counts, kind);
            options |= add(afterPair, 0, 0, 1); // The unique head.
            options |= add(afterPair, 0, 1, 0); // A prospective pung, not a seven-pairs route.
            counts[first] += 2;
        }
        if (kind != HONORS) {
            // MAN's three array positions are W1/W5/W9, so this is its only legal run.
            if (first + 2 < counts.length && counts[first + 1] > 0 && counts[first + 2] > 0) {
                counts[first]--; counts[first + 1]--; counts[first + 2]--;
                options |= add(profiles(counts, kind), 1, 0, 0);
                counts[first]++; counts[first + 1]++; counts[first + 2]++;
            }
            for (int next = first + 1; next <= first + 2 && next < counts.length; next++) if (counts[next] > 0) {
                counts[first]--; counts[next]--;
                options |= add(profiles(counts, kind), 0, 1, 0);
                counts[first]++; counts[next]++;
            }
        }
        groups.put(key, options);
        return options;
    }

    private static long add(long profiles, int meld, int incomplete, int pair) {
        long result = 0;
        for (long remaining = profiles; remaining != 0; remaining &= remaining - 1) {
            int index = Long.numberOfTrailingZeros(remaining);
            int m = index / 10 + meld, t = index % 10 / 2 + incomplete, p = (index & 1) + pair;
            if (m + t <= 4 && p <= 1) result |= 1L << (m * 10 + t * 2 + p);
        }
        return result;
    }

    private static long combine(long first, long second, int declared) {
        long result = 0;
        for (long a = first; a != 0; a &= a - 1) {
            int ai = Long.numberOfTrailingZeros(a);
            for (long b = second; b != 0; b &= b - 1) {
                int bi = Long.numberOfTrailingZeros(b);
                int m = ai / 10 + bi / 10, t = ai % 10 / 2 + bi % 10 / 2, p = (ai & 1) + (bi & 1);
                if (m + t + declared <= 4 && p <= 1) result |= 1L << (m * 10 + t * 2 + p);
            }
        }
        return result;
    }

    private static int windDragon(int[] counts, int best) {
        int missingHonors = 0;
        for (int i = 21; i < 27; i++) if (counts[i] == 0) missingHonors++;
        if (missingHonors - 1 >= best) return best;
        // There are 15 legal sequences, hence 120 unordered pairs with repetition. For
        // each one, reserve six distinct honors, then try the best of all 27 possible heads.
        // This is exactly the 120 * 27 target search, without allocating 3,240 full hands.
        for (SequencePair target : WIND_SEQUENCE_PAIRS) {
            int missing = missingHonors;
            for (int index : target.occupied()) missing += Math.max(0, target.counts()[index] - counts[index]);
            if (missing - 1 >= best) continue;
            int pairMissing = 2;
            for (int i = 0; i < 27 && pairMissing != 0; i++) {
                int reserved = i >= 21 ? 1 : target.counts()[i];
                pairMissing = Math.min(pairMissing, Math.max(0, 2 - Math.max(0, counts[i] - reserved)));
            }
            best = Math.min(best, missing + pairMissing - 1);
            if (best == -1) return best;
        }
        return best;
    }

    private int unconnected(int[] counts) {
        long present = 0;
        for (int index = 0; index < counts.length; index++) if (counts[index] > 0) present |= 1L << index;
        int matched = 0;
        for (long mask : unconnectedMasks) matched = Math.max(matched, Long.bitCount(mask & present));
        return 13 - matched;
    }

    private static List<SequencePair> windSequencePairs() {
        List<int[]> sequences = new ArrayList<>();
        sequences.add(new int[] { 0, 1, 2 });
        for (int start : new int[] { 3, 12 })
            for (int rank = 0; rank < 7; rank++) sequences.add(new int[] { start + rank, start + rank + 1, start + rank + 2 });
        List<SequencePair> targets = new ArrayList<>();
        for (int first = 0; first < sequences.size(); first++) for (int second = first; second < sequences.size(); second++) {
            int[] counts = new int[27];
            for (int index : sequences.get(first)) counts[index]++;
            for (int index : sequences.get(second)) counts[index]++;
            int[] occupied = java.util.stream.IntStream.range(0, 21).filter(index -> counts[index] != 0).toArray();
            targets.add(new SequencePair(counts, occupied));
        }
        return List.copyOf(targets);
    }

    private static <K, V> Map<K, V> boundedMap(int maximum) {
        return new LinkedHashMap<>(128, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > maximum; }
        };
    }
}
