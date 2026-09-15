package com.mahjong.yaoming;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class YmPushTest {
    private final List<TestEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failNext = new AtomicBoolean();
    private final AtomicBoolean blockNext = new AtomicBoolean();
    private final YmPush push = new YmPush(() -> {
        TestEmitter emitter = new TestEmitter(failNext.getAndSet(false), blockNext.getAndSet(false));
        emitters.add(emitter);
        return emitter;
    });

    @AfterEach void close() {
        emitters.forEach(emitter -> emitter.unblock.countDown());
        push.close();
    }

    private TestEmitter open(String roomId, String playerId, long version) {
        return (TestEmitter) push.open(roomId, playerId, version);
    }

    private static YmRoom room(String id, long version, String... playerIds) {
        YmRoom room = new YmRoom();
        room.id = id;
        room.version = version;
        for (String playerId : playerIds) {
            YmRoom.Player player = new YmRoom.Player();
            player.id = playerId;
            room.players.add(player);
        }
        return room;
    }

    private static YmPush.Notice next(TestEmitter emitter) throws InterruptedException {
        YmPush.Notice notice = emitter.notices.poll(2, TimeUnit.SECONDS);
        assertThat(notice).as("expected an SSE notification within 2 seconds").isNotNull();
        return notice;
    }

    private static void ready(TestEmitter emitter, String roomId, long version) throws InterruptedException {
        assertThat(next(emitter)).isEqualTo(new YmPush.Notice(roomId, version, "READY"));
    }

    @Test void globalConnectionLimitRejects257thAndReleasedSlotCanBeReused() {
        for (int index = 0; index < YmPush.MAX_CONNECTIONS; index++) open("room", "player-" + index, 1);
        assertThat(push.size()).isEqualTo(256);
        assertThatThrownBy(() -> open("another-room", "another-player", 1)).hasMessageContaining("连接过多");
        assertThat(push.size()).isEqualTo(256);
        emitters.getFirst().clientCompletion();
        assertThat(push.size()).isEqualTo(255);
        open("another-room", "another-player", 1);
        assertThat(push.size()).isEqualTo(256);
    }

    @Test void failedSocketWriteReclaimsConnectionAndPerPlayerQuota() throws Exception {
        failNext.set(true);
        TestEmitter broken = open("room", "player", 1);
        assertThat(broken.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(push.size()).isZero();
        for (int index = 0; index < YmPush.MAX_PER_PLAYER; index++) ready(open("room", "player", 1), "room", 1);
        assertThat(push.size()).isEqualTo(3);
    }

    @Test void changedRoomDoesNotNotifyUnrelatedRoomAndKeepsOriginalPrivateSubscriptions() throws Exception {
        TestEmitter first = open("first", "alice", 1);
        TestEmitter second = open("second", "bob", 7);
        ready(first, "first", 1);
        ready(second, "second", 7);
        push.committed(List.of(room("first", 2, "alice"), room("second", 7, "bob")));
        assertThat(next(first)).isEqualTo(new YmPush.Notice("first", 2, "CHANGED"));
        assertThat(second.notices.poll(100, TimeUnit.MILLISECONDS)).isNull();
        push.committed(List.of(room("first", 2, "alice"), room("second", 8, "bob")));
        assertThat(next(second)).isEqualTo(new YmPush.Notice("second", 8, "CHANGED"));
        assertThat(first.notices.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test void unchangedAndOlderVersionsDoNotProduceChangeNotifications() throws Exception {
        TestEmitter emitter = open("room", "player", 5);
        ready(emitter, "room", 5);
        push.committed(List.of(room("room", 5, "player")));
        push.committed(List.of(room("room", 4, "player")));
        assertThat(emitter.notices.poll(100, TimeUnit.MILLISECONDS)).isNull();
        push.committed(List.of(room("room", 6, "player")));
        assertThat(next(emitter)).isEqualTo(new YmPush.Notice("room", 6, "CHANGED"));
        push.committed(List.of(room("room", 6, "player")));
        assertThat(emitter.notices.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test void clientCompletionReleasesConnectionImmediatelyAndIdempotently() throws Exception {
        TestEmitter emitter = open("room", "player", 1);
        ready(emitter, "room", 1);
        emitter.clientCompletion();
        emitter.clientCompletion();
        assertThat(push.size()).isZero();
        assertThat(emitter.completed.await(2, TimeUnit.SECONDS)).isTrue();
        push.committed(List.of(room("room", 2, "player")));
        assertThat(emitter.notices.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test void clientTimeoutReleasesConnectionAndAllowsNewSubscription() throws Exception {
        TestEmitter emitter = open("room", "player", 1);
        ready(emitter, "room", 1);
        emitter.clientTimeout();
        assertThat(push.size()).isZero();
        assertThat(emitter.completed.await(2, TimeUnit.SECONDS)).isTrue();
        ready(open("room", "player", 2), "room", 2);
        assertThat(push.size()).isEqualTo(1);
    }

    @Test void clientErrorReleasesConnectionWithoutWaitingForAnotherCommit() throws Exception {
        TestEmitter emitter = open("room", "player", 1);
        ready(emitter, "room", 1);
        emitter.clientError(new IOException("client disconnected"));
        assertThat(push.size()).isZero();
        assertThat(emitter.completed.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test void removedOrLeftPlayerGetsClosedAndCannotReceiveLaterVersions() throws Exception {
        TestEmitter first = open("room", "alice", 1);
        TestEmitter second = open("room", "bob", 1);
        ready(first, "room", 1);
        ready(second, "room", 1);
        YmRoom updated = room("room", 2, "alice", "bob");
        updated.players.getFirst().left = true;
        push.committed(List.of(updated));
        assertThat(next(first)).isEqualTo(new YmPush.Notice("room", 2, "CLOSED"));
        assertThat(next(second)).isEqualTo(new YmPush.Notice("room", 2, "CHANGED"));
        assertThat(first.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(push.size()).isEqualTo(1);
        push.committed(List.of(room("room", 3, "bob")));
        assertThat(next(second)).isEqualTo(new YmPush.Notice("room", 3, "CHANGED"));
        assertThat(first.notices.poll(100, TimeUnit.MILLISECONDS)).isNull();
        push.committed(List.of());
        assertThat(next(second)).isEqualTo(new YmPush.Notice("room", 3, "CLOSED"));
        assertThat(second.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(push.size()).isZero();
    }

    @Test void slowSocketCoalescesChangesWithoutBlockingOthersAndStillReceivesClosure() throws Exception {
        blockNext.set(true);
        TestEmitter slow = open("slow", "alice", 1);
        assertThat(slow.entered.await(2, TimeUnit.SECONDS)).isTrue();
        TestEmitter fast = open("fast", "bob", 1);
        ready(fast, "fast", 1);
        // The slow socket is still blocked in send. Commit notifications must only enqueue.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (long version = 2; version <= 50; version++)
                push.committed(List.of(room("slow", version, "alice"), room("fast", 1, "bob")));
            push.committed(List.of(room("slow", 51, "alice"), room("fast", 2, "bob")));
        });
        assertThat(next(fast)).isEqualTo(new YmPush.Notice("fast", 2, "CHANGED"));
        slow.unblock.countDown();
        ready(slow, "slow", 1);
        YmPush.Notice latest = next(slow);
        while (latest.version() < 51) latest = next(slow);
        assertThat(latest).isEqualTo(new YmPush.Notice("slow", 51, "CHANGED"));
        push.committed(List.of(room("fast", 2, "bob")));
        assertThat(next(slow).type()).isEqualTo("CLOSED");
        assertThat(slow.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(push.size()).isEqualTo(1);
    }

    @Test void shutdownClosesAllConnectionsAndRejectsNewOnes() throws Exception {
        TestEmitter emitter = open("room", "player", 1);
        ready(emitter, "room", 1);
        push.close();
        push.close();
        assertThat(push.size()).isZero();
        assertThat(emitter.completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> open("room", "player", 2)).hasMessageContaining("实时连接暂不可用");
    }

    private static final class TestEmitter extends SseEmitter {
        private final BlockingQueue<YmPush.Notice> notices = new LinkedBlockingQueue<>();
        private final CountDownLatch completed = new CountDownLatch(1);
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch unblock;
        private final boolean fail;
        private Runnable completionCallback;
        private Runnable timeoutCallback;
        private Consumer<Throwable> errorCallback;

        private TestEmitter(boolean fail, boolean block) {
            super(0L);
            this.fail = fail;
            this.unblock = new CountDownLatch(block ? 1 : 0);
        }
        @Override public void onCompletion(Runnable callback) { completionCallback = callback; }
        @Override public void onTimeout(Runnable callback) { timeoutCallback = callback; }
        @Override public void onError(Consumer<Throwable> callback) { errorCallback = callback; }
        @Override public void send(SseEventBuilder event) throws IOException {
            entered.countDown();
            if (fail) throw new IOException("broken socket fixture");
            try {
                if (!unblock.await(5, TimeUnit.SECONDS)) throw new IOException("slow socket fixture timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("writer interrupted", interrupted);
            }
            for (var item : event.build()) if (item.getData() instanceof YmPush.Notice notice) notices.add(notice);
        }
        @Override public void complete() {
            if (completionCallback != null) completionCallback.run();
            completed.countDown();
        }
        private void clientCompletion() { completionCallback.run(); }
        private void clientTimeout() { timeoutCallback.run(); }
        private void clientError(Throwable error) { errorCallback.accept(error); }
    }
}
