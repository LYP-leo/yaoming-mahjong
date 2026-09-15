package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.mahjong.yaoming.YmViews.*;

class YmServiceTest {
    @TempDir Path directory;
    private YmService service() { return new YmService(new ObjectMapper().findAndRegisterModules(), directory.resolve("rooms.json"), new Random(31)); }
    private RoomView view(YmService service, Identity id) { return service.view(id.roomId(), id.playerId(), id.token()); }
    private RoomView action(YmService service, Identity id, String type) {
        return service.action(id.roomId(), id.playerId(), id.token(), view(service, id).version(), UUID.randomUUID().toString(), type, List.of());
    }
    private Identity[] started(YmService s) {
        Identity a = s.create("要命测试", "甲"), b = s.join(a.roomId(), "乙"), c = s.join(a.roomId(), "丙");
        for (Identity p : List.of(a, b, c)) action(s, p, "READY");
        return new Identity[]{a,b,c};
    }

    @Test void catalogContainsOnlyTwentyFansAndTwentySevenTileTypes() {
        Rules rules = service().rules();
        assertThat(rules.fans()).hasSize(20); assertThat(rules.tiles()).hasSize(27);
        assertThat(rules.tiles()).extracting(YmTiles::code).doesNotContain("H4", "W2", "W8");
    }
    @Test void authenticatesPrivateViewAndNeverExposesOpponentHandOrTokens() throws Exception {
        YmService s = service(); Identity[] ids = started(s); RoomView a = view(s, ids[0]);
        assertThat(a.players().stream().filter(p -> p.id().equals(ids[0].playerId())).findFirst().orElseThrow().hand()).hasSize(13);
        assertThat(a.players().stream().filter(p -> !p.id().equals(ids[0].playerId())).allMatch(p -> p.hand().isEmpty() && p.handSize() == 13)).isTrue();
        String json = new ObjectMapper().writeValueAsString(a);
        for (Identity id : ids) assertThat(json).doesNotContain(id.token());
        assertThatThrownBy(() -> s.view(ids[0].roomId(), ids[1].playerId(), ids[0].token())).hasMessageContaining("身份无效");
    }
    @Test void staleAndDuplicateCommandsDoNotToggleReadyTwice() {
        YmService s = service(); Identity a = s.create("桌", "甲"); long revision = view(s,a).version();
        s.action(a.roomId(),a.playerId(),a.token(),revision,"same-command","READY",List.of());
        RoomView repeated = s.action(a.roomId(),a.playerId(),a.token(),revision,"same-command","READY",List.of());
        assertThat(repeated.players().getFirst().ready()).isTrue(); assertThat(repeated.version()).isEqualTo(revision + 1);
        assertThatThrownBy(() -> s.action(a.roomId(),a.playerId(),a.token(),revision,"other","READY",List.of())).isInstanceOf(YmService.VersionConflict.class);
        assertThatThrownBy(() -> s.action(a.roomId(),a.playerId(),a.token(),repeated.version(),"same-command","ADD_BOT",List.of())).hasMessageContaining("已用于其他操作");
    }
    @Test void robotWaitsWithoutTogglingReadyAndSingleHumanCanStart() {
        YmService s = service(); Identity a = s.create("机器人", "甲"); action(s,a,"ADD_BOT");
        long revision = view(s,a).version();
        for(int i=0;i<8;i++) s.advance(System.currentTimeMillis() + i * 2000);
        assertThat(view(s,a).version()).isEqualTo(revision);
        assertThat(view(s,a).players().stream().filter(PlayerView::bot)).allMatch(PlayerView::ready);
        action(s,a,"READY"); action(s,a,"ADD_BOT");
        assertThat(view(s,a).status()).isEqualTo("NEED_DRAW");
    }
    @Test void onlyHostCanAddBotAndFullOrPlayingRoomRejectsJoin() {
        YmService s=service(); Identity a=s.create("桌","甲"), b=s.join(a.roomId(),"乙");
        assertThatThrownBy(() -> action(s,b,"ADD_BOT")).hasMessageContaining("只有房主");
        action(s,a,"ADD_BOT"); assertThatThrownBy(() -> s.join(a.roomId(),"丙")).hasMessageContaining("不能加入");
    }
    @Test void atomicSnapshotRestoresSameTilesPhaseAndPrivateIdentity() {
        YmService s=service(); Identity[] ids=started(s); YmRoom r=s.state(ids[0].roomId());
        Identity dealer=Arrays.stream(ids).filter(i -> i.playerId().equals(r.seat(r.dealerSeat).id)).findFirst().orElseThrow();
        RoomView before=action(s,dealer,"DRAW");
        YmService restored=service(); RoomView after=view(restored,dealer);
        assertThat(after.version()).isEqualTo(before.version()); assertThat(after.status()).isEqualTo("NEED_DISCARD");
        assertThat(after.wallCount()).isEqualTo(68);
        assertThat(after.players().stream().filter(p -> p.id().equals(dealer.playerId())).findFirst().orElseThrow().hand())
                .isEqualTo(before.players().stream().filter(p -> p.id().equals(dealer.playerId())).findFirst().orElseThrow().hand());
        assertThat(restored.resume(dealer.roomId(),dealer.token())).isEqualTo(dealer);
    }
    @Test void leavingInGamePreservesMatchAndHandsAndUsesRobotTakeover() {
        YmService s=service(); Identity[] ids=started(s); YmRoom r=s.state(ids[0].roomId());
        Identity current=Arrays.stream(ids).filter(i -> i.playerId().equals(r.seat(r.currentSeat).id)).findFirst().orElseThrow();
        int before=r.wall.size(); action(s,current,"LEAVE");
        assertThat(s.state(current.roomId()).phase).isEqualTo(YmRoom.Phase.NEED_DRAW);
        s.advance(System.currentTimeMillis()+2000);
        assertThat(r.phase).isEqualTo(YmRoom.Phase.NEED_DISCARD); assertThat(r.wall).hasSize(before-1);
        assertThatThrownBy(() -> view(s,current)).hasMessageContaining("身份无效");
        for(Identity p:ids) if(!p.equals(current)) action(s,p,"LEAVE");
        assertThat(s.list()).hasSize(1);
        s.advance(System.currentTimeMillis()+YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS+1);
        assertThat(s.list()).isEmpty();
    }
    @Test void matchResultSurvivesOtherPlayerExitAndRestart() {
        YmService s=service(); Identity[] ids=started(s); YmRoom r=s.state(ids[0].roomId());
        r.phase=YmRoom.Phase.MATCH_END;
        r.result=new Result(true,true,"南三流局",null,null,0,0,List.of(),List.of(),
                r.players.stream().map(p->new Score(p.id,p.name,p.score,0,p.seat+1)).toList(),List.of(),"南三结束");
        action(s,ids[0],"LEAVE"); RoomView before=view(s,ids[1]);
        assertThat(before.result().scores()).hasSize(3);
        YmService restored=service(); assertThat(view(restored,ids[1]).result()).isEqualTo(before.result());
        action(restored,ids[1],"LEAVE"); assertThat(view(restored,ids[2]).status()).isEqualTo("MATCH_END");
        action(restored,ids[2],"LEAVE"); assertThat(restored.list()).hasSize(1);
        restored.advance(System.currentTimeMillis()+YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS+1);
        assertThat(restored.list()).isEmpty();
    }
    @Test void roomsHaveIndependentPhasesAndVersions() {
        YmService s=service(); Identity[] ids=started(s); Identity other=s.create("另一桌","另一甲");
        action(s,other,"ADD_BOT"); assertThat(view(s,ids[0]).players()).hasSize(3);
        assertThat(view(s,ids[0]).status()).isEqualTo("NEED_DRAW"); assertThat(view(s,other).status()).isEqualTo("WAITING");
    }
    @Test void failedDiskCommitRollsBackMemoryAndSameRequestCanRetry() throws Exception {
        YmService s=service(); Identity a=s.create("桌","甲"); long revision=view(s,a).version();
        Path file=directory.resolve("rooms.json"), backup=directory.resolve("saved.json");
        Files.move(file,backup); Files.createDirectory(file); Files.writeString(file.resolve("blocker"),"test");
        assertThatThrownBy(() -> s.action(a.roomId(),a.playerId(),a.token(),revision,"retry-after-disk","READY",List.of())).hasMessageContaining("未生效");
        assertThat(view(s,a).version()).isEqualTo(revision); assertThat(view(s,a).players().getFirst().ready()).isFalse();
        Files.delete(file.resolve("blocker")); Files.delete(file); Files.move(backup,file);
        s.action(a.roomId(),a.playerId(),a.token(),revision,"retry-after-disk","READY",List.of());
        assertThat(view(service(),a).players().getFirst().ready()).isTrue();
    }
    @Test void disconnectedPlayersAutoTrusteeAfterGraceAndRegainControlOnReturn() {
        YmService s=service(); Identity[] ids=started(s); YmRoom r=s.state(ids[0].roomId());
        // Exercise OFFLINE separately from the earlier authoritative action timeout.
        r.deadlineAt=System.currentTimeMillis()+120000;
        s.advance(System.currentTimeMillis()+65000);
        assertThat(r.players).allMatch(p -> p.autoTrustee && p.trustee);
        RoomView returned=view(s,ids[0]);
        assertThat(returned.players().stream().filter(p->p.id().equals(ids[0].playerId())).findFirst().orElseThrow().trustee()).isFalse();
    }
    @Test void actionChecksReactionDeadlineWithoutWaitingForScheduler() {
        YmService s=service(); Identity[] ids=started(s); YmRoom r=s.state(ids[0].roomId());
        YmRoom.Player responder=r.players.stream().filter(p->p.id.equals(ids[0].playerId())).findFirst().orElseThrow();
        r.phase=YmRoom.Phase.REACTION; r.currentSeat=-1; r.window=new YmRoom.Window();
        r.window.tile=YmTiles.of("B2"); r.window.fromSeat=(responder.seat+2)%3; r.window.deadline=System.currentTimeMillis()-1;
        r.window.offered.put(responder.seat,List.of(Action.of("PASS","过")));
        long version=r.version;
        assertThatThrownBy(() -> s.action(r.id,responder.id,responder.token,version,"late","PASS",List.of())).isInstanceOf(YmService.VersionConflict.class);
        assertThat(s.state(r.id).phase).isEqualTo(YmRoom.Phase.NEED_DRAW); assertThat(s.state(r.id).version).isGreaterThan(version);
    }
    @Test void allTrusteeGameFinishesAndConservesTilesAndPoints() {
        YmService s=service(); Identity[] ids=started(s); for(Identity id:ids) action(s,id,"TRUSTEE");
        YmRoom r=s.state(ids[0].roomId()); long now=System.currentTimeMillis();
        int iterations=0;
        while(r.phase!=YmRoom.Phase.MATCH_END && iterations++<2000) {
            // These are connected humans choosing trustee, not an abandoned room.
            for (YmRoom.Player p : r.players) p.lastSeen = now + 2000;
            s.advance(now+=2000);
            List<String> tiles=new ArrayList<>(); r.wall.forEach(t->tiles.add(t.id()));
            for(YmRoom.Player p:r.players){p.hand.forEach(t->tiles.add(t.id()));p.discards.forEach(t->tiles.add(t.id()));p.melds.forEach(m->m.tiles().forEach(t->tiles.add(t.id())));}
            assertThat(tiles).hasSize(108).doesNotHaveDuplicates();
            assertThat(r.players.stream().mapToInt(p->p.score).sum()).isEqualTo(30);
            assertThat(r.players).allMatch(p->p.score>=0);
        }
        assertThat(r.phase).isEqualTo(YmRoom.Phase.MATCH_END); assertThat(r.result).isNotNull();
    }

