package com.mahjong.yaoming;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mahjong.domain.Tile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Clock;
import java.util.*;
import java.util.random.RandomGenerator;
import jakarta.annotation.PreDestroy;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static com.mahjong.yaoming.YmViews.*;
import static com.mahjong.yaoming.YmRoom.Phase.*;

@Service
public class YmService {
    private static final Logger log = LoggerFactory.getLogger(YmService.class);
    static final long LEAVE_RECEIPT_TTL_MS = 15 * 60 * 1000;
    static final long DEFAULT_EMPTY_ROOM_RETENTION_MS = 10 * 60 * 1000;
    static final long OFFLINE_GRACE_MS = 60 * 1000;
    static final int MAX_LEAVE_RECEIPTS = 2048;
    // The revised document retains its printed version; fingerprint the actual source.
    static final String RULEBOOK_SHA256 = "035c0d8708dec106c15bdc56604815d458bcc08a0ad17b052dce04fb50c7711c";
    private final Map<String, YmRoom> rooms = new LinkedHashMap<>();
    private final Map<String, LeaveReceipt> leaveReceipts = new LinkedHashMap<>();
    private final Map<String, ReplayArchive> replayArchives = new LinkedHashMap<>();
    private final YmPush push;
    static final int MAX_REPLAY_ARCHIVES = 20;
    private final ObjectMapper mapper;
    private final Path file;
    private final SecureRandom secrets = new SecureRandom();
    private final YmEngine engine;
    private final Clock clock;
    private final long startedAt;
    private final long emptyRoomRetentionMs;
    /** Restart-only reconnect grace. Never granted to an already-running empty-room timer. */
    private final Map<String, Long> restoredPresenceUntil = new HashMap<>();
    private byte[] committed = "[]".getBytes(StandardCharsets.UTF_8);
    // Server-only receipts survive removal of the room and contain no reusable recovery token.
    private record LeaveReceipt(String roomId, String playerId, String tokenHash, String requestId, long expiresAt) {}
    private record ReplayAccess(String playerId, String tokenHash) {}
    private record ReplayArchive(String roomId, String roomName, List<ReplayAccess> access,
                                 List<YmReplay.HandRecord> hands, long updatedAt) {}
    private record Snapshot(List<YmRoom> rooms, List<LeaveReceipt> leaveReceipts, List<ReplayArchive> replayArchives,
                            String rulebookSha256) {}
    public record HintView(String roomId, String playerId, long version, YmHints.Analysis analysis) {}
    public record ReplaySummary(int round, String roundLabel, long startedAt, long completedAt,
                                int frameCount, boolean incomplete, String title, String ruleId, String ruleName, int capacity) {
        public ReplaySummary {
            YmRules rules = YmRules.fromId(ruleId);
            ruleId = rules.id(); ruleName = rules.displayName(); capacity = rules.playerCount();
        }
        public ReplaySummary(int round, String roundLabel, long startedAt, long completedAt, int frameCount, boolean incomplete, String title) {
            this(round, roundLabel, startedAt, completedAt, frameCount, incomplete, title, null, null, 3);
        }
    }
    public record ReplayList(String roomId, String roomName, List<ReplaySummary> hands, String note,
                             String ruleId, String ruleName, int capacity) {
        public ReplayList {
            YmRules rules = YmRules.fromId(ruleId);
            ruleId = rules.id(); ruleName = rules.displayName(); capacity = rules.playerCount();
        }
        public ReplayList(String roomId, String roomName, List<ReplaySummary> hands, String note) {
            this(roomId, roomName, hands, note, null, null, 3);
        }
    }

