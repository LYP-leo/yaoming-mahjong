package com.mahjong.yaoming;

/**
 * OFFLINE TRAINING ONLY. Rule-source snapshot compiles against this no-op recorder.
 * No production source is patched: only replay allocations and persistence are omitted.
 */
public final class YmReplay {
    private YmReplay() {}
    public record HandRecord() {}
    public static void start(YmRoom room, long now) {}
    public static boolean ensureActive(YmRoom room, long now) { return false; }
    public static void append(YmRoom room, String type, int actorSeat, String message, long now) {}
}
