package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.assertj.core.api.Assertions.*;

class YmFeaturesTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-07T14:00:00Z"), ZoneOffset.UTC);
    private final List<YmService> services = new ArrayList<>();
    private final BlockingQueue<YmPush.Notice> notifications = new LinkedBlockingQueue<>();
    private final YmPush push = new YmPush(() -> new SseEmitter(0L) {
        @Override public void send(SseEventBuilder event) {
            for (var item : event.build()) if (item.getData() instanceof YmPush.Notice notice) notifications.add(notice);
        }
    });
    private YmService service() {
        YmService service = new YmService(mapper, directory.resolve("rooms.json"), new Random(31), clock, push);
        services.add(service); return service;
    }
    @AfterEach void close() { services.forEach(YmService::closeStreams); push.close(); }
    private RoomView view(YmService s, Identity p) { return s.view(p.roomId(), p.playerId(), p.token()); }
    private RoomView act(YmService s, Identity p, String type) {
        return s.action(p.roomId(), p.playerId(), p.token(), view(s,p).version(), UUID.randomUUID().toString(), type, List.of());
    }
    private Identity[] started(YmService s) {
        Identity a=s.create("功能回归","甲"), b=s.join(a.roomId(),"乙"), c=s.join(a.roomId(),"丙");
        Identity[] ids={a,b,c}; for(Identity p:ids) act(s,p,"READY"); return ids;
    }
    private Identity dealer(YmService s, Identity[] ids) {
        String id=s.state(ids[0].roomId()).seat(s.state(ids[0].roomId()).currentSeat).id;
        return Arrays.stream(ids).filter(p->p.playerId().equals(id)).findFirst().orElseThrow();
    }
    private RoomView finish(YmService s, Identity[] ids) {
        s.state(ids[0].roomId()).wall.clear(); // Focused fixture for publication/transaction tests.
        return act(s,dealer(s,ids),"DRAW");
    }
    private void blockFile() throws IOException {
        Path path=directory.resolve("rooms.json"); Files.move(path,directory.resolve("saved.json"));
        Files.createDirectory(path); Files.writeString(path.resolve("blocker"),"fault fixture");
    }
    private void unblockFile() throws IOException {
        Path path=directory.resolve("rooms.json"); Files.delete(path.resolve("blocker")); Files.delete(path);
        Files.move(directory.resolve("saved.json"),path);
    }

    @Test void hintsArePrivateReadOnlyAndVersioned() {
        YmService s=service(); Identity[] ids=started(s); RoomView before=view(s,ids[0]);
        YmService.HintView hint=s.hints(ids[0].roomId(),ids[0].playerId(),ids[0].token());
        assertThat(hint.roomId()).isEqualTo(before.id()); assertThat(hint.playerId()).isEqualTo(before.meId());
        assertThat(hint.version()).isEqualTo(before.version()); assertThat(hint.analysis().mode()).isEqualTo("WAIT");
        assertThat(view(s,ids[0])).isEqualTo(before);
        assertThatThrownBy(()->s.hints(ids[0].roomId(),ids[1].playerId(),ids[0].token())).hasMessageContaining("身份无效");
    }
    @Test void liveHandsAreNeverListedOrPublishedAsReplays() throws Exception {
        YmService s=service(); Identity[] ids=started(s); Identity p=ids[0];
        assertThat(s.replays(p.roomId(),p.playerId(),p.token()).hands()).isEmpty();
        assertThatThrownBy(()->s.replay(p.roomId(),1,p.playerId(),p.token())).isInstanceOf(NoSuchElementException.class);
        String json=mapper.writeValueAsString(view(s,p));
        assertThat(json).doesNotContain("activeReplay","replayHands","frames");
        for(Identity id:ids) assertThat(json).doesNotContain(id.token());
    }
    @Test void completedReplayMatchesResultAndContainsNoSecretsOrWallOrder() throws Exception {
        YmService s=service(); Identity[] ids=started(s); RoomView end=finish(s,ids); Identity p=ids[0];
        var list=s.replays(p.roomId(),p.playerId(),p.token()); assertThat(list.hands()).hasSize(1);
        var replay=s.replay(p.roomId(),1,p.playerId(),p.token());
        assertThat(replay.complete()).isTrue(); assertThat(replay.incomplete()).isFalse();
        assertThat(replay.frames().getFirst().type()).isEqualTo("START");
        assertThat(replay.result()).isEqualTo(end.result()); assertThat(replay.frames().getLast().result()).isEqualTo(end.result());
        for(Identity id:ids) assertThat(s.replay(id.roomId(),1,id.playerId(),id.token())).isEqualTo(replay);
        String json=mapper.writeValueAsString(replay);
        assertThat(json).doesNotContain("token","processed","lastSeen","\"wall\"","passedCodes");
        for(Identity id:ids) assertThat(json).doesNotContain(id.token());
    }
    @Test void replayAuthRejectsCrossRoomAndCrossPlayerTokens() {
        YmService s=service(); Identity[] ids=started(s); finish(s,ids);
        Identity stranger=s.create("隔壁","丁");
        assertThatThrownBy(()->s.replays(ids[0].roomId(),stranger.playerId(),stranger.token())).hasMessageContaining("牌谱身份无效");
        assertThatThrownBy(()->s.replay(ids[0].roomId(),1,ids[1].playerId(),ids[0].token())).hasMessageContaining("牌谱身份无效");
    }
    @Test void formerPlayersCanReplayAfterAllLeaveAndRestartButCannotResumeSeat() throws Exception {
        YmService s=service(); Identity[] ids=started(s); finish(s,ids); Identity p=ids[0];
        var expected=s.replay(p.roomId(),1,p.playerId(),p.token());
        act(s,p,"LEAVE"); assertThat(s.replay(p.roomId(),1,p.playerId(),p.token())).isEqualTo(expected);
        for(int i=1;i<ids.length;i++)act(s,ids[i],"LEAVE");
        assertThat(s.list()).hasSize(1);
        s.advance(clock.millis()+YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS);
        assertThat(s.list()).isEmpty();
        String disk=Files.readString(directory.resolve("rooms.json"));
        for(Identity id:ids) assertThat(disk).doesNotContain(id.token()); // Only hashes remain after room removal.
        YmService restored=service();
        assertThat(restored.replay(p.roomId(),1,p.playerId(),p.token())).isEqualTo(expected);
        assertThatThrownBy(()->view(restored,p)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(()->restored.replay(p.roomId(),1,p.playerId(),"wrong")).hasMessageContaining("牌谱身份无效");
    }
    @Test void failedCompletionCommitDoesNotPublishReplayAndRollsBackFrames() throws Exception {
        YmService s=service(); Identity[] ids=started(s); Identity current=dealer(s,ids);
        int original=s.state(current.roomId()).activeReplay.frames().size(); blockFile();
        assertThatThrownBy(()->finish(s,ids)).hasMessageContaining("未生效");
        assertThat(s.replays(current.roomId(),current.playerId(),current.token()).hands()).isEmpty();
        assertThat(s.state(current.roomId()).activeReplay.frames()).hasSize(original);
        assertThat(s.state(current.roomId()).wall).hasSize(69);
        unblockFile(); finish(s,ids);
        assertThat(s.replays(current.roomId(),current.playerId(),current.token()).hands()).hasSize(1);
    }
    @Test void failedLastLeaveCommitDoesNotLoseRoomOrArchive() throws Exception {
        YmService s=service(); Identity[] ids=started(s); finish(s,ids);
        act(s,ids[0],"LEAVE");act(s,ids[1],"LEAVE");blockFile();
        assertThatThrownBy(()->act(s,ids[2],"LEAVE")).hasMessageContaining("未生效");
        assertThat(view(s,ids[2]).result()).isNotNull();
        assertThat(s.replays(ids[0].roomId(),ids[0].playerId(),ids[0].token()).hands()).hasSize(1);
        unblockFile();act(s,ids[2],"LEAVE");
        assertThat(s.list()).hasSize(1);
        s.advance(clock.millis()+YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS);
        assertThat(s.list()).isEmpty();
        assertThat(s.replays(ids[0].roomId(),ids[0].playerId(),ids[0].token()).hands()).hasSize(1);
    }
    @Test void legacySnapshotWithoutReplayArchiveStillLoadsAndDoesNotInventOldResult() throws Exception {
        YmService s=service(); Identity[] ids=started(s);finish(s,ids);
        var tree=mapper.readTree(directory.resolve("rooms.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("replayArchives");
        for(var room:tree.path("rooms")) { var object=(com.fasterxml.jackson.databind.node.ObjectNode)room;object.remove("activeReplay");object.remove("replayHands"); }
        mapper.writeValue(directory.resolve("rooms.json").toFile(),tree);
        YmService restored=service();assertThat(view(restored,ids[0]).result()).isNotNull();
        assertThat(restored.replays(ids[0].roomId(),ids[0].playerId(),ids[0].token()).hands()).isEmpty();
    }
    @Test void streamRejectsWrongIdentityAndLimitsPerPlayer() throws Exception {
        YmService s=service();Identity a=s.create("推送","甲");
        assertThatThrownBy(()->s.stream(a.roomId(),a.playerId(),"wrong")).hasMessageContaining("身份无效");
        assertThat(push.size()).isZero();
        for(int i=0;i<3;i++)s.stream(a.roomId(),a.playerId(),a.token());
        assertThatThrownBy(()->s.stream(a.roomId(),a.playerId(),a.token())).hasMessageContaining("连接过多");
        for(int i=0;i<3;i++)assertThat(notifications.poll(2,TimeUnit.SECONDS).type()).isEqualTo("READY");
        s.closeStreams();assertThat(push.size()).isZero();
    }
    @Test void archiveCapEvictionIsAtomicAndRetainedAcrossRestart() throws Exception {
        YmService s=service();List<Identity> previous=new ArrayList<>();
        for(int i=0;i<20;i++) {
            Identity[] ids=started(s);finish(s,ids);previous.add(ids[0]);
            for(Identity id:ids)act(s,id,"LEAVE");
            s.advance(clock.millis()+YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS);
        }
        Identity first=previous.getFirst();Identity[] newest=started(s);finish(s,newest);
        act(s,newest[0],"LEAVE");act(s,newest[1],"LEAVE");act(s,newest[2],"LEAVE");blockFile();
        assertThatThrownBy(()->s.advance(clock.millis()+YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS)).hasMessageContaining("未生效");
        assertThat(s.replays(first.roomId(),first.playerId(),first.token()).hands()).hasSize(1);
        unblockFile();s.advance(clock.millis()+YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS);
        assertThatThrownBy(()->s.replays(first.roomId(),first.playerId(),first.token())).isInstanceOf(NoSuchElementException.class);
        assertThat(mapper.readTree(directory.resolve("rooms.json").toFile()).path("replayArchives").size()).isEqualTo(20);
        YmService restored=service();Identity second=previous.get(1);
        assertThat(restored.replays(second.roomId(),second.playerId(),second.token()).hands()).hasSize(1);
        assertThat(restored.replays(newest[0].roomId(),newest[0].playerId(),newest[0].token()).hands()).hasSize(1);
    }
    @Test void streamSendsOnlyCommittedVersionsAndClosesRemovedSeat() throws Exception {
        YmService s=service();Identity a=s.create("推送","甲");s.stream(a.roomId(),a.playerId(),a.token());
        YmPush.Notice ready=notifications.poll(2,TimeUnit.SECONDS);assertThat(ready.type()).isEqualTo("READY");
        act(s,a,"READY");YmPush.Notice changed=notifications.poll(2,TimeUnit.SECONDS);
        assertThat(changed).isEqualTo(new YmPush.Notice(a.roomId(),view(s,a).version(),"CHANGED"));
        assertThat(mapper.writeValueAsString(changed)).doesNotContain(a.token(),a.playerId(),"hand","tile");
        blockFile();assertThatThrownBy(()->act(s,a,"READY")).hasMessageContaining("未生效");
        assertThat(notifications.poll(120,TimeUnit.MILLISECONDS)).isNull();
        unblockFile();act(s,a,"LEAVE");assertThat(notifications.poll(2,TimeUnit.SECONDS).type()).isEqualTo("CLOSED");
    }
}
