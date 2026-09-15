import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';

// Only run against an explicitly isolated local backend. Creates its own two-human/one-bot room.
const base = (process.argv[2] || 'http://127.0.0.1:18089').replace(/\/$/, '');
const url = new URL(base);
assert.ok(['127.0.0.1','localhost'].includes(url.hostname) && url.port === '18089', 'Use isolated localhost port 18089');
const sessions = [];
async function request(path, method = 'GET', body, identity) {
  const response = await fetch(`${base}/api/yaoming${path}`, {method, headers: {'Content-Type':'application/json', Origin:base,
    ...(identity ? {'X-Resume-Token':identity.token} : {})}, body: body === undefined ? undefined : JSON.stringify(body), signal:AbortSignal.timeout(5000)});
  const raw = await response.text();
  if (response.status === 409) throw Object.assign(new Error('version conflict'), {conflict:true});
  assert.ok(response.ok, `${method} ${path}: ${response.status}`);
  return raw ? JSON.parse(raw) : null;
}
const view = identity => request(`/rooms/${identity.roomId}?playerId=${identity.playerId}`, 'GET', undefined, identity);
async function act(identity, type) {
  for (let attempt=0; attempt<5; attempt++) {
    const current = await view(identity);
    const action = current.actions.find(action => action.type === type);
    assert.ok(action, `Expected available ${type}`);
    try { return await request(`/rooms/${identity.roomId}/actions`, 'POST', {playerId:identity.playerId, token:identity.token,
      version:current.version, requestId:randomUUID(), type, tileIds:action.tileIds}, identity); }
    catch (error) { if (!error.conflict || attempt === 4) throw error; }
  }
}
try {
  const host = await request('/rooms', 'POST', {name:'隔离智能机器人验收',playerName:'测试甲'}); sessions.push(host);
  const guest = await request(`/rooms/${host.roomId}/join`, 'POST', {playerName:'测试乙'}); sessions.push(guest);
  await act(host, 'ADD_BOT');
  let current = await view(host);
  assert.equal(current.players.filter(player => player.bot).length, 1);
  assert.equal(current.players.filter(player => !player.bot).length, 2);
  const bot = current.players.find(player => player.bot);
  for (const identity of sessions) await act(identity, 'READY');
  for (const identity of sessions) await act(identity, 'TRUSTEE');
  const start = Date.now(), observedDiscards = new Set();
  let firstVersion, latestVersion, samples = 0;
  while (Date.now()-start < 45000) {
    current = await view(host); firstVersion ??= current.version; latestVersion = current.version; samples++;
    assert.equal(current.players.reduce((sum,player)=>sum+player.score,0),30);
    assert.ok(current.players.filter(player=>player.id!==host.playerId).every(player=>player.hand.length===0));
    assert.ok(current.players.filter(player=>!player.bot).every(player=>player.trustee));
    assert.ok(!Object.hasOwn(current,'wall'));
    current.players.find(player=>player.id===bot.id).discards.forEach(tile=>observedDiscards.add(tile.id));
    if (current.result && !current.result.draw) assert.ok(current.result.fan >= 4);
    if (observedDiscards.size >= 2 || current.result) break;
    await new Promise(resolve=>setTimeout(resolve,300));
  }
  assert.ok(observedDiscards.size>=2 || current.result, 'Robot must advance without any client DRAW/DISCARD');
  assert.ok(latestVersion>firstVersion, 'Server state must advance');
  console.log(JSON.stringify({ok:true,mode:'two humans plus one bot, both humans trustee',samples,botDiscardEntities:observedDiscards.size,
    versionAdvances:latestVersion-firstVersion,elapsedMs:Date.now()-start,status:current.status}));
} finally {
  for (const identity of sessions) await act(identity,'LEAVE');
  if (sessions.length) assert.ok(!(await request('/rooms')).some(room=>room.id===sessions[0].roomId));
  console.log('Isolated bot test room removed through normal LEAVE actions; no player tokens logged.');
}
