import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';

// The target and fake credentials are fixed: this test cannot write to the public server.
const origin = 'http://127.0.0.1:18094';
const api = `${origin}/api/yaoming`;
const runtime = fileURLToPath(new URL('../artifacts/four-player-three-fan-20260912/runtime/', import.meta.url));
const threeCodes = ['W1', 'W5', 'W9', ...'123456789'.split('').map(n => `B${n}`),
  ...'123456789'.split('').map(n => `D${n}`), 'H1', 'H2', 'H3', 'H5', 'H6', 'H7'];
const fourCodes = [...['W', 'B', 'D'].flatMap(s => '123456789'.split('').map(n => s + n)),
  ...'1234567'.split('').map(n => `H${n}`)];
const code = tile => ({ CHARACTERS: 'W', BAMBOO: 'B', DOTS: 'D', HONORS: 'H' }[tile.suit] + tile.rank);
const identity = (roomId, seat) => ({ roomId, playerId: `fake-four-${roomId}-${seat}`, token: `fake-four-token-${roomId}-${seat}` });
const scenarios = [
  { id: 'four-self', size: 4, hand: 'W1 W2 W3 W4 W5 W6 W7 W8 W9 W5 W5 W5 W2 W2', win: 'W2', self: 5, ron: 4,
    via: 'DRAW', items: { MENQING: 1, BUQIUREN: 1, QINGYISE: 3 } },
  { id: 'four-ron', size: 4, hand: 'W1 W2 W3 W4 W5 W6 W7 W8 W9 W5 W5 W5 W2 W2', win: 'W2', self: 5, ron: 4,
    via: 'RON', items: { MENQING: 1, QINGYISE: 3 } },
  { id: 'four-unconnected', size: 4, hand: 'W1 W4 W7 B2 B5 B8 D3 H1 H2 H3 H4 H5 H6 H7', win: 'H7', self: 3, ron: 2,
    via: 'DRAW', items: { QUANBUKAO: 2, BUQIUREN: 1 } },
  { id: 'four-unconnected-ron-low', size: 4, hand: 'W1 W4 W7 B2 B5 B8 D3 H1 H2 H3 H4 H5 H6 H7', win: 'H7', self: 3, ron: 2, via: 'RON' },
  { id: 'four-flat-three', size: 4, hand: 'W1 W2 W3 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2', win: 'D2', self: 3, ron: 2,
    via: 'DRAW', items: { PINGHE: 1, MENQING: 1, BUQIUREN: 1 } },
  { id: 'four-ron-three', size: 4, hand: 'W2 W3 W4 W3 W4 W5 W5 W6 W7 W6 W7 W8 H3 H3', win: 'H3', self: 4, ron: 3,
    via: 'RON', items: { MENQING: 1, HUNYISE: 2 } },
  { id: 'four-self-two-low', size: 4, hand: 'B1 B2 B3 D4 D5 D6 B7 B8 B9 W5 W5 W5 H3 H3', win: 'H3', self: 2, ron: 1, via: 'DRAW' },
  { id: 'three-self-three-low', size: 3, hand: 'B1 B2 B3 D4 D5 D6 B7 B8 B9 W5 W5 W5 H3 H3', win: 'H3', self: 3, ron: 2, via: 'DRAW' },
  { id: 'three-flat', size: 3, hand: 'W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2', win: 'D2', self: 4, ron: 3,
    via: 'DRAW', items: { PINGHE: 1, MENQING: 2, BUQIUREN: 1 } },
  { id: 'four-north-chi', size: 4, hand: 'W1 W2 W3 B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3', win: 'W2', self: 2, ron: 1, via: 'RON', chi: true },
  { id: 'four-last-round', size: 4, hand: 'W1 W2 W3 B1 B2 B3 D4 D5 D6 B7 B8 B9 H3 H3', win: 'H3', via: 'DRAW', exhausted: true },
];
function deck(size) {
  return (size === 4 ? fourCodes : threeCodes).flatMap(c => Array.from({ length: 4 }, (_, n) => ({
    id: `ym-${c}-${n}`, suit: { W: 'CHARACTERS', B: 'BAMBOO', D: 'DOTS', H: 'HONORS' }[c[0]],
    rank: +c[1], red: false, label: c[0] === 'H' ? ['东', '南', '西', '北', '中', '发', '白'][+c[1] - 1]
      : c[1] + { W: '万', B: '条', D: '筒' }[c[0]],
  })));
}
function seed(scenario) {
  const wall = deck(scenario.size);
  const take = c => {
    const index = wall.findIndex(t => code(t) === c);
    assert.ok(index >= 0, 'Fixture exceeds four physical copies');
    return wall.splice(index, 1)[0];
  };
  const players = Array.from({ length: scenario.size }, (_, seat) => ({ ...identity(scenario.id, seat),
    id: identity(scenario.id, seat).playerId, name: `隔离验证${seat}`, seat, score: 10,
    hand: [], discards: [], melds: [], ready: false, bot: false, trustee: false }));
  players[0].hand = scenario.hand.split(' ').map(take);
  const tile = players[0].hand.splice(players[0].hand.findLastIndex(t => code(t) === scenario.win), 1)[0];
  for (const player of players.slice(1)) while (player.hand.length < 13) player.hand.push(wall.shift());
  if (scenario.via === 'DRAW') wall.unshift(tile);
  else { players.at(-1).hand.push(tile); players.at(-1).lastDrawnId = tile.id; }
  if (scenario.exhausted) players[1].discards.push(...wall.splice(0));
  for (const player of players) { delete player.roomId; delete player.playerId; }
  const all = [...wall, ...players.flatMap(p => [...p.hand, ...p.discards])];
  assert.equal(all.length, scenario.size === 4 ? 136 : 108);
  assert.equal(new Set(all.map(t => t.id)).size, all.length);
  return { id: scenario.id, ruleId: `yaoming-${scenario.size}p`, name: scenario.id, hostId: players[0].id,
    version: 1, round: scenario.exhausted ? 8 : 1, dealerSeat: 0,
    phase: scenario.via === 'DRAW' ? 'NEED_DRAW' : 'NEED_DISCARD', currentSeat: scenario.via === 'DRAW' ? 0 : scenario.size - 1,
    deadlineKind: scenario.via === 'DRAW' ? 'DRAW' : 'DISCARD', deadlineAt: Date.now() + 3600000,
    lastActivity: Date.now(), nextBotAt: Date.now() + 3600000, players, wall };
}

