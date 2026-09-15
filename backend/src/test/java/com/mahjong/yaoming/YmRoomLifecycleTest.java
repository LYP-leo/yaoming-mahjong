package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.assertj.core.api.Assertions.*;

/** Lifecycle tests advance the injected clock, never wait for the real retention period. */
class YmRoomLifecycleTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock(1_000_000);
    private final List<YmService> services = new ArrayList<>();
    private final BlockingQueue<YmPush.Notice> notifications = new LinkedBlockingQueue<>();
    private Path file() { return directory.resolve("rooms.json"); }
    private YmService service() { return service(YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS); }
    private YmService service(long retention) {
        YmPush push = new YmPush(() -> new SseEmitter(0L) {
            @Override public void send(SseEventBuilder event) {
                for (var item : event.build()) if (item.getData() instanceof YmPush.Notice notice) notifications.add(notice);
            }
        });
        YmService service = new YmService(mapper, file(), new Random(317), clock, push, retention);
        services.add(service); return service;
    }
    @AfterEach void close() { services.forEach(YmService::closeStreams); }
    private RoomView view(YmService service, Identity id) { return service.view(id.roomId(), id.playerId(), id.token()); }
    private RoomView action(YmService service, Identity id, String type) {
        return service.action(id.roomId(), id.playerId(), id.token(), view(service, id).version(), UUID.randomUUID().toString(), type, List.of());
    }
    private List<Identity> started(YmService service) {
        Identity a = service.create("生命周期", "甲"), b = service.join(a.roomId(), "乙"), c = service.join(a.roomId(), "丙");
        List<Identity> ids = List.of(a, b, c); ids.forEach(id -> action(service, id, "READY")); return ids;
    }
    private void tick(YmService service, long now) { clock.now = now; service.tick(); }
    private void blockFile() throws IOException {
        Files.move(file(), directory.resolve("saved.json")); Files.createDirectory(file()); Files.writeString(file().resolve("blocker"), "failure fixture");
    }
    private void unblockFile() throws IOException {
        Files.delete(file().resolve("blocker")); Files.delete(file()); Files.move(directory.resolve("saved.json"), file());
    }
    private YmPush.Notice notice() throws InterruptedException {
        YmPush.Notice notice = notifications.poll(2, TimeUnit.SECONDS); assertThat(notice).isNotNull(); return notice;
    }

    @Test void waitingLeaveReleasesSeatTransfersHostAndResetsOnlyHumanReadiness() {
        YmService service = service(); Identity a = service.create("等待退出", "甲"), b = service.join(a.roomId(), "乙");
        action(service, a, "ADD_BOT"); action(service, b, "READY");
        action(service, a, "LEAVE"); YmRoom room = service.state(a.roomId());
        assertThat(room.phase).isEqualTo(WAITING); assertThat(room.hostId).isEqualTo(b.playerId());
        assertThat(room.players).hasSize(2).noneMatch(p -> p.id.equals(a.playerId()));
        assertThat(room.players.stream().filter(p -> !p.bot)).allMatch(p -> !p.ready);
        assertThat(room.players.stream().filter(p -> p.bot)).allMatch(p -> p.ready);
        assertThat(room.emptySince).isNull(); assertThatThrownBy(() -> service.resume(a.roomId(), a.token())).hasMessageContaining("恢复码无效");
        Identity replacement = service.join(a.roomId(), "甲");
        assertThat(service.state(a.roomId()).players.stream().filter(p -> p.id.equals(replacement.playerId())).findFirst().orElseThrow().seat).isZero();
        assertThat(view(service, replacement).players()).hasSize(3);
    }

    @Test void emptyWaitingRoomSurvivesDefaultTenMinutesAndExpiresExactlyAtBoundaryEvenAtEpochZero() {
        clock.now = 0; YmService service = service(); Identity host = service.create("空房", "甲"); action(service, host, "LEAVE");
        assertThat(YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS).isEqualTo(600_000);
        assertThat(service.state(host.roomId()).emptySince).isEqualTo(0L);
        assertThat(service.list()).hasSize(1); assertThat(service.state(host.roomId()).players).isEmpty();
        tick(service, 599_999); assertThat(service.list()).hasSize(1);
        tick(service, 600_000); assertThat(service.list()).isEmpty();
        assertThatThrownBy(() -> service.join(host.roomId(), "乙")).isInstanceOf(NoSuchElementException.class);
    }

    @Test void configurableRetentionControlsEmptyRoomLifetimeWithoutChangingOfflineGrace() {
        YmService service = service(2_000); Identity host = service.create("短保留", "甲"); long seen = clock.now;
        tick(service, seen + YmService.OFFLINE_GRACE_MS - 1); assertThat(service.state(host.roomId()).emptySince).isNull();
        tick(service, seen + YmService.OFFLINE_GRACE_MS); assertThat(service.state(host.roomId()).emptySince).isEqualTo(seen + 60_000);
        tick(service, seen + 61_999); assertThat(service.list()).hasSize(1);
        tick(service, seen + 62_000); assertThat(service.list()).isEmpty();
    }

    @Test void joiningRetainedEmptyOrBotOnlyRoomCancelsTimerAndFirstHumanBecomesHost() {
        YmService service = service(); Identity a = service.create("空房重用", "甲"); action(service, a, "LEAVE");
        clock.now += 100_000; Identity b = service.join(a.roomId(), "乙");
        assertThat(service.state(a.roomId()).hostId).isEqualTo(b.playerId()); assertThat(service.state(a.roomId()).emptySince).isNull();
        action(service, b, "ADD_BOT"); action(service, b, "LEAVE");
        YmRoom room = service.state(a.roomId()); assertThat(room.players).hasSize(1).allMatch(p -> p.bot);
        assertThat(room.hostId).isEmpty(); assertThat(room.emptySince).isEqualTo(clock.now);
        clock.now += 100_000; Identity c = service.join(a.roomId(), "丙");
        assertThat(room.hostId).isEqualTo(c.playerId()); assertThat(room.emptySince).isNull();
        assertThat(view(service, c).actions()).extracting(Action::type).contains("ADD_BOT");
    }

    @ParameterizedTest @EnumSource(YmRoom.Phase.class)
    void lastSeenGraceAndExpiryApplyInEveryPhaseAndDoNotFollowLastActivity(YmRoom.Phase phase) {
        YmService service = service(); List<Identity> ids = started(service); YmRoom room = service.state(ids.getFirst().roomId());
        room.phase = phase; room.deadlineAt = Long.MAX_VALUE; room.nextBotAt = Long.MAX_VALUE;
        if (phase == REACTION) {
            room.window = new YmRoom.Window(); room.window.tile = room.wall.getFirst(); room.window.deadline = Long.MAX_VALUE;
            room.deadlineKind = "REACTION";
        }
        long seen = clock.now;
        tick(service, seen + 59_999); assertThat(room.emptySince).isNull();
        tick(service, seen + 70_000); assertThat(room.emptySince).isEqualTo(seen + 60_000);
        room.lastActivity = seen + 659_999;
        tick(service, seen + 659_999); assertThat(service.list()).hasSize(1);
        tick(service, seen + 660_000); assertThat(service.list()).isEmpty();
    }

    @Test void latestRemainingHumanHeartbeatDeterminesOfflineTimer() {
        YmService service = service(); Identity a = service.create("错开断线", "甲"), b = service.join(a.roomId(), "乙"); long start = clock.now;
        clock.now += 30_000; view(service, b);
        tick(service, start + 60_000); assertThat(service.state(a.roomId()).emptySince).isNull();
        tick(service, start + 90_000); assertThat(service.state(a.roomId()).emptySince).isEqualTo(start + 90_000);
    }

    @Test void onlineManualTrusteeHumanKeepsRoomWhileBotsNeverCountAsHumanPresence() {
        YmService service = service(); Identity host = service.create("托管真人", "甲"); action(service, host, "ADD_BOT"); action(service, host, "TRUSTEE");
        for (int index = 0; index < 25; index++) { clock.now += 30_000; view(service, host); service.tick(); }
        YmRoom room = service.state(host.roomId()); assertThat(room.emptySince).isNull(); assertThat(room.players.stream().filter(p -> !p.bot)).allMatch(p -> p.trustee && !p.left);
        action(service, host, "LEAVE"); long empty = clock.now;
        room.players.forEach(p -> p.lastSeen = empty + 600_000); room.lastActivity = empty + 600_000;
        tick(service, empty + 600_000); assertThat(service.list()).isEmpty();
    }

    @Test void explicitLastLeaveStartsTimerNowRatherThanOlderOtherPlayersDisconnectTimeAndFreezesBots() {
        YmService service = service(); List<Identity> ids = started(service); YmRoom room = service.state(ids.getFirst().roomId());
        room.deadlineAt = Long.MAX_VALUE;
        clock.now += 100_000; // Only the leaving player reconnects; the other humans have gone offline.
        action(service, ids.getFirst(), "LEAVE"); long empty = clock.now;
        assertThat(room.emptySince).isEqualTo(empty); int wall = room.wall.size();
        tick(service, empty + 2_000); assertThat(room.wall).hasSize(wall); assertThat(room.phase).isEqualTo(NEED_DRAW);
        assertThat(room.players.stream().filter(p -> !p.left)).allMatch(p -> p.autoTrustee && p.trustee);
        assertThat(room.replayHands).isEmpty();
        tick(service, empty + 600_000); assertThat(service.list()).isEmpty();
    }

    @Test void resumeBeforeExpiryCancelsDurableTimerAndStartsNewGraceFromContact() throws Exception {
        YmService service = service(); Identity host = service.create("重连", "甲"); long start = clock.now;
        tick(service, start + 60_000); assertThat(service.state(host.roomId()).emptySince).isNotNull();
        clock.now = start + 659_999; assertThat(service.resume(host.roomId(), host.token())).isEqualTo(host);
        assertThat(service.state(host.roomId()).emptySince).isNull();
        assertThat(mapper.readTree(file().toFile()).path("rooms").get(0).path("emptySince").isNull()).isTrue();
        tick(service, start + 660_000); assertThat(service.list()).hasSize(1);
        tick(service, start + 719_999); assertThat(service.state(host.roomId()).emptySince).isEqualTo(start + 719_999);
    }

    @Test void expirationIsCheckedBeforeResumeJoinOrActionEvenWithoutScheduledTick() {
        YmService service = service(); Identity disconnected = service.create("不可复活", "甲"); long version = view(service, disconnected).version();
        clock.now += 660_000;
        assertThatThrownBy(() -> service.resume(disconnected.roomId(), disconnected.token())).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.action(disconnected.roomId(), disconnected.playerId(), disconnected.token(), version, "late-ready", "READY", List.of())).isInstanceOf(NoSuchElementException.class);
        Identity empty = service.create("禁止重新加入", "乙"); action(service, empty, "LEAVE"); clock.now += 600_000;
        assertThatThrownBy(() -> service.join(empty.roomId(), "丙")).isInstanceOf(NoSuchElementException.class);
        assertThat(service.list()).isEmpty();
    }

    @Test void persistedEmptyWaitingRoomRetainsOriginalTimerAcrossRestart() throws Exception {
        YmService service = service(); Identity host = service.create("空房重启", "甲"); action(service, host, "LEAVE"); long empty = clock.now;
        clock.now += 400_000; YmService restored = service();
        assertThat(restored.state(host.roomId()).players).isEmpty(); assertThat(restored.state(host.roomId()).emptySince).isEqualTo(empty);
        tick(restored, empty + 599_999); assertThat(restored.list()).hasSize(1);
        tick(restored, empty + 600_000); assertThat(restored.list()).isEmpty();
    }

    @Test void persistedOfflineHumanTimerDoesNotGainAnotherStartupGraceOrGetClearedByReplayRead() throws Exception {
        YmService service = service(); Identity host = service.create("离线计时重启", "甲");
        tick(service, clock.now + 60_000); long empty = service.state(host.roomId()).emptySince;
        clock.now = empty + 599_999; YmService restored = service();
        assertThat(restored.state(host.roomId()).emptySince).isEqualTo(empty);
        assertThat(restored.replays(host.roomId(), host.playerId(), host.token()).hands()).isEmpty();
        assertThat(restored.state(host.roomId()).emptySince).isEqualTo(empty);
        tick(restored, empty + 600_000); assertThat(restored.list()).isEmpty();
    }

    @Test void legacyBotOnlyRoomWithoutTimerStartsRetentionAtStartupNotBotActivity() throws Exception {
        YmService service = service(); Identity host = service.create("旧机器人房", "甲"); action(service, host, "ADD_BOT"); action(service, host, "LEAVE");
        ObjectNode snapshot = (ObjectNode) mapper.readTree(file().toFile());
        ObjectNode room = (ObjectNode) snapshot.path("rooms").get(0); room.remove("emptySince"); room.put("lastActivity", 0);
        mapper.writeValue(file().toFile(), snapshot);
        clock.now += 86_400_000; long startup = clock.now; YmService restored = service();
        assertThat(restored.state(host.roomId()).emptySince).isEqualTo(startup);
        tick(restored, startup + 599_999); assertThat(restored.list()).hasSize(1);
        tick(restored, startup + 600_000); assertThat(restored.list()).isEmpty();
    }

    @Test void legacyHumanRoomWithoutTimerGetsStartupGraceNotImmediateDeletionAfterLongDowntime() throws Exception {
        YmService service = service(); Identity host = service.create("旧房重启", "甲");
        ObjectNode snapshot = (ObjectNode) mapper.readTree(file().toFile()); ((ObjectNode) snapshot.path("rooms").get(0)).remove("emptySince"); mapper.writeValue(file().toFile(), snapshot);
        clock.now += 86_400_000; long startup = clock.now; YmService restored = service();
        assertThat(restored.list()).hasSize(1); tick(restored, startup + 59_999); assertThat(restored.state(host.roomId()).emptySince).isNull();
        tick(restored, startup + 60_000); assertThat(restored.state(host.roomId()).emptySince).isEqualTo(startup + 60_000);
        clock.now++; assertThat(restored.resume(host.roomId(), host.token())).isEqualTo(host); assertThat(restored.state(host.roomId()).emptySince).isNull();
    }

    @Test void leaveReceiptRemainsIdempotentAfterReuseExpiryAndRestartWithoutReturningOldSeat() {
        YmService service = service(); Identity first = service.create("退出回执", "甲"); long version = view(service, first).version();
        service.action(first.roomId(), first.playerId(), first.token(), version, "leave-once", "LEAVE", List.of());
        Identity second = service.join(first.roomId(), "乙"); long reusedVersion = view(service, second).version();
        assertThat(service.action(first.roomId(), first.playerId(), first.token(), -1, "leave-once", "LEAVE", List.of())).isNull();
        assertThat(view(service, second).version()).isEqualTo(reusedVersion); assertThat(service.state(first.roomId()).hostId).isEqualTo(second.playerId());
        action(service, second, "LEAVE"); tick(service, clock.now + 600_000); YmService restored = service();
        assertThat(restored.action(first.roomId(), first.playerId(), first.token(), version, "leave-once", "LEAVE", List.of())).isNull();
        assertThatThrownBy(() -> restored.resume(first.roomId(), first.token())).isInstanceOf(NoSuchElementException.class);
    }

    @Test void failedExpiryCommitRollsBackRoomTimerAndDoesNotCloseSseUntilSuccessfulCommit() throws Exception {
        YmService service = service(); Identity host = service.create("回收事务", "甲"); service.stream(host.roomId(), host.playerId(), host.token()); assertThat(notice().type()).isEqualTo("READY");
        long start = clock.now; tick(service, start + 60_000); notifications.clear();
        blockFile(); clock.now = start + 660_000;
        assertThatThrownBy(service::tick).hasMessageContaining("未生效");
        assertThat(notifications.poll(150, TimeUnit.MILLISECONDS)).isNull();
        clock.now = start + 60_000; assertThat(service.state(host.roomId()).emptySince).isEqualTo(start + 60_000); assertThat(service.list()).hasSize(1);
        unblockFile(); tick(service, start + 660_000); assertThat(service.list()).isEmpty(); assertThat(notice().type()).isEqualTo("CLOSED");
        assertThat(mapper.readTree(file().toFile()).path("rooms")).isEmpty();
    }

    @Test void failedReconnectCommitDoesNotClearDurableTimerAndCanBeRetried() throws Exception {
        YmService service = service(); Identity host = service.create("重连事务", "甲"); tick(service, clock.now + 60_000); long empty = service.state(host.roomId()).emptySince;
        blockFile(); clock.now += 100_000;
        assertThatThrownBy(() -> service.resume(host.roomId(), host.token())).hasMessageContaining("未生效");
        assertThat(service.state(host.roomId()).emptySince).isEqualTo(empty);
        unblockFile(); assertThat(service.resume(host.roomId(), host.token())).isEqualTo(host); assertThat(service.state(host.roomId()).emptySince).isNull();
    }

    @Test void expiryArchivesOnlyCompletedHandAndKeepsHistoricResultAndPlayerTokenAuthorization() throws Exception {
        YmService service = service(); List<Identity> ids = started(service); Identity first = ids.getFirst(); YmRoom room = service.state(first.roomId());
        Identity dealer = ids.stream().filter(id -> id.playerId().equals(room.seat(room.currentSeat).id)).findFirst().orElseThrow();
        room.seat(0).discards.addAll(room.wall); room.wall.clear(); action(service, dealer, "DRAW");
        YmReplay.HandRecord completed = service.replay(room.id, 1, first.playerId(), first.token());
        ids.forEach(id -> action(service, id, "ACK")); assertThat(room.round).isEqualTo(2); assertThat(room.activeReplay).isNotNull();
        ids.forEach(id -> action(service, id, "LEAVE")); assertThat(service.list()).hasSize(1);
        tick(service, clock.now + 600_000); assertThat(service.list()).isEmpty();
        YmService restored = service();
        for (Identity id : ids) {
            assertThat(restored.replay(room.id, 1, id.playerId(), id.token())).isEqualTo(completed);
            assertThat(restored.replays(room.id, id.playerId(), id.token()).hands()).hasSize(1);
            assertThatThrownBy(() -> restored.replay(room.id, 2, id.playerId(), id.token())).isInstanceOf(NoSuchElementException.class);
        }
        assertThatThrownBy(() -> restored.replay(room.id, 1, first.playerId(), ids.get(1).token())).hasMessageContaining("牌谱身份无效");
        String stored = Files.readString(file()); ids.forEach(id -> assertThat(stored).doesNotContain(id.token()));
    }

    private static final class MutableClock extends Clock {
        long now;
        MutableClock(long now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
}
