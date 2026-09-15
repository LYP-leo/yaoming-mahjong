// Explicitly scoped deployment smoke test. Never touches an existing user's room.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const base = 'http://82.156.207.98:5173';
const api = `${base}/api/yaoming`;
const reportPath = fileURLToPath(new URL('../artifacts/dell-redeploy-20260914/public-smoke.json', import.meta.url));
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const roomLimitMs = 60_000;
const exerciseLimitMs = 45_000; // Reserve the remaining 15 seconds for LEAVE cleanup.
const requiredActions = 8;

if (process.argv.length !== 3 || process.argv[2] !== '--run') {
  console.log(`No requests made. To create ONLY deployment test rooms at ${base}, run:\nnode scripts/dell-redeploy-smoke.mjs --run`);
  process.exit(process.argv.length === 2 ? 0 : 2);
}

const report = {
  startedAt: new Date().toISOString(), base, ok: false,
  roomLimitMs, requiredManualGameActions: requiredActions,
  checks: {}, rooms: [],
  privacy: 'No recovery tokens, player identity IDs, hand contents or raw event text are recorded.',
};

function remaining(deadline) {
  const ms = deadline - Date.now();
  if (ms <= 0) throw new Error('Per-room deadline reached');
  return ms;
}

// Error messages intentionally exclude response bodies and request payloads.
async function request(path, { method = 'GET', body, identity, deadline, expected = 200, stats } = {}) {
  const allowed = Array.isArray(expected) ? expected : [expected];
  const response = await fetch(api + path, {
    method,
    headers: {
      Origin: base, Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(identity ? { 'X-Resume-Token': identity.token } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(Math.min(6000, remaining(deadline))),
  });
  if (stats) {
    stats.httpRequests++;
    stats.httpStatuses[response.status] = (stats.httpStatuses[response.status] ?? 0) + 1;
    if (method === 'POST') stats.sameOriginPostRequests++;
    if (response.status === 403) stats.checks.no403 = false;
  }
  const text = await response.text();
  assert.ok(allowed.includes(response.status), `Unexpected HTTP ${response.status}; expected ${allowed.join('/')}`);
  let data = null;
  if (text) {
    try { data = JSON.parse(text); }
    catch { throw new Error('Endpoint returned non-JSON data'); }
  }
  return { status: response.status, data };
}

function validateIdentity(identity, expectedRoomId) {
  assert.ok(identity && typeof identity.roomId === 'string' && /^\d{6}$/.test(identity.roomId), 'Invalid created room identity');
  assert.ok(typeof identity.playerId === 'string' && typeof identity.token === 'string' && identity.token.length > 10, 'Incomplete player identity');
  if (expectedRoomId) assert.equal(identity.roomId, expectedRoomId, 'Join returned a different room');
}

function validateView(view, identity, stats) {
  assert.equal(view?.id, identity.roomId, 'Room view identity mismatch');
  assert.equal(view.meId, identity.playerId, 'Room view perspective mismatch');
  assert.ok(Number.isSafeInteger(view.version) && view.version >= 0, 'Invalid room version');
  assert.equal(view.capacity, stats.capacity, 'Room capacity mismatch');
  assert.equal(view.ruleId, stats.ruleId, 'Room rule mismatch');
  assert.ok(Array.isArray(view.players) && Array.isArray(view.actions), 'Missing room collections');
  const me = view.players.find(player => player.id === identity.playerId);
  assert.ok(me && Array.isArray(me.hand), 'Missing own hand collection');
  assert.equal(me.hand.length, me.handSize, 'Own hand does not match own public hand size');
  for (const player of view.players) {
    if (player.id === identity.playerId) continue;
    assert.ok(Array.isArray(player.hand) && player.hand.length === 0, 'Opponent concealed hand leaked');
    // Jackson NON_NULL omits this field; both absence and explicit null hide it.
    assert.ok(player.drawnTileId == null, 'Opponent drawn tile identity leaked');
    assert.deepEqual(player.discardedCodes, [], 'Opponent private discard metadata leaked');
    assert.deepEqual(player.passedCodes, [], 'Opponent private pass metadata leaked');
  }
  stats.privacyViewsChecked++;
  if (view.status !== 'WAITING' && me.hand.length > 0) stats.visibleOwnHandPerspectives.add(identity.playerId);
  stats.latestVersion = Math.max(stats.latestVersion ?? 0, view.version);
  stats.lastStatus = view.status;
  collectBotActions(view, stats);
  return view;
}

function collectBotActions(view, stats) {
  const bots = view.players.filter(player => player.bot);
  for (const event of view.events ?? []) {
    if (stats.seenEventSequences.has(event.sequence)) continue;
    stats.seenEventSequences.add(event.sequence);
    for (const bot of bots) {
      if (!event.text.startsWith(`${bot.name} `)) continue;
      const actionText = event.text.slice(bot.name.length + 1);
      const action = [
        ['摸牌', 'DRAW'], ['从牌山尾补牌', 'REPLACEMENT_DRAW'], ['打出 ', 'DISCARD'],
        ['吃 ', 'CHI'], ['碰 ', 'PONG'], ['明杠 ', 'OPEN_KONG'],
        ['暗杠 ', 'CONCEALED_KONG'], ['加杠 ', 'ADDED_KONG'], ['自摸', 'WIN'], ['点和', 'WIN'],
      ].find(([prefix]) => actionText.startsWith(prefix));
      if (action) {
        stats.botActionCount++;
        stats.botActionTypes[action[1]] = (stats.botActionTypes[action[1]] ?? 0) + 1;
      }
    }
  }
}

async function view(identity, stats, deadline) {
  const response = await request(`/rooms/${identity.roomId}?playerId=${encodeURIComponent(identity.playerId)}`,
    { identity, stats, deadline });
  return validateView(response.data, identity, stats);
}

function commandBody(identity, version, action, requestId = randomUUID()) {
  return { playerId: identity.playerId, version, requestId, type: action.type, tileIds: action.tileIds ?? [] };
}

// Refresh both the version and legal options on every conflict; never replay an old tile choice.
async function act(identity, stats, deadline, choose, { cleanup = false } = {}) {
  const requestId = randomUUID();
  for (let attempt = 0; attempt < 5; attempt++) {
    const current = await view(identity, stats, deadline);
    const selected = choose(current);
    if (!selected) return null;
    assert.ok(current.actions.some(action => action.type === selected.type
      && JSON.stringify(action.tileIds) === JSON.stringify(selected.tileIds ?? [])), 'Selected action is not currently legal');
    const response = await request(`/rooms/${identity.roomId}/actions`, {
      method: 'POST', identity, stats, deadline, expected: [200, 409],
      body: commandBody(identity, current.version, selected, cleanup ? requestId : randomUUID()),
    });
    if (response.status === 409) { stats.versionRetries++; await sleep(Math.min(50, remaining(deadline))); continue; }
    if (selected.type !== 'LEAVE') validateView(response.data, identity, stats);
    return { action: selected.type, view: response.data };
  }
  throw new Error('Version conflict retry limit reached');
}

async function openStream(identity, stats, deadline) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), remaining(deadline));
  let reader;
  try {
    const response = await fetch(`${api}/rooms/${identity.roomId}/stream?playerId=${encodeURIComponent(identity.playerId)}`, {
      headers: { Origin: base, Accept: 'text/event-stream', 'X-Resume-Token': identity.token },
      signal: controller.signal,
    });
    assert.equal(response.status, 200, 'SSE status is not 200');
    assert.match(response.headers.get('content-type') ?? '', /^text\/event-stream/, 'SSE content type incorrect');
    reader = response.body.getReader();
    let streamError;
    const notices = [];
    const consume = (async () => {
      const decoder = new TextDecoder();
      let buffer = '';
      try {
        while (true) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true }).replace(/\r/g, '');
          assert.ok(buffer.length < 65536, 'Unexpected oversized SSE buffer');
          let end;
          while ((end = buffer.indexOf('\n\n')) >= 0) {
            const block = buffer.slice(0, end); buffer = buffer.slice(end + 2);
            const data = block.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n');
            if (!data) continue;
            const notice = JSON.parse(data);
            assert.deepEqual(Object.keys(notice).sort(), ['roomId', 'type', 'version'], 'Unexpected SSE payload fields');
            assert.equal(notice.roomId, identity.roomId, 'SSE room mismatch');
            assert.ok(Number.isSafeInteger(notice.version), 'SSE version missing');
            notices.push({ type: notice.type, version: notice.version });
          }
        }
      } catch (error) { if (!controller.signal.aborted) streamError = error; }
      finally { reader.releaseLock(); }
    })();
    return {
      notices,
      async waitFor(type, minimumVersion = 0) {
        const waitDeadline = Math.min(deadline, Date.now() + 5000);
        while (Date.now() < waitDeadline) {
          if (streamError) throw new Error('SSE parser or connection failed');
          const notice = notices.find(item => item.type === type && item.version >= minimumVersion);
          if (notice) return notice;
          await sleep(25);
        }
        throw new Error(`SSE did not deliver ${type} with the expected version`);
      },
      async close() { controller.abort(); clearTimeout(timer); await consume; },
    };
  } catch (error) {
    controller.abort(); clearTimeout(timer);
    if (reader) await reader.cancel().catch(() => {});
    throw error;
  }
}

