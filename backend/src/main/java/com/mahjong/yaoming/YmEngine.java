package com.mahjong.yaoming;

import com.mahjong.domain.Tile;
import java.util.*;
import java.util.random.RandomGenerator;
import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.*;

/** Pure match orchestration. Room service serializes commands; all game decisions live here. */
public final class YmEngine {
    public static final long DRAW_TIMEOUT_MS = 15000, DISCARD_TIMEOUT_MS = 30000, REACTION_TIMEOUT_MS = 20000, SETTLEMENT_TIMEOUT_MS = 60000;
    private final RandomGenerator random;
    public YmEngine(RandomGenerator random) { this.random = random; }

    public List<Action> gameActions(YmRoom r, YmRoom.Player p) {
        List<Action> result = new ArrayList<>();
        if (r.phase == WAITING) result.add(Action.of("READY", p.ready ? "取消准备" : "准备开局"));
        else if (r.phase == HAND_END && !p.acknowledged) result.add(Action.of("ACK", "确认结算，准备下一局"));
        else if (r.phase == REACTION && r.window != null && r.window.offered.containsKey(p.seat) && !r.window.responses.containsKey(p.seat)) {
            // Durable response options may predate a scoring correction. Reading
            // actions must never expose an invalid WIN or mutate the old window.
            for (Action action : r.window.offered.get(p.seat))
                if (!action.type().equals("WIN") || canRonInWindow(r, p, r.window)) result.add(action);
        }
        else if (r.currentSeat == p.seat && r.phase == NEED_DRAW) result.add(Action.of("DRAW", "摸牌"));
        else if (r.currentSeat == p.seat && r.phase == NEED_DISCARD) {
            for (Tile tile : p.hand) result.add(new Action("DISCARD", "打出 " + tile.label(), List.of(tile.id())));
            if (p.lastDrawnId != null && evaluate(r, p, null, true, false).eligible()) result.add(Action.of("WIN", "自摸"));
            if (p.lastDrawnId != null && !r.wall.isEmpty()) {
                Map<String, List<Tile>> groups = new LinkedHashMap<>();
                p.hand.forEach(t -> groups.computeIfAbsent(YmTiles.code(t), k -> new ArrayList<>()).add(t));
                for (List<Tile> group : groups.values()) if (group.size() == 4)
                    result.add(new Action("CONCEALED_KONG", "暗杠 " + group.getFirst().label(), group.stream().map(Tile::id).toList()));
                for (YmScoring.Meld meld : p.melds) if (meld.type().equals("PONG"))
                    for (Tile tile : p.hand) if (YmTiles.same(tile, meld.tiles().getFirst()))
                        result.add(new Action("ADDED_KONG", "加杠 " + tile.label(), List.of(tile.id())));
            }
        }
        return result;
    }

