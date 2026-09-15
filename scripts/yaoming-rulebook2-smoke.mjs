import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';

// Only this isolated local server is allowed. Never read, reset, or send commands to production.
const runtime = fileURLToPath(new URL('../artifacts/rulebook-2-20260911/http-runtime/', import.meta.url));
const endpoint = 'http://127.0.0.1:18094';
const CLOSED_FLAT = 'W1 W5 W9 B1 B2 B3 D4 D5 D6 B7 B8 B9 D2 D2';
const scenarios = [
  { id: 'rb2-closed-flat-self', hand: CLOSED_FLAT, win: 'D2', via: 'DRAW', self: 4, ron: 3,
    items: { PINGHE: 1, MENQING: 2, BUQIUREN: 1 } },
  { id: 'rb2-closed-flat-ron', hand: CLOSED_FLAT, win: 'D2', via: 'RON', self: 4, ron: 3 },
  { id: 'rb2-open-flat-flush', hand: 'B2 B3 B4 B5 B6 B7 B7 B8 B9 B5 B5', win: 'B5',
    meld: 'B1 B2 B3', meldType: 'CHI', via: 'RON', self: 4, ron: 4, items: { PINGHE: 1, QINGYISE: 3 } },
  { id: 'rb2-removed-tanyao', hand: 'B2 B3 B4 D3 D4 D5 B6 B7 B8 D6 D6 D6 B5 B5',
    win: 'B5', via: 'DRAW', self: 3, ron: 2 },
  { id: 'rb2-open-pure-outside', hand: 'B1 B2 B3 D7 D8 D9 B9 B9 B9 D1 D1', win: 'D1',
    meld: 'W1 W1 W1', meldType: 'PONG', via: 'DRAW', self: 3, ron: 3 },
  { id: 'rb2-open-all-honors', hand: 'H2 H2 H2 H3 H3 H3 H5 H5 H5 H6 H6', win: 'H6',
    meld: 'H1 H1 H1', meldType: 'PONG', via: 'DRAW', self: 6, ron: 6, items: { ZIYISE: 6 } },
  { id: 'rb2-wind-dragon', hand: 'H1 H2 H3 H5 H6 H7 W1 W5 W9 B1 B2 B3 D2 D2', win: 'D2',
    via: 'DRAW', self: 4, ron: 3, items: { FENGLONG: 3, BUQIUREN: 1 } },
];
const codes = ['W1', 'W5', 'W9', ...Array.from({ length: 9 }, (_, i) => `B${i + 1}`),
  ...Array.from({ length: 9 }, (_, i) => `D${i + 1}`), 'H1', 'H2', 'H3', 'H5', 'H6', 'H7'];
const suit = { W: 'CHARACTERS', B: 'BAMBOO', D: 'DOTS', H: 'HONORS' };
const code = tile => ({ CHARACTERS: 'W', BAMBOO: 'B', DOTS: 'D', HONORS: 'H' }[tile.suit] + tile.rank);
const identity = (room, seat) => ({ playerId: `fake-${room}-${seat}`, token: `fake-rulebook2-token-${room}-${seat}` });
const me = (view, seat) => view.players.find(player => player.seat === seat);

function deck() {
  return codes.flatMap(value => Array.from({ length: 4 }, (_, copy) => ({ id: `ym-${value}-${copy}`,
    suit: suit[value[0]], rank: Number(value[1]), red: false,
    label: value[0] === 'H' ? ['东', '南', '西', '北', '中', '发', '白'][Number(value[1]) - 1]
      : value[1] + { W: '万', B: '条', D: '筒' }[value[0]] })));
}

