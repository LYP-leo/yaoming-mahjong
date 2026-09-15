import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';

// Deterministic, local-only HTTP checks with an isolated saved deck, no production hooks.
const root=fileURLToPath(new URL('../artifacts/nine-ranks-no-honors-20260909/http-runtime/',import.meta.url));
const scenarios=[
  {id:'nine-negative',hand:'B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3 H3 H6',win:'H6',ron:false,fan:1,nine:false},
  {id:'nine-positive',hand:'W1 W1 W1 D9 D9 D9 B2 B2 B3 B4 B5 B6 B7',win:'B8',ron:true,fan:5,nine:true},
  {id:'nine-other-fans',hand:'B1 B2 B3 B4 B5 B6 B7 B8 B9 H5 H5 H5 H6',win:'H6',ron:true,fan:4,nine:false},
];
const code=t=>({CHARACTERS:'W',BAMBOO:'B',DOTS:'D',HONORS:'H'}[t.suit]+t.rank);
const codes=['W1','W5','W9',...Array.from({length:9},(_,i)=>`B${i+1}`),...Array.from({length:9},(_,i)=>`D${i+1}`),'H1','H2','H3','H5','H6','H7'];
const identity=(room,seat)=>({playerId:`local-${seat}`,token:`nine-ranks-local-test-${room}-${seat}`});
if(process.argv[2]==='--seed') {
  const rooms=scenarios.map(s=>{
    const wall=codes.flatMap(c=>Array.from({length:4},(_,i)=>({id:`ym-${c}-${i}`,
      suit:{W:'CHARACTERS',B:'BAMBOO',D:'DOTS',H:'HONORS'}[c[0]],rank:Number(c[1]),red:false,
      label:c[0]==='H'?['东','南','西','北','中','发','白'][Number(c[1])-1]:c[1]+{W:'万',B:'条',D:'筒'}[c[0]]})));
    function take(c){const at=wall.findIndex(t=>code(t)===c);assert.ok(at>=0);return wall.splice(at,1)[0];}
    const players=[0,1,2].map(seat=>({id:identity(s.id,seat).playerId,token:identity(s.id,seat).token,
      name:`隔离验证${seat}`,seat,score:10,ready:true,hand:[],discards:[],melds:[]}));
    players[1].hand=s.hand.split(' ').map(take);players[0].hand=[take(s.win)];
    while(players[0].hand.length<14)players[0].hand.push(wall.shift());
    while(players[2].hand.length<13)players[2].hand.push(wall.shift());
    players[0].lastDrawnId=players[0].hand.at(-1).id;
    const all=[...wall,...players.flatMap(p=>p.hand)];
    assert.equal(all.length,108);assert.equal(new Set(all.map(t=>t.id)).size,108);
    return {id:s.id,name:s.id,hostId:'local-0',phase:'NEED_DISCARD',round:1,dealerSeat:0,currentSeat:0,
      deadlineKind:'DISCARD',deadlineAt:Date.now()+600000,lastActivity:Date.now(),version:1,players,wall};
  });
  await mkdir(resolve(root,'data'),{recursive:true});
  await writeFile(resolve(root,'data/yaoming-rooms.json'),JSON.stringify(rooms),{flag:'wx'});
  console.log(JSON.stringify({seeded:rooms.length,physicalTilesPerRoom:108}));process.exit(0);
}
const url=new URL(process.argv[2]||'http://127.0.0.1:18093');
assert.equal(url.hostname,'127.0.0.1');assert.equal(url.port,'18093');
const api=`${url.origin}/api/yaoming`;
async function request(path,id,body,status=200){
  const r=await fetch(api+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',
    ...(id?{'X-Resume-Token':id.token}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(5000)});
  assert.equal(r.status,status,`${path} status`);const raw=await r.text();return raw?JSON.parse(raw):null;
}
const view=(room,seat)=>request(`/rooms/${room}?playerId=local-${seat}`,identity(room,seat));
async function act(room,seat,type,tileIds=[],status=200){
  const v=await view(room,seat),id=identity(room,seat);
  return request(`/rooms/${room}/actions`,id,{playerId:id.playerId,version:v.version,requestId:randomUUID(),type,tileIds},status);
}
assert.deepEqual((await request('/rooms')).map(r=>r.id).sort(),scenarios.map(s=>s.id).sort());
const rules=await request('/rules'),nine=rules.fans.find(f=>f.id==='JIUSHUQI');
assert.equal(nine.fan,4);assert.ok(nine.description.includes('不得含字牌'));
const results=[];
for(const s of scenarios){
  const hint=await request(`/rooms/${s.id}/hints?playerId=local-1`,identity(s.id,1));
  const wait=hint.analysis.waits.find(w=>code(w.tile)===s.win);
  assert.ok(wait);assert.equal(wait.canRon,s.ron);assert.equal(wait.ronFan,s.fan);
  const from=await view(s.id,0),tile=from.players.find(p=>p.id==='local-0').hand.find(t=>code(t)===s.win);
  await act(s.id,0,'DISCARD',[tile.id]);
  const offered=await view(s.id,1);
  assert.equal(offered.actions.some(a=>a.type==='WIN'),s.ron);
  for(const p of offered.players)if(p.id!=='local-1')assert.deepEqual(p.hand,[]);
  if(!s.ron){
    await act(s.id,1,'WIN',[],400);
    const unchanged=await view(s.id,1);assert.equal(unchanged.version,offered.version);assert.equal(unchanged.status,offered.status);
    assert.ok(!unchanged.result);
    for(const seat of [1,2]){const v=await view(s.id,seat);if(v.actions.some(a=>a.type==='PASS'))await act(s.id,seat,'PASS');}
    const next=await view(s.id,1);assert.equal(next.status,'NEED_DRAW');await act(s.id,1,'DRAW');
    assert.equal((await view(s.id,1)).status,'NEED_DISCARD');
    results.push({room:s.id,incorrectWinRejected:true,handContinues:true,ronFan:wait.ronFan});continue;
  }
  await act(s.id,1,'WIN');
  const other=await view(s.id,2);if(other.actions.some(a=>a.type==='PASS'))await act(s.id,2,'PASS');
  const views=await Promise.all([0,1,2].map(seat=>view(s.id,seat)));
  const result=views[0].result;assert.equal(result.winnerId,'local-1');assert.equal(result.fan,s.fan);
  assert.equal(result.items.some(f=>f.id==='JIUSHUQI'),s.nine);
  assert.equal(result.hands.find(p=>p.playerId==='local-1').hand.length,14);
  assert.equal(result.scores.reduce((sum,p)=>sum+p.score,0),30);
  for(const v of views)assert.deepEqual(v.result,result);
  const replay=await request(`/replays/${s.id}/1?playerId=local-1`,identity(s.id,1));
  assert.deepEqual(replay.result,result);
  results.push({room:s.id,fan:result.fan,nineRanks:s.nine,threeSeatSettlement:true,replayMatches:true});
}
console.log(JSON.stringify({ok:true,results}));
