import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';

// Three real HTTP seats, no server hooks or production data access. Pass an explicit
// authorized base URL; the default is reserved for the isolated trustee smoke server.
const base=(process.argv[2] || 'http://127.0.0.1:18091').replace(/\/$/,'');
const api=`${base}/api/yaoming`, sessions=[], actions={};
const startedAt=Date.now(), testDeadline=startedAt+60000;
let cleaning=false, beforeRooms, roomId, targetId, targetSeat;
let privacyViews=0, secondDrawObserved=false;
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const ids=tiles=>tiles.map(tile=>tile.id).sort();
const me=v=>v.players.find(player=>player.id===v.meId);
const target=v=>v.players.find(player=>player.id===targetId);
const normalize=rooms=>[...rooms].sort((a,b)=>a.id.localeCompare(b.id));
function remaining() {
  const milliseconds=cleaning?5000:testDeadline-Date.now();
  assert.ok(milliseconds>0,'Trustee smoke exceeded its 60-second test budget');
  return Math.min(5000,milliseconds);
}
async function request(path, method='GET', body, identity, expected=200) {
  // Retry only reads or commands with the same idempotency key; never repeat join/create.
  const attempts=method==='GET' || body?.requestId?3:1;
  for(let attempt=0;attempt<attempts;attempt++) {
    let response, raw;
    try {
      response=await fetch(api+path,{method,headers:{'Content-Type':'application/json',Origin:base,
        ...(identity?{'X-Resume-Token':identity.token}:{})},
        body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(remaining())});
      raw=await response.text();
    } catch(error) {
      if(attempt+1===attempts)throw new Error(`${method} ${path}: network request failed (${error.name})`);
      continue;
    }
    if(response.status===409) {
      const error=new Error('Concurrent server tick changed the action version');error.stale=true;throw error;
    }
    // Never include command bodies, resume tokens or private response bodies in logs.
    assert.equal(response.status,expected,`${method} ${path}: unexpected HTTP status`);
    return raw?JSON.parse(raw):null;
  }
}
const view=p=>request(`/rooms/${p.roomId}?playerId=${p.playerId}`,'GET',undefined,p);
async function act(p,type,tileIds=[],current,desiredTrustee) {
  let v=current || await view(p);
  const requestId=randomUUID();
  for(let attempt=0;attempt<6;attempt++) {
    // A lost success response must never toggle the requested state back again.
    if(type==='TRUSTEE' && me(v).trustee===desiredTrustee)return v;
    try {
      const result=await request(`/rooms/${p.roomId}/actions`,'POST',{
        playerId:p.playerId,version:v.version,requestId,type,tileIds,
      },p);
      actions[type]=(actions[type]||0)+1;
      return result;
    } catch(error) {
      if(!error.stale || attempt===5)throw error;
      v=await view(p);
    }
  }
}
async function readViews() {
  const views=await Promise.all(sessions.map(view));
  for(const v of views) {
    assert.equal(v.id,roomId);assert.equal(v.round,1);assert.ok(v.result==null);
    assert.ok(v.players.every(player=>!player.bot),'The scenario must contain three real seats');
    for(const player of v.players) {
      if(player.id!==v.meId) {
        assert.deepEqual(player.hand,[],'An opponent hand must stay private');
        assert.ok(player.drawnTileId==null,'An opponent drawn entity must stay private');
      }
      if(player.id!==targetId)assert.equal(player.trustee,false,'Companion seats must not time out or go offline');
    }
    const json=JSON.stringify(v);
    for(const identity of sessions)assert.ok(!json.includes(identity.token),'Private views must not contain resume tokens');
    privacyViews++;
  }
  // Reads are also heartbeats. During a server tick they may legitimately have
  // different versions, so cross-seat equality is required only for equal versions.
  for(const v of views)for(const other of views)if(v.version===other.version) {
    assert.deepEqual(v.lastDiscard,other.lastDiscard);
    for(const player of v.players)assert.deepEqual(player.discardKinds,other.players.find(p=>p.id===player.id).discardKinds);
  }
  return views;
}
function verifyRetainedHand(v, original, discardCount) {
  const player=me(v);
  assert.deepEqual(ids(player.hand),original,'Pure trustee must preserve all original thirteen physical tiles');
  assert.equal(player.discards.length,discardCount);assert.deepEqual(player.melds,[]);
  assert.deepEqual(Object.keys(player.discardKinds).sort(),ids(player.discards));
  assert.ok(player.discards.every(tile=>player.discardKinds[tile.id]==='TSUMOGIRI'));
  assert.equal(v.lastDiscard.fromSeat,targetSeat);assert.equal(v.lastDiscard.kind,'TSUMOGIRI');
  assert.equal(v.lastDiscard.claimed,false);
}
async function waitFor(label,predicate,timeout=8000) {
  const deadline=Date.now()+timeout;
  while(Date.now()<deadline) {
    const views=await readViews();if(predicate(views))return views;
    await pause(150);
  }
  throw new Error(`${label}: timed out waiting for the server tick`);
}
async function advanceOthersUntil(label,done,observe=()=>{}) {
  const deadline=Date.now()+20000;
  for(let step=0;step<100 && Date.now()<deadline;step++) {
    const views=await readViews();observe(views);
    if(done(views))return views;
    let played=false;
    for(let i=0;i<views.length;i++) {
      const v=views[i];if(v.meId===targetId)continue;
      const player=me(v);
      const action=v.actions.find(a=>a.type==='PASS') || v.actions.find(a=>a.type==='DRAW') ||
        v.actions.find(a=>a.type==='DISCARD' && a.tileIds[0]===player.drawnTileId);
      if(!action)continue;
      await act(sessions[i],action.type,action.tileIds || [],v);played=true;break;
    }
    // Only the target trustee is allowed to act automatically. Never send a
    // DISCARD, PASS, WIN or meld command on its behalf while trustee is enabled.
    if(!played)await pause(150);
  }
  throw new Error(`${label}: no bounded progress to the expected target turn`);
}

