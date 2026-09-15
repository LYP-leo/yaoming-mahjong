package com.mahjong.yaoming;

import com.mahjong.domain.Tile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure evaluator for the supplied flowerless three- and four-player rules. */
public final class YmScoring {
    private YmScoring() {}
    public record Meld(String type, List<Tile> tiles, int fromSeat, String claimedTileId, boolean concealed, boolean added) {
        public Meld { tiles = List.copyOf(tiles); }
        public Meld(String type, List<Tile> tiles, int fromSeat, String claimedTileId, boolean concealed) {
            this(type, tiles, fromSeat, claimedTileId, concealed, false);
        }
    }
    public record Fan(String id, String name, int fan, String description) {}
    public record Evaluation(boolean validStructure, boolean eligible, int rawFan, int fan, List<Fan> items) {
        public Evaluation { items = List.copyOf(items); }
    }
    private record Group(List<String> codes, boolean sequence) {}
    private static final List<Fan> CATALOG = List.of(
        new Fan("PINGHE", "平和", 1, "四组顺子加数牌雀头；允许吃，159 万也算顺子，不限听口。"),
        new Fan("FANPAI", "番牌", 1, "每组圈风刻、门风刻或箭刻 1 番；圈风与门风相同累加。"),
        new Fan("KONG", "杠", 1, "每个明杠、暗杠或加杠 1 番。"),
        new Fan("MENQING", "门清", 2, "没有吃、碰、明杠或加杠；允许暗杠。"),
        new Fan("PENGPENG", "碰碰和", 1, "四刻子或杠加一雀头。"),
        new Fan("SHIERLUOTAI", "十二落抬", 1, "四组副露成和；暗杠置于副露区，亦计入。"),
        new Fan("BUQIUREN", "不求人", 1, "门清自摸成和。"),
        new Fan("GANGSHANGPAO", "杠上炮", 1, "点和他家开杠补牌后打出的牌。"),
        new Fan("WUMENQI", "五门齐", 2, "包含万、条、筒、风、箭。"),
        new Fan("HUNYISE", "混一色", 2, "恰有一种数牌花色和字牌。"),
        new Fan("HUNQUANDAIYAO", "混全带幺", 2, "每组面子和雀头均含 1、9 或字牌。"),
        new Fan("QINGYISE", "清一色", 3, "只有一种数牌花色，不含字牌；不计混一色。"),
        new Fan("QINGQUANDAIYAO", "清全带幺", 3, "每组面子和雀头含 1 或 9，且无字牌；不计混全带幺。"),
        new Fan("HUNSILIAN", "混四连", 3, "恰有连续三种数值，以及风或箭中的一种类别。"),
        new Fan("FENGLONG", "风龙", 3, "六种字牌各一张，加两顺子和一雀头；无副露或暗杠，只可加不求人或杠上炮。"),
        new Fan("QINGSILIAN", "清四连", 4, "仅有连续四种数值，不含字牌；159 万支持特殊连续。"),
        new Fan("QUANDAIWU", "全带五", 4, "每组面子和雀头均包含数牌 5。"),
        new Fan("JIUSHUQI", "九数齐", 4, "完整和牌（含雀头和所有副露）不得含字牌；包含 1 至 9，每种数值只能出现在一个面子或雀头中。"),
        new Fan("ZIYISE", "字一色", 6, "只有字牌；不计混全带幺、碰碰和、番牌、清一色。"),
        new Fan("QINGSANLIAN", "清三连", 6, "仅有连续三种数值，不含字牌；159 万支持特殊连续。")
    );

    private static final List<Fan> FOUR_PLAYER_CATALOG = CATALOG.stream().map(fan -> switch (fan.id()) {
        case "PINGHE" -> new Fan("PINGHE", "平和", 1, "四组正常顺子加数牌雀头；允许吃，不限听口。159 万不算顺子。");
        case "MENQING" -> new Fan("MENQING", "门清", 1, fan.description());
        case "FENGLONG" -> new Fan("QUANBUKAO", "全不靠", 2, "三种数牌花色分别取147、258、369，加七种字牌共16种，任选14种且不重复；无副露或暗杠，只可加不求人或杠上炮。");
        case "QINGSILIAN", "QINGSANLIAN" -> new Fan(fan.id(), fan.name(), fan.fan(),
            fan.description().replace("；159 万支持特殊连续", "；各花色均按普通数值连续"));
        default -> fan;
    }).toList();
    private static final List<Set<String>> UNCONNECTED_TEMPLATES = buildUnconnectedTemplates();

