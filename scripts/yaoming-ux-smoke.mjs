import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';

// Public API only: keep a human connected but idle, then recover control.
const base = (process.argv[2] || 'http://127.0.0.1:5173').replace(/\/$/, '');
const identities = [];
async function request(path, method = 'GET', body, identity) {
  const options = { method, headers: { 'Content-Type': 'application/json', Origin: base,
    ...(identity ? { 'X-Resume-Token': identity.token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) };
  let response;
  try { response = await fetch(`${base}/api/yaoming${path}`, options); }
  catch (error) {
    if (method !== 'GET' && !body?.requestId) throw error;
    response = await fetch(`${base}/api/yaoming${path}`, { ...options, signal: AbortSignal.timeout(15000) });
  }
  const text = await response.text();
  if (!response.ok) throw Object.assign(new Error(`${method} ${path}: ${response.status} ${text}`), { status: response.status });
  return text ? JSON.parse(text) : null;
}
const view = p => request(`/rooms/${p.roomId}?playerId=${p.playerId}`, 'GET', undefined, p);
const mine = (v, p) => v.players.find(player => player.id === p.playerId);
async function act(p, type, tileIds = []) {
  for (let attempt = 0; attempt < 3; attempt++) {
    const v = await view(p);
    const body = { playerId: p.playerId, token: p.token, version: v.version,
      requestId: randomUUID(), type, tileIds };
    try { return { body, result: await request(`/rooms/${p.roomId}/actions`, 'POST', body, p) }; }
    catch (error) { if (error.status !== 409 || attempt === 2) throw error; }
  }
}

try {
  identities.push(await request('/rooms', 'POST', { name: '优化第一批验收', playerName: '体验甲' }));
  for (const playerName of ['体验乙', '体验丙'])
    identities.push(await request(`/rooms/${identities[0].roomId}/join`, 'POST', { playerName }));
  for (const p of identities) await act(p, 'READY');
  const opening = await view(identities[0]);
  assert.equal(opening.deadlineKind, 'DRAW');
  const dealer = identities.find(p => opening.players.find(player => player.id === p.playerId).seat === opening.currentSeat);
  const openingDeadline = opening.deadlineAt;
  const deadlineDelta = Date.parse(openingDeadline) - Date.parse(opening.serverTime);
  assert.ok(deadlineDelta > 0 && deadlineDelta <= 15000, 'draw timer must originate on the server');
  const timeLimit = Date.now() + 22000;
  let timedOut;
  while (Date.now() < timeLimit) {
    const views = await Promise.all(identities.map(view));
    const v = views[identities.indexOf(dealer)];
    if (mine(v, dealer).trusteeReason === 'TIMEOUT') { timedOut = v; break; }
    assert.equal(v.deadlineAt, openingDeadline, 'heartbeats must not extend the turn');
    await pause(250);
  }
  assert.ok(timedOut, 'an online idle player must enter timeout trustee mode');
  assert.equal(mine(timedOut, dealer).trustee, true);
  assert.equal(mine(await view(dealer), dealer).trusteeReason, 'TIMEOUT', 'polling must not cancel timeout trustee');
  const recovered = (await act(dealer, 'TRUSTEE')).result;
  assert.equal(mine(recovered, dealer).trustee, false);
  if (recovered.currentSeat === mine(recovered, dealer).seat) {
    assert.ok(Date.parse(recovered.deadlineAt) > Date.parse(recovered.serverTime), 'taking control renews your turn');
  }
  console.log(JSON.stringify({ test: 'online-idle-timeout-and-recovery', ok: true, base }));

  // Reach a manual draw using only the actions currently offered by the server.
  for (let guard = 0; guard < 10; guard++) {
    const views = await Promise.all(identities.map(view));
    const drawIndex = views.findIndex(v => v.actions.some(a => a.type === 'DRAW'));
    if (drawIndex >= 0) {
      const p = identities[drawIndex];
      const { result: drawn, body } = await act(p, 'DRAW');
      const drawId = mine(drawn, p).drawnTileId;
      assert.ok(drawId && mine(drawn, p).hand.some(t => t.id === drawId));
      assert.equal(drawn.deadlineKind, 'DISCARD');
      const duplicate = await request(`/rooms/${p.roomId}/actions`, 'POST', body, p);
      assert.equal(duplicate.deadlineAt, drawn.deadlineAt, 'duplicate draw must not renew timer');
      assert.equal(duplicate.wallCount, drawn.wallCount, 'duplicate draw must not consume another tile');
      for (const observer of identities.filter(other => other !== p)) {
        const observed = await view(observer);
        assert.ok(!mine(observed, p).drawnTileId, 'opponent must not learn a private drawn tile ID');
      }
      const discarded = (await act(p, 'DISCARD', [drawId])).result;
      assert.equal(discarded.lastDiscard.tile.id, drawId);
      assert.equal(discarded.lastDiscard.fromSeat, mine(drawn, p).seat);
      assert.equal(discarded.lastDiscard.claimed, false);
      assert.ok(!mine(discarded, p).drawnTileId);
      console.log(JSON.stringify({ test: 'private-drawn-tile-idempotency-latest-discard', ok: true }));
      break;
    }
    const playerIndex = views.findIndex(v => v.actions.some(a => ['PASS', 'DISCARD'].includes(a.type)));
    assert.ok(playerIndex >= 0, 'expected a legal path to the next draw');
    const action = views[playerIndex].actions.find(a => ['PASS', 'DISCARD'].includes(a.type));
    await act(identities[playerIndex], action.type, action.tileIds);
    assert.ok(guard < 9, 'failed to reach the next draw');
  }
} finally {
  for (const p of identities) {
    try { await act(p, 'LEAVE'); }
    catch (error) { console.error(`Test cleanup failed: ${error.message}`); }
  }
  if (identities.length) {
    const rooms = await request('/rooms');
    assert.ok(!rooms.some(r => r.id === identities[0].roomId), 'temporary test room must be removed');
  }
}