    public void perform(YmRoom r, YmRoom.Player p, String type, List<String> tileIds, long now) {
        Action choice = gameActions(r, p).stream().filter(a -> a.type().equals(type) && sameIds(a.tileIds(), tileIds))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("当前不能进行此操作，请重新同步牌桌"));
        boolean gameplay = !type.equals("READY") && !type.equals("ACK");
        if (gameplay) YmReplay.ensureActive(r, now);
        long firstEvent = r.eventSequence;
        switch (type) {
            case "READY" -> {
                p.ready = !p.ready;
                event(r, p.name + (p.ready ? " 已准备" : " 取消准备"));
                startIfReady(r, now);
            }
            case "DRAW" -> draw(r, p, false, now);
            case "DISCARD" -> discard(r, p, tileIds.getFirst(), now);
            case "CONCEALED_KONG" -> {
                List<Tile> tiles = removeTiles(p, tileIds);
                p.melds.add(new YmScoring.Meld("KONG", tiles, p.seat, "", true));
                event(r, p.name + " 暗杠 " + tiles.getFirst().label()); draw(r, p, true, now);
            }
            case "ADDED_KONG" -> {
                Tile tile = removeTiles(p, tileIds).getFirst();
                int index = 0;
                while (!p.melds.get(index).type().equals("PONG") || !YmTiles.same(p.melds.get(index).tiles().getFirst(), tile)) index++;
                YmScoring.Meld meld = p.melds.get(index);
                List<Tile> tiles = new ArrayList<>(meld.tiles()); tiles.add(tile);
                p.melds.set(index, new YmScoring.Meld("KONG", List.copyOf(tiles), meld.fromSeat(), meld.claimedTileId(), false, true));
                event(r, p.name + " 加杠 " + tile.label()); draw(r, p, true, now);
            }
            case "WIN" -> {
                if (r.phase == REACTION) respond(r, p, choice, now);
                else finishWin(r, p, null, p.hand.stream().filter(t -> t.id().equals(p.lastDrawnId)).findFirst().orElseThrow(), false, now);
            }
            case "PONG", "CHI", "OPEN_KONG", "PASS" -> respond(r, p, choice, now);
            case "ACK" -> {
                p.acknowledged = true; event(r, p.name + " 已确认结算");
                if (r.players.stream().allMatch(x -> x.acknowledged)) {
                    r.round++; r.dealerSeat = (r.dealerSeat + 1) % r.rules().playerCount(); startHand(r, now);
                }
            }
            default -> throw new IllegalArgumentException("未知操作");
        }
        if (gameplay) {
            String recorded = r.events.stream().filter(event -> event.sequence() > firstEvent).map(Event::text)
                    .reduce((first, next) -> first + " · " + next).orElse(p.name + " " + choice.label() + " · " + r.message);
            if ("TIMEOUT".equals(p.trusteeReason)) recorded = "（超时托管）" + recorded;
            YmReplay.append(r, type, p.seat, recorded, now);
        }
        r.version++; r.lastActivity = now; r.nextBotAt = now + 650;
    }

    public void startIfReady(YmRoom r, long now) {
        if (r.phase != WAITING || r.players.size() != r.rules().playerCount() || !r.players.stream().allMatch(p -> p.ready)) return;
        List<YmRoom.Player> lots = new ArrayList<>(r.players);
        for (int i = lots.size() - 1; i > 0; i--) Collections.swap(lots, i, random.nextInt(i + 1));
        for (int seat = 0; seat < lots.size(); seat++) lots.get(seat).seat = seat;
        r.initialDealer = 0; r.dealerSeat = 0;
        startHand(r, now);
    }

    private void startHand(YmRoom r, long now) {
        YmRules rules = r.rules();
        int seats = rules.playerCount();
        r.wall = new ArrayList<>(YmTiles.deck(rules));
        for (int i = r.wall.size() - 1; i > 0; i--) Collections.swap(r.wall, i, random.nextInt(i + 1));
        List<Integer> opening = dice(), breaking = dice();
        int openingSeat = (r.dealerSeat + (opening.stream().mapToInt(Integer::intValue).sum() - 1) % seats) % seats;
        int stack = breaking.stream().mapToInt(Integer::intValue).sum();
        Collections.rotate(r.wall, -(openingSeat * (rules.tileCount() / seats) + stack * 2) % rules.tileCount());
        r.dice = new Dice(opening, breaking, openingSeat, stack);
        r.window = null; r.result = null; r.lastDiscard = null;
        for (YmRoom.Player p : r.players) {
            p.hand.clear(); p.discards.clear(); p.discardKinds.clear(); p.melds.clear(); p.discardedCodes.clear(); p.passedCodes.clear();
            p.acknowledged = false; p.afterKong = false; p.lastDrawnId = null; p.ready = false;
        }
        for (int batch = 0; batch < 3; batch++) for (int step = 0; step < seats; step++)
            for (int count = 0; count < 4; count++) r.seat((r.dealerSeat + step) % seats).hand.add(r.wall.removeFirst());
        for (int step = 0; step < seats; step++) r.seat((r.dealerSeat + step) % seats).hand.add(r.wall.removeFirst());
        r.players.forEach(p -> YmTiles.sort(p.hand));
        r.currentSeat = r.dealerSeat; r.phase = NEED_DRAW;
        resetDeadline(r, now);
        event(r, roundLabel(r) + " 开局，" + r.seat(r.dealerSeat).name + " 请摸牌");
        YmReplay.start(r, now);
        r.nextBotAt = now + 650;
    }

    private void draw(YmRoom r, YmRoom.Player p, boolean replacement, long now) {
        if (r.wall.isEmpty()) { finishDraw(r, now); return; }
        Tile tile = replacement ? r.wall.removeLast() : r.wall.removeFirst();
        p.hand.add(tile); YmTiles.sort(p.hand); p.lastDrawnId = tile.id();
        p.passedCodes.clear(); p.afterKong = replacement;
        r.currentSeat = p.seat; r.phase = NEED_DISCARD;
        resetDeadline(r, now);
        event(r, p.name + (replacement ? " 从牌山尾补牌" : " 摸牌") + "，请出牌");
    }

    private void discard(YmRoom r, YmRoom.Player p, String tileId, long now) {
        Tile tile = removeTiles(p, List.of(tileId)).getFirst();
        String kind = tile.id().equals(p.lastDrawnId) ? "TSUMOGIRI" : "TEDASHI";
        p.discardKinds.put(tile.id(), kind);
        p.discards.add(tile); p.discardedCodes.add(YmTiles.code(tile)); p.lastDrawnId = null;
        YmRoom.Window window = new YmRoom.Window();
        window.tile = tile; window.fromSeat = p.seat; window.kongDiscard = p.afterKong; window.deadline = now + REACTION_TIMEOUT_MS;
        r.lastDiscard = new LastDiscard(tile, p.seat, false, kind);
        p.afterKong = false;
        int seats = r.rules().playerCount();
        for (int step = 1; step < seats; step++) {
            YmRoom.Player other = r.seat((p.seat + step) % seats);
            List<Action> options = new ArrayList<>();
            if (canRon(r, other, tile, window.kongDiscard)) options.add(Action.of("WIN", "点和"));
            List<Tile> same = other.hand.stream().filter(t -> YmTiles.same(t, tile)).toList();
            if (same.size() >= 3 && !r.wall.isEmpty()) options.add(new Action("OPEN_KONG", "明杠", same.stream().limit(3).map(Tile::id).toList()));
            if (same.size() >= 2) options.add(new Action("PONG", "碰", same.stream().limit(2).map(Tile::id).toList()));
            if (step == 1) for (List<String> ids : YmTiles.chiOptions(other.hand, tile, r.rules())) {
                String label = "吃 " + String.join(" + ", other.hand.stream().filter(t -> ids.contains(t.id())).map(Tile::label).toList());
                options.add(new Action("CHI", label, ids));
            }
            if (!options.isEmpty()) { options.add(Action.of("PASS", "过")); window.offered.put(other.seat, options); }
        }
        event(r, p.name + " 打出 " + tile.label());
        if (window.offered.isEmpty()) { r.currentSeat = (p.seat + 1) % seats; r.phase = NEED_DRAW; }
        else { r.window = window; r.currentSeat = -1; r.phase = REACTION; r.message += " · 等待响应"; }
        resetDeadline(r, now);
    }

    private void respond(YmRoom r, YmRoom.Player p, Action choice, long now) {
        YmRoom.Window w = r.window;
        w.responses.put(p.seat, choice);
        if (w.responses.size() == w.offered.size()) resolve(r, now);
        else r.message = "等待其他玩家响应";
    }

    public boolean expire(YmRoom r, long now) {
        boolean initialized = initializeDeadline(r, now);
        if (r.deadlineAt <= 0 || r.deadlineAt > now) {
            if (initialized) r.version++;
            return initialized;
        }
        if (r.phase == NEED_DRAW || r.phase == NEED_DISCARD) {
            YmRoom.Player player = r.seat(r.currentSeat);
            player.trustee = true; player.autoTrustee = false; player.trusteeReason = "TIMEOUT";
            event(r, player.name + " 操作超时，已开启托管；可主动收回控制");
            Action action = automaticAction(r, player, gameActions(r, player));
            if (action == null) throw new IllegalStateException("超时阶段没有合法操作");
            perform(r, player, action.type(), action.tileIds(), now);
            return true;
        }
        if (r.phase == HAND_END) {
            r.players.stream().filter(player -> !player.acknowledged).forEach(player -> player.acknowledged = true);
            event(r, "结算确认已到时，自动确认并开始下一局");
            startAcknowledgedHand(r, now);
            return true;
        }
        if (r.phase != REACTION) return initialized;
        YmReplay.ensureActive(r, now);
        YmRoom.Window w = r.window;
        for (int seat : w.offered.keySet()) if (!w.responses.containsKey(seat)) {
            w.responses.put(seat, Action.of("PASS", "超时过"));
        }
        resolve(r, now);
        YmReplay.append(r, "REACTION_TIMEOUT", -1, "响应时限结束，未响应玩家自动过 · " + r.message, now);
        r.version++; r.nextBotAt = now + 650; return true;
    }

    Action automaticAction(YmRoom room, YmRoom.Player player, List<Action> legal) {
        if (legal.isEmpty()) return null;
        if (player.bot || player.left) return YmBots.choose(YmBotObservation.capture(room, player), legal);
        if (player.trustee) return YmTrustee.choose(player.hand, player.lastDrawnId, player.ready, legal);
        return null;
    }

    /** Reconcile durable WIN options with current scoring without reopening submitted decisions. */
    boolean restoreRonOptions(YmRoom room, long now) {
        YmRoom.Window window = room.window;
        if (room.phase != REACTION || window == null || window.tile == null
                || window.offered == null || window.responses == null) return false;
        YmRoom.Player from = room.players.stream().filter(p -> p.seat == window.fromSeat).findFirst().orElse(null);
        if (from == null || from.discards.stream().noneMatch(tile -> tile.id().equals(window.tile.id()))) return false;
        boolean mayExpand = room.deadlineAt > now && window.deadline > now;
        boolean changed = false;
        for (YmRoom.Player player : room.players) {
            if (player.seat == window.fromSeat) continue;
            List<Action> prior = window.offered.getOrDefault(player.seat, List.of());
            List<Action> updated = new ArrayList<>(prior);
            if (!canRonInWindow(room, player, window)) {
                boolean removed = updated.removeIf(action -> action.type().equals("WIN"));
                Action response = window.responses.get(player.seat);
                boolean submittedWin = response != null && response.type().equals("WIN");
                if (submittedWin) {
                    window.responses.put(player.seat, Action.of("PASS", "规则修正，原点和失效，按过处理"));
                    changed = true;
                }
                if (removed || submittedWin) {
                    if (updated.stream().noneMatch(action -> action.type().equals("PASS"))) updated.add(Action.of("PASS", "过"));
                    event(room, "规则修正：" + player.name + " 原点和不再满足当前和牌条件，"
                            + (submittedWin ? "已按过处理" : "已移除失效和牌选项"));
                }
            } else if (mayExpand && !window.responses.containsKey(player.seat)) {
                // Keep the previous no-furiten migration, but never add a fresh
                // choice after timeout or reverse a participant's submitted pass.
                if (prior.stream().noneMatch(action -> action.type().equals("WIN"))) updated.addFirst(Action.of("WIN", "点和"));
                if (prior.stream().noneMatch(action -> action.type().equals("PASS"))) updated.add(Action.of("PASS", "过"));
            }
            if (!updated.equals(prior)) { window.offered.put(player.seat, updated); changed = true; }
        }
        return changed;
    }

    private boolean canRonInWindow(YmRoom room, YmRoom.Player player, YmRoom.Window window) {
        return window != null && window.tile != null && player != null && player.seat != window.fromSeat
                && canRon(room, player, window.tile, window.kongDiscard);
    }

    private void resolve(YmRoom r, long now) {
        YmRoom.Window w = r.window;
        // A submitted legacy WIN can survive until another player responds or
        // the window expires. Recheck it before ranking claims or clearing w.
        for (Map.Entry<Integer, Action> response : w.responses.entrySet()) {
            if (!response.getValue().type().equals("WIN")) continue;
            YmRoom.Player player = r.players.stream().filter(p -> p.seat == response.getKey()).findFirst().orElse(null);
            if (!canRonInWindow(r, player, w)) {
                response.setValue(Action.of("PASS", "规则修正，原点和失效，按过处理"));
                event(r, "规则修正：" + (player == null ? "旧响应" : player.name) + " 原点和不再满足当前和牌条件，已按过处理");
            }
        }
        Map.Entry<Integer, Action> chosen = w.responses.entrySet().stream().filter(e -> !e.getValue().type().equals("PASS"))
                .min(Comparator.<Map.Entry<Integer, Action>>comparingInt(e -> priority(e.getValue().type()))
                        .thenComparingInt(e -> (e.getKey() - w.fromSeat + r.rules().playerCount()) % r.rules().playerCount())).orElse(null);
        if (chosen == null) { r.window = null; r.phase = NEED_DRAW; r.currentSeat = (w.fromSeat + 1) % r.rules().playerCount(); resetDeadline(r, now); r.message = "响应结束，下一家请摸牌"; return; }
        YmRoom.Player p = r.seat(chosen.getKey()), from = r.seat(w.fromSeat);
        Action action = chosen.getValue();
        // finishWin validates before mutating; retain the window until that
        // validation succeeds. finish() itself clears it on a successful win.
        if (action.type().equals("WIN")) { finishWin(r, p, from, w.tile, w.kongDiscard, now); return; }
        r.window = null;
        claimDiscard(r, from, w.tile);
        List<Tile> tiles = removeTiles(p, action.tileIds()); tiles.add(w.tile); YmTiles.sort(tiles);
        String type = action.type().equals("OPEN_KONG") ? "KONG" : action.type();
        p.melds.add(new YmScoring.Meld(type, List.copyOf(tiles), from.seat, w.tile.id(), false));
        p.lastDrawnId = null; p.afterKong = false; r.currentSeat = p.seat; r.phase = NEED_DISCARD;
        resetDeadline(r, now);
        event(r, p.name + " " + (type.equals("CHI") ? "吃" : type.equals("PONG") ? "碰" : "明杠") + " " + w.tile.label());
        if (type.equals("KONG")) draw(r, p, true, now);
    }

    private void finishWin(YmRoom r, YmRoom.Player winner, YmRoom.Player from, Tile tile, boolean kongDiscard, long now) {
        boolean selfDraw = from == null;
        YmScoring.Evaluation evaluation = evaluate(r, winner, selfDraw ? null : tile, selfDraw, kongDiscard);
        if (!evaluation.eligible()) throw new IllegalStateException("和牌至少需要" + r.rules().minimumFan() + "番");
        if (!selfDraw) { claimDiscard(r, from, tile); winner.hand.add(tile); YmTiles.sort(winner.hand); }
        Map<String, Integer> deltas = new HashMap<>(); r.players.forEach(p -> deltas.put(p.id, 0));
        List<Payment> payments = new ArrayList<>();
        for (YmRoom.Player p : r.players) if (p != winner && (selfDraw || p == from)) {
            int requested = evaluation.fan() * (selfDraw ? 1 : r.rules().playerCount()), amount = Math.min(requested, p.score);
            p.score -= amount; winner.score += amount; deltas.put(p.id, -amount); deltas.merge(winner.id, amount, Integer::sum);
            payments.add(new Payment(p.id, winner.id, amount, requested));
        }
        boolean matchOver = r.round >= r.rules().totalRounds() || r.players.stream().anyMatch(p -> p.score == 0);
        String reason = r.players.stream().anyMatch(p -> p.score == 0) ? "有人点数归零，本场结束"
                : r.round >= r.rules().totalRounds() ? finalRoundMessage(r) : "本局结束，全部确认后轮庄";
        r.result = new Result(false, matchOver, winner.name + (selfDraw ? " 自摸" : " 点和"), winner.id, tile,
                evaluation.rawFan(), evaluation.fan(), evaluation.items(), List.copyOf(payments), scores(r, deltas), hands(r), reason);
        finish(r, matchOver, r.result.title() + " · " + evaluation.fan() + "番 · " + reason, now);
    }

    private void claimDiscard(YmRoom r, YmRoom.Player from, Tile tile) {
        String kind = r.lastDiscard != null && r.lastDiscard.fromSeat() == from.seat
                && r.lastDiscard.tile().id().equals(tile.id()) ? r.lastDiscard.kind() : from.discardKinds.get(tile.id());
        from.discards.removeIf(t -> t.id().equals(tile.id()));
        from.discardKinds.remove(tile.id());
        r.lastDiscard = new LastDiscard(tile, from.seat, true, kind);
    }

    private void finishDraw(YmRoom r, long now) {
        boolean matchOver = r.round >= r.rules().totalRounds();
        String reason = matchOver ? finalRoundMessage(r) : "牌山耗尽，无罚分，全部确认后轮庄";
        r.result = new Result(true, matchOver, "荒牌流局", null, null, 0, 0, List.of(), List.of(), scores(r, Map.of()), hands(r), reason);
        finish(r, matchOver, reason, now);
    }

    private void finish(YmRoom r, boolean matchOver, String message, long now) {
        r.phase = matchOver ? MATCH_END : HAND_END; r.currentSeat = -1; r.window = null;
        resetDeadline(r, now);
        r.players.forEach(p -> { p.acknowledged = p.bot || p.trustee || p.left; p.lastDrawnId = null; p.afterKong = false; });
        event(r, message); r.nextBotAt = now + 1000;
    }

    public void startAcknowledgedHand(YmRoom r, long now) {
        if (r.phase == HAND_END && r.players.stream().allMatch(p -> p.acknowledged)) {
            r.round++; r.dealerSeat = (r.dealerSeat + 1) % r.rules().playerCount(); startHand(r, now); r.version++;
        }
    }

    /** Initializes a missing legacy deadline once; never extends a valid running deadline. */
    public boolean initializeDeadline(YmRoom r, long now) {
        String expected = deadlineKind(r);
        if (Objects.equals(expected, r.deadlineKind) && (expected == null ? r.deadlineAt == 0 : r.deadlineAt > 0)) return false;
        resetDeadline(r, now);
        return true;
    }
    public void resetActionDeadline(YmRoom r, YmRoom.Player player, long now) {
        if (r.currentSeat == player.seat && (r.phase == NEED_DRAW || r.phase == NEED_DISCARD)) resetDeadline(r, now);
    }
    private static String deadlineKind(YmRoom r) {
        return switch (r.phase) {
            case NEED_DRAW -> "DRAW";
            case NEED_DISCARD -> "DISCARD";
            case REACTION -> "REACTION";
            case HAND_END -> "SETTLEMENT";
            default -> null;
        };
    }
    private static void resetDeadline(YmRoom r, long now) {
        r.deadlineKind = deadlineKind(r);
        r.deadlineAt = switch (r.phase) {
            case NEED_DRAW -> now + DRAW_TIMEOUT_MS;
            case NEED_DISCARD -> now + DISCARD_TIMEOUT_MS;
            case REACTION -> r.window.deadline > 0 ? r.window.deadline : now + REACTION_TIMEOUT_MS;
            case HAND_END -> now + SETTLEMENT_TIMEOUT_MS;
            default -> 0;
        };
        if (r.phase == REACTION) r.window.deadline = r.deadlineAt;
    }

    public YmScoring.Evaluation evaluate(YmRoom r, YmRoom.Player p, Tile extra, boolean selfDraw, boolean kongDiscard) {
        List<Tile> hand = new ArrayList<>(p.hand); if (extra != null) hand.add(extra);
        int seats = r.rules().playerCount();
        return YmScoring.evaluate(hand, p.melds, (p.seat - r.dealerSeat + seats) % seats + 1,
                r.round <= seats ? 1 : 2, selfDraw, kongDiscard, r.rules());
    }
    public boolean canRon(YmRoom r, YmRoom.Player p, Tile tile, boolean kongDiscard) {
        // Yaoming has neither discard-based nor temporary pass-based furiten. Old snapshot
        // history fields remain readable, but never participate in win eligibility.
        return evaluate(r, p, tile, false, kongDiscard).eligible();
    }
    public static String roundLabel(YmRoom r) {
        int seats = r.rules().playerCount();
        return (r.round <= seats ? "东" : "南") + ((r.round - 1) % seats + 1) + "局";
    }
    private static String finalRoundMessage(YmRoom r) { return r.rules().playerCount() == 4 ? "南四局结束，本场结束" : "南三局结束，本场结束"; }
    public static void event(YmRoom r, String text) {
        r.message = text; r.events.add(new Event(++r.eventSequence, text));
        if (r.events.size() > 30) r.events.removeFirst();
    }
    private static int priority(String type) { return type.equals("WIN") ? 0 : type.equals("CHI") ? 2 : 1; }
    private static boolean sameIds(List<String> a, List<String> b) { return a.size() == b.size() && new HashSet<>(a).equals(new HashSet<>(b)); }
    private static List<Tile> removeTiles(YmRoom.Player p, List<String> ids) {
        List<Tile> selected = p.hand.stream().filter(t -> ids.contains(t.id())).toList();
        if (selected.size() != ids.size()) throw new IllegalArgumentException("所选牌已不在手中");
        p.hand.removeAll(selected); return new ArrayList<>(selected);
    }
    private List<Integer> dice() { return List.of(random.nextInt(1, 7), random.nextInt(1, 7), random.nextInt(1, 7)); }
    private static List<Hand> hands(YmRoom r) { return r.players.stream().map(p -> new Hand(p.id, List.copyOf(p.hand), List.copyOf(p.melds))).toList(); }
    private static List<Score> scores(YmRoom r, Map<String, Integer> deltas) {
        List<YmRoom.Player> ordered = new ArrayList<>(r.players);
        ordered.sort(Comparator.comparingInt((YmRoom.Player p) -> -p.score)
                .thenComparingInt(p -> (p.seat - r.initialDealer + r.rules().playerCount()) % r.rules().playerCount()));
        List<Score> scores = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) { YmRoom.Player p = ordered.get(i); scores.add(new Score(p.id, p.name, p.score, deltas.getOrDefault(p.id, 0), i + 1)); }
        return List.copyOf(scores);
    }
}