function gameplayChoice(current) {
  // Take an offered win; otherwise pass reactions and discard the actual drawn tile.
  for (const type of ['WIN', 'PASS', 'DRAW', 'ACK']) {
    const action = current.actions.find(item => item.type === type);
    if (action) return action;
  }
  const discards = current.actions.filter(item => item.type === 'DISCARD');
  const drawnId = current.players.find(player => player.id === current.meId)?.drawnTileId;
  return discards.find(item => item.tileIds.includes(drawnId)) ?? discards[0] ?? null;
}

async function exerciseRoom(ruleId, capacity) {
  const started = Date.now();
  const deadline = started + exerciseLimitMs;
  const identities = [];
  let stream;
  const stats = {
    ruleId, capacity, roomId: null, name: `部署验证0914-${capacity}人-${randomUUID().slice(0, 8)}`,
    startedAt: new Date(started).toISOString(), ok: false,
    checks: { no403: true }, httpRequests: 0, sameOriginPostRequests: 0, httpStatuses: {},
    versionRetries: 0, privacyViewsChecked: 0, manualGameActions: 0, manualActionTypes: {},
    botActionCount: 0, botActionTypes: {}, cleanup: [],
    // These transient sets are removed before the report is serialized.
    visibleOwnHandPerspectives: new Set(), seenEventSequences: new Set(),
  };
  report.rooms.push(stats);
  try {
    const created = await request('/rooms', { method: 'POST', expected: 201, deadline, stats,
      body: { name: stats.name, playerName: '部署验证甲', ruleId } });
    const first = created.data;
    // Register a returned identity immediately, so later checks cannot bypass cleanup.
    identities.push(first);
    validateIdentity(first);
    stats.roomId = first.roomId;
    const initial = await view(first, stats, deadline);
    assert.equal(initial.status, 'WAITING', 'New room is not waiting');
    const joined = await request(`/rooms/${first.roomId}/join`, { method: 'POST', deadline, stats,
      body: { playerName: '部署验证乙' } });
    const second = joined.data;
    identities.push(second);
    validateIdentity(second, first.roomId);
    assert.notEqual(second.playerId, first.playerId, 'Join reused host identity');
    stats.checks.twoHumanJoin = true;

    const resumed = await request(`/rooms/${first.roomId}/resume`, { method: 'POST', deadline, stats,
      body: { token: first.token } });
    assert.equal(resumed.data?.roomId, first.roomId, 'Resume returned wrong room');
    assert.equal(resumed.data?.playerId, first.playerId, 'Resume returned wrong player');
    assert.equal(resumed.data?.token, first.token, 'Resume changed recovery identity');
    stats.checks.validTokenResume = true;

    await request(`/rooms/${first.roomId}?playerId=${encodeURIComponent(first.playerId)}`, {
      identity: { ...first, token: 'deployment-invalid-token' }, deadline, stats, expected: 400,
    });
    await request(`/rooms/${first.roomId}/resume`, { method: 'POST', deadline, stats, expected: 400,
      body: { token: 'deployment-invalid-token' } });
    stats.checks.invalidToken400 = true;

    const stale = await request(`/rooms/${first.roomId}/actions`, {
      method: 'POST', identity: first, deadline, stats, expected: 409,
      body: commandBody(first, initial.version, { type: 'READY', tileIds: [] }),
    });
    assert.equal(stale.data?.code, 'STALE_VERSION', 'Incorrect stale-version error code');
    assert.equal((await view(first, stats, deadline)).players.find(player => player.id === first.playerId).ready, false,
      'Stale request changed ready state');
    stats.checks.staleVersion409 = true;

    for (let index = 0; index < capacity - 2; index++) {
      const added = await act(first, stats, deadline, current => current.actions.find(action => action.type === 'ADD_BOT'));
      assert.ok(added, 'Host cannot add required bot');
    }
    const filled = await view(first, stats, deadline);
    assert.equal(filled.players.length, capacity, 'Room did not fill all seats');
    assert.equal(filled.players.filter(player => player.bot).length, capacity - 2, 'Incorrect bot count');
    stats.checks.correctBotsAdded = true;

    stream = await openStream(first, stats, deadline);
    const ready = await stream.waitFor('READY', filled.version);
    stats.sseReadyVersion = ready.version;
    for (const identity of identities) {
      const readyResult = await act(identity, stats, deadline, current => current.actions.find(action => action.type === 'READY'));
      assert.ok(readyResult, 'Human cannot ready');
    }
    const startedView = await view(first, stats, deadline);
    assert.notEqual(startedView.status, 'WAITING', 'Room stayed waiting after all players ready');
    stats.checks.readyStartedGame = true;
    const changed = await stream.waitFor('CHANGED', startedView.version);
    stats.sseChangedVersion = changed.version;
    stats.checks.sseVersionDelivered = changed.version > ready.version;
    assert.equal(stats.checks.sseVersionDelivered, true, 'SSE version did not advance');

    while (stats.manualGameActions < requiredActions || stats.botActionCount === 0) {
      remaining(deadline);
      let acted = false;
      for (const identity of identities) {
        const result = await act(identity, stats, deadline, gameplayChoice);
        if (result) {
          stats.manualGameActions++;
          stats.manualActionTypes[result.action] = (stats.manualActionTypes[result.action] ?? 0) + 1;
          acted = true;
        }
      }
      if (!acted) await sleep(Math.min(150, remaining(deadline)));
      if (stats.lastStatus === 'MATCH_END' && stats.manualGameActions < requiredActions)
        throw new Error('Match ended before eight manual game actions; rerun this random smoke case');
    }
    // Confirm both human perspectives after play, without storing either player's hand.
    for (const identity of identities) await view(identity, stats, deadline);
    assert.equal(stats.visibleOwnHandPerspectives.size, 2, 'Did not observe own hand from both human perspectives');
    stats.checks.privateHandsIsolated = true;
    stats.checks.eightManualGameActions = stats.manualGameActions >= requiredActions;
    stats.checks.botActuallyActed = stats.botActionCount > 0;
    stats.ok = Object.values(stats.checks).every(Boolean);
  } catch (error) {
    stats.error = error instanceof assert.AssertionError ? error.message.split('\n')[0] : String(error.message ?? 'Smoke failure');
    stats.ok = false;
  } finally {
    if (stream) {
      await stream.close();
      stats.sseNoticeCounts = {};
      for (const notice of stream.notices) stats.sseNoticeCounts[notice.type] = (stats.sseNoticeCounts[notice.type] ?? 0) + 1;
    }
    // Only the identities returned by THIS test's create/join calls can reach LEAVE.
    for (const [index, identity] of identities.entries()) {
      const cleanup = { human: index + 1, attempted: true, left: false };
      stats.cleanup.push(cleanup);
      try {
        validateIdentity(identity, stats.roomId || undefined);
        const result = await act(identity, stats, started + roomLimitMs,
          current => current.actions.find(action => action.type === 'LEAVE'), { cleanup: true });
        cleanup.left = result?.action === 'LEAVE';
        assert.equal(cleanup.left, true, 'LEAVE was not available');
      } catch (error) {
        cleanup.error = error instanceof assert.AssertionError ? error.message.split('\n')[0] : String(error.message ?? 'Cleanup failure');
        stats.ok = false;
      }
    }
    stats.finishedAt = new Date().toISOString();
    stats.durationMs = Date.now() - started;
    stats.cleanupComplete = stats.cleanup.length === identities.length && stats.cleanup.every(item => item.left);
    if (stats.cleanupComplete && identities.length > 0) {
      stats.expectedEmptyExpiryAfter = new Date(Date.now() + 600_000).toISOString();
      stats.expiryNote = 'All test humans left; server default empty-room retention is ten minutes. This script does not delete rooms or change retention.';
    }
    delete stats.visibleOwnHandPerspectives;
    delete stats.seenEventSequences;
    console.log(JSON.stringify({ ruleId, roomId: stats.roomId, ok: stats.ok, manualGameActions: stats.manualGameActions,
      botActionCount: stats.botActionCount, cleanupComplete: stats.cleanupComplete, durationMs: stats.durationMs }));
  }
}

try {
  const rules = await request('/rulesets', { deadline: Date.now() + 6000 });
  for (const [id, count] of [['yaoming-3p', 3], ['yaoming-4p', 4]]) {
    assert.ok(rules.data.some(rule => rule.id === id && rule.playerCount === count), 'Required rule profile unavailable');
  }
  report.checks.bothRuleProfilesAvailable = true;
  await exerciseRoom('yaoming-3p', 3);
  await exerciseRoom('yaoming-4p', 4);
  report.ok = report.rooms.length === 2 && report.rooms.every(room => room.ok && room.cleanupComplete);
} catch (error) {
  report.error = error instanceof assert.AssertionError ? error.message.split('\n')[0] : String(error.message ?? 'Smoke failure');
} finally {
  report.finishedAt = new Date().toISOString();
  await mkdir(fileURLToPath(new URL('../artifacts/dell-redeploy-20260914/', import.meta.url)), { recursive: true });
  await writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  console.log(JSON.stringify({ ok: report.ok, reportPath, roomIds: report.rooms.map(room => room.roomId).filter(Boolean) }));
  process.exitCode = report.ok ? 0 : 1;
}
