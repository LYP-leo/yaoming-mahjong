import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {createInterface} from 'node:readline';

// Three independent players using only public HTTP endpoints. No test-only server hooks.
const base = (process.argv[2] || 'http://127.0.0.1:5173').replace(/\/$/, '');
const api = `${base}/api/yaoming`;
const sessions = [];
const counts = {};
const results = [];
// Opt in only against an updated, isolated backend. Includes completed-replay checks.
const discardKinds = process.argv.includes('--discard-kinds');
const features = process.argv.includes('--features') || discardKinds;
const discardHistory = new Map();
const observedDiscardKinds = {TSUMOGIRI:0, TEDASHI:0};
const claimedDiscards = new Set();
const replayDiscardSnapshots = new Map();
// Optional manual-browser handoff. Only this script's test seats are disclosed;
// Enter (or the ten-minute safety timeout) resumes the normal LEAVE cleanup.
const inspectFinal = process.argv.includes('--inspect-final');
const notices = [];
const replayedRounds = [];
let streamAbort, streamDone, streamError;
function history(round) {
  if(!discardHistory.has(round))discardHistory.set(round,new Map());
  return discardHistory.get(round);
}
function verifyDiscardState(state, ledger, label) {
  for(const player of state.players) {
    assert.ok(player.discardKinds && typeof player.discardKinds==='object' && !Array.isArray(player.discardKinds),`${label}: missing discardKinds`);
    assert.deepEqual(Object.keys(player.discardKinds).sort(),player.discards.map(tile=>tile.id).sort(),`${label}: kinds must describe only the current river`);
    for(const tile of player.discards) {
      const expected=ledger.get(tile.id);
      assert.ok(expected,`${label}: discard was not observed before the action`);
      assert.equal(expected.seat,player.seat);
      assert.equal(player.discardKinds[tile.id],expected.kind,`${label}: wrong entity discard kind`);
    }
  }
  if(state.lastDiscard) {
    const last=state.lastDiscard, expected=ledger.get(last.tile.id);
    assert.ok(expected,`${label}: latest discard must have a recorded action`);
    assert.equal(last.kind,expected.kind,`${label}: latest discard lost its kind`);
    assert.equal(last.fromSeat,expected.seat);
    const from=state.players.find(player=>player.seat===last.fromSeat);
    assert.equal(Object.hasOwn(from.discardKinds,last.tile.id),!last.claimed,`${label}: claimed tile must leave the river map`);
    if(last.claimed)assert.ok(!from.discards.some(tile=>tile.id===last.tile.id));
  }
}
function verifyPrivateDiscardViews(views) {
  const first=views[0], ledger=history(first.round);
  for(const v of views) {
    assert.equal(v.version,first.version,'Discard checks require a consistent three-seat snapshot');
    assert.equal(v.round,first.round);
    verifyDiscardState(v,ledger,`round ${v.round} private view`);
    assert.deepEqual(v.lastDiscard,first.lastDiscard);
    for(const player of v.players) {
      assert.deepEqual(player.discardKinds,first.players.find(other=>other.id===player.id).discardKinds);
      if(player.id!==v.meId)assert.ok(player.drawnTileId==null,'Public discard kinds must not expose an opponent drawn entity');
    }
  }
  if(first.lastDiscard?.claimed)claimedDiscards.add(`${first.round}:${first.lastDiscard.tile.id}`);
}
function replayKindSnapshot(replay) {
  return replay.frames.map(frame=>({index:frame.index,lastDiscard:frame.lastDiscard,
    players:frame.players.map(player=>({id:player.id,discardKinds:player.discardKinds}))}));
}
async function startStream(p) {
  streamAbort = new AbortController();
  const response = await fetch(`${api}/rooms/${p.roomId}/stream?playerId=${p.playerId}`, {
    headers: {'X-Resume-Token':p.token, Origin:base}, signal:streamAbort.signal,
  });
  assert.equal(response.status,200); assert.match(response.headers.get('content-type'),/text\/event-stream/);
  const reader=response.body.getReader(), decoder=new TextDecoder(); let buffer='';
  streamDone=(async()=>{
    try {
      while(true) {
        const {value,done}=await reader.read();if(done)break;
        buffer+=decoder.decode(value,{stream:true}).replace(/\r/g,'');
        let boundary;
        while((boundary=buffer.indexOf('\n\n'))>=0) {
          const block=buffer.slice(0,boundary);buffer=buffer.slice(boundary+2);
          const data=block.split('\n').filter(line=>line.startsWith('data:')).map(line=>line.slice(5).trimStart()).join('\n');
          if(!data)continue;const notice=JSON.parse(data);
          assert.deepEqual(Object.keys(notice).sort(),['roomId','type','version']);assert.equal(notice.roomId,p.roomId);
          assert.ok(['READY','CHANGED','PING','CLOSED'].includes(notice.type));assert.ok(Number.isInteger(notice.version));
          assert.ok(!JSON.stringify(notice).includes(p.token));notices.push(notice);
        }
      }
    } catch(error) { if(!streamAbort.signal.aborted)streamError=error; }
    finally {reader.releaseLock();}
  })();
  const limit=Date.now()+5000;
  while(!notices.length&&Date.now()<limit)await new Promise(resolve=>setTimeout(resolve,25));
  if(streamError)throw streamError;assert.equal(notices[0]?.type,'READY','SSE must flush READY before any mutation');
}
async function verifyReplay(p, current) {
  const list=await request(`/replays?roomId=${p.roomId}&playerId=${p.playerId}`,'GET',undefined,p);
  assert.ok(list.hands.some(hand=>hand.round===current.round));
  const replay=await request(`/replays/${p.roomId}/${current.round}?playerId=${p.playerId}`,'GET',undefined,p);
  assert.equal(replay.complete,true);assert.equal(replay.incomplete,false);assert.equal(replay.frames[0].type,'START');
  assert.deepEqual(replay.result,current.result);assert.deepEqual(replay.frames.at(-1).result,current.result);
  assert.ok(replay.frames.length>2);assert.ok(Number.isFinite(replay.completedAt));
  replay.frames.forEach((frame,index)=>{
    assert.equal(frame.index,index);assert.equal(frame.players.reduce((sum,p)=>sum+p.score,0),30);
    const tiles=frame.players.flatMap(p=>[...p.hand,...p.discards,...p.melds.flatMap(m=>m.tiles)]);
    assert.equal(tiles.length+frame.wallCount,108);assert.equal(new Set(tiles.map(t=>t.id)).size,tiles.length);
    assert.ok(!Object.hasOwn(frame,'wall'));assert.ok(Number.isFinite(frame.timestamp));
    if(discardKinds) {
      verifyDiscardState(frame,history(current.round),`round ${current.round} replay frame ${index}`);
      if(frame.type==='DISCARD') {
        const before=replay.frames[index-1]?.players.find(player=>player.seat===frame.actorSeat);
        assert.ok(before,'Every recorded discard must have a preceding actor snapshot');
        assert.equal(frame.lastDiscard.fromSeat,frame.actorSeat);
        assert.equal(frame.lastDiscard.kind,before.drawnTileId===frame.lastDiscard.tile.id?'TSUMOGIRI':'TEDASHI',
          'Replay kind must use the pre-action physical drawn entity, not its tile type');
      }
    }
  });
  if(discardKinds) {
    const final=replay.frames.at(-1);
    assert.deepEqual(final.lastDiscard,current.lastDiscard);
    for(const player of current.players)assert.deepEqual(final.players.find(p=>p.id===player.id).discardKinds,player.discardKinds);
    replayDiscardSnapshots.set(current.round,replayKindSnapshot(replay));
  }
  const json=JSON.stringify(replay);for(const identity of sessions)assert.ok(!json.includes(identity.token));
  assert.ok(!json.includes('"token"'));assert.ok(!json.includes('"processed"'));
  replayedRounds.push(current.round);
}
async function request(path, method='GET', body, identity, expected=200) {
  // Reads and idempotent action commands can retry a lost connection. Never retry creation/join.
  const attempts = method === 'GET' || body?.requestId ? 3 : 1;
  let networkError;
  for(let attempt=0;attempt<attempts;attempt++) {
    let response, raw;
    try {
      response = await fetch(api + path, {method, headers:{'Content-Type':'application/json', Origin:base,
        ...(identity ? {'X-Resume-Token':identity.token}: {})}, body:body === undefined ? undefined : JSON.stringify(body), signal:AbortSignal.timeout(15000)});
      raw = await response.text();
    } catch(e) { networkError=e;console.error(`Network retry ${attempt+1}/${attempts}: ${method} ${path}`);continue; }
    assert.equal(response.status, expected, `${method} ${path}: ${raw}`);
    return raw ? JSON.parse(raw) : null;
  }
  throw networkError;
}
const view = p => request(`/rooms/${p.roomId}?playerId=${p.playerId}`, 'GET', undefined, p);
async function act(p, action, current) {
  const v = current || await view(p);
  return request(`/rooms/${p.roomId}/actions`, 'POST', {playerId:p.playerId,token:p.token,
    version:v.version,requestId:randomUUID(),type:action.type,tileIds:action.tileIds || []},p);
}
function choose(v) {
  for (const type of ['WIN','DRAW','ACK','CONCEALED_KONG','ADDED_KONG']) {
    const action = v.actions.find(a=>a.type===type); if(action) return action;
  }
  const me = v.players.find(p=>p.id===v.meId);
  const discards = v.actions.filter(a=>a.type==='DISCARD');
  if(discards.length) {
    // Keep the default playing strategy untouched. This explicit test mode alternates
    // physical drawn/old tiles; after chi/pong, all legal discards are naturally tedashi.
    if(discardKinds) {
      const wantTsumogiri=(counts.DISCARD||0)%2===0;
      const candidates=discards.filter(action=>(action.tileIds[0]===me.drawnTileId)===wantTsumogiri);
      if(candidates.length)return candidates[0];
    }
    function usefulness(action) {
      const t = me.hand.find(t=>t.id===action.tileIds[0]); let n=0;
      for(const other of me.hand) if(other.id!==t.id && other.suit===t.suit) {
        if(other.rank===t.rank)n+=4;
        else if(t.suit==='CHARACTERS')n+=1.6;
        else if(t.suit!=='HONORS') { const gap=Math.abs(t.rank-other.rank);n+=gap===1?2:gap===2?.8:0; }
      }
      return n - (t.suit==='HONORS'||t.rank===1||t.rank===9?.3:0);
    }
    return discards.sort((a,b)=>usefulness(a)-usefulness(b))[0];
  }
  // Exercise actual open melds as well as closed routes to a four-fan win.
  if(me.melds.length || v.round % 2 === 0) {
    for(const type of ['OPEN_KONG','PONG','CHI']) {const a=v.actions.find(a=>a.type===type);if(a)return a;}
  }
  return v.actions.find(a=>a.type==='PASS');
}
try {
  const rules = await request('/rules'); assert.equal(rules.fans.length,20); assert.equal(rules.tiles.length,27);
  const a = await request('/rooms','POST',{name:'要命全流程验收',playerName:'验收甲'},undefined,201);sessions.push(a);
  for(const playerName of ['验收乙','验收丙']) sessions.push(await request(`/rooms/${a.roomId}/join`,'POST',{playerName}));
  if(features) {
    await request(`/rooms/${a.roomId}/stream?playerId=${sessions[1].playerId}`,'GET',undefined,a,400);
    await startStream(a);
  }
  assert.deepEqual(await request(`/rooms/${a.roomId}/resume`,'POST',{token:a.token}),a);
  await request(`/rooms/${a.roomId}?playerId=${sessions[1].playerId}`,'GET',undefined,a,400);
  for(const p of sessions) await act(p,{type:'READY'});
  if(features) {
    const listed=await request(`/replays?roomId=${a.roomId}&playerId=${a.playerId}`,'GET',undefined,a);
    assert.deepEqual(listed.hands,[]);
    await request(`/replays/${a.roomId}/1?playerId=${a.playerId}`,'GET',undefined,a,404);
    await request(`/rooms/${a.roomId}/hints?playerId=${sessions[1].playerId}`,'GET',undefined,a,400);
    const hint=await request(`/rooms/${a.roomId}/hints?playerId=${a.playerId}`,'GET',undefined,a);
    assert.equal(hint.playerId,a.playerId);assert.equal(hint.analysis.mode,'WAIT');
  }
  let finished=false;
  for(let step=0;step<1800;step++) {
    const views=await Promise.all(sessions.map(view)); const current=views[0];
    assert.equal(current.players.reduce((n,p)=>n+p.score,0),30);
    assert.ok(current.players.every(p=>p.score>=0));
    for(const v of views) {
      assert.ok(v.players.filter(p=>p.id!==v.meId).every(p=>p.hand.length===0));
      assert.ok(!JSON.stringify(v).includes(sessions.find(p=>p.playerId!==v.meId).token));
    }
    if(discardKinds)verifyPrivateDiscardViews(views);
    const liveTiles = views.flatMap(v=>v.players.find(p=>p.id===v.meId).hand)
      .concat(current.players.flatMap(p=>p.discards.concat(p.melds.flatMap(m=>m.tiles))));
    assert.equal(liveTiles.length+current.wallCount,108);
    assert.equal(new Set(liveTiles.map(t=>t.id)).size,liveTiles.length);
    if(current.result) {
      for(const v of views) assert.deepEqual(v.result,current.result);
      if(!current.result.draw) {
        const result=current.result, winner=result.hands.find(h=>h.playerId===result.winnerId);
        assert.ok(winner,'The result must contain the winner full hand snapshot');
        assert.equal(winner.hand.length+winner.melds.length*3,14,'Winner concealed hand plus declared groups must be complete');
        assert.equal(winner.hand.filter(t=>t.id===result.winningTile.id).length,1,'Winning entity must appear exactly once, including ron');
        const winningEntities=[...winner.hand,...winner.melds.flatMap(m=>m.tiles)];
        assert.equal(new Set(winningEntities.map(t=>t.id)).size,winningEntities.length);
        assert.equal(result.items.reduce((sum,item)=>sum+item.fan,0),result.rawFan);
        assert.equal(result.fan,Math.min(8,result.rawFan));assert.ok(result.fan>=4);
        assert.ok(result.items.every(item=>item.name&&item.description&&item.fan>0));
      }
      if(!results.some(r=>r.round===current.round)) {
        results.push({round:current.round,title:current.result.title,fan:current.result.fan,scores:current.result.scores.map(p=>p.score)});
        process.stdout.write(JSON.stringify(results.at(-1))+'\n');
        if(features)await verifyReplay(sessions[0],current);
      }
    }
    if(current.status==='MATCH_END'){finished=true;break;}
    let played=false;
    for(let i=0;i<views.length;i++) {
      const action=choose(views[i]);if(!action)continue;
      if(features&&action.type==='DISCARD'&&(counts.DISCARD||0)<10) {
        const p=sessions[i], hint=await request(`/rooms/${p.roomId}/hints?playerId=${p.playerId}`,'GET',undefined,p);
        assert.equal(hint.version,views[i].version);assert.equal(hint.analysis.mode,'DISCARD');
        const myHand=views[i].players.find(player=>player.id===p.playerId).hand;
        for(const option of hint.analysis.discards) {
          assert.ok(myHand.some(tile=>tile.id===option.tile.id));
          for(const wait of option.waits) {
            assert.ok(wait.unseenCount>=0&&wait.unseenCount<=4);
            assert.ok(wait.tsumoFan<=8&&wait.ronFan<=8);
            if(wait.canTsumo)assert.ok(wait.tsumoFan>=4);if(wait.canRon)assert.ok(wait.ronFan>=4);
          }
        }
      }
      const after=await act(sessions[i],action,views[i]);
      if(discardKinds && action.type==='DISCARD') {
        const before=views[i], actor=before.players.find(player=>player.id===before.meId), tileId=action.tileIds[0];
        assert.ok(actor.hand.some(tile=>tile.id===tileId));
        const kind=actor.drawnTileId===tileId?'TSUMOGIRI':'TEDASHI';
        assert.ok(!history(before.round).has(tileId),'A physical tile cannot be discarded twice in one hand');
        history(before.round).set(tileId,{kind,seat:actor.seat});
        observedDiscardKinds[kind]++;
        assert.equal(after.lastDiscard?.tile.id,tileId);
        verifyDiscardState(after,history(before.round),'Immediate discard response');
      }
      counts[action.type]=(counts[action.type]||0)+1;played=true;break;
    }
    assert.ok(played,`No progress possible: ${current.status}`);
  }
  assert.ok(finished,'Game did not reach terminal settlement');
  if(discardKinds) {
    assert.ok(observedDiscardKinds.TSUMOGIRI>0 && observedDiscardKinds.TEDASHI>0,'Both physical discard kinds must be exercised');
    console.log(JSON.stringify({discardKinds:true,observed:observedDiscardKinds,claimedDiscardsChecked:claimedDiscards.size}));
  }
  if(features) { if(streamError)throw streamError;assert.ok(notices.filter(n=>n.type==='CHANGED').length>2); }
  console.log(JSON.stringify({ok:true,base,actions:counts,hands:results.length}));
  if(inspectFinal) {
    console.log(JSON.stringify({browserSeat:sessions[0],note:'Only this test seat; resume through the normal UI. Press Enter after review to leave all test seats.'}));
    await new Promise(resolve=>{
      const input=createInterface({input:process.stdin});
      const timeout=setTimeout(()=>input.close(),600000);
      input.once('line',()=>input.close());input.once('close',()=>{clearTimeout(timeout);resolve();});
    });
  }
} finally {
  for(const p of sessions) {
    try { await act(p,{type:'LEAVE'}); } catch(e) { console.error('Test cleanup failure:',e.message); }
  }
  if(features) {streamAbort?.abort();await streamDone;}
  if(sessions[0]) assert.ok(!(await request('/rooms')).some(r=>r.id===sessions[0].roomId),'Test room was not removed');
  if(features) {
    if(replayedRounds.length) {
      const p=sessions[0];
      const archive=await request(`/replays?roomId=${p.roomId}&playerId=${p.playerId}`,'GET',undefined,p);
      assert.deepEqual(archive.hands.map(h=>h.round),replayedRounds);
      await request(`/replays/${p.roomId}/${replayedRounds[0]}?playerId=${sessions[1].playerId}`,'GET',undefined,p,400);
      if(discardKinds)for(const round of replayedRounds) {
        const replay=await request(`/replays/${p.roomId}/${round}?playerId=${p.playerId}`,'GET',undefined,p);
        assert.deepEqual(replayKindSnapshot(replay),replayDiscardSnapshots.get(round),'Later hands and leaving must not mutate archived discard kinds');
      }
      console.log(JSON.stringify({features:true,replayedRounds,streamEvents:notices.length,archiveAfterLeave:true}));
    }
  }
}