    public static List<Fan> catalog() { return catalog(YmRules.THREE_PLAYER); }
    public static List<Fan> catalog(YmRules rules) {
        return java.util.Objects.requireNonNull(rules) == YmRules.FOUR_PLAYER ? FOUR_PLAYER_CATALOG : CATALOG;
    }

    /** Six suit assignments, each containing nine numbered types and all seven honors. */
    static List<Set<String>> unconnectedTemplates() { return UNCONNECTED_TEMPLATES; }

    private static List<Set<String>> buildUnconnectedTemplates() {
        List<Set<String>> templates = new ArrayList<>();
        for (int first = 0; first < 3; first++) for (int second = 0; second < 3; second++) {
            if (first == second) continue;
            int[] residues = { first, second, 3 - first - second };
            Set<String> codes = new LinkedHashSet<>();
            for (int suit = 0; suit < 3; suit++)
                for (int rank = residues[suit] + 1; rank <= 9; rank += 3) codes.add("WBD".charAt(suit) + String.valueOf(rank));
            for (int rank = 1; rank <= 7; rank++) codes.add("H" + rank);
            templates.add(Set.copyOf(codes));
        }
        return List.copyOf(templates);
    }

    /** Concealed contains the winning tile; declared melds must not be regrouped. */
    public static Evaluation evaluate(List<Tile> concealed, List<Meld> melds, int seatWind, int roundWind,
                                      boolean selfDraw, boolean kongDiscard) {
        return evaluate(concealed, melds, seatWind, roundWind, selfDraw, kongDiscard, YmRules.THREE_PLAYER);
    }

    public static Evaluation evaluate(List<Tile> concealed, List<Meld> melds, int seatWind, int roundWind,
                                      boolean selfDraw, boolean kongDiscard, YmRules rules) {
        return new Evaluator(rules).evaluate(concealed, melds, seatWind, roundWind, selfDraw, kongDiscard);
    }

    private static final class Evaluator {
        private final YmRules rules;
        private final List<String> codes;
        Evaluator(YmRules rules) {
            this.rules = java.util.Objects.requireNonNull(rules);
            this.codes = rules.codes();
        }
        private Evaluation evaluate(List<Tile> concealed, List<Meld> melds, int seatWind, int roundWind,
                                    boolean selfDraw, boolean kongDiscard) {
            if (concealed == null || melds == null || melds.size() > 4 || seatWind < 1 || seatWind > rules.playerCount()
                || roundWind < 1 || roundWind > rules.playerCount() || concealed.size() != 14 - 3 * melds.size()) return invalid();
            List<Tile> all = new ArrayList<>(concealed);
            List<Group> fixed = new ArrayList<>();
            for (Meld meld : melds) {
                if (meld == null) return invalid();
                List<String> codes = meld.tiles().stream().map(YmTiles::code).toList();
                boolean sequence = "CHI".equals(meld.type());
                boolean valid = sequence ? !meld.concealed() && YmTiles.sequence(codes, rules)
                    : "PONG".equals(meld.type()) ? !meld.concealed() && codes.size() == 3 && new HashSet<>(codes).size() == 1
                    : "KONG".equals(meld.type()) && codes.size() == 4 && new HashSet<>(codes).size() == 1;
                if (!valid) return invalid();
                fixed.add(new Group(codes, sequence));
                all.addAll(meld.tiles());
            }
            int[] physicalCounts = new int[codes.size()];
            Set<String> ids = new HashSet<>();
            for (Tile tile : all) {
                int index = codes.indexOf(YmTiles.code(tile));
                if (index < 0 || tile.red() || tile.id() == null || !ids.add(tile.id()) || ++physicalCounts[index] > 4) return invalid();
            }
            int[] counts = new int[codes.size()];
            for (Tile tile : concealed) counts[codes.indexOf(YmTiles.code(tile))]++;
            List<List<Group>> decompositions = new ArrayList<>();
            for (int pair = 0; pair < counts.length; pair++) if (counts[pair] >= 2) {
                counts[pair] -= 2;
                List<Group> groups = new ArrayList<>(fixed);
                groups.add(new Group(List.of(codes.get(pair), codes.get(pair)), false));
                split(counts, 4 - melds.size(), false, groups, decompositions);
                counts[pair] += 2;
            }
            Evaluation best = invalid();
            for (List<Group> groups : decompositions) {
                Evaluation result = normal(groups, all, melds, seatWind, roundWind, selfDraw, kongDiscard);
                if (!best.validStructure() || result.rawFan() > best.rawFan()) best = result;
            }
            if (melds.isEmpty() && rules == YmRules.THREE_PLAYER && windDragon(counts)) {
                List<Fan> items = new ArrayList<>();
                add(items, "FENGLONG");
                if (selfDraw) add(items, "BUQIUREN");
                if (kongDiscard && !selfDraw) add(items, "GANGSHANGPAO");
                Evaluation dragon = result(items);
                if (!best.validStructure() || dragon.rawFan() > best.rawFan()) best = dragon;
            }
            if (melds.isEmpty() && rules == YmRules.FOUR_PLAYER && unconnected(concealed)) {
                List<Fan> items = new ArrayList<>();
                add(items, "QUANBUKAO");
                if (selfDraw) add(items, "BUQIUREN");
                if (kongDiscard && !selfDraw) add(items, "GANGSHANGPAO");
                Evaluation special = result(items);
                if (!best.validStructure() || special.rawFan() > best.rawFan()) best = special;
            }
            return best;
        }