assert.ok(process.argv.length <= 3 && (!process.argv[2] || process.argv[2] === '--seed'), 'Only optional --seed is supported');
if (process.argv[2] === '--seed') {
  await mkdir(runtime, { recursive: true });
  await writeFile(`${runtime}/rooms.json`, JSON.stringify(scenarios.map(seed)), { flag: 'wx' });
  console.log(JSON.stringify({ seeded: scenarios.length, onlyFakeLocalData: true }));
  process.exit(0);
}

async function request(path, player, body, expected = 200) {
  const response = await fetch(api + path, { method: body ? 'POST' : 'GET', redirect: 'error',
    headers: { 'Content-Type': 'application/json', ...(player ? { 'X-Resume-Token': player.token } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15000) });
  const text = await response.text();
  assert.equal(response.status, expected, `${path}: HTTP ${response.status} ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}
const view = player => request(`/rooms/${player.roomId}?playerId=${player.playerId}`, player);
const own = (current, player) => current.players.find(p => p.id === player.playerId);
async function act(player, type, tileIds = [], expected = 200) {
  const current = await view(player);
  return request(`/rooms/${player.roomId}/actions`, player,
    { playerId: player.playerId, version: current.version, requestId: randomUUID(), type, tileIds }, expected);
}
async function passAll(players) {
  for (const player of players) if ((await view(player)).actions.some(a => a.type === 'PASS')) await act(player, 'PASS');
}
async function conserved(players) {
  const views = await Promise.all(players.map(view)), count = players.length === 4 ? 136 : 108;
  for (const [i, current] of views.entries()) {
    assert.equal(current.version, views[0].version);
    assert.equal(current.capacity, players.length);
    assert.equal(current.ruleId, `yaoming-${players.length}p`);
    for (const p of current.players) if (p.id !== players[i].playerId) assert.deepEqual(p.hand, [], 'Private opponent hand');
  }
  const tiles = [...views.flatMap((v, i) => own(v, players[i]).hand),
    ...views[0].players.flatMap(p => [...p.discards, ...p.melds.flatMap(m => m.tiles)])];
  assert.equal(tiles.length + views[0].wallCount, count);
  assert.equal(new Set(tiles.map(t => t.id)).size, tiles.length);
  assert.equal(views[0].players.reduce((sum, p) => sum + p.score, 0), players.length * 10);
  assert.ok(views[0].players.every(p => p.score >= 0));
  return views;
}

const allRules = await request('/rulesets');
assert.deepEqual(allRules.map(r => r.id).sort(), ['yaoming-3p', 'yaoming-4p']);
for (const size of [3, 4]) {
  const r = await request(`/rules?ruleId=yaoming-${size}p`);
  assert.equal(r.playerCount, size); assert.equal(r.tileCount, size === 4 ? 136 : 108);
  assert.equal(r.totalRounds, size * 2); assert.equal(r.tiles.length, size === 4 ? 34 : 27);
  assert.equal(r.fans.find(f => f.id === 'MENQING').fan, size === 4 ? 1 : 2);
  assert.equal(r.fans.some(f => f.id === 'FENGLONG'), size === 3);
  assert.ok(r.description.includes(`${size === 4 ? 3 : 4}番起和`));
}
assert.equal((await request('/rules')).id, 'yaoming-3p');
await request('/rules?ruleId=invalid', null, null, 400);
const countBefore = (await request('/rooms')).length;
await request('/rooms', null, { name: '无效规则', playerName: '测试', ruleId: 'invalid' }, 400);
assert.equal((await request('/rooms')).length, countBefore);
const results = [];
for (const scenario of scenarios) {
  const players = Array.from({ length: scenario.size }, (_, seat) => identity(scenario.id, seat));
  const initial = (await conserved(players))[0];
  await request(`/rooms/${scenario.id}?playerId=${players[0].playerId}`, { token: 'incorrect-token' }, null, 400);
  await request(`/replays/${scenario.id}/${initial.round}?playerId=${players[0].playerId}`, players[0], null, 404);
  if (scenario.exhausted) {
    const finished = await act(players[0], 'DRAW');
    assert.equal(finished.status, 'MATCH_END'); assert.equal(finished.roundLabel, '南4局');
    assert.equal(finished.result.draw, true); assert.equal(finished.result.matchOver, true);
    await conserved(players); results.push({ id: scenario.id, south4Ends: true }); continue;
  }
  const hint = await request(`/rooms/${scenario.id}/hints?playerId=${players[0].playerId}`, players[0]);
  const wait = hint.analysis.waits.find(w => code(w.tile) === scenario.win);
  assert.ok(wait); assert.equal(wait.tsumoFan, scenario.self); assert.equal(wait.ronFan, scenario.ron);
  const minimum = scenario.size === 4 ? 3 : 4;
  assert.equal(wait.canTsumo, scenario.self >= minimum);
  assert.equal(wait.canRon, scenario.ron >= minimum);
  assert.ok(hint.analysis.note.includes(`${minimum} 番起和`));
  const actor = scenario.via === 'DRAW' ? players[0] : players.at(-1);
  if (scenario.via === 'DRAW') await act(actor, 'DRAW');
  else {
    const current = await view(actor), id = own(current, actor).drawnTileId;
    assert.equal(code(own(current, actor).hand.find(t => t.id === id)), scenario.win);
    await act(actor, 'DISCARD', [id]);
  }
  const offered = await view(players[0]);
  if (scenario.chi) {
    const chi = offered.actions.find(a => a.type === 'CHI'); assert.ok(chi);
    await act(players[0], 'CHI', chi.tileIds); await passAll(players.slice(1));
    const after = await view(players[0]);
    assert.equal(after.status, 'NEED_DISCARD'); assert.equal(after.currentSeat, 0);
    assert.equal(own(after, players[0]).melds[0].fromSeat, 3);
    assert.deepEqual(own(after, players[0]).melds[0].tiles.map(code).sort(), ['W1', 'W2', 'W3']);
    assert.equal(own(after, players[0]).drawnTileId ?? null, null);
    await conserved(players); results.push({ id: scenario.id, northToEastChi: true }); continue;
  }
  const fan = scenario.via === 'DRAW' ? scenario.self : scenario.ron;
  assert.equal(offered.actions.some(a => a.type === 'WIN'), fan >= minimum);
  if (fan < minimum) {
    await act(players[0], 'WIN', [], 400);
    assert.equal((await view(players[0])).version, offered.version);
    results.push({ id: scenario.id, belowMinimumWinRejected: true }); continue;
  }
  await act(players[0], 'WIN'); await passAll(players.slice(1));
  const settled = (await conserved(players))[0], result = settled.result;
  assert.ok(result); assert.equal(result.rawFan, fan); assert.equal(result.fan, fan);
  assert.deepEqual(Object.fromEntries(result.items.map(item => [item.id, item.fan])), scenario.items);
  assert.equal(result.hands.length, scenario.size); assert.equal(result.hands[0].hand.length, 14);
  if (scenario.via === 'DRAW') {
    assert.equal(result.payments.length, scenario.size - 1);
    assert.ok(result.payments.every(p => p.requested === fan && p.amount === fan));
    assert.equal(own(settled, players[0]).score, 10 + fan * (scenario.size - 1));
    assert.equal(settled.status, 'HAND_END');
  } else {
    assert.equal(result.payments.length, 1); assert.equal(result.payments[0].requested, scenario.size * fan);
    assert.equal(result.payments[0].amount, 10); assert.equal(settled.status, 'MATCH_END');
  }
  const replay = await request(`/replays/${scenario.id}/1?playerId=${players[0].playerId}`, players[0]);
  assert.equal(replay.ruleId, `yaoming-${scenario.size}p`); assert.equal(replay.capacity, scenario.size);
  assert.equal(replay.complete, true); assert.deepEqual(replay.result, result);
  assert.equal(replay.frames.at(-1).players.length, scenario.size);
  if (settled.status === 'HAND_END') {
    for (const player of players) await act(player, 'ACK');
    const next = (await conserved(players))[0];
    assert.equal(next.round, 2); assert.equal(next.dealerSeat, 1); assert.equal(next.status, 'NEED_DRAW');
    assert.equal(next.wallCount, (scenario.size === 4 ? 136 : 108) - scenario.size * 13);
  }
  results.push({ id: scenario.id, fan, settlementReplayAndRotation: true });
}

// Exercise the ordinary public creation/join/ready path for both room variants.
for (const size of [3, 4]) {
  const host = await request('/rooms', null, { name: `联机验证${size}`, playerName: '创建者', ruleId: `yaoming-${size}p` }, 201);
  const players = [host];
  for (let i = 1; i < size; i++) players.push(await request(`/rooms/${host.roomId}/join`, null, { playerName: `玩家${i}` }));
  await request(`/rooms/${host.roomId}/join`, null, { playerName: '满员不得加入' }, 400);
  for (let i = 0; i < size - 1; i++) assert.equal((await act(players[i], 'READY')).status, 'WAITING');
  await act(players.at(-1), 'READY');
  let current = (await conserved(players))[0];
  assert.equal(current.status, 'NEED_DRAW'); assert.equal(current.wallCount, (size === 4 ? 136 : 108) - size * 13);
  assert.deepEqual(current.players.map(p => p.wind).sort(), ['东', '南', '西', ...(size === 4 ? ['北'] : [])].sort());
  const seenSeats = new Set();
  for (let turn = 0; turn < size * 2; turn++) {
    current = await view(host);
    const actorId = current.players.find(p => p.seat === current.currentSeat).id;
    const actor = players.find(p => p.playerId === actorId); seenSeats.add(current.currentSeat);
    const drawn = await act(actor, 'DRAW');
    const me = own(drawn, actor); assert.ok(me.drawnTileId);
    const discarded = await act(actor, 'DISCARD', [me.drawnTileId]);
    assert.equal(discarded.lastDiscard.kind, 'TSUMOGIRI');
    await passAll(players); await conserved(players);
  }
  assert.equal(seenSeats.size, size);
  results.push({ ordinaryRoom: host.roomId, size, readyAndTwoLaps: true });
}
await mkdir(runtime, { recursive: true });
await writeFile(`${runtime}/http-results.json`, JSON.stringify({ origin, passed: results }, null, 2));
console.log(JSON.stringify({ origin, passed: results }, null, 2));