function seed(scenario) {
  const wall = deck();
  const take = value => {
    const index = wall.findIndex(tile => code(tile) === value);
    assert.ok(index >= 0, `${scenario.id}: requested more than four ${value}`);
    return wall.splice(index, 1)[0];
  };
  const players = [0, 1, 2].map(seat => ({ id: identity(scenario.id, seat).playerId,
    token: identity(scenario.id, seat).token, name: `隔离验证${seat}`, seat, score: 10,
    bot: false, trustee: false, ready: false, hand: [], discards: [], melds: [] }));
  const winner = players[0];
  winner.hand = scenario.hand.split(' ').map(take);
  if (scenario.meld) {
    const tiles = scenario.meld.split(' ').map(take);
    winner.melds.push({ type: scenario.meldType, tiles, fromSeat: 2, claimedTileId: tiles[0].id, concealed: false });
  }
  const winningIndex = winner.hand.findLastIndex(tile => code(tile) === scenario.win);
  assert.ok(winningIndex >= 0);
  const winningTile = winner.hand.splice(winningIndex, 1)[0];
  for (const seat of [1, 2]) while (players[seat].hand.length < 13) players[seat].hand.push(wall.shift());
  if (scenario.via === 'DRAW') wall.unshift(winningTile);
  else { players[2].hand.push(winningTile); players[2].lastDrawnId = winningTile.id; }
  assert.equal(winner.hand.length + 3 * winner.melds.length, 13);
  const all = [...wall, ...players.flatMap(player => [...player.hand, ...player.melds.flatMap(meld => meld.tiles)])];
  assert.equal(all.length, 108); assert.equal(new Set(all.map(tile => tile.id)).size, 108);
  assert.deepEqual(all.map(tile => tile.id).sort(), deck().map(tile => tile.id).sort());
  for (const value of codes) assert.equal(all.filter(tile => code(tile) === value).length, 4);
  return { id: scenario.id, name: scenario.id, hostId: winner.id, version: 1, round: 1, dealerSeat: 0,
    phase: scenario.via === 'DRAW' ? 'NEED_DRAW' : 'NEED_DISCARD', currentSeat: scenario.via === 'DRAW' ? 0 : 2,
    deadlineKind: scenario.via === 'DRAW' ? 'DRAW' : 'DISCARD', deadlineAt: Date.now() + 600000,
    lastActivity: Date.now(), nextBotAt: Date.now() + 600000, players, wall };
}

if (process.argv[2] === '--seed') {
  assert.equal(process.argv.length, 3, 'Seed mode takes no other arguments');
  const rooms = scenarios.map(seed);
  await mkdir(resolve(runtime, 'data'), { recursive: true });
  // Fail instead of overwriting any prior run, including a still-running local server's data.
  await writeFile(resolve(runtime, 'data/yaoming-rooms.json'), JSON.stringify(rooms), { flag: 'wx' });
  console.log(JSON.stringify({ seeded: rooms.length, physicalTilesPerRoom: 108, fakeCredentialsOnly: true }));
  process.exit(0);
}

assert.ok(process.argv.length <= 3, 'Expected no argument or the exact isolated local origin');
const url = new URL(process.argv[2] || endpoint);
assert.equal(url.origin, endpoint); assert.equal(url.protocol, 'http:');
assert.equal(url.hostname, '127.0.0.1'); assert.equal(url.port, '18094');
assert.equal(url.pathname, '/'); assert.equal(url.username, ''); assert.equal(url.password, '');
assert.equal(url.search, ''); assert.equal(url.hash, '');
const api = `${endpoint}/api/yaoming`;