    @Test void leaveRetryReturnsNullWithoutChangingRemainingPlayersRoom() {
        YmService s=service(); Identity a=s.create("退出重试","甲"), b=s.join(a.roomId(),"乙");
        long revision=view(s,a).version(); String request="leave-retry";
        assertThat(s.action(a.roomId(),a.playerId(),a.token(),revision,request,"LEAVE",List.of())).isNull();
        RoomView remaining=view(s,b);
        assertThat(s.action(a.roomId(),a.playerId(),a.token(),revision,request,"LEAVE",List.of())).isNull();
        assertThat(view(s,b).version()).isEqualTo(remaining.version());
        assertThat(view(s,b).players()).hasSize(1);
        assertThatThrownBy(() -> view(s,a)).hasMessageContaining("身份无效");
    }

    @Test void inGameLeaveRetryDoesNotAdvanceTakeoverOrMutateVersion() {
        YmService s=service(); Identity[] ids=started(s); Identity a=ids[0]; long revision=view(s,a).version();
        s.action(a.roomId(),a.playerId(),a.token(),revision,"leave-playing","LEAVE",List.of());
        RoomView remaining=view(s,ids[1]);
        assertThat(s.action(a.roomId(),a.playerId(),a.token(),revision,"leave-playing","LEAVE",List.of())).isNull();
        assertThat(view(s,ids[1]).version()).isEqualTo(remaining.version());
        assertThat(view(s,ids[1]).wallCount()).isEqualTo(remaining.wallCount());
        assertThat(s.state(a.roomId()).players.stream().filter(p -> p.id.equals(a.playerId())).findFirst().orElseThrow().left).isTrue();
    }