        private static Evaluation invalid() { return new Evaluation(false, false, 0, 0, List.of()); }
        private Evaluation result(List<Fan> items) {
            int raw = items.stream().mapToInt(Fan::fan).sum();
            return new Evaluation(true, raw >= rules.minimumFan(), raw, Math.min(8, raw), items);
        }

        private void split(int[] counts, int remaining, boolean sequencesOnly, List<Group> groups,
                                  List<List<Group>> output) {
            int first = 0;
            while (first < counts.length && counts[first] == 0) first++;
            if (first == counts.length) {
                if (remaining == 0) output.add(List.copyOf(groups));
                return;
            }
            if (remaining == 0) return;
            String code = codes.get(first);
            if (!sequencesOnly && counts[first] >= 3) {
                counts[first] -= 3;
                groups.add(new Group(List.of(code, code, code), false));
                split(counts, remaining - 1, false, groups, output);
                groups.removeLast();
                counts[first] += 3;
            }
            List<String> run = sequenceFrom(code);
            if (run.isEmpty()) return;
            int[] indexes = run.stream().mapToInt(codes::indexOf).toArray();
            for (int index : indexes) if (index < 0 || counts[index] == 0) return;
            for (int index : indexes) counts[index]--;
            groups.add(new Group(run, true));
            split(counts, remaining - 1, sequencesOnly, groups, output);
            groups.removeLast();
            for (int index : indexes) counts[index]++;
        }

        private List<String> sequenceFrom(String code) {
            char suit = code.charAt(0);
            int rank = code.charAt(1) - '0';
            if (suit == 'W' && rules.legacyWanSequence()) return rank == 1 ? List.of("W1", "W5", "W9") : List.of();
            if (suit == 'H' || rank > 7) return List.of();
            return List.of(code, "" + suit + (rank + 1), "" + suit + (rank + 2));
        }

        private boolean windDragon(int[] original) {
            int[] counts = original.clone();
            for (String honor : List.of("H1", "H2", "H3", "H5", "H6", "H7")) {
                int index = codes.indexOf(honor);
                if (--counts[index] < 0) return false;
            }
            for (int pair = 0; pair < counts.length; pair++) if (counts[pair] >= 2) {
                counts[pair] -= 2;
                List<List<Group>> possible = new ArrayList<>();
                split(counts, 2, true, new ArrayList<>(), possible);
                counts[pair] += 2;
                if (!possible.isEmpty()) return true;
            }
            return false;
        }

        private static boolean unconnected(List<Tile> hand) {
            Set<String> actual = new HashSet<>();
            hand.forEach(tile -> actual.add(YmTiles.code(tile)));
            return actual.size() == 14 && UNCONNECTED_TEMPLATES.stream().anyMatch(template -> template.containsAll(actual));
        }

