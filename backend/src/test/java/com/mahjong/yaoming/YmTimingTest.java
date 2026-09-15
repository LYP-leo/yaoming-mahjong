package com.mahjong.yaoming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mahjong.domain.Tile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

import static com.mahjong.yaoming.YmRoom.Phase.*;
import static com.mahjong.yaoming.YmViews.*;
import static org.assertj.core.api.Assertions.*;

class YmTimingTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private YmService service;
    private Identity[] identities;

    private static final class MutableClock extends Clock {
        long now = Instant.parse("2026-09-07T12:00:00Z").toEpochMilli();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
    private YmService createService() { return new YmService(mapper, directory.resolve("rooms.json"), new Random(42), clock); }
    private void start() {
        service=createService(); Identity a=service.create("计时测试","甲"), b=service.join(a.roomId(),"乙"), c=service.join(a.roomId(),"丙");
        identities=new Identity[]{a,b,c}; for(Identity id:identities) command(id,"READY");
    }
    private YmRoom room() { return service.state(identities[0].roomId()); }
    private Identity seat(int seat) {
        String id=room().seat(seat).id; return Arrays.stream(identities).filter(i -> i.playerId().equals(id)).findFirst().orElseThrow();
    }
    private Identity current() { return seat(room().currentSeat); }
    private RoomView view(Identity id) { return service.view(id.roomId(),id.playerId(),id.token()); }
    private RoomView command(Identity id,String type) {
        return service.action(id.roomId(),id.playerId(),id.token(),view(id).version(),UUID.randomUUID().toString(),type,List.of());
    }
    private RoomView submit(Identity id,long version,String request,String type,List<String> ids) {
        return service.action(id.roomId(),id.playerId(),id.token(),version,request,type,ids);
    }
    private PlayerView me(RoomView room,Identity id) { return room.players().stream().filter(p -> p.id().equals(id.playerId())).findFirst().orElseThrow(); }
    private void assertEntities() {
        List<String> ids=new ArrayList<>(); room().wall.forEach(t -> ids.add(t.id()));
        for(YmRoom.Player p:room().players) {p.hand.forEach(t->ids.add(t.id()));p.discards.forEach(t->ids.add(t.id()));p.melds.forEach(m->m.tiles().forEach(t->ids.add(t.id())));}
        assertThat(ids).hasSize(108).doesNotHaveDuplicates(); assertThat(room().players.stream().mapToInt(p->p.score).sum()).isEqualTo(30);
    }
    private void blockSnapshot() throws Exception {
        Path file=directory.resolve("rooms.json"); Files.move(file,directory.resolve("saved.json"));
        Files.createDirectory(file); Files.writeString(file.resolve("blocker"),"expected fault injection");
    }
    private void unblockSnapshot() throws Exception {
        Path file=directory.resolve("rooms.json"); Files.delete(file.resolve("blocker")); Files.delete(file); Files.move(directory.resolve("saved.json"),file);
    }
    private void handEnd() {
        YmRoom r=room(); r.phase=HAND_END; r.currentSeat=-1; r.deadlineKind="SETTLEMENT"; r.deadlineAt=clock.millis()+60000;
        r.players.forEach(p->{p.acknowledged=false;p.trustee=false;p.autoTrustee=false;p.trusteeReason=null;});
        r.result=new Result(true,false,"流局",null,null,0,0,List.of(),List.of(),List.of(),List.of(),"等待结算");
    }
    private Tile take(String code) {
        Tile tile=room().wall.stream().filter(t->YmTiles.code(t).equals(code)).findFirst().orElseThrow(); room().wall.remove(tile); return tile;
    }
    private void hands(String a,String b,String c) {
        room().wall=YmTiles.deck(); room().window=null; room().lastDiscard=null; room().phase=NEED_DISCARD; room().currentSeat=0;
        room().deadlineKind="DISCARD"; room().deadlineAt=clock.millis()+30000;
        for(YmRoom.Player p:room().players) {p.hand.clear();p.discards.clear();p.melds.clear();p.passedCodes.clear();p.discardedCodes.clear();p.lastDrawnId=null;}
        String[] values={a,b,c}; for(int i=0;i<3;i++) for(String code:values[i].split("\\s+")) if(!code.isBlank()) room().seat(i).hand.add(take(code));
    }
    private RoomView choose(Identity id,String type) {
        RoomView view=view(id); Action action=view.actions().stream().filter(a->a.type().equals(type)).findFirst().orElseThrow();
        return submit(id,view.version(),UUID.randomUUID().toString(),type,action.tileIds());
    }

    @Test void onlinePollingDoesNotRenewDeadlineAndExactBoundaryDrawsOnceIntoTimeoutTrustee() {
        start(); Identity actor=current(); long started=clock.millis(), deadline=room().deadlineAt, version=room().version;
        assertThat(deadline).isEqualTo(started+15000); assertThat(room().deadlineKind).isEqualTo("DRAW");
        for(int second=1;second<15;second++) {clock.now=started+second*1000; for(Identity id:identities) view(id); assertThat(room().deadlineAt).isEqualTo(deadline);}
        clock.now=deadline-1; service.tick(); assertThat(room().phase).isEqualTo(NEED_DRAW); assertThat(room().version).isEqualTo(version);
        clock.now=deadline; RoomView timed=view(actor);
        assertThat(timed.status()).isEqualTo("NEED_DISCARD"); assertThat(timed.deadlineKind()).isEqualTo("DISCARD");
        assertThat(Instant.parse(timed.deadlineAt()).toEpochMilli()).isEqualTo(deadline+30000); assertThat(timed.serverTime()).isEqualTo(clock.instant().toString());
        assertThat(me(timed,actor).trusteeReason()).isEqualTo("TIMEOUT"); assertThat(me(timed,actor).trustee()).isTrue();
        assertThat(timed.wallCount()).isEqualTo(68); long afterVersion=timed.version();
        service.tick(); assertThat(room().version).isEqualTo(afterVersion); assertThat(room().wall).hasSize(68);
        service.resume(actor.roomId(),actor.token()); assertThat(me(view(actor),actor).trusteeReason()).isEqualTo("TIMEOUT"); assertEntities();
    }

    @Test void actionAfterDeadlineProcessesExpiryBeforeVersionOrOrdinaryOperation() {
        start(); Identity actor=current(); long version=room().version; clock.now=room().deadlineAt;
        assertThatThrownBy(()->submit(actor,version,"late-draw","DRAW",List.of())).isInstanceOf(YmService.VersionConflict.class);
        assertThat(room().wall).hasSize(68); assertThat(room().phase).isEqualTo(NEED_DISCARD);
        assertThat(room().seat(room().currentSeat).trusteeReason).isEqualTo("TIMEOUT");
        assertThat(room().processed).doesNotContainKey(actor.playerId()+":late-draw"); assertEntities();
    }

    @Test void invalidAndRepeatedCommandsAndResumeNeverRefreshActionDeadline() {
        start(); Identity actor=current(); long drawDeadline=room().deadlineAt, revision=room().version;
        clock.now+=1000; service.resume(actor.roomId(),actor.token()); service.rules();
        assertThatThrownBy(()->submit(actor,revision,"invalid","DISCARD",List.of("absent"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(room().deadlineAt).isEqualTo(drawDeadline);
        submit(actor,revision,"once","DRAW",List.of()); long deadline=room().deadlineAt, after=room().version;
        clock.now+=1000; RoomView repeated=submit(actor,revision,"once","DRAW",List.of());
        assertThat(repeated.version()).isEqualTo(after); assertThat(room().deadlineAt).isEqualTo(deadline); assertThat(room().wall).hasSize(68);
        assertThatThrownBy(()->submit(actor,revision,"another","DRAW",List.of())).isInstanceOf(YmService.VersionConflict.class);
        assertThat(room().deadlineAt).isEqualTo(deadline); assertEntities();
    }

    @Test void duplicateDrawAfterNewDeadlineExpiresCannotRenewItOrDrawAgain() {
        start(); Identity actor=current(); long revision=room().version; submit(actor,revision,"once","DRAW",List.of());
        clock.now=room().deadlineAt; RoomView result=submit(actor,revision,"once","DRAW",List.of());
        assertThat(result.wallCount()).isEqualTo(68); assertThat(room().seat(me(result,actor).seat()).discards).hasSize(1);
        assertThat(me(result,actor).trusteeReason()).isEqualTo("TIMEOUT"); assertEntities();
    }

    @Test void explicitTakebackResetsOnlyCurrentActionAndItsDuplicateDoesNotExtendAgain() {
        start(); Identity actor=current(); clock.now=room().deadlineAt; view(actor); clock.now+=300;
        long version=room().version; submit(actor,version,"takeback","TRUSTEE",List.of()); long reset=room().deadlineAt;
        assertThat(reset).isEqualTo(clock.millis()+30000); assertThat(me(view(actor),actor).trustee()).isFalse(); assertThat(me(view(actor),actor).trusteeReason()).isNull();
        clock.now+=200; submit(actor,version,"takeback","TRUSTEE",List.of()); assertThat(room().deadlineAt).isEqualTo(reset);
        Identity other=Arrays.stream(identities).filter(i->!i.equals(actor)).findFirst().orElseThrow();
        command(other,"TRUSTEE"); assertThat(me(view(other),other).trusteeReason()).isEqualTo("MANUAL");
        command(other,"TRUSTEE"); assertThat(room().deadlineAt).isEqualTo(reset);
    }

    @Test void malformedTrusteeCannotResetDeadlineAndFinalPhaseCannotToggleTrustee() {
        start(); Identity actor=current(); clock.now=room().deadlineAt; view(actor); clock.now+=300;
        long version=room().version, deadline=room().deadlineAt;
        assertThatThrownBy(()->submit(actor,version,"malformed","TRUSTEE",List.of("nonexistent"))).hasMessageContaining("不接受牌张参数");
        assertThat(room().version).isEqualTo(version); assertThat(room().deadlineAt).isEqualTo(deadline);
        assertThat(me(view(actor),actor).trusteeReason()).isEqualTo("TIMEOUT");
        room().phase=MATCH_END; room().currentSeat=-1; RoomView finalView=view(actor); long finalVersion=finalView.version();
        assertThatThrownBy(()->submit(actor,finalVersion,"final-trustee","TRUSTEE",List.of())).hasMessageContaining("本场已结束");
        assertThat(room().version).isEqualTo(finalVersion); assertThat(room().deadlineAt).isZero();
    }

    @Test void malformedAddBotLeavesWaitingRoomAndVersionUnchanged() {
        service=createService(); Identity host=service.create("机器人参数","甲"); identities=new Identity[]{host}; long version=room().version;
        assertThatThrownBy(()->submit(host,version,"bad-bot","ADD_BOT",List.of("nonexistent"))).hasMessageContaining("不接受牌张参数");
        assertThat(room().players).hasSize(1); assertThat(room().phase).isEqualTo(WAITING);
        assertThat(room().version).isEqualTo(version); assertThat(room().deadlineAt).isZero();
    }

    @Test void lateSchedulerWithHumanPresentGivesNextStageAFullNewPeriodInsteadOfCascadingTimeouts() {
        start(); Identity actor=current(); YmRoom table=room(); clock.now=table.deadlineAt+120000;
        // Isolate delayed scheduling while a spectator is still connected; an entirely
        // disconnected table is now intentionally frozen by the room retention policy.
        table.players.stream().filter(player -> !player.id.equals(actor.playerId())).findFirst().orElseThrow().lastSeen=clock.now;
        service.tick();
        assertThat(room().phase).isEqualTo(NEED_DISCARD); assertThat(room().deadlineAt).isEqualTo(clock.millis()+30000);
        assertThat(room().wall).hasSize(68); assertThat(me(view(actor),actor).trusteeReason()).isEqualTo("TIMEOUT");
        assertEntities();
    }

    @Test void drawnTileIdIsVisibleOnlyToItsOwnerAndClearsOnDiscard() {
        start(); Identity actor=current(); RoomView drawn=command(actor,"DRAW"); String id=me(drawn,actor).drawnTileId();
        assertThat(id).isNotBlank(); assertThat(me(drawn,actor).hand()).extracting(Tile::id).contains(id);
        for(Identity observer:identities) if(!observer.equals(actor)) {
            PlayerView opponent=view(observer).players().stream().filter(p->p.id().equals(actor.playerId())).findFirst().orElseThrow();
            assertThat(opponent.drawnTileId()).isNull(); assertThat(opponent.hand()).isEmpty();
        }
        submit(actor,drawn.version(),"discard-drawn","DISCARD",List.of(id)); RoomView discarded=view(actor);
        assertThat(me(discarded,actor).drawnTileId()).isNull(); assertThat(discarded.lastDiscard().tile().id()).isEqualTo(id);
        assertThat(discarded.lastDiscard().claimed()).isFalse(); assertEntities();
    }

    @Test void responseDeadlineDoesNotChangeAfterFirstResponseOrTrusteeToggleAndClaimHasCorrectLastDiscard() {
        start(); hands("B3","B1 B2","B3 B3"); Identity from=seat(0),chi=seat(1),pong=seat(2);
        choose(from,"DISCARD"); long deadline=room().deadlineAt; Tile discarded=room().lastDiscard.tile();
        assertThat(room().deadlineKind).isEqualTo("REACTION"); assertThat(deadline).isEqualTo(clock.millis()+20000);
        clock.now+=1000; command(chi,"TRUSTEE"); command(chi,"TRUSTEE"); assertThat(room().deadlineAt).isEqualTo(deadline);
        choose(chi,"CHI"); assertThat(room().phase).isEqualTo(REACTION); assertThat(room().deadlineAt).isEqualTo(deadline);
        clock.now+=200; choose(pong,"PONG");
        assertThat(room().deadlineAt).isEqualTo(clock.millis()+30000); assertThat(room().deadlineKind).isEqualTo("DISCARD");
        assertThat(room().lastDiscard).isEqualTo(new LastDiscard(discarded,0,true,"TEDASHI")); assertThat(room().seat(0).discards).isEmpty();
        assertThat(me(view(pong),pong).drawnTileId()).isNull(); assertEntities();
    }

    @Test void allPassAndTimedOutReactionStartTheNextDrawWithoutTimeoutTrustee() {
        start(); hands("B3","B1 B2","B3 B3"); choose(seat(0),"DISCARD"); long deadline=room().deadlineAt;
        clock.now+=1000; command(seat(1),"PASS"); assertThat(room().deadlineAt).isEqualTo(deadline);
        clock.now=deadline; service.tick();
        assertThat(room().phase).isEqualTo(NEED_DRAW); assertThat(room().currentSeat).isEqualTo(1);
        assertThat(room().deadlineAt).isEqualTo(clock.millis()+15000); assertThat(room().players).allMatch(p->!p.trustee);
        assertThat(room().lastDiscard.claimed()).isFalse(); assertEntities();
    }

    @Test void replacementDrawHasPrivateDrawIdAndFreshDiscardDeadline() {
        start(); hands("W1 W1 W1 W1 B2 B3 B4 D4 D5 D6 B7 B8 B9 H3","","");
        room().seat(0).lastDrawnId=room().seat(0).hand.getFirst().id(); Tile tail=room().wall.getLast(); clock.now+=2000;
        RoomView replaced=choose(seat(0),"CONCEALED_KONG");
        assertThat(me(replaced,seat(0)).drawnTileId()).isEqualTo(tail.id());
        assertThat(room().deadlineAt).isEqualTo(clock.millis()+30000); assertThat(room().deadlineKind).isEqualTo("DISCARD"); assertEntities();
    }

    @Test void ronMarksLastDiscardClaimedAndMatchEndHasNoDeadline() {
        start(); hands("H6","H1 H1 H1 H2 H2 H2 H3 H3 H3 H5 H5 H5 H6","");
        choose(seat(0),"DISCARD"); Tile discarded=room().lastDiscard.tile(); command(seat(1),"WIN");
        assertThat(room().phase).isEqualTo(MATCH_END); assertThat(room().deadlineAt).isZero(); assertThat(room().deadlineKind).isNull();
        assertThat(room().lastDiscard).isEqualTo(new LastDiscard(discarded,0,true,"TEDASHI")); assertThat(room().seat(0).discards).isEmpty(); assertEntities();
    }

    @Test void settlementAcknowledgementsNeverRenewAndDeadlineAcknowledgesOnlyPendingThenRotatesOnce() {
        start(); handEnd(); long deadline=room().deadlineAt; int dealer=room().dealerSeat;
        clock.now+=1000; Identity a=identities[0]; long revision=room().version; submit(a,revision,"ack","ACK",List.of());
        clock.now+=1000; submit(a,revision,"ack","ACK",List.of()); assertThat(room().deadlineAt).isEqualTo(deadline);
        clock.now=deadline-1; service.tick(); assertThat(room().phase).isEqualTo(HAND_END);
        clock.now=deadline; RoomView next=view(identities[1]);
        assertThat(next.round()).isEqualTo(2); assertThat(next.dealerSeat()).isEqualTo((dealer+1)%3); assertThat(next.status()).isEqualTo("NEED_DRAW");
        assertThat(room().deadlineAt).isEqualTo(clock.millis()+15000); assertThat(next.lastDiscard()).isNull();
        assertThat(room().players).allMatch(p->!p.trustee); service.tick(); assertThat(room().round).isEqualTo(2); assertEntities();
    }

    @Test void waitingDoesNotAutoReadyAndFinalResultDoesNotAutoLeave() {
        service=createService(); Identity a=service.create("等待","甲"); identities=new Identity[]{a}; clock.now+=180000; service.tick();
        assertThat(view(a).status()).isEqualTo("WAITING"); assertThat(view(a).deadlineAt()).isNull(); assertThat(me(view(a),a).ready()).isFalse();
        room().phase=MATCH_END; room().currentSeat=-1; clock.now+=180000; service.tick();
        assertThat(view(a).status()).isEqualTo("MATCH_END"); assertThat(view(a).deadlineKind()).isNull(); assertThat(service.list()).hasSize(1);
    }

    @Test void restartPreservesAbsoluteDeadlineAndExpiryIgnoresOfflineStartupGrace() {
        start(); Identity actor=current(); long deadline=room().deadlineAt; clock.now+=10000;
        service=createService(); assertThat(room().deadlineAt).isEqualTo(deadline); assertThat(view(actor).status()).isEqualTo("NEED_DRAW");
        clock.now=deadline; service.tick(); assertThat(room().phase).isEqualTo(NEED_DISCARD); assertThat(room().wall).hasSize(68);
        assertThat(me(view(actor),actor).trusteeReason()).isEqualTo("TIMEOUT");
        long nextDeadline=room().deadlineAt; clock.now+=1000; service=createService();
        assertThat(room().deadlineAt).isEqualTo(nextDeadline); assertThat(me(view(actor),actor).trusteeReason()).isEqualTo("TIMEOUT"); assertEntities();
    }

    @Test void legacySnapshotDeadlineIsInitializedAndPersistedOnceAcrossRepeatedRestarts() throws Exception {
        start(); Path file=directory.resolve("rooms.json"); ArrayNode old=(ArrayNode)mapper.readTree(file.toFile()).path("rooms");
        ObjectNode stored=(ObjectNode)old.get(0); stored.remove(List.of("deadlineAt","deadlineKind","lastDiscard"));
        mapper.writeValue(file.toFile(),old); clock.now+=4000; service=createService(); long migrated=room().deadlineAt;
        assertThat(migrated).isEqualTo(clock.millis()+15000); assertThat(room().deadlineKind).isEqualTo("DRAW");
        assertThat(mapper.readTree(file.toFile()).path("rooms").get(0).path("deadlineAt").asLong()).isEqualTo(migrated);
        clock.now+=5000; service=createService(); assertThat(room().deadlineAt).isEqualTo(migrated);
    }

    @Test void offlineReconnectClearsOnlyOfflineReasonWithoutRenewingDeadline() {
        start(); room().deadlineAt=clock.millis()+120000; clock.now+=65000; service.tick();
        Identity other=Arrays.stream(identities).filter(id->!id.playerId().equals(room().seat(room().currentSeat).id)).findFirst().orElseThrow();
        YmRoom.Player offline=room().players.stream().filter(p->p.id.equals(other.playerId())).findFirst().orElseThrow();
        assertThat(offline.trusteeReason).isEqualTo("OFFLINE"); long deadline=room().deadlineAt;
        assertThat(me(view(other),other).trustee()).isFalse(); assertThat(offline.trusteeReason).isNull(); assertThat(room().deadlineAt).isEqualTo(deadline);
    }

    @Test void expiryCommitFailureRollsBackDrawDeadlineTrusteeVersionAndRetriesOnce() throws Exception {
        start(); Identity actor=current(); long version=room().version, deadline=room().deadlineAt; blockSnapshot(); clock.now=deadline;
        assertThatThrownBy(()->view(actor)).hasMessageContaining("未生效");
        assertThat(room().phase).isEqualTo(NEED_DRAW); assertThat(room().deadlineAt).isEqualTo(deadline); assertThat(room().wall).hasSize(69);
        assertThat(room().version).isEqualTo(version); assertThat(room().players).allMatch(p->!p.trustee && p.trusteeReason==null);
        unblockSnapshot(); view(actor); assertThat(room().wall).hasSize(68); assertThat(room().phase).isEqualTo(NEED_DISCARD); assertEntities();
    }

    @Test void reclaimCommitFailureRestoresTimeoutReasonAndOriginalDeadline() throws Exception {
        start(); Identity actor=current(); clock.now=room().deadlineAt; view(actor); clock.now+=300;
        long version=room().version, deadline=room().deadlineAt; blockSnapshot();
        assertThatThrownBy(()->submit(actor,version,"failed-takeback","TRUSTEE",List.of())).hasMessageContaining("未生效");
        assertThat(room().deadlineAt).isEqualTo(deadline); assertThat(room().version).isEqualTo(version);
        assertThat(room().seat(room().currentSeat).trusteeReason).isEqualTo("TIMEOUT"); assertThat(room().processed).doesNotContainKey(actor.playerId()+":failed-takeback");
        unblockSnapshot(); submit(actor,version,"failed-takeback","TRUSTEE",List.of());
        assertThat(room().deadlineAt).isEqualTo(clock.millis()+30000); assertThat(room().seat(room().currentSeat).trustee).isFalse(); assertEntities();
    }

    @Test void lastAcknowledgementCommitFailureRestoresSettlementAndCanRetry() throws Exception {
        start(); handEnd(); command(identities[0],"ACK"); command(identities[1],"ACK");
        long version=room().version, deadline=room().deadlineAt; Identity last=identities[2]; blockSnapshot();
        assertThatThrownBy(()->submit(last,version,"last-ack","ACK",List.of())).hasMessageContaining("未生效");
        assertThat(room().phase).isEqualTo(HAND_END); assertThat(room().round).isEqualTo(1); assertThat(room().deadlineAt).isEqualTo(deadline);
        assertThat(room().players.stream().filter(p->p.acknowledged)).hasSize(2); unblockSnapshot();
        submit(last,version,"last-ack","ACK",List.of()); assertThat(room().round).isEqualTo(2); assertThat(room().phase).isEqualTo(NEED_DRAW); assertEntities();
    }
}