    @Test void lastHumanLeaveRetrySucceedsAfterRoomDeletionAndServerRestart() throws Exception {
        YmService s=service(); Identity a=s.create("最后退出","甲"); long revision=view(s,a).version();
        assertThat(s.action(a.roomId(),a.playerId(),a.token(),revision,"last-leave","LEAVE",List.of())).isNull();
        assertThat(s.list()).hasSize(1);
        assertThat(s.action(a.roomId(),a.playerId(),a.token(),revision,"last-leave","LEAVE",List.of())).isNull();
        s.advance(System.currentTimeMillis()+YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS+1);
        assertThat(s.list()).isEmpty();
        YmService restored=service(); assertThat(restored.list()).isEmpty();
        assertThat(restored.action(a.roomId(),a.playerId(),a.token(),revision,"last-leave","LEAVE",List.of())).isNull();
        String saved=Files.readString(directory.resolve("rooms.json"));
        assertThat(saved).contains("leaveReceipts", "last-leave").doesNotContain(a.token());
    }

    @Test void roomWithRemainingHumanAlsoRestoresExitReceiptAndNeverReturnsItInViews() throws Exception {
        YmService s=service(); Identity a=s.create("保留房间","甲"), b=s.join(a.roomId(),"乙"); long revision=view(s,a).version();
        s.action(a.roomId(),a.playerId(),a.token(),revision,"restart-leave","LEAVE",List.of());
        YmService restored=service(); long remainingRevision=view(restored,b).version();
        assertThat(restored.action(a.roomId(),a.playerId(),a.token(),revision,"restart-leave","LEAVE",List.of())).isNull();
        assertThat(view(restored,b).version()).isEqualTo(remainingRevision);
        String json=new ObjectMapper().writeValueAsString(view(restored,b));
        assertThat(json).doesNotContain("leaveReceipts", "tokenHash", a.token(), b.token());
    }