        private Evaluation normal(List<Group> groups, List<Tile> all, List<Meld> melds,
                                         int seatWind, int roundWind, boolean selfDraw, boolean kongDiscard) {
            List<Fan> items = new ArrayList<>();
            boolean closed = melds.stream().allMatch(Meld::concealed);
            boolean allHonors = all.stream().allMatch(tile -> tile.suit().equals("HONORS"));
            boolean hasHonors = all.stream().anyMatch(tile -> tile.suit().equals("HONORS"));
            if (groups.stream().filter(Group::sequence).count() == 4
                && groups.stream().anyMatch(group -> group.codes().size() == 2 && group.codes().getFirst().charAt(0) != 'H')) {
                add(items, "PINGHE");
            }
            if (!allHonors) {
                int value = 0;
                for (Group group : groups) if (group.codes().size() >= 3 && !group.sequence()) {
                    String code = group.codes().getFirst();
                    if (code.charAt(0) == 'H') {
                        int rank = code.charAt(1) - '0';
                        if (rank >= 5) value++;
                        if (rank == seatWind) value++;
                        if (rank == roundWind) value++;
                    }
                }
                if (value > 0) add(items, "FANPAI", value);
            }
            int kongs = (int) melds.stream().filter(meld -> meld.type().equals("KONG")).count();
            if (kongs > 0) add(items, "KONG", kongs);
            if (closed) add(items, "MENQING");
            if (!allHonors && groups.stream().noneMatch(Group::sequence)) add(items, "PENGPENG");
            if (melds.size() == 4) add(items, "SHIERLUOTAI");
            if (closed && selfDraw) add(items, "BUQIUREN");
            if (kongDiscard && !selfDraw) add(items, "GANGSHANGPAO");
            Set<Character> suits = new HashSet<>();
            boolean winds = false, dragons = false;
            Set<String> actualCodes = new LinkedHashSet<>();
            Set<Integer> ranks = new HashSet<>();
            for (Tile tile : all) {
                String code = YmTiles.code(tile);
                suits.add(code.charAt(0));
                actualCodes.add(code);
                if (code.charAt(0) == 'H') {
                    if (tile.rank() <= 4) winds = true; else dragons = true;
                } else ranks.add(tile.rank());
            }
            if (suits.containsAll(Set.of('W', 'B', 'D')) && winds && dragons) add(items, "WUMENQI");
            if (suits.size() == 2 && hasHonors) add(items, "HUNYISE");
            if (suits.size() == 1 && !hasHonors) add(items, "QINGYISE");
            boolean everyTerminal = groups.stream().allMatch(group -> group.codes().stream().anyMatch(Evaluator::terminalOrHonor));
            if (everyTerminal && !allHonors) add(items, hasHonors ? "HUNQUANDAIYAO" : "QINGQUANDAIYAO");
            if (hasHonors && (winds ^ dragons) && ranks.size() == 3 && consecutive(ranks, actualCodes)) add(items, "HUNSILIAN");
            if (!hasHonors && ranks.size() == 4 && consecutive(ranks, actualCodes)) add(items, "QINGSILIAN");
            if (!hasHonors && ranks.size() == 3 && consecutive(ranks, actualCodes)) add(items, "QINGSANLIAN");
            if (groups.stream().allMatch(group -> group.codes().stream().anyMatch(code -> code.charAt(0) != 'H' && code.charAt(1) == '5'))) add(items, "QUANDAIWU");
            if (!hasHonors && nineRanks(groups)) add(items, "JIUSHUQI");
            if (allHonors) add(items, "ZIYISE");
            return result(items);
        }

        private static boolean terminalOrHonor(String code) { return code.charAt(0) == 'H' || code.charAt(1) == '1' || code.charAt(1) == '9'; }

        /** One connected chain through the rank categories; 5-man only links to 1/9-man. */
        private boolean consecutive(Set<Integer> ranks, Set<String> actualCodes) {
            for (int first : ranks) if (chain(first, new HashSet<>(Set.of(first)), ranks, actualCodes)) return true;
            return false;
        }

        private boolean chain(int last, Set<Integer> visited, Set<Integer> ranks, Set<String> codes) {
            if (visited.size() == ranks.size()) return true;
            for (int next : ranks) if (!visited.contains(next) && adjacent(last, next, codes)) {
                visited.add(next);
                if (chain(next, visited, ranks, codes)) return true;
                visited.remove(next);
            }
            return false;
        }

        private boolean adjacent(int first, int second, Set<String> codes) {
            if (!rules.legacyWanSequence()) return Math.abs(first - second) == 1;
            if ((first == 5 && (second == 1 || second == 9) || second == 5 && (first == 1 || first == 9))
                && codes.contains("W" + first) && codes.contains("W" + second)) return true;
            if (Math.abs(first - second) != 1) return false;
            // A lone 5-man is not consecutive with 4/6 of another suit.
            return first != 5 && second != 5 || codes.contains("B5") || codes.contains("D5");
        }

        private static boolean nineRanks(List<Group> groups) {
            int[] occurrences = new int[10];
            for (Group group : groups) {
                Set<Integer> seen = new HashSet<>();
                for (String code : group.codes()) if (code.charAt(0) != 'H') seen.add(code.charAt(1) - '0');
                for (int rank : seen) if (++occurrences[rank] > 1) return false;
            }
            for (int rank = 1; rank <= 9; rank++) if (occurrences[rank] != 1) return false;
            return true;
        }

        private void add(List<Fan> items, String id) {
            items.add(catalog(rules).stream().filter(fan -> fan.id().equals(id)).findFirst().orElseThrow());
        }
        private void add(List<Fan> items, String id, int value) {
            Fan fan = catalog(rules).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
            items.add(new Fan(id, fan.name(), value, fan.description()));
        }
    }
}
