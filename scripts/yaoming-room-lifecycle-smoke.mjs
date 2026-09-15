import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';

// Only an isolated server configured with --mahjong.rooms.empty-retention-ms=2000.
// Never override this origin with a production destination.
const origin = 'http://127.0.0.1:18094';
const api = `${origin}/api/yaoming`;
const evidence = new URL('../artifacts/room-lifecycle-20260912/', import.meta.url);
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function request(path, identity, body, expected = 200) {
  const response = await fetch(api + path, {
    method: body ? 'POST' : 'GET', redirect: 'error', signal: AbortSignal.timeout(10000),
    headers: { 'Content-Type': 'application/json', ...(identity ? { 'X-Resume-Token': identity.token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  assert.equal(response.status, expected, `${path}: ${response.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : null;
}
const view = identity => request(`/rooms/${identity.roomId}?playerId=${identity.playerId}`, identity);
const lobby = () => request('/rooms');
const create = name => request('/rooms', null, { name, playerName: '隔离房主', ruleId: 'yaoming-4p' }, 201);
async function act(identity, type) {
  const room = await view(identity);
  const body = { playerId: identity.playerId, version: room.version, requestId: randomUUID(), type, tileIds: [] };
  return { body, result: await request(`/rooms/${identity.roomId}/actions`, identity, body) };
}
async function gone(id) {
  const until = Date.now() + 6500;
  while (Date.now() < until) {
    if (!(await lobby()).some(room => room.id === id)) return;
    await sleep(150);
  }
  assert.fail('Empty room did not expire; run ONLY the isolated server with a 2000ms retention');
}

const passed = [];
const empty = await create('空房直接退出');
const streamAbort = new AbortController();
const stream = await fetch(`${api}/rooms/${empty.roomId}/stream?playerId=${empty.playerId}`, {
  headers: { 'X-Resume-Token': empty.token }, signal: streamAbort.signal,
});
assert.equal(stream.status, 200);
const closing = stream.text();
const exitEmpty = await act(empty, 'LEAVE');
assert.equal(exitEmpty.result, null);
assert.equal((await lobby()).find(room => room.id === empty.roomId)?.players, 0);
await request(`/rooms/${empty.roomId}?playerId=${empty.playerId}`, empty, null, 400);
const streamTimeout = setTimeout(() => streamAbort.abort(), 4000);
try { assert.match(await closing, /event:closed/); } finally { clearTimeout(streamTimeout); }
passed.push('等待时真正退出、立即释放座位、撤销身份、关闭原推送；空房暂留');

const rescued = await request(`/rooms/${empty.roomId}/join`, null, { playerName: '新房主' });
assert.ok((await view(rescued)).actions.some(action => action.type === 'ADD_BOT'));
await act(rescued, 'ADD_BOT');
await sleep(2400);
assert.equal((await view(rescued)).status, 'WAITING');
passed.push('空等待房可重新加入，第一位真人接任房主并取消销毁');

await act(rescued, 'LEAVE');
const botOnly = (await lobby()).find(room => room.id === rescued.roomId);
assert.equal(botOnly.players, 1);
assert.equal(botOnly.status, 'WAITING');
await gone(rescued.roomId);
await request(`/rooms/${empty.roomId}/actions`, empty, exitEmpty.body);
await request(`/rooms/${empty.roomId}/join`, null, { playerName: '已过期不可复活' }, 404);
passed.push('只剩机器人仍会到期销毁，机器人不能自行开局或续命；退出重试保持幂等');

const host = await create('房主移交');
const guest = await request(`/rooms/${host.roomId}/join`, null, { playerName: '第二位真人' });
await act(host, 'ADD_BOT');
await act(guest, 'READY');
await act(host, 'LEAVE');
let current = await view(guest);
assert.equal(current.players.find(player => player.id === guest.playerId).ready, false);
assert.ok(current.actions.some(action => action.type === 'ADD_BOT'));
assert.equal(current.players.some(player => player.id === host.playerId), false);
await sleep(2400);
current = await view(guest);
assert.equal(current.status, 'WAITING');
await act(guest, 'LEAVE');
assert.ok((await lobby()).some(room => room.id === host.roomId));
await gone(host.roomId);
passed.push('房主退出后转交真人、重置准备；仍有真人的房间不销毁，最后真人退出后延迟回收');

await mkdir(evidence, { recursive: true });
await writeFile(new URL('http-results.json', evidence), JSON.stringify({ origin, testRetentionMs: 2000, passed }, null, 2));
console.log(JSON.stringify({ origin, testRetentionMs: 2000, passed }, null, 2));