    @Test void leaveReceiptOnlyAcceptsExactIdentityRequestAndEmptyTileParameters() {
        YmService s=service(); Identity a=s.create("验证回执","甲"), b=s.join(a.roomId(),"乙"); long revision=view(s,a).version();
        s.action(a.roomId(),a.playerId(),a.token(),revision,"exact-leave","LEAVE",List.of());
        assertThatThrownBy(() -> s.action(a.roomId(),a.playerId(),"wrong",revision,"exact-leave","LEAVE",List.of())).hasMessageContaining("身份无效");
        assertThatThrownBy(() -> s.action(a.roomId(),a.playerId(),null,revision,"exact-leave","LEAVE",List.of())).hasMessageContaining("身份无效");
        assertThatThrownBy(() -> s.action(a.roomId(),a.playerId(),a.token(),revision,"other-request","LEAVE",List.of())).hasMessageContaining("身份无效");
        assertThatThrownBy(() -> s.action(a.roomId(),b.playerId(),a.token(),revision,"exact-leave","LEAVE",List.of())).hasMessageContaining("身份无效");
        assertThatThrownBy(() -> s.action(a.roomId(),a.playerId(),a.token(),revision,"exact-leave","READY",List.of())).hasMessageContaining("身份无效");
        assertThatThrownBy(() -> s.action(a.roomId(),a.playerId(),a.token(),revision,"exact-leave","LEAVE",List.of("tile"))).hasMessageContaining("不接受牌张参数");
        assertThatThrownBy(() -> s.action("not-this-room",a.playerId(),a.token(),revision,"exact-leave","LEAVE",List.of())).hasMessageContaining("不存在");
        assertThat(s.action(a.roomId(),a.playerId(),a.token(),-999,"exact-leave","LEAVE",null)).isNull();
        assertThat(view(s,b).players()).hasSize(1);
    }

    @Test void failedLeaveCommitRollsBackRoomAndReceiptSoSameRequestMustReallyRetry() throws Exception {
        YmService s=service(); Identity a=s.create("退出落盘失败","甲"); long revision=view(s,a).version();
        Path file=directory.resolve("rooms.json"), backup=directory.resolve("before-leave.json");
        Files.move(file,backup); Files.createDirectory(file); Files.writeString(file.resolve("blocker"),"test");
        for(int attempt=0;attempt<2;attempt++) {
            assertThatThrownBy(() -> s.action(a.roomId(),a.playerId(),a.token(),revision,"failed-leave","LEAVE",List.of())).hasMessageContaining("未生效");
            assertThat(view(s,a).version()).isEqualTo(revision); assertThat(s.list()).hasSize(1);
        }
        Files.delete(file.resolve("blocker")); Files.delete(file); Files.move(backup,file);
        assertThat(s.action(a.roomId(),a.playerId(),a.token(),revision,"failed-leave","LEAVE",List.of())).isNull();
        assertThat(s.list()).hasSize(1);
        s.advance(System.currentTimeMillis()+YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS+1);
        assertThat(s.list()).isEmpty();
        assertThat(service().action(a.roomId(),a.playerId(),a.token(),revision,"failed-leave","LEAVE",List.of())).isNull();
    }