async function request(path, player, body, expectedStatus = 200) {
  const response = await fetch(api + path, { method: body ? 'POST' : 'GET', redirect: 'error',
    headers: { 'Content-Type': 'application/json', ...(player ? { 'X-Resume-Token': player.token } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(5000) });
  assert.equal(response.status, expectedStatus, `${path}: HTTP status`);
  const raw = await response.text(); return raw ? JSON.parse(raw) : null;
}
const view = (room, seat) => request(`/rooms/${room}?playerId=${identity(room, seat).playerId}`, identity(room, seat));
const hints = room => request(`/rooms/${room}/hints?playerId=${identity(room, 0).playerId}`, identity(room, 0));
async function act(room, seat, type, tileIds = [], expectedStatus = 200) {
  const current = await view(room, seat), player = identity(room, seat);
  return request(`/rooms/${room}/actions`, player,
    { playerId: player.playerId, version: current.version, requestId: randomUUID(), type, tileIds }, expectedStatus);
}

async function conserved(room) {
  const views = await Promise.all([0, 1, 2].map(seat => view(room, seat)));
  for (const [seat, current] of views.entries()) {
    assert.equal(current.version, views[0].version, `${room}: coherent three-seat view`);
    for (const player of current.players) if (player.seat !== seat) assert.deepEqual(player.hand, [], 'No opponent hand leak');
  }
  const visible = [...views.flatMap((current, seat) => me(current, seat).hand),
    ...views[0].players.flatMap(player => [...player.discards, ...player.melds.flatMap(meld => meld.tiles)])];
  assert.equal(visible.length + views[0].wallCount, 108, `${room}: all physical tiles conserved`);
  assert.equal(new Set(visible.map(tile => tile.id)).size, visible.length, `${room}: no duplicate physical tile`);
  assert.ok(visible.every(tile => codes.includes(code(tile))));
  for (const value of codes) assert.ok(visible.filter(tile => code(tile) === value).length <= 4);
  assert.equal(views[0].players.reduce((sum, player) => sum + player.score, 0), 30);
  assert.ok(views[0].players.every(player => player.score >= 0));
  return views;
}

async function passRemaining(room) {
  for (const seat of [0, 1, 2]) {
    const current = await view(room, seat);
    if (current.actions.some(action => action.type === 'PASS')) await act(room, seat, 'PASS');
  }
}

assert.deepEqual((await request('/rooms')).map(room => room.id).sort(), scenarios.map(scenario => scenario.id).sort());
const rules = await request('/rules');
assert.equal(rules.version, '26.9 LTS'); assert.equal(rules.fans.length, 20);
const catalog = Object.fromEntries(rules.fans.map(fan => [fan.id, fan.fan]));
assert.equal(Object.keys(catalog).length, 20); assert.equal(catalog.PINGHE, 1); assert.equal(catalog.MENQING, 2);
assert.equal(catalog.QINGQUANDAIYAO, 3); assert.equal(catalog.ZIYISE, 6); assert.ok(!('DUANYAO' in catalog));
assert.ok(JSON.stringify(rules).includes('规则集(2)'));
assert.ok(!JSON.stringify(rules).includes('仍加清一色'));
const results = [];

for (const scenario of scenarios) {
  const room = scenario.id;
  const beforeViews = await conserved(room), initial = beforeViews[0];
  assert.equal(me(initial, 0).hand.length + me(initial, 0).melds.length * 3, 13);
  const analysis = (await hints(room)).analysis;
  assert.equal(analysis.mode, 'WAIT');
  const wait = analysis.waits.find(candidate => code(candidate.tile) === scenario.win);
  assert.ok(wait, `${room}: expected structural wait`);
  assert.equal(wait.tsumoFan, scenario.self); assert.equal(wait.ronFan, scenario.ron);
  assert.equal(wait.canTsumo, scenario.self >= 4); assert.equal(wait.canRon, scenario.ron >= 4);
  const known = [...me(initial, 0).hand, ...initial.players.flatMap(player => [...player.discards,
    ...player.melds.flatMap(meld => meld.tiles)])].filter(tile => code(tile) === scenario.win).length;
  assert.equal(wait.unseenCount, 4 - known);
  let winningTile;
  if (scenario.via === 'DRAW') {
    await act(room, 0, 'DRAW');
    const drawn = await view(room, 0);
    winningTile = me(drawn, 0).hand.find(tile => tile.id === me(drawn, 0).drawnTileId);
    assert.ok(winningTile); assert.equal(code(winningTile), scenario.win); assert.equal(drawn.status, 'NEED_DISCARD');
  } else {
    const from = await view(room, 2);
    winningTile = me(from, 2).hand.find(tile => tile.id === me(from, 2).drawnTileId);
    assert.ok(winningTile); assert.equal(code(winningTile), scenario.win);
    await act(room, 2, 'DISCARD', [winningTile.id]);
  }
  const offered = await view(room, 0), fan = scenario.via === 'DRAW' ? scenario.self : scenario.ron;
  const eligible = fan >= 4;
  assert.equal(offered.actions.some(action => action.type === 'WIN'), eligible, `${room}: action agrees with hints`);
  const afterHint = (await hints(room)).analysis;
  const updatedWaits = afterHint.mode === 'DISCARD'
    ? afterHint.discards.find(option => code(option.tile) === scenario.win).waits : afterHint.waits;
  const updatedWait = updatedWaits.find(candidate => code(candidate.tile) === scenario.win);
  assert.ok(updatedWait); assert.equal(updatedWait.tsumoFan, scenario.self); assert.equal(updatedWait.ronFan, scenario.ron);
  assert.equal(updatedWait.unseenCount, wait.unseenCount - 1, `${room}: drawn or discarded tile is now known`);
  await conserved(room);
  if (!eligible) {
    await act(room, 0, 'WIN', [], 400);
    const unchanged = await view(room, 0);
    assert.equal(unchanged.version, offered.version); assert.equal(unchanged.status, offered.status);
    assert.deepEqual(unchanged.players, offered.players); assert.deepEqual(unchanged.result, offered.result);
    assert.equal(unchanged.deadlineAt, offered.deadlineAt); assert.ok(!unchanged.result);
    if (scenario.via === 'DRAW') await act(room, 0, 'DISCARD', [winningTile.id]);
    await passRemaining(room);
    const next = await view(room, 0); assert.equal(next.status, 'NEED_DRAW');
    const nextSeat = next.currentSeat; await act(room, nextSeat, 'DRAW');
    assert.equal((await view(room, nextSeat)).status, 'NEED_DISCARD');
    await conserved(room);
    results.push({ room, tsumoFan: scenario.self, ronFan: scenario.ron, illegalWinRejected: true, handContinues: true });
    continue;
  }

  await act(room, 0, 'WIN'); await passRemaining(room);
  const settled = await conserved(room), result = settled[0].result;
  assert.ok(result); assert.equal(result.winnerId, identity(room, 0).playerId);
  assert.equal(result.rawFan, fan); assert.equal(result.fan, fan); assert.equal(result.draw, false);
  assert.equal(result.winningTile.id, winningTile.id);
  assert.deepEqual(Object.fromEntries(result.items.map(item => [item.id, item.fan])), scenario.items);
  const winningHand = result.hands.find(hand => hand.playerId === identity(room, 0).playerId);
  assert.equal(winningHand.hand.length, scenario.hand.split(' ').length);
  assert.equal(winningHand.melds.length, scenario.meld ? 1 : 0);
  assert.equal(winningHand.hand.filter(tile => tile.id === winningTile.id).length, 1);
  const expectedScores = scenario.via === 'DRAW' ? [10 + 2 * fan, 10 - fan, 10 - fan] : [20, 10, 0];
  assert.deepEqual(settled[0].players.map(player => player.score), expectedScores);
  const payerSeats = scenario.via === 'DRAW' ? [1, 2] : [2];
  assert.equal(result.payments.length, payerSeats.length);
  for (const seat of payerSeats) {
    const payment = result.payments.find(item => item.fromId === identity(room, seat).playerId);
    assert.ok(payment); assert.equal(payment.toId, identity(room, 0).playerId);
    assert.equal(payment.requested, fan * (scenario.via === 'DRAW' ? 1 : 3));
    assert.equal(payment.amount, Math.min(10, payment.requested));
  }
  assert.equal(result.matchOver, expectedScores.includes(0));
  for (const current of settled) assert.deepEqual(current.result, result, `${room}: identical settlement for every seat`);
  for (const seat of [0, 1, 2]) {
    const player = identity(room, seat);
    const list = await request(`/replays?roomId=${room}&playerId=${player.playerId}`, player);
    assert.equal(list.hands.length, 1); assert.equal(list.hands[0].round, 1);
    const replay = await request(`/replays/${room}/1?playerId=${player.playerId}`, player);
    assert.equal(replay.complete, true); assert.equal(replay.incomplete, true, 'Seeded ongoing hand honestly records only resumed play');
    assert.deepEqual(replay.result, result); assert.deepEqual(replay.frames.at(-1).result, result);
  }
  results.push({ room, fan, items: scenario.items, expectedScores, threeSeatSettlement: true, payments: true, threeSeatReplay: true });
}
console.log(JSON.stringify({ ok: true, endpoint, catalogCount: rules.fans.length, results }));