    @Autowired
    public YmService(ObjectMapper mapper, @Value("${mahjong.data.yaoming-file:./data/yaoming-rooms.json}") String path,
                     @Value("${mahjong.rooms.empty-retention-ms:600000}") long emptyRoomRetentionMs) {
        this(mapper, Path.of(path), new SecureRandom(), Clock.systemUTC(), new YmPush(), emptyRoomRetentionMs);
    }
    public YmService(ObjectMapper mapper, String path) {
        this(mapper, path, DEFAULT_EMPTY_ROOM_RETENTION_MS);
    }
    YmService(ObjectMapper mapper, Path file, RandomGenerator random) {
        this(mapper, file, random, Clock.systemUTC());
    }
    YmService(ObjectMapper mapper, Path file, RandomGenerator random, Clock clock) {
        this(mapper, file, random, clock, new YmPush());
    }
    YmService(ObjectMapper mapper, Path file, RandomGenerator random, Clock clock, YmPush push) {
        this(mapper, file, random, clock, push, DEFAULT_EMPTY_ROOM_RETENTION_MS);
    }
    YmService(ObjectMapper mapper, Path file, RandomGenerator random, Clock clock, long emptyRoomRetentionMs) {
        this(mapper, file, random, clock, new YmPush(), emptyRoomRetentionMs);
    }
    YmService(ObjectMapper mapper, Path file, RandomGenerator random, Clock clock, YmPush push, long emptyRoomRetentionMs) {
        if (emptyRoomRetentionMs <= 0) throw new IllegalArgumentException("无人房间保留时间必须大于0毫秒");
        this.mapper = mapper; this.file = file.toAbsolutePath(); this.engine = new YmEngine(random);
        this.clock = Objects.requireNonNull(clock); this.push = Objects.requireNonNull(push);
        this.emptyRoomRetentionMs = emptyRoomRetentionMs;
        this.startedAt = clock.millis(); restore();
    }
    @PreDestroy public void closeStreams() { push.close(); }
    public synchronized List<Summary> list() {
        reclaimExpired(clock.millis());
        return rooms.values().stream().map(r -> new Summary(r.id, r.name, r.phase.name(),
                (int) r.players.stream().filter(p -> !p.left).count(), r.rules().playerCount(), r.rules().id(), r.rules().displayName())).toList();
    }
    public Rules rules() { return rules(null); }
    public List<Rules> rulesets() { return Arrays.stream(YmRules.values()).map(rule -> rules(rule.id())).toList(); }
    public Rules rules(String ruleId) {
        YmRules rule = YmRules.fromId(ruleId);
        boolean four = rule == YmRules.FOUR_PLAYER;
        return new Rules(rule.displayName(), "26.9 LTS",
                four ? "四人136张 · " + rule.minimumFan() + "番起和 · 8番封顶 · 各10点 · 东南八局 · 归零终场"
                        : "三人108张 · 4番起和 · 8番封顶 · 各10点 · 东南六局 · 归零终场", List.of(
                four ? "依据《要命麻将规则集 - 四人实验性》及用户最新调整：开门按四家计算，自摸另外三家各付番数，改为3番起和、仍8番封顶。"
                        : "依据本次提供的《要命麻将规则集(2)》，封面版本仍为26.9 LTS；本次修订新增平和1番、门清2番、清全带幺3番，不再计断幺。",
                four ? "使用标准34种麻将牌各4张，万子为正常连续顺子，159万不是顺子；无花牌和红宝牌。"
                        : "万子仅一五九，159万可组成顺子；无北、花牌和红宝牌。",
                four ? "点和付4倍番数，自摸其余三家各付1倍，最多付至0点。无庄闲倍率、无连庄、无流局罚分。"
                        : "点和付3倍番数，自摸其余两家各付1倍，最多付至0点。无庄闲倍率、无连庄、无流局罚分。",
                "只能吃上家的牌；点和优先于碰杠、碰杠优先于吃；多家点和按行动顺序截和。杠从墙尾补牌，本规则无抢杠和。",
                "本规则无振听：打过或放过同种牌仍可点和，不必等待自己摸牌；点和仍须满足合法牌型与" + rule.minimumFan() + "番起和。",
                four ? "平和为四组顺子加数牌雀头，允许副露，不限制听口；门清1番，清全带幺3番，不计混全带幺。"
                        : "平和为四组顺子加数牌雀头，允许副露，不限制听口；159万也算顺子。清全带幺3番，不计混全带幺。",
                four ? "清一色仅限一种数牌花色；字一色6番，不叠加清一色、混全带幺、碰碰和、番牌。取消风龙，改为全不靠2番，只可加不求人、杠上炮；全不靠本身不足起和，加其中1番达到3番后可以和。"
                        : "清一色仅限一种数牌花色；字一色6番，不叠加清一色、混全带幺、碰碰和、番牌。风龙只可加不求人、杠上炮。",
                "每个杠加1番，门风与圈风可叠加；十二落抬按四组副露计算，包含暗杠。",
                "文档花牌规则与终场积分公式未提供，当前无花牌，终场按剩余点数排名，同分按起始风位。",
                "摸牌15秒、出牌30秒，操作超时自动托管，需主动收回控制；吃碰和响应20秒，超时自动过。",
                "局间结算最多60秒，全部确认或到时后轮庄；终场结算不会自动退出。断线60秒后临时托管，重连交还控制。机器人为练习对手。",
                "无人房间默认保留10分钟后自动销毁，保留时长可由服务器配置。机器人和已退出玩家不算真人，在线托管真人仍算；真人离线60秒后开始无人计时，重连或新真人加入取消计时。销毁时仅按原有鉴权及最近20场保留策略归档已结束小局，未完小局不补造结算。"
        ), YmScoring.catalog(rule), YmTiles.deck(rule).stream().filter(t -> t.id().endsWith("-0")).toList(),
                rule.id(), rule.playerCount(), rule.tileCount(), rule.totalRounds());
    }
    public synchronized Identity create(String name, String playerName) { return create(name, playerName, null); }
    public synchronized Identity create(String name, String playerName, String ruleId) {
        YmRules rules = YmRules.fromId(ruleId);
        reclaimExpired(clock.millis());
        if (rooms.size() >= 100) throw new IllegalArgumentException("房间已满，请稍后再试");
        name = text(name, 30, "牌局名称"); playerName = text(playerName, 20, "昵称");
        YmRoom r = new YmRoom();
        r.ruleId = rules.id(); r.appliedMinimumFan = rules.minimumFan();
        r.message = rules.playerCount() + "人入席并准备后开局";
        do { r.id = String.format("%06d", secrets.nextInt(1_000_000)); } while (rooms.containsKey(r.id) || replayArchives.containsKey(r.id));
        r.name = name; r.lastActivity = clock.millis(); YmRoom.Player p = player(playerName, 0, false); r.players.add(p); r.hostId = p.id;
        YmEngine.event(r, playerName + " 创建了牌局"); rooms.put(r.id, r); persist();
        return new Identity(r.id, p.id, p.token);
    }
    public synchronized Identity join(String roomId, String name) {
        YmRoom r = room(roomId); name = text(name, 20, "昵称");
        if (r.phase != WAITING || r.players.size() >= r.rules().playerCount()) throw new IllegalArgumentException("当前房间不能加入，请选择等待中的空房间");
        String nickname = name;
        if (r.players.stream().anyMatch(p -> p.name.equals(nickname))) throw new IllegalArgumentException("这个昵称已有人使用");
        int seat = 0; while (occupied(r, seat)) seat++;
        YmRoom.Player p = player(name, seat, false); r.players.add(p); r.version++; r.lastActivity = clock.millis();
        r.emptySince = null;
        if (r.players.stream().noneMatch(player -> !player.bot && !player.left && player.id.equals(r.hostId))) r.hostId = p.id;
        YmEngine.event(r, name + " 加入房间"); persist(); return new Identity(r.id, p.id, p.token);
    }
    public synchronized Identity resume(String roomId, String token) {
        YmRoom r = room(roomId);
        YmRoom.Player p = r.players.stream().filter(x -> !x.bot && !x.left && secureEquals(x.token, token)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("恢复码无效或玩家已退出"));
        if (engine.expire(r, clock.millis())) persist();
        contact(r, p); return new Identity(r.id, p.id, p.token);
    }
    public synchronized RoomView view(String id, String playerId, String token) {
        YmRoom r = room(id); YmRoom.Player p = authorize(r, playerId, token);
        if (engine.expire(r, clock.millis())) persist();
        contact(r, p);
        return project(r, p);
    }
    public synchronized HintView hints(String id, String playerId, String token) {
        YmRoom r = room(id); YmRoom.Player p = authorize(r, playerId, token);
        if (engine.expire(r, clock.millis())) persist();
        contact(r, p);
        return new HintView(id, playerId, r.version, YmHints.analyze(r, p));
    }
    public synchronized SseEmitter stream(String id, String playerId, String token) {
        YmRoom r = room(id); YmRoom.Player p = authorize(r, playerId, token);
        if (engine.expire(r, clock.millis())) persist();
        contact(r, p);
        return push.open(r.id, p.id, r.version);
    }
    public synchronized ReplayList replays(String id, String playerId, String token) {
        ReplayArchive archive = authorizedReplay(id, playerId, token);
        YmRules rules = rooms.containsKey(id) ? rooms.get(id).rules()
                : archive.hands().stream().findFirst().map(hand -> YmRules.fromId(hand.ruleId())).orElse(YmRules.THREE_PLAYER);
        return new ReplayList(id, archive.roomName(), archive.hands().stream().filter(YmReplay.HandRecord::complete)
                .map(h -> new ReplaySummary(h.round(), h.roundLabel(), h.startedAt(), h.completedAt(), h.frames().size(),
                        h.incomplete(), h.result() == null ? "已结束小局" : h.result().title(), h.ruleId(), h.ruleName(), h.capacity())).toList(),
                "仅开放已结束小局；旧版本未记录的牌局无法补齐。离席后的归档最多保留最近20场，请及时导出。",
                rules.id(), rules.displayName(), rules.playerCount());
    }
    public synchronized YmReplay.HandRecord replay(String id, int round, String playerId, String token) {
        return authorizedReplay(id, playerId, token).hands().stream().filter(h -> h.complete() && h.round() == round)
                .findFirst().orElseThrow(() -> new NoSuchElementException("该局尚未结束或没有完整记录，暂不能复盘"));
    }
    private ReplayArchive authorizedReplay(String id, String playerId, String token) {
        YmRoom room = rooms.get(id);
        if (room != null) {
            // Former participants may view finished hands, but never regain an active seat this way.
            boolean allowed = room.players.stream().anyMatch(p -> !p.bot && Objects.equals(p.id, playerId) && secureEquals(p.token, token));
            if (!allowed) throw new IllegalArgumentException("牌谱身份无效，请使用本场自己的座位恢复码");
            return new ReplayArchive(id, room.name, List.of(), List.copyOf(room.replayHands), clock.millis());
        }
        ReplayArchive archive = replayArchives.get(id);
        if (archive == null) throw new NoSuchElementException("牌谱不存在、尚未记录或已超过保留范围");
        if (archive.access().stream().noneMatch(a -> Objects.equals(a.playerId(), playerId) && secureEquals(a.tokenHash(), tokenHash(token))))
            throw new IllegalArgumentException("牌谱身份无效，请使用本场自己的座位恢复码");
        return archive;
    }
    public synchronized RoomView action(String id, String playerId, String token, long version, String requestId, String type, List<String> tileIds) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 100) throw new IllegalArgumentException("缺少有效请求编号");
        if (type == null) throw new IllegalArgumentException("缺少操作类型");
        List<String> ids = tileIds == null ? List.of() : tileIds;
        if (ids.size() > 4 || ids.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("所选牌无效");
        long now = clock.millis();
        if (type.equals("LEAVE")) {
            if (!ids.isEmpty()) throw new IllegalArgumentException("退出操作不接受牌张参数");
            LeaveReceipt receipt = leaveReceipts.get(receiptKey(id, playerId, requestId));
            if (receipt != null && receipt.expiresAt() > now && Objects.equals(receipt.roomId(), id)
                    && Objects.equals(receipt.playerId(), playerId) && Objects.equals(receipt.requestId(), requestId)) {
                if (!secureEquals(receipt.tokenHash(), tokenHash(token))) throw new IllegalArgumentException("玩家身份无效，请使用恢复码重新进入");
                return null;
            }
        }
        YmRoom r = room(id); YmRoom.Player p = authorize(r, playerId, token);
        if (engine.expire(r, now)) persist();
        String key = p.id + ":" + requestId;
        String signature = type + ":" + String.join(",", ids.stream().sorted().toList());
        if (r.processed.containsKey(key)) {
            if (!r.processed.get(key).equals(signature)) throw new IllegalArgumentException("请求编号已用于其他操作");
            contact(r, p);
            return project(r, p);
        }
        if (version != r.version) throw new VersionConflict("牌桌已更新，已为你同步，请重新操作");
        if ((type.equals("TRUSTEE") || type.equals("ADD_BOT")) && !ids.isEmpty()) throw new IllegalArgumentException("此操作不接受牌张参数");
        if (type.equals("TRUSTEE") && r.phase == MATCH_END) throw new IllegalArgumentException("本场已结束，不能切换托管");
        p.lastSeen = now;
        if (type.equals("ADD_BOT")) {
            if (!p.id.equals(r.hostId) || r.phase != WAITING || r.players.size() >= r.rules().playerCount()) throw new IllegalArgumentException("只有房主可在开局前补机器人");
            int seat = 0; while (occupied(r, seat)) seat++;
            YmRoom.Player bot = player("练习雀友" + (seat + 1), seat, true); bot.ready = true; r.players.add(bot);
            YmEngine.event(r, bot.name + " 入席"); r.version++;
            engine.startIfReady(r, now);
        } else if (type.equals("LEAVE")) {
            leave(r, p, now);
            leaveReceipts.put(receiptKey(id, playerId, requestId), new LeaveReceipt(id, playerId, tokenHash(token), requestId, now + LEAVE_RECEIPT_TTL_MS));
            persist(); return null;
        } else if (type.equals("TRUSTEE")) {
            p.trustee = !p.trustee; p.autoTrustee = false; r.version++; r.nextBotAt = now + 650;
            p.trusteeReason = p.trustee ? "MANUAL" : null;
            if (!p.trustee) engine.resetActionDeadline(r, p, now);
            if (r.phase == HAND_END && p.trustee) p.acknowledged = true;
            YmEngine.event(r, p.name + (p.trustee ? " 开启托管" : " 关闭托管"));
        } else engine.perform(r, p, type, ids, now);
        r.processed.put(key, signature);
        while (r.processed.size() > 256) r.processed.remove(r.processed.keySet().iterator().next());
        r.lastActivity = now; r.emptySince = null; persist(); return project(r, p);
    }

    @Scheduled(fixedDelay = 400)
    public synchronized void tick() { advance(clock.millis()); }
    synchronized void advance(long now) {
        boolean changed = pruneReceipts(now);
        for (YmRoom r : new ArrayList<>(rooms.values())) {
            changed |= refreshEmptyInterval(r, now);
            if (emptyExpired(r, now)) {
                removeExpiredRoom(r, now); changed = true; continue;
            }
            if (r.phase != WAITING && r.phase != MATCH_END && now - startedAt >= OFFLINE_GRACE_MS) {
                for (YmRoom.Player p : r.players) if (!p.bot && !p.left && !p.trustee && now - p.lastSeen >= OFFLINE_GRACE_MS) {
                    p.trustee = true; p.autoTrustee = true; p.trusteeReason = "OFFLINE";
                    if (r.phase == HAND_END) p.acknowledged = true;
                    YmEngine.event(r, p.name + " 离线超过60秒，已临时托管"); r.version++; changed = true;
                }
            }
            // Keep offline/reconnection status truthful, but with nobody present retain
            // the actual table instead of bot-only starts, new hands or final results.
            if (!hasPresentHuman(r, now)) continue;
            changed |= engine.expire(r, now);
            if (now < r.nextBotAt) continue;
            long version = r.version; engine.startAcknowledgedHand(r, now); changed |= version != r.version;
            for (YmRoom.Player p : r.players) if (p.bot || p.trustee || p.left) {
                List<Action> legal = engine.gameActions(r, p);
                Action choice = engine.automaticAction(r, p, legal);
                if (choice == null) continue;
                engine.perform(r, p, choice.type(), choice.tileIds(), now); changed = true;
                break;
            }
        }
        if (changed) persist();
    }
    private void leave(YmRoom r, YmRoom.Player p, long now) {
        if (r.phase == WAITING) {
            r.players.remove(p);
            if (p.id.equals(r.hostId)) r.hostId = r.players.stream().filter(x -> !x.bot && !x.left).map(x -> x.id).findFirst().orElse("");
            r.players.stream().filter(x -> !x.bot && !x.left).forEach(x -> x.ready = false);
        } else { p.left = true; p.trustee = true; p.autoTrustee = false; p.trusteeReason = "MANUAL"; p.acknowledged = true; }
        r.version++; r.nextBotAt = now + 650; r.lastActivity = now;
        YmEngine.event(r, p.name + (r.phase == WAITING || r.phase == MATCH_END ? " 退出房间" : " 退出房间，座位由机器人接管"));
        // The departing human was present until this action, even if all remaining
        // humans were already offline. Start at departure, not their old timestamps.
        r.emptySince = null;
        if (!hasPresentHuman(r, now)) r.emptySince = now;
    }

    private long presenceUntil(YmRoom room, YmRoom.Player player) {
        long until = player.lastSeen + OFFLINE_GRACE_MS;
        if (player.lastSeen == 0 && room.emptySince == null)
            until = Math.max(until, restoredPresenceUntil.getOrDefault(room.id, Long.MIN_VALUE));
        return until;
    }
    private boolean hasPresentHuman(YmRoom room, long now) {
        return room.players.stream().anyMatch(player -> !player.bot && !player.left
                // A restored zero lastSeen is unknown, not new contact capable of
                // cancelling the durable interval (including an interval starting at 0).
                && (room.emptySince == null || player.lastSeen > room.emptySince)
                && now < presenceUntil(room, player));
    }
    private boolean refreshEmptyInterval(YmRoom room, long now) {
        if (hasPresentHuman(room, now)) {
            if (room.emptySince == null) return false;
            room.emptySince = null; return true;
        }
        if (room.emptySince != null) return false;
        // Derive the actual last human's grace expiry, rather than the scheduler's
        // observation time, so a delayed tick or API-only access cannot extend retention.
        room.emptySince = room.players.stream().filter(player -> !player.bot && !player.left)
                .mapToLong(player -> presenceUntil(room, player)).max().orElse(now);
        return true;
    }
    private boolean emptyExpired(YmRoom room, long now) {
        return room.emptySince != null && now >= room.emptySince && now - room.emptySince >= emptyRoomRetentionMs;
    }
    private void removeExpiredRoom(YmRoom room, long now) {
        List<YmReplay.HandRecord> completed = room.replayHands.stream().filter(YmReplay.HandRecord::complete).toList();
        if (!completed.isEmpty()) {
            replayArchives.put(room.id, new ReplayArchive(room.id, room.name,
                    room.players.stream().filter(player -> !player.bot)
                            .map(player -> new ReplayAccess(player.id, tokenHash(player.token))).toList(), completed, now));
            pruneReplayArchives();
        }
        rooms.remove(room.id);
        // Subscribers are closed by push.committed only after durable removal succeeds.
    }
    private void reclaimExpired(long now) {
        boolean changed = false;
        for (YmRoom room : new ArrayList<>(rooms.values())) {
            changed |= refreshEmptyInterval(room, now);
            if (emptyExpired(room, now)) { removeExpiredRoom(room, now); changed = true; }
        }
        if (changed) persist();
    }

    private RoomView project(YmRoom r, YmRoom.Player me) {
        long now = clock.millis();
        List<PlayerView> players = r.players.stream().sorted(Comparator.comparingInt(p -> p.seat)).map(p -> {
            boolean mine = p.id.equals(me.id);
            return new PlayerView(p.id, p.name, p.seat, r.rules().windNames().get((p.seat - r.dealerSeat + r.rules().playerCount()) % r.rules().playerCount()),
                    p.score, p.bot || p.left, p.ready, p.bot || p.left || now - p.lastSeen < 15000,
                    p.acknowledged, p.trustee, mine ? List.copyOf(p.hand) : List.of(), p.hand.size(),
                    List.copyOf(p.discards), List.copyOf(p.melds), mine ? List.copyOf(p.discardedCodes) : List.of(), mine ? List.copyOf(p.passedCodes) : List.of(),
                    mine ? p.lastDrawnId : null, p.trustee ? p.trusteeReason : null, p.discardKinds);
        }).toList();
        List<Action> actions = new ArrayList<>(engine.gameActions(r, me));
        if (r.phase == WAITING && me.id.equals(r.hostId) && r.players.size() < r.rules().playerCount()) actions.add(Action.of("ADD_BOT", "添加机器人"));
        if (r.phase != MATCH_END) actions.add(Action.of("TRUSTEE", me.trustee ? "关闭托管" : "开启托管"));
        actions.add(Action.of("LEAVE", r.phase == WAITING || r.phase == MATCH_END ? "退出房间" : "退出并由机器人接管"));
        int minimumFan = r.rules().minimumFan();
        String hint = minimumFan + "番起和，8番封顶；无振听，打过或放过同种牌仍可点和";
        if (r.phase == NEED_DISCARD && r.currentSeat == me.seat) {
            if (me.lastDrawnId == null) hint = "吃碰后请出牌，本次不能自摸或开杠";
            else {
                YmScoring.Evaluation e = engine.evaluate(r, me, null, true, false);
                hint = e.eligible() ? "可自摸：" + e.rawFan() + "番" + (e.rawFan() > 8 ? "（按8番结算）" : "") : e.validStructure() ? "已成和牌形，当前仅" + e.rawFan() + "番，至少需要" + minimumFan + "番" : "尚未成和牌形，至少需要" + minimumFan + "番";
            }
        }
        return new RoomView(r.id, r.name, r.version, r.phase.name(), r.round, YmEngine.roundLabel(r), r.dealerSeat, r.currentSeat,
                r.wall.size(), r.message, me.id, players, List.copyOf(actions), r.result, List.copyOf(r.events), r.dice,
                r.deadlineAt <= 0 ? null : Instant.ofEpochMilli(r.deadlineAt).toString(), hint,
                Instant.ofEpochMilli(now).toString(), r.deadlineKind, r.lastDiscard, r.rules().id(), r.rules().displayName(), r.rules().playerCount());
    }
    private YmRoom.Player player(String name, int seat, boolean bot) {
        YmRoom.Player p = new YmRoom.Player(); p.id = UUID.randomUUID().toString(); p.name = name; p.seat = seat; p.bot = bot; p.lastSeen = clock.millis();
        byte[] bytes = new byte[24]; secrets.nextBytes(bytes); p.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); return p;
    }
    private void contact(YmRoom r, YmRoom.Player p) {
        p.lastSeen = clock.millis();
        boolean changed = r.emptySince != null;
        r.emptySince = null;
        if (p.autoTrustee && "OFFLINE".equals(p.trusteeReason)) {
            p.autoTrustee = false; p.trustee = false; p.trusteeReason = null; r.version++;
            YmEngine.event(r, p.name + " 已重连，收回控制"); changed = true;
        }
        if (changed) persist();
    }
    private YmRoom room(String id) {
        YmRoom room = rooms.get(id);
        if (room != null) {
            long now = clock.millis();
            boolean changed = refreshEmptyInterval(room, now);
            if (emptyExpired(room, now)) { removeExpiredRoom(room, now); changed = true; }
            if (changed) persist();
        }
        if (!rooms.containsKey(id)) throw new NoSuchElementException("房间不存在或已因无人自动销毁");
        return room;
    }
    private YmRoom.Player authorize(YmRoom r, String id, String token) {
        return r.players.stream().filter(p -> !p.bot && !p.left && Objects.equals(p.id, id) && secureEquals(p.token, token)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("玩家身份无效，请使用恢复码重新进入"));
    }
    private static boolean secureEquals(String a, String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static String tokenHash(String token) {
        if (token == null) return null;
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
    private static String receiptKey(String roomId, String playerId, String requestId) { return roomId + ":" + playerId + ":" + requestId; }
    private static boolean occupied(YmRoom r, int seat) { return r.players.stream().anyMatch(p -> p.seat == seat); }
    private static String text(String value, int limit, String label) {
        if (value == null || value.isBlank() || value.trim().length() > limit) throw new IllegalArgumentException(label + "必须为1至" + limit + "字");
        return value.trim();
    }
    private void restore() {
        if (!Files.exists(file)) return;
        try {
            Snapshot saved = readSnapshot(Files.readAllBytes(file));
            boolean rulebookChanged = !RULEBOOK_SHA256.equals(saved.rulebookSha256());
            replaceState(saved);
            rooms.values().forEach(r -> {
                if (r.emptySince == null && r.players.stream().anyMatch(p -> !p.bot && !p.left))
                    restoredPresenceUntil.put(r.id, startedAt + OFFLINE_GRACE_MS);
                r.players.forEach(p -> p.lastSeen = 0);
            });
            boolean migrated = rulebookChanged;
            for (YmRoom room : rooms.values()) {
                migrated |= refreshEmptyInterval(room, clock.millis());
                boolean minimumChanged = room.appliedMinimumFan != room.rules().minimumFan();
                room.appliedMinimumFan = room.rules().minimumFan();
                migrated |= engine.initializeDeadline(room, clock.millis());
                if (room.lastDiscard == null && room.window != null) {
                    room.lastDiscard = new LastDiscard(room.window.tile, room.window.fromSeat, false); migrated = true;
                }
                boolean optionsChanged = engine.restoreRonOptions(room, clock.millis());
                // Even an unchanged turn needs a fresh version so connected clients
                // recalculate hints and reject writes based on the previous rules.
                if (rulebookChanged || optionsChanged || minimumChanged) {
                    room.version++; migrated = true;
                }
                for (YmRoom.Player player : room.players) if (player.trustee && player.trusteeReason == null) {
                    player.trusteeReason = player.autoTrustee ? "OFFLINE" : "MANUAL"; migrated = true;
                }
            }
            pruneReceipts(clock.millis());
            committed = snapshotBytes();
            if (migrated) persist();
        } catch (IOException ex) { throw new IllegalStateException("要命麻将快照无法读取，已保留原文件，请先修复快照：" + file, ex); }
    }
    private Snapshot readSnapshot(byte[] bytes) throws IOException {
        JsonNode root = mapper.readTree(bytes);
        if (root != null && root.isArray()) {
            List<YmRoom> legacy = mapper.readerFor(new TypeReference<List<YmRoom>>() {}).readValue(root);
            return new Snapshot(legacy, List.of(), List.of(), null);
        }
        if (root == null || !root.isObject() || !root.path("rooms").isArray() || !root.path("leaveReceipts").isArray())
            throw new IOException("Invalid Yaoming snapshot structure");
        return mapper.treeToValue(root, Snapshot.class);
    }
    private void replaceState(Snapshot snapshot) {
        rooms.clear(); leaveReceipts.clear(); replayArchives.clear();
        for (YmRoom room : snapshot.rooms()) {
            if (room == null || room.id == null || room.players == null || room.players.isEmpty() && room.phase != WAITING) continue;
            // Do not infer rules from occupied seats or silently convert an existing match.
            // Unknown nonempty IDs fail restoration before any file can be overwritten.
            room.ruleId = room.rules().id();
            // Missing or explicit-null provenance in old durable data means unknown, not tedashi.
            room.players.forEach(player -> player.discardKinds = player.discardKinds == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(player.discardKinds));
            rooms.put(room.id, room);
        }
        for (LeaveReceipt receipt : snapshot.leaveReceipts()) {
            if (receipt == null || receipt.roomId() == null || receipt.playerId() == null || receipt.tokenHash() == null || receipt.requestId() == null) continue;
            leaveReceipts.put(receiptKey(receipt.roomId(), receipt.playerId(), receipt.requestId()), receipt);
        }
        if (snapshot.replayArchives() != null) for (ReplayArchive archive : snapshot.replayArchives()) {
            if (archive != null && archive.roomId() != null && archive.access() != null && archive.hands() != null)
                replayArchives.put(archive.roomId(), archive);
        }
        pruneReplayArchives();
    }
    private void pruneReplayArchives() {
        while (replayArchives.size() > MAX_REPLAY_ARCHIVES) {
            ReplayArchive oldest = replayArchives.values().stream().min(Comparator.comparingLong(ReplayArchive::updatedAt)).orElseThrow();
            replayArchives.remove(oldest.roomId());
        }
    }
    private boolean pruneReceipts(long now) {
        boolean changed = leaveReceipts.values().removeIf(receipt -> receipt.expiresAt() <= now);
        while (leaveReceipts.size() > MAX_LEAVE_RECEIPTS) {
            leaveReceipts.remove(leaveReceipts.keySet().iterator().next()); changed = true;
        }
        return changed;
    }
    private byte[] snapshotBytes() throws IOException {
        return mapper.writeValueAsBytes(new Snapshot(new ArrayList<>(rooms.values()), new ArrayList<>(leaveReceipts.values()), new ArrayList<>(replayArchives.values()), RULEBOOK_SHA256));
    }
    private void persist() {
        Path temp = null;
        try {
            Files.createDirectories(file.getParent()); temp = Files.createTempFile(file.getParent(), "yaoming-", ".tmp");
            pruneReceipts(clock.millis());
            byte[] snapshot = snapshotBytes();
            Files.write(temp, snapshot);
            try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            committed = snapshot;
        } catch (IOException ex) {
            // A failed durable commit must not leave a different, acknowledged in-memory game.
            try {
                replaceState(readSnapshot(committed));
            } catch (IOException restoreError) { ex.addSuppressed(restoreError); }
            log.error("要命麻将快照写入失败，内存状态已回滚", ex);
            throw new IllegalStateException("牌局保存失败，本次操作未生效，请稍后重试", ex);
        }
        finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
        push.committed(rooms.values());
    }
    // Package-private access for deterministic state-machine tests, not reachable from HTTP.
    YmRoom state(String id) { return room(id); }
    public static final class VersionConflict extends RuntimeException { public VersionConflict(String message) { super(message); } }
}
