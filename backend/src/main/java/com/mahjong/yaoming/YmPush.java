package com.mahjong.yaoming;

import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Version-only notifications. Slow sockets never run under the room transaction lock. */
public final class YmPush implements AutoCloseable {
    static final int MAX_CONNECTIONS = 256, MAX_PER_PLAYER = 3;
    public record Notice(String roomId, long version, String type) {}
    private record Delivery(String event, Notice notice) {}
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();
    private final Supplier<SseEmitter> factory;
    private volatile boolean stopped;

    public YmPush() { this(() -> new SseEmitter(0L)); }
    YmPush(Supplier<SseEmitter> factory) { this.factory = factory; }

    // The service authenticates and subscribes while holding its room lock.
    public synchronized SseEmitter open(String roomId, String playerId, long version) {
        if (stopped) throw new IllegalStateException("实时连接暂不可用，请稍后重试");
        if (connections.size() >= MAX_CONNECTIONS || connections.stream()
                .filter(c -> c.roomId.equals(roomId) && c.playerId.equals(playerId)).count() >= MAX_PER_PLAYER)
            throw new IllegalArgumentException("实时连接过多，请关闭多余页面后重试");
        Connection connection = new Connection(roomId, playerId, version, factory.get());
        connections.add(connection);
        connection.emitter.onCompletion(connection::dispose);
        connection.emitter.onTimeout(connection::dispose);
        connection.emitter.onError(error -> connection.dispose());
        connection.enqueue("ready", "READY", version);
        writers.execute(connection::write);
        return connection.emitter;
    }

    /** Called only after the entire durable commit succeeds. No hand data is queued. */
    public void committed(Collection<YmRoom> rooms) {
        Map<String, YmRoom> current = new HashMap<>();
        rooms.forEach(room -> current.put(room.id, room));
        for (Connection connection : connections) {
            YmRoom room = current.get(connection.roomId);
            boolean authorized = room != null && room.players.stream()
                    .anyMatch(p -> !p.bot && !p.left && p.id.equals(connection.playerId));
            if (!authorized) connection.enqueue("closed", "CLOSED", room == null ? connection.version : room.version);
            else if (room.version > connection.version) connection.enqueue("room-change", "CHANGED", room.version);
        }
    }

    int size() { return connections.size(); }
    @Override @PreDestroy public synchronized void close() {
        stopped = true;
        for (Connection connection : List.copyOf(connections)) connection.dispose();
        writers.shutdownNow();
    }

    private final class Connection {
        final String roomId, playerId;
        final SseEmitter emitter;
        final BlockingQueue<Delivery> queue = new ArrayBlockingQueue<>(16);
        final AtomicBoolean alive = new AtomicBoolean(true);
        volatile long version;
        volatile boolean closing;
        Connection(String roomId, String playerId, long version, SseEmitter emitter) {
            this.roomId = roomId; this.playerId = playerId; this.version = version; this.emitter = emitter;
        }
        void enqueue(String event, String type, long nextVersion) {
            if (!alive.get() || closing) return;
            version = nextVersion;
            if (type.equals("CLOSED")) closing = true;
            Delivery delivery = new Delivery(event, new Notice(roomId, nextVersion, type));
            // Events are invalidations, not the authoritative game log. Coalesce a slow reader.
            if (!queue.offer(delivery)) { queue.clear(); queue.offer(delivery); }
        }
        void write() {
            try {
                while (alive.get()) {
                    Delivery delivery = queue.poll(15, TimeUnit.SECONDS);
                    if (delivery == null) delivery = new Delivery("ping", new Notice(roomId, version, "PING"));
                    if (!alive.get()) break;
                    emitter.send(SseEmitter.event().name(delivery.event()).data(delivery.notice(), MediaType.APPLICATION_JSON));
                    if (delivery.notice().type().equals("CLOSED")) break;
                }
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            catch (IOException | IllegalStateException error) { /* Disconnected clients reconnect or poll. */ }
            finally { dispose(); }
        }
        void dispose() {
            if (!alive.compareAndSet(true, false)) return;
            connections.remove(this);
            queue.offer(new Delivery("closed", new Notice(roomId, version, "CLOSED")));
            try { emitter.complete(); } catch (IllegalStateException ignored) { }
        }
    }
}
