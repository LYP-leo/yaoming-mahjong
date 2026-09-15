package com.mahjong.yaoming;

import java.util.ArrayList;
import java.util.List;

/** Immutable match rules, selected once on creation, never inferred from occupied seats. */
public enum YmRules {
    THREE_PLAYER("yaoming-3p", "要命麻将 · 三人版", 3, true),
    FOUR_PLAYER("yaoming-4p", "要命麻将 · 四人实验版", 4, false);

    private final String id, displayName;
    private final int playerCount;
    private final boolean legacyWanSequence;
    private final List<String> codes;

    YmRules(String id, String displayName, int playerCount, boolean legacyWanSequence) {
        this.id = id;
        this.displayName = displayName;
        this.playerCount = playerCount;
        this.legacyWanSequence = legacyWanSequence;
        List<String> tiles = new ArrayList<>();
        for (char suit : new char[] {'W', 'B', 'D'}) for (int rank = 1; rank <= 9; rank++) {
            if (legacyWanSequence && suit == 'W' && rank != 1 && rank != 5 && rank != 9) continue;
            tiles.add("" + suit + rank);
        }
        for (int rank = 1; rank <= 7; rank++) if (!legacyWanSequence || rank != 4) tiles.add("H" + rank);
        this.codes = List.copyOf(tiles);
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public int playerCount() { return playerCount; }
    public int tileCount() { return codes.size() * 4; }
    public int minimumFan() { return this == FOUR_PLAYER ? 3 : 4; }
    public int totalRounds() { return playerCount * 2; }
    public List<String> codes() { return codes; }
    public boolean legacyWanSequence() { return legacyWanSequence; }
    public List<String> windNames() { return List.of("东", "南", "西", "北").subList(0, playerCount); }

    /** Missing legacy fields remain three-player; an unrecognized nonempty ID must fail closed. */
    public static YmRules fromId(String id) {
        if (id == null || id.isBlank()) return THREE_PLAYER;
        for (YmRules rule : values()) if (rule.id.equals(id)) return rule;
        throw new IllegalArgumentException("未知的要命麻将规则：" + id);
    }
}
