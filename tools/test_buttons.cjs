'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/web/static/app.js'), 'utf8');
const settle = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
function fixture({ wifiFailure } = {}) {
  const nodes = new Map(), timers = new Map(), calls = [];
  let serial = 0;
  const node = selector => {
    if (!nodes.has(selector)) nodes.set(selector, {
      listeners: {},
      addEventListener(type, listener) { this.listeners[type] = listener; },
      classList: { toggle() {}, add() {} }, contains() { return false; }
    });
    return nodes.get(selector);
  };
  const snapshot = {
    paused: false, activeVoices: [], catalog: [], schedule: [], settings: { canScheduleExactAlarms: true },
    wifiWatchdog: { enabled: false, status: 'disabled' }
  };
  const context = vm.createContext({
    URLSearchParams, console, confirm: () => true,
    window: { location: { search: '' }, addEventListener() {} },
    document: { querySelector: node, querySelectorAll: () => [], addEventListener() {}, documentElement: node('html') },
    setTimeout(fn) { const id = ++serial; timers.set(id, fn); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval() {}, EventSource: function () {},
    fetch: async (url, opts) => {
      calls.push({ url, ...opts });
      if (url !== '/api/state' && opts.method !== 'POST') return { ok: false, status: 404 };
      if (url === '/api/settings') {
        if (wifiFailure) return { ok: false, status: 409, json: async () => ({ error: wifiFailure }) };
        const enabled = JSON.parse(opts.body).wifiWatchdogEnabled;
        snapshot.wifiWatchdog = enabled
          ? { enabled, status: 'connected', targetSsid: 'Test Wi-Fi' }
          : { enabled, status: 'disabled' };
      }
      if (url === '/api/pause') snapshot.paused = true;
      if (url === '/api/resume') snapshot.paused = false;
      return { ok: true, headers: { get: () => 'application/json' }, json: async () => ({ ...snapshot }) };
    }
  });
  vm.runInContext(source, context);
  return { node, calls, timers, run: code => vm.runInContext(code, context), async drain() {
    await settle();
    for (const [id, fn] of Array.from(timers)) { timers.delete(id); await fn(); }
    await settle();
  } };
}
for (const paused of [false, true]) test(paused ? 'Resume posts and refreshes' : 'Pause posts and refreshes', async () => {
  const f = fixture(); await settle();
  f.run(`state.paused = ${paused}`);
  f.calls.length = 0;
  await f.node('#btn-pause').onclick();
  assert.equal(f.calls[0].url, paused ? '/api/resume' : '/api/pause');
  assert.equal(f.calls[0].method, 'POST');
  await f.drain();
  assert.equal(f.calls[1].url, '/api/state');
  assert.equal(f.calls[1].method, 'GET');
  assert.equal(f.node('#btn-pause').textContent, paused ? 'Pause' : 'Resume');
});
test('Stop All cancels queued volume and waits for in-flight volume before POST and refresh', async () => {
  const f = fixture(); await settle(); f.calls.length = 0;
  f.run('queueVoiceVolume("queued", 0.5); queueVoiceVolume("flying", 0.7); flushVoiceVolume("flying"); voiceVolumes.get("flying").request = new Promise(resolve => { globalThis.releaseVolume = resolve; })');
  const stopped = f.node('#btn-stop').onclick();
  await settle();
  assert.deepEqual(f.calls.map(c => c.url), ['/api/voice/volume']);
  f.run('releaseVolume()');
  await stopped; await settle();
  assert.deepEqual(f.calls.map(c => c.url), ['/api/voice/volume', '/api/stop-all']);
  assert.equal(f.calls[1].method, 'POST');
  assert.equal(f.run('voiceVolumes.size'), 0);
  await f.drain();
  assert.deepEqual(f.calls.map(c => c.url), ['/api/voice/volume', '/api/stop-all', '/api/state']);
  assert.equal(f.calls[2].method, 'GET');
});

test('Wi-Fi Watchdog toggle applies a settings response without waiting for a poll', async () => {
  const f = fixture(); await settle();
  f.run('visibleTab = "settings"; render()');
  const toggle = f.node('#set-wifi-watchdog');
  for (const enabled of [true, false]) {
    toggle.checked = enabled;
    await toggle.listeners.change({ target: toggle });
    assert.equal(f.calls.at(-1).url, '/api/settings');
    assert.deepEqual(JSON.parse(f.calls.at(-1).body), { wifiWatchdogEnabled: enabled });
    assert.equal(toggle.checked, enabled);
    assert.equal(toggle.disabled, false);
    assert.equal(f.node('#wifi-watchdog-details').hidden, !enabled);
  }
});

test('Wi-Fi Watchdog shows the Android setup error and restores the toggle', async () => {
  const wifiFailure = 'Select a saved Wi-Fi network in Android Settings.';
  const f = fixture({ wifiFailure }); await settle();
  f.run('visibleTab = "settings"; render()');
  const toggle = f.node('#set-wifi-watchdog');
  toggle.checked = true;
  await toggle.listeners.change({ target: toggle });
  assert.equal(toggle.checked, false);
  assert.equal(f.node('#wifi-watchdog-error').textContent, wifiFailure);
  assert.equal(f.node('#wifi-watchdog-error').hidden, false);
});
