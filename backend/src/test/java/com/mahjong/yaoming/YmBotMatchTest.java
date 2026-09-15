package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.Action;
import static org.junit.jupiter.api.Assertions.*;

/** Engine-only, reproducible sample comparison; neither strategy is given its opponents' tiles.
 * Each strategy gets a fresh engine/room for every seed. The seed fixes shuffles, not the later
 * draw sequence: claims, kongs, early wins and bankruptcy change how each match consumes tiles.
 */
class YmBotMatchTest {
    private static final int[] SEEDS = { 1, 7, 31, 71, 113, 271, 997, 2026, 20260822, 260907, 260909, 8675309 };
    private static final int MAX_STEPS = 3000;
    private static final Set<String> DECK_IDS = YmTiles.deck().stream().map(Tile::id).collect(Collectors.toSet());
    private static final List<String> CLAIM_TYPES = List.of("CHI", "PONG", "OPEN_KONG", "CONCEALED_KONG", "ADDED_KONG", "PASS");

    private enum Strategy {
        SMART {
            @Override Action choose(YmRoom room, YmRoom.Player player, List<Action> legal) {
                return YmBots.choose(YmBotObservation.capture(room, player), legal);
            }
        },
        BASELINE {
            @Override Action choose(YmRoom room, YmRoom.Player player, List<Action> legal) {
                return Baseline.choose(player, legal);
            }
        };
        abstract Action choose(YmRoom room, YmRoom.Player player, List<Action> legal);
    }

    @Test void fixedSeedCompleteMatchesCompareLegalWinsDrawsAndDecisionCostsWithoutStrengthClaims() {
        Map<Strategy, Statistics> totals = new LinkedHashMap<>();
        for (Strategy strategy : Strategy.values()) totals.put(strategy, new Statistics());
        System.out.println("YM_BOT_BENCHMARK: 12 fixed seeds per strategy; independent all-bot rooms; same seed is not same draw order.");
        System.out.println("YM_BOT_BENCHMARK: rates are normalized per 100 completed hands, not 100 observed hands; submitted claims may lose response priority.");
        System.out.println("YM_BOT_BENCHMARK: latency includes SMART observation capture + choose (BASELINE choose only), excludes engine/actions/assertions, and includes cold JVM effects.");
        for (int index = 0; index < SEEDS.length; index++) {
            // Alternate execution order to avoid always assigning cold execution to one strategy.
            List<Strategy> order = index % 2 == 0 ? List.of(Strategy.BASELINE, Strategy.SMART) : List.of(Strategy.SMART, Strategy.BASELINE);
            for (Strategy strategy : order) {
                Statistics match = play(strategy, SEEDS[index]);
                totals.get(strategy).add(match);
                System.out.printf(Locale.ROOT,
                        "YM_BOT_MATCH strategy=%s seed=%d hands=%d eligibleWins=%d draws=%d actions=%d steps=%d decisionMs[p50=%.3f,p95=%.3f,max=%.3f] submittedClaims=%s%n",
                        strategy, SEEDS[index], match.hands, match.eligibleWins, match.draws, match.actions, match.steps,
                        percentileMs(match.latencies, .50), percentileMs(match.latencies, .95), percentileMs(match.latencies, 1), match.claims);
            }
        }
        for (Map.Entry<Strategy, Statistics> entry : totals.entrySet()) {
            Statistics total = entry.getValue();
            assertEquals(SEEDS.length, total.matches);
            assertTrue(total.hands >= SEEDS.length && total.hands <= SEEDS.length * 6);
            assertEquals(total.hands, total.eligibleWins + total.draws);
            assertEquals(total.actions - SEEDS.length * 3, total.latencies.size(), "Only the three setup READY commands bypass the decision timer");
            System.out.printf(Locale.ROOT,
                    "YM_BOT_TOTAL strategy=%s matches=%d hands=%d eligibleWins=%d draws=%d actions=%d roundAdvances=%d "
                            + "per100Hands[eligibleWins=%.3f,draws=%.3f,actions=%.3f] "
                            + "decisionMs[count=%d,p50=%.3f,p95=%.3f,max=%.3f] tacticalMs[count=%d,p50=%.3f,p95=%.3f,max=%.3f] submittedClaims=%s%n",
                    entry.getKey(), total.matches, total.hands, total.eligibleWins, total.draws, total.actions, total.roundAdvances,
                    per100(total.eligibleWins, total.hands), per100(total.draws, total.hands), per100(total.actions, total.hands),
                    total.latencies.size(), percentileMs(total.latencies, .50), percentileMs(total.latencies, .95), percentileMs(total.latencies, 1),
                    total.tacticalLatencies.size(), percentileMs(total.tacticalLatencies, .50), percentileMs(total.tacticalLatencies, .95), percentileMs(total.tacticalLatencies, 1), total.claims);
        }
        // Deliberately no SMART > BASELINE assertion: this is a small descriptive sample,
        // not a mixed-seat tournament, a statistical strength claim or a wall-clock SLA.
    }

