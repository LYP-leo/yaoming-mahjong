package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import java.util.*;

/** Server-only durable state. Never serialize this object into an API response. */
public final class YmRoom {
    public enum Phase { WAITING, NEED_DRAW, NEED_DISCARD, REACTION, HAND_END, MATCH_END }
    public String id, name, hostId;
    /** Fixed at room creation; absent legacy fields always mean the original three-player rules. */
    public String ruleId = "yaoming-3p";
    /** Cache revision marker, not a rule override: old rooms used a four-fan minimum. */
    public int appliedMinimumFan = 4;
    public long version = 1, eventSequence, lastActivity = System.currentTimeMillis(), nextBotAt;
    /** Start of the continuous no-human interval; null means occupied, and zero is a valid clock value. */
    public Long emptySince;
    public long deadlineAt;
    public String deadlineKind;
    public Phase phase = Phase.WAITING;
    public int round = 1, dealerSeat, currentSeat = -1, initialDealer;
    public String message = "三人入席并准备后开局";
    public List<Player> players = new ArrayList<>();
    public List<Tile> wall = new ArrayList<>();
    public List<YmViews.Event> events = new ArrayList<>();
    public YmViews.Dice dice;
    public YmViews.Result result;
    public Window window;
    public YmViews.LastDiscard lastDiscard;
    /** Server-only until this hand has ended. Never include in a live player view. */
    public YmReplay.HandRecord activeReplay;
    public List<YmReplay.HandRecord> replayHands = new ArrayList<>();
    public LinkedHashMap<String, String> processed = new LinkedHashMap<>();

    public static final class Player {
        public String id, name, token;
        public int seat, score = 10;
        public boolean bot, ready, acknowledged, trustee, autoTrustee, left, afterKong;
        public long lastSeen = System.currentTimeMillis();
        public String lastDrawnId;
        public String trusteeReason;
        public List<Tile> hand = new ArrayList<>(), discards = new ArrayList<>();
        /** Public river metadata, recorded only after the corresponding entity is discarded. */
        public Map<String, String> discardKinds = new LinkedHashMap<>();
        public List<YmScoring.Meld> melds = new ArrayList<>();
        public Set<String> discardedCodes = new LinkedHashSet<>(), passedCodes = new LinkedHashSet<>();
    }

    public static final class Window {
        public Tile tile;
        public int fromSeat;
        public boolean kongDiscard;
        public long deadline;
        public Map<Integer, List<YmViews.Action>> offered = new LinkedHashMap<>();
        public Map<Integer, YmViews.Action> responses = new LinkedHashMap<>();
    }

    public Player seat(int seat) {
        return players.stream().filter(p -> p.seat == seat).findFirst().orElseThrow();
    }

    public YmRules rules() { return YmRules.fromId(ruleId); }
}
