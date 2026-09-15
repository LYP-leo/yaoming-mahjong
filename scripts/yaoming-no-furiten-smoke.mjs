import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';

// Deterministic fixture ONLY in a reserved local artifact directory. Never seed
// a running or public server. Verification refuses any non-loopback endpoint.
const root = fileURLToPath(new URL('../artifacts/no-furiten-20260909/http-runtime-final/', import.meta.url));
const file = resolve(root, 'data/yaoming-rooms.json');
const roomId = 'nofuriten-fixture';
const code = t => ({ CHARACTERS: 'W', BAMBOO: 'B', DOTS: 'D', HONORS: 'H' }[t.suit] + t.rank);
const codes = ['W1','W5','W9', ...Array.from({length:9},(_,i)=>`B${i+1}`),
  ...Array.from({length:9},(_,i)=>`D${i+1}`), 'H1','H2','H3','H5','H6','H7'];
const identities = [0,1,2].map(seat => ({ roomId, playerId:`fixture-p${seat}`, token:`local-no-furiten-fixture-token-${seat}` }));
if (process.argv[2] === '--seed') {
  const wall = codes.flatMap(c => Array.from({length:4},(_,i) => ({id:`ym-${c}-${i}`,
    suit:{W:'CHARACTERS',B:'BAMBOO',D:'DOTS',H:'HONORS'}[c[0]], rank:Number(c[1]),
    label:c[0]==='H'?['东','南','西','北','中','发','白'][Number(c[1])-1]:c[1]+{W:'万',B:'条',D:'筒'}[c[0]], red:false })));
  function take(c) { const at=wall.findIndex(t=>code(t)===c); assert.ok(at>=0); return wall.splice(at,1)[0]; }
  const players=identities.map((p,seat)=>({id:p.playerId,token:p.token,name:`隔离验证${seat}`,seat,
    score:10,ready:true,hand:[],discards:[],discardKinds:{},melds:[],discardedCodes:[],passedCodes:[]}));
  players[2].hand='B2 B3 B2 B3 B4 B6 B6 B6 B9 B9 B9 B5 B5'.split(' ').map(take);
  const previous=take('B1');players[2].discards.push(previous);players[2].discardKinds[previous.id]='TEDASHI';
  players[2].discardedCodes=['B1'];players[2].passedCodes=['B1'];
  players[0].hand.push(take('B1'));
  const nextDraw=take('B1');
  while(players[0].hand.length<14)players[0].hand.push(wall.shift());
  while(players[1].hand.length<13)players[1].hand.push(wall.shift());
  wall.unshift(nextDraw);players[0].lastDrawnId=players[0].hand.at(-1).id;
  const physical=[...wall,...players.flatMap(p=>[...p.hand,...p.discards])];
  assert.equal(physical.length,108);assert.equal(new Set(physical.map(t=>t.id)).size,108);
  const now=Date.now();
  await mkdir(resolve(root,'data'),{recursive:true});
  await writeFile(file, JSON.stringify([{id:roomId,name:'取消振听隔离验证',hostId:players[0].id,version:1,
    phase:'NEED_DISCARD',round:1,dealerSeat:0,currentSeat:0,players,wall,lastActivity:now,
    deadlineAt:now+600000,deadlineKind:'DISCARD',events:[]}]),{flag:'wx'});
  console.log(JSON.stringify({seeded:true,file,physicalTiles:108,legacyRestrictionFields:true}));
  process.exit(0);
}
const base=process.argv[2]||'http://127.0.0.1:18092';
const url=new URL(base);assert.equal(url.hostname,'127.0.0.1');assert.equal(url.port,'18092');
const api=`${url.origin}/api/yaoming`;
const events=[];
async function request(path, identity, body) {
  const response=await fetch(api+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',
    ...(identity?{'X-Resume-Token':identity.token}:{})},body:body?JSON.stringify(body):undefined,
    signal:AbortSignal.timeout(5000)});
  assert.equal(response.status,200,`${path} HTTP status`);
  const raw=await response.text();return raw?JSON.parse(raw):null;
}
const view=seat=>request(`/rooms/${roomId}?playerId=${identities[seat].playerId}`,identities[seat]);
async function act(seat,type,tileIds=[]) {
  const identity=identities[seat],v=await view(seat);
  assert.ok(v.actions.some(a=>a.type===type && (type!=='DISCARD'||a.tileIds[0]===tileIds[0])),`missing ${type} for seat ${seat}`);
  const result=await request(`/rooms/${roomId}/actions`,identity,{playerId:identity.playerId,
    version:v.version,requestId:randomUUID(),type,tileIds});
  events.push({seat,type});return result;
}
async function checkViews() {
  const views=await Promise.all([0,1,2].map(view));
  for(const v of views)for(const p of v.players)if(p.id!==v.meId) {
    assert.deepEqual(p.hand,[]);assert.deepEqual(p.discardedCodes,[]);assert.deepEqual(p.passedCodes,[]);
    assert.ok(!p.drawnTileId);
  }
  for(const v of views)for(const identity of identities)assert.ok(!JSON.stringify(v).includes(identity.token));
  return views;
}
async function waitB1(expectedUnseen) {
  const hint=await request(`/rooms/${roomId}/hints?playerId=fixture-p2`,identities[2]);
  const w=hint.analysis.waits.find(w=>code(w.tile)==='B1');assert.ok(w);
  assert.equal(w.canRon,true);assert.equal(w.ronReason,'');assert.equal(w.unseenCount,expectedUnseen);
}
async function discardB1(seat) {
  const v=await view(seat),tile=v.players.find(p=>p.id===v.meId).hand.find(t=>code(t)==='B1');
  assert.ok(tile);return act(seat,'DISCARD',[tile.id]);
}
assert.deepEqual((await request('/rooms')).map(r=>r.id),[roomId],'Only the seeded isolated room may exist');
const rules=await request('/rules');
assert.ok(!rules.notes.some(n=>/不能再点和同种牌|下一次摸牌前不能点和/.test(n)));
await checkViews();await waitB1(3);
await discardB1(0);
assert.ok((await view(2)).actions.some(a=>a.type==='WIN'),'own past discard and legacy pass must not block the first ron');
await waitB1(2);await act(2,'PASS');
let first=await view(1);if(first.actions.some(a=>a.type==='PASS'))await act(1,'PASS');
assert.equal((await view(1)).status,'NEED_DRAW');
const before=await view(2),original=before.players.find(p=>p.id===before.meId).hand.map(t=>t.id).sort();
await act(1,'DRAW');await discardB1(1);await checkViews();await waitB1(1);
const after=await view(2);
assert.deepEqual(after.players.find(p=>p.id===after.meId).hand.map(t=>t.id).sort(),original,'winner must not draw between the two offers');
assert.ok(after.actions.some(a=>a.type==='WIN'),'passing must not block the next identical ron before drawing');
await act(2,'WIN');
const other=await view(0);if(other.actions.some(a=>a.type==='PASS'))await act(0,'PASS');
const settled=await Promise.all([0,1,2].map(view));
for(const v of settled) {
  assert.ok(['HAND_END','MATCH_END'].includes(v.status));assert.equal(v.result.winnerId,'fixture-p2');
  assert.equal(v.result.draw,false);assert.equal(code(v.result.winningTile),'B1');assert.ok(v.result.fan>=4);
  assert.equal(v.result.hands.find(p=>p.playerId==='fixture-p2').hand.length,14);
  assert.equal(v.result.scores.reduce((sum,p)=>sum+p.score,0),30);
  assert.deepEqual(v.result,settled[0].result);
}
console.log(JSON.stringify({success:true,oldDiscardAndPassIgnored:true,passThenSameTileRonWithoutDraw:true,
  unseenCounts:[3,2,1],privateViews:6,threeSeatSettlement:true,fan:settled[0].result.fan,events}));