    private static Statistics play(Strategy strategy, int seed) {
        YmEngine engine = new YmEngine(new Random(seed));
        YmRoom room = freshRoom(strategy, seed);
        Statistics statistics = new Statistics();
        long now = 1_000_000;
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player player = room.seat(seat);
            Action ready = engine.gameActions(room, player).stream().filter(action -> action.type().equals("READY")).findFirst().orElseThrow();
            engine.perform(room, player, ready.type(), ready.tileIds(), ++now);
            statistics.actions++; statistics.steps++;
        }
        assertEquals(NEED_DRAW, room.phase);
        assertConservation(room);
        int completedRound = 0;
        while (room.phase != MATCH_END && statistics.steps < MAX_STEPS) {
            if (room.phase == HAND_END) {
                assertEquals(completedRound, room.round, "Record a result exactly once before advancing the hand");
                assertTrue(room.players.stream().allMatch(player -> player.acknowledged));
                long previousVersion = room.version;
                engine.startAcknowledgedHand(room, ++now);
                statistics.roundAdvances++; statistics.steps++;
                assertEquals(completedRound + 1, room.round);
                assertTrue(room.version > previousVersion);
                assertEquals(NEED_DRAW, room.phase);
                assertConservation(room);
                continue;
            }
            boolean advanced = false;
            for (YmRoom.Player player : room.players) {
                List<Action> legal = List.copyOf(engine.gameActions(room, player));
                if (legal.isEmpty()) continue;
                long started = System.nanoTime();
                Action chosen = strategy.choose(room, player, legal);
                long elapsed = System.nanoTime() - started;
                assertNotNull(chosen, () -> strategy + " failed to choose at seed " + seed + ", " + room.phase + ", seat " + player.seat);
                assertTrue(legal.contains(chosen), () -> "Strategy submitted an action not offered by the engine: " + chosen);
                assertEquals((long) chosen.tileIds().size(), chosen.tileIds().stream().distinct().count(), "Never repeat a physical tile in an action");
                long previousVersion = room.version;
                engine.perform(room, player, chosen.type(), chosen.tileIds(), ++now);
                assertTrue(room.version > previousVersion, "A successful decision must advance the authoritative state");
                statistics.actions++; statistics.steps++;
                statistics.latencies.add(elapsed);
                if (legal.stream().anyMatch(action -> action.type().equals("DISCARD") || CLAIM_TYPES.contains(action.type())))
                    statistics.tacticalLatencies.add(elapsed);
                if (statistics.claims.containsKey(chosen.type())) statistics.claims.merge(chosen.type(), 1L, Long::sum);
                assertConservation(room);
                if (room.result != null && room.round > completedRound) {
                    assertTrue(room.phase == HAND_END || room.phase == MATCH_END);
                    assertEquals(completedRound + 1, room.round);
                    recordResult(room, statistics);
                    completedRound = room.round;
                }
                advanced = true;
                break;
            }
            assertTrue(advanced, () -> "No legal progress for " + strategy + " seed " + seed + " at " + room.phase + " round " + room.round);
        }
        assertEquals(MATCH_END, room.phase, () -> strategy + " seed " + seed + " did not finish within " + MAX_STEPS + " steps");
        assertTrue(statistics.steps <= MAX_STEPS);
        assertNotNull(room.result); assertTrue(room.result.matchOver());
        assertTrue(room.round >= 1 && room.round <= 6);
        assertEquals(room.round, statistics.hands);
        assertEquals(statistics.hands - 1, statistics.roundAdvances);
        assertConservation(room);
        statistics.matches = 1;
        return statistics;
    }

    private static YmRoom freshRoom(Strategy strategy, int seed) {
        YmRoom room = new YmRoom();
        room.id = "bot-benchmark-" + strategy + "-" + seed;
        room.name = "隔离引擎基准"; room.hostId = "p0";
        for (int seat = 0; seat < 3; seat++) {
            YmRoom.Player player = new YmRoom.Player();
            player.id = "p" + seat; player.name = "基准机器人" + seat; player.seat = seat;
            player.token = "test-only-token-" + seat; player.bot = true;
            room.players.add(player);
        }
        return room;
    }

    private static void assertConservation(YmRoom room) {
        List<Tile> physical = new ArrayList<>(room.wall);
        for (YmRoom.Player player : room.players) {
            physical.addAll(player.hand); physical.addAll(player.discards);
            player.melds.forEach(meld -> physical.addAll(meld.tiles()));
        }
        assertEquals(108, physical.size(), "Physical tiles must belong to one wall/hand/river/meld location; replay and last-discard references are not additional tiles");
        Set<String> ids = physical.stream().map(Tile::id).collect(Collectors.toSet());
        assertEquals(108, ids.size()); assertEquals(DECK_IDS, ids);
        assertEquals(30, room.players.stream().mapToInt(player -> player.score).sum());
        assertTrue(room.players.stream().allMatch(player -> player.score >= 0));
    }

    private static void recordResult(YmRoom room, Statistics statistics) {
        YmViews.Result result = room.result;
        assertNotNull(result);
        assertEquals(30, result.scores().stream().mapToInt(YmViews.Score::score).sum());
        assertEquals(0, result.scores().stream().mapToInt(YmViews.Score::delta).sum());
        statistics.hands++;
        if (result.draw()) {
            statistics.draws++;
            assertEquals(0, result.fan()); assertNull(result.winnerId()); assertTrue(result.payments().isEmpty());
        } else {
            statistics.eligibleWins++;
            assertTrue(result.fan() >= 4 && result.fan() <= 8, "Every counted win must pass the actual four-fan threshold and cap");
            assertTrue(result.rawFan() >= result.fan()); assertNotNull(result.winningTile());
            assertTrue(room.players.stream().anyMatch(player -> player.id.equals(result.winnerId())));
            assertTrue(result.payments().stream().allMatch(payment -> payment.amount() >= 0 && payment.amount() <= payment.requested()));
        }
    }

    private static double per100(long value, int hands) { return value * 100.0 / hands; }
    private static double percentileMs(List<Long> samples, double percentile) {
        if (samples.isEmpty()) return 0;
        List<Long> ordered = samples.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(percentile * ordered.size()) - 1);
        return ordered.get(index) / 1_000_000.0;
    }

    private static final class Statistics {
        int matches, hands, eligibleWins, draws, actions, steps, roundAdvances;
        final List<Long> latencies = new ArrayList<>(), tacticalLatencies = new ArrayList<>();
        final Map<String, Long> claims = new LinkedHashMap<>();
        Statistics() { CLAIM_TYPES.forEach(type -> claims.put(type, 0L)); }
        void add(Statistics other) {
            matches += other.matches; hands += other.hands; eligibleWins += other.eligibleWins; draws += other.draws;
            actions += other.actions; steps += other.steps; roundAdvances += other.roundAdvances;
            latencies.addAll(other.latencies); tacticalLatencies.addAll(other.tacticalLatencies);
            other.claims.forEach((type, count) -> claims.merge(type, count, Long::sum));
        }
    }

    /** Frozen pre-upgrade YmBots policy: preserve its exact priorities, tie order and weights. */
    private static final class Baseline {
        private Baseline() {}
        static Action choose(YmRoom.Player player, List<Action> actions) {
            for (String type : List.of("WIN", "ACK", "READY", "DRAW", "CONCEALED_KONG", "ADDED_KONG"))
                for (Action action : actions) if (action.type().equals(type) && !(type.equals("READY") && player.ready)) return action;
            List<Action> discards = actions.stream().filter(a -> a.type().equals("DISCARD")).toList();
            if (!discards.isEmpty()) return discards.stream().min(Comparator.comparingDouble(a -> {
                Tile tile = player.hand.stream().filter(t -> t.id().equals(a.tileIds().getFirst())).findFirst().orElseThrow();
                return usefulness(tile, player.hand);
            })).orElseThrow();
            // Preserve the closed-hand + self-draw route to four fan, unless already open.
            if (!player.melds.isEmpty()) for (String type : List.of("OPEN_KONG", "PONG", "CHI"))
                for (Action action : actions) if (action.type().equals(type)) return action;
            return actions.stream().filter(a -> a.type().equals("PASS")).findFirst().orElse(null);
        }
        private static double usefulness(Tile tile, List<Tile> hand) {
            double value = 0;
            for (Tile other : hand) {
                if (tile.id().equals(other.id())) continue;
                if (YmTiles.same(tile, other)) value += 4;
                else if (tile.suit().equals(other.suit()) && !tile.suit().equals("HONORS")) {
                    if (tile.suit().equals("CHARACTERS")) value += 1.6;
                    else { int gap = Math.abs(tile.rank() - other.rank()); value += gap == 1 ? 2 : gap == 2 ? 0.8 : 0; }
                }
            }
            // Slight preference for terminal/honor removal makes all-simples attainable.
            if (tile.suit().equals("HONORS") || tile.rank() == 1 || tile.rank() == 9) value -= 0.3;
            return value;
        }
    }
}