    @Test void legacyArraySnapshotLoadsAndUpgradesOnNextSuccessfulWrite() throws Exception {
        YmService s=service(); Identity a=s.create("旧版快照","甲");
        ObjectMapper mapper=new ObjectMapper().findAndRegisterModules(); Path file=directory.resolve("rooms.json");
        mapper.writeValue(file.toFile(),List.of(s.state(a.roomId())));
        YmService restored=service(); assertThat(view(restored,a).name()).isEqualTo("旧版快照");
        action(restored,a,"READY");
        assertThat(mapper.readTree(file.toFile()).path("rooms").isArray()).isTrue();
        assertThat(mapper.readTree(file.toFile()).path("leaveReceipts").isArray()).isTrue();
        assertThat(view(service(),a).players().getFirst().ready()).isTrue();
    }

    @Test void expiredReceiptIsRejectedAfterRestartAndSchedulerReclaimsIt() throws Exception {
        YmService s=service(); Identity a=s.create("回执过期","甲"); long revision=view(s,a).version();
        s.action(a.roomId(),a.playerId(),a.token(),revision,"expires","LEAVE",List.of());
        s.advance(System.currentTimeMillis()+YmService.LEAVE_RECEIPT_TTL_MS+1);
        assertThatThrownBy(() -> s.action(a.roomId(),a.playerId(),a.token(),revision,"expires","LEAVE",List.of())).hasMessageContaining("不存在");
        ObjectMapper mapper=new ObjectMapper();
        assertThat(mapper.readTree(directory.resolve("rooms.json").toFile()).path("leaveReceipts").size()).isZero();
        assertThatThrownBy(() -> service().action(a.roomId(),a.playerId(),a.token(),revision,"expires","LEAVE",List.of())).hasMessageContaining("不存在");
    }

    @Test void restoredReceiptCollectionIsBoundedAndExpiredEntriesAreDiscarded() throws Exception {
        YmService s=service(); Identity a=s.create("回执上限","甲"); long revision=view(s,a).version();
        s.action(a.roomId(),a.playerId(),a.token(),revision,"original","LEAVE",List.of());
        s.advance(System.currentTimeMillis()+YmService.DEFAULT_EMPTY_ROOM_RETENTION_MS+1);
        ObjectMapper mapper=new ObjectMapper(); Path file=directory.resolve("rooms.json");
        com.fasterxml.jackson.databind.node.ObjectNode root=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(file.toFile());
        com.fasterxml.jackson.databind.node.ObjectNode original=(com.fasterxml.jackson.databind.node.ObjectNode)root.path("leaveReceipts").get(0);
        com.fasterxml.jackson.databind.node.ArrayNode receipts=mapper.createArrayNode();
        for(int index=0;index<YmService.MAX_LEAVE_RECEIPTS+2;index++) receipts.add(original.deepCopy().put("requestId","bounded-"+index));
        receipts.add(original.deepCopy().put("requestId","expired").put("expiresAt",0));
        root.set("leaveReceipts",receipts); mapper.writeValue(file.toFile(),root);
        YmService restored=service();
        assertThatThrownBy(() -> restored.action(a.roomId(),a.playerId(),a.token(),revision,"bounded-0","LEAVE",List.of())).hasMessageContaining("不存在");
        assertThatThrownBy(() -> restored.action(a.roomId(),a.playerId(),a.token(),revision,"expired","LEAVE",List.of())).hasMessageContaining("不存在");
        assertThat(restored.action(a.roomId(),a.playerId(),a.token(),revision,"bounded-2","LEAVE",List.of())).isNull();
        assertThat(restored.action(a.roomId(),a.playerId(),a.token(),revision,"bounded-"+(YmService.MAX_LEAVE_RECEIPTS+1),"LEAVE",List.of())).isNull();
        restored.create("持久化清理","乙");
        assertThat(mapper.readTree(file.toFile()).path("leaveReceipts").size()).isEqualTo(YmService.MAX_LEAVE_RECEIPTS);
    }
}
