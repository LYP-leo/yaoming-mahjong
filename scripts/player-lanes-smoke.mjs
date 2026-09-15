import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

// Standalone local integration check. No production address, recovery code, or saved room is accepted.
const origin = 'http://127.0.0.1:18094';
assert.equal(process.argv.length, 2, 'This isolated smoke test takes no arguments');
const api = `${origin}/api/yaoming`;
const suitCode = { CHARACTERS: 'W', BAMBOO: 'B', DOTS: 'D', HONORS: 'H' };
const legalCodes = new Set(['W1', 'W5', 'W9', ...Array.from({ length: 9 }, (_, i) => `B${i + 1}`),
  ...Array.from({ length: 9 }, (_, i) => `D${i + 1}`), 'H1', 'H2', 'H3', 'H5', 'H6', 'H7']);
const code = tile => suitCode[tile.suit] + tile.rank;
let commandCount = 0;

async function request(path, identity, body, status = 200) {
  const response = await fetch(api + path, { method: body ? 'POST' : 'GET', redirect: 'error',
    headers: { 'Content-Type': 'application/json', ...(identity ? { 'X-Resume-Token': identity.token } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(5000) });
  assert.equal(response.status, status, `${path}: HTTP status`);
  const raw = await response.text(); return raw ? JSON.parse(raw) : null;
}

const created = await request('/rooms', null, { name: '纵向玩家分区隔离回归', playerName: '本地验证甲' }, 201);
const identities = [created];
for (const name of ['本地验证乙', '本地验证丙'])
  identities.push(await request(`/rooms/${created.roomId}/join`, null, { playerName: name }));
const roomId = created.roomId;
assert.ok(identities.every(identity => identity.roomId === roomId));
assert.equal(new Set(identities.map(identity => identity.playerId)).size, 3);
assert.equal(new Set(identities.map(identity => identity.token)).size, 3);

const view = identity => request(`/rooms/${roomId}?playerId=${identity.playerId}`, identity);
const self = (current, identity) => current.players.find(player => player.id === identity.playerId);
function identityForSeat(current, seat) {
  const owner = current.players.find(player => player.seat === seat);
  assert.ok(owner); const identity = identities.find(identity => identity.playerId === owner.id);
  assert.ok(identity); return identity;
}
async function act(identity, type, tileIds = [], status = 200, version) {
  const current = await view(identity);
  const result = await request(`/rooms/${roomId}/actions`, identity,
    { playerId: identity.playerId, version: version ?? current.version, requestId: randomUUID(), type, tileIds }, status);
  commandCount++; return result;
}

async function inspect() {
  const views = await Promise.all(identities.map(view));
  const reference = views[0];
  for (const [index, current] of views.entries()) {
    assert.equal(current.version, reference.version, 'Every player receives the same room revision');
    assert.equal(current.status, reference.status);
    assert.equal(current.meId, identities[index].playerId);
    assert.deepEqual(current.players.map(player => player.seat), [0, 1, 2]);
    for (const player of current.players) {
      assert.equal(player.wind, ['东', '南', '西'][(player.seat - current.dealerSeat + 3) % 3]);
      if (player.id === current.meId) assert.equal(player.hand.length, player.handSize);
      else { assert.deepEqual(player.hand, []); assert.equal(player.drawnTileId ?? null, null); }
      assert.ok(player.score >= 0);
    }
  }
  // Combining three independently authorized views is only for this all-human local test.
  const physical = [...views.flatMap((current, index) => self(current, identities[index]).hand),
    ...reference.players.flatMap(player => [...player.discards, ...player.melds.flatMap(meld => meld.tiles)])];
  if (reference.status !== 'WAITING') {
    assert.equal(physical.length + reference.wallCount, 108, '108 physical tiles are conserved');
    assert.equal(new Set(physical.map(tile => tile.id)).size, physical.length, 'No public/owned entity is counted twice');
    assert.ok(physical.every(tile => legalCodes.has(code(tile)) && !tile.red));
    for (const kind of legalCodes) assert.ok(physical.filter(tile => code(tile) === kind).length <= 4);
  }
  assert.equal(reference.players.reduce((sum, player) => sum + player.score, 0), 30);
  return views;
}

let snapshots = await inspect();
assert.equal(snapshots[0].status, 'WAITING');
assert.ok(snapshots.every((current, index) => self(current, identities[index]).handSize === 0));
for (const identity of identities) await act(identity, 'READY');
snapshots = await inspect();
assert.equal(snapshots[0].status, 'NEED_DRAW');
assert.equal(snapshots[0].currentSeat, snapshots[0].dealerSeat);
assert.equal(snapshots[0].wallCount, 69);
assert.ok(snapshots.every((current, index) => self(current, identities[index]).handSize === 13));
const startingWinds = snapshots[0].players.map(player => ({ seat: player.seat, wind: player.wind }));

// Reject an out-of-turn draw before entering the scripted valid action loop.
const first = snapshots[0], nonCurrent = identityForSeat(first, (first.currentSeat + 1) % 3);
await act(nonCurrent, 'DRAW', [], 400);
assert.equal((await view(nonCurrent)).version, first.version);

const turns = [];
for (let turn = 0; turn < 6; turn++) {
  snapshots = await inspect(); const before = snapshots[0];
  assert.equal(before.status, 'NEED_DRAW');
  const actingIdentity = identityForSeat(before, before.currentSeat);
  const ownBefore = self(await view(actingIdentity), actingIdentity);
  assert.equal(ownBefore.handSize + ownBefore.melds.length * 3, 13);
  const drawn = await act(actingIdentity, 'DRAW'), owner = self(drawn, actingIdentity);
  assert.equal(drawn.status, 'NEED_DISCARD'); assert.equal(drawn.currentSeat, before.currentSeat);
  assert.equal(drawn.wallCount, before.wallCount - 1); assert.equal(owner.handSize, ownBefore.handSize + 1);
  const winningEntity = owner.hand.find(tile => tile.id === owner.drawnTileId);
  assert.ok(winningEntity); assert.ok(!ownBefore.hand.some(tile => tile.id === winningEntity.id));
  assert.ok(ownBefore.hand.every(tile => owner.hand.some(retained => retained.id === tile.id)));
  const tsumogiri = turn % 2 === 0;
  const discarded = tsumogiri ? winningEntity : owner.hand.find(tile => tile.id !== owner.drawnTileId);
  assert.ok(discarded);
  assert.ok(drawn.actions.some(action => action.type === 'DISCARD' && action.tileIds.length === 1 && action.tileIds[0] === discarded.id));
  if (turn === 0) {
    await act(actingIdentity, 'DISCARD', [discarded.id], 409, before.version);
    const stillDrawn = await view(actingIdentity);
    assert.equal(stillDrawn.version, drawn.version); assert.deepEqual(self(stillDrawn, actingIdentity).hand, owner.hand);
  }
  const after = await act(actingIdentity, 'DISCARD', [discarded.id]);
  const kind = tsumogiri ? 'TSUMOGIRI' : 'TEDASHI';
  assert.equal(after.lastDiscard.tile.id, discarded.id); assert.equal(after.lastDiscard.fromSeat, owner.seat);
  assert.equal(after.lastDiscard.kind, kind); assert.equal(after.lastDiscard.claimed, false);
  assert.equal(self(after, actingIdentity).discardKinds[discarded.id], kind);
  assert.equal(self(after, actingIdentity).handSize, ownBefore.handSize);
  assert.ok(!self(after, actingIdentity).hand.some(tile => tile.id === discarded.id));
  if (!tsumogiri) assert.ok(self(after, actingIdentity).hand.some(tile => tile.id === winningEntity.id));
  await inspect();
  for (const identity of identities) {
    const current = await view(identity);
    if (current.actions.some(action => action.type === 'PASS')) await act(identity, 'PASS');
  }
  const next = (await inspect())[0];
  assert.equal(next.status, 'NEED_DRAW'); assert.equal(next.currentSeat, (before.currentSeat + 1) % 3);
  assert.equal(next.result ?? null, null);
  turns.push({ turn: turn + 1, seat: owner.seat, wind: owner.wind, discardKind: kind, wallCount: next.wallCount });
}

console.log(JSON.stringify({ ok: true, endpoint: origin, roomId, humanPlayers: 3, startingWinds, validTurns: turns,
  commandCount, outOfTurnRejected: true, staleRevisionRejected: true, ownHandsComplete: true,
  opponentHandsPrivate: true, physicalTiles: 108, totalPoints: 30, serverLeftRunning: true }));
