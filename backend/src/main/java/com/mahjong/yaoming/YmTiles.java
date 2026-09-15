package com.mahjong.yaoming;

import com.mahjong.domain.Tile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Rule-specific flowerless decks. Tile entities themselves support all 34 standard types. */
public final class YmTiles {
    private YmTiles() {}
    private static final AtomicLong IDS = new AtomicLong();
    static final List<String> CODES = List.of(
        "W1", "W5", "W9", "B1", "B2", "B3", "B4", "B5", "B6", "B7", "B8", "B9",
        "D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8", "D9", "H1", "H2", "H3", "H5", "H6", "H7");

    public static List<Tile> deck() {
        return deck(YmRules.THREE_PLAYER);
    }

    public static List<Tile> deck(YmRules rules) {
        List<Tile> result = new ArrayList<>(rules.tileCount());
        for (String code : rules.codes()) for (int copy = 0; copy < 4; copy++) {
            Tile tile = of(code);
            result.add(new Tile("ym-" + code + "-" + copy, tile.suit(), tile.rank(), tile.label(), false));
        }
        return result;
    }

    public static Tile of(String code) {
        return of(code, YmRules.FOUR_PLAYER);
    }

    public static Tile of(String code, YmRules rules) {
        if (!rules.codes().contains(code)) throw new IllegalArgumentException("此规则不使用此牌：" + code);
        int rank = code.charAt(1) - '0';
        String suit = switch (code.charAt(0)) {
            case 'W' -> "CHARACTERS";
            case 'B' -> "BAMBOO";
            case 'D' -> "DOTS";
            default -> "HONORS";
        };
        String label = switch (code.charAt(0)) {
            case 'W' -> rank + "万";
            case 'B' -> rank + "条";
            case 'D' -> rank + "筒";
            default -> List.of("东", "南", "西", "北", "中", "发", "白").get(rank - 1);
        };
        return new Tile("ym-test-" + IDS.incrementAndGet(), suit, rank, label, false);
    }

    public static String code(Tile tile) {
        if (tile == null || tile.suit() == null) return "";
        return switch (tile.suit()) {
            case "CHARACTERS" -> "W";
            case "BAMBOO" -> "B";
            case "DOTS" -> "D";
            case "HONORS" -> "H";
            default -> "?";
        } + tile.rank();
    }

    public static boolean same(Tile first, Tile second) {
        return first != null && second != null && code(first).equals(code(second));
    }

    public static void sort(List<Tile> tiles) {
        tiles.sort(Comparator.comparingInt(tile -> YmRules.FOUR_PLAYER.codes().indexOf(code(tile))));
    }

    /** Actual two-tile choices, retaining distinct physical tiles for server validation. */
    public static List<List<String>> chiOptions(List<Tile> hand, Tile discard) {
        return chiOptions(hand, discard, YmRules.THREE_PLAYER);
    }

    public static List<List<String>> chiOptions(List<Tile> hand, Tile discard, YmRules rules) {
        List<List<String>> options = new ArrayList<>();
        if (discard == null || !rules.codes().contains(code(discard))) return options;
        for (int first = 0; first < hand.size(); first++) for (int second = first + 1; second < hand.size(); second++) {
            Tile a = hand.get(first), b = hand.get(second);
            if (sequence(List.of(code(a), code(b), code(discard)), rules)) options.add(List.of(a.id(), b.id()));
        }
        return options;
    }

    static boolean sequence(List<String> codes) {
        return sequence(codes, YmRules.THREE_PLAYER);
    }

    static boolean sequence(List<String> codes, YmRules rules) {
        if (codes.size() != 3 || codes.stream().anyMatch(code -> !rules.codes().contains(code))) return false;
        char suit = codes.getFirst().charAt(0);
        if (suit == 'H' || codes.stream().anyMatch(code -> code.charAt(0) != suit)) return false;
        List<Integer> ranks = codes.stream().map(code -> code.charAt(1) - '0').sorted().toList();
        if (suit == 'W' && rules.legacyWanSequence()) return ranks.equals(List.of(1, 5, 9));
        return ranks.get(0) + 1 == ranks.get(1) && ranks.get(1) + 1 == ranks.get(2);
    }
}