try {
  beforeRooms=await request('/rooms');
  sessions.push(await request('/rooms','POST',{name:'纯摸切托管短流程验收',playerName:'托管验收甲'},undefined,201));
  roomId=sessions[0].roomId;
  for(const playerName of ['托管验收乙','托管验收丙'])sessions.push(await request(`/rooms/${roomId}/join`,'POST',{playerName}));
  for(const p of sessions)await act(p,'READY');
  const initial=await readViews(), first=initial[0];
  assert.equal(first.status,'NEED_DRAW');
  targetId=first.players.find(player=>player.seat===first.currentSeat).id;
  targetSeat=first.currentSeat;
  const identity=sessions.find(p=>p.playerId===targetId);
  const original=ids(me(initial.find(v=>v.meId===targetId)).hand);
  assert.equal(original.length,13);

  let current=await act(identity,'DRAW');
  const firstDrawnId=me(current).drawnTileId;
  assert.ok(firstDrawnId && !original.includes(firstDrawnId));
  assert.deepEqual(ids(me(current).hand),[...original,firstDrawnId].sort());
  current=await act(identity,'TRUSTEE',[],current,true);
  assert.equal(me(current).trustee,true);assert.equal(me(current).trusteeReason,'MANUAL');
  let views=await waitFor('First exact-entity tsumogiri',all=>all.every(v=>target(v).discards.length===1));
  current=views.find(v=>v.meId===targetId);
  verifyRetainedHand(current,original,1);
  assert.equal(current.lastDiscard.tile.id,firstDrawnId,'The first trustee discard must be the exact manually drawn entity');
  const firstWallCount=current.wallCount;

  let secondDrawnId;
  views=await advanceOthersUntil('Automatic draw then tsumogiri',all=>all.every(v=>target(v).discards.length===2),all=>{
    const own=all.find(v=>v.meId===targetId), player=me(own);
    if(player.discards.length===1 && player.drawnTileId) {
      secondDrawnId=player.drawnTileId;secondDrawObserved=true;
      assert.ok(!original.includes(secondDrawnId));
      assert.deepEqual(ids(player.hand),[...original,secondDrawnId].sort());
    }
  });
  current=views.find(v=>v.meId===targetId);
  verifyRetainedHand(current,original,2);
  assert.equal(current.wallCount,firstWallCount-3,'Two companions draw once and the trustee must draw automatically once');
  assert.equal(actions.DRAW,3,'The script itself draws only the first target tile and the two companion tiles');
  assert.ok(!original.includes(current.lastDiscard.tile.id));
  if(secondDrawnId)assert.equal(current.lastDiscard.tile.id,secondDrawnId);

  current=await act(identity,'TRUSTEE',[],current,false);
  assert.equal(me(current).trustee,false);
  views=await advanceOthersUntil('Next manually controlled turn',all=>{
    const own=all.find(v=>v.meId===targetId);
    assert.equal(me(own).trustee,false);assert.equal(me(own).discards.length,2);
    return own.status==='NEED_DRAW' && own.currentSeat===targetSeat;
  });
  current=views.find(v=>v.meId===targetId);
  assert.deepEqual(ids(me(current).hand),original);
  current=await act(identity,'DRAW',[],current);
  const heldIds=ids(me(current).hand), heldDrawnId=me(current).drawnTileId;
  assert.equal(heldIds.length,14);assert.ok(heldDrawnId);
  assert.deepEqual(heldIds,[...original,heldDrawnId].sort());
  const watchStarted=Date.now();
  assert.ok(Date.parse(current.deadlineAt)-Date.parse(current.serverTime)>10000,'Cancellation observation must stay far below the normal discard deadline');
  do {
    views=await readViews();current=views.find(v=>v.meId===targetId);
    assert.equal(current.status,'NEED_DISCARD');assert.equal(current.currentSeat,targetSeat);
    assert.equal(me(current).trustee,false);assert.equal(me(current).discards.length,2);
    assert.deepEqual(ids(me(current).hand),heldIds,'Cancelling trustee must retain the manually held fourteen tiles');
    assert.equal(me(current).drawnTileId,heldDrawnId);
    await pause(150);
  } while(Date.now()-watchStarted<1800);
  console.log(JSON.stringify({ok:true,base,roomId,manualTrustee:true,exactFirstDrawnEntity:true,
    automaticDrawAndDiscard:true,secondDrawObserved,originalThirteenRetained:true,tsumogiri:2,
    cancellationHeldMs:Date.now()-watchStarted,privacyViews,actions,elapsedMs:Date.now()-startedAt,
    note:'Short active-hand scenario; no completed replay or intelligent-bot coverage is claimed.'}));
} finally {
  cleaning=true;
  const cleanupErrors=[];
  for(const identity of sessions) {
    try { await act(identity,'LEAVE'); }
    catch(error) { cleanupErrors.push(error.message); }
  }
  const afterRooms=await request('/rooms');
  const testRoomRemoved=!roomId || !afterRooms.some(room=>room.id===roomId);
  const othersBefore=normalize(beforeRooms || []), othersAfter=normalize(afterRooms.filter(room=>room.id!==roomId));
  const othersUnchanged=JSON.stringify(othersBefore)===JSON.stringify(othersAfter);
  console.log(JSON.stringify({cleanup:true,testRoomRemoved,othersUnchanged,beforeRooms:othersBefore.length,afterRooms:othersAfter.length,
    ...(othersUnchanged?{}:{publicRoomDifferences:{before:othersBefore,after:othersAfter}}),cleanupErrors}));
  assert.equal(cleanupErrors.length,0,'A test seat failed normal LEAVE cleanup');
  assert.ok(testRoomRemoved,'The smoke test room was not removed');
  assert.ok(othersUnchanged,'Other public rooms changed concurrently; inspect the reported summaries, never remove or restore them');
}
