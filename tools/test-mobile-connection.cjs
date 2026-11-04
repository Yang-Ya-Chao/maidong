const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const elements = new Map();
function element(selector) {
  if (!elements.has(selector)) elements.set(selector, {
    textContent: '', innerHTML: '', value: '', dataset: {}, children: [],
    classList: { values: new Set(), toggle(name, on) { on ? this.values.add(name) : this.values.delete(name); }, add() {}, remove() {} },
    addEventListener() {}, scrollIntoView() {}, insertAdjacentHTML() {}, focus() {}
  });
  return elements.get(selector);
}
let offline = false;
let calls = 0;
const timers = new Map();
let nextTimer = 0;
const context = vm.createContext({
  console, AbortController,
  document: { hidden: false, querySelector: element, querySelectorAll: () => [], addEventListener() {} },
  window: {
    setTimeout(fn, ms) { const id = ++nextTimer; timers.set(id, { fn, ms }); return id; },
    clearTimeout(id) { timers.delete(id); }, addEventListener() {}
  },
  async fetch() {
    calls++;
    if (offline) throw new Error('模拟网络中断');
    return { ok: true, json: async () => ({ ok: true, songs: [], queue: [] }) };
  }
});
vm.runInContext(fs.readFileSync('app/src/main/assets/mobile/app.js', 'utf8'), context);
const run = code => vm.runInContext(code, context);
(async () => {
  // Allow startup state + hot-list requests to finish.
  for (let i = 0; i < 10; i++) await Promise.resolve();
  assert.equal(run('uiState.connected'), true);
  offline = true;
  await assert.rejects(run('api("/api/v1/songs?q=test")'));
  assert.equal(run('uiState.connected'), true, 'search timeout must not mark the phone disconnected');
  await run('refresh()');
  await run('refresh()');
  assert.equal(run('uiState.connected'), true, 'transient failures must keep the connected indicator');
  await run('refresh()');
  assert.equal(run('uiState.connected'), false, 'sustained heartbeat failure must become visible');
  offline = false;
  await run('refresh()');
  assert.equal(run('uiState.connected'), true, 'connection must recover without reloading or scanning again');
  assert.equal(run('uiState.heartbeatFailures'), 0);
  const before = calls;
  await Promise.all([run('heartbeat()'), run('heartbeat()'), run('heartbeat()')]);
  assert.equal(calls - before, 1, 'resume events must not create overlapping heartbeat requests');
  assert.equal([...timers.values()].filter(t => t.ms === 1500).length, 1, 'only one heartbeat timer may remain');
  run('uiState.visible = false');
  const paused = calls;
  await run('heartbeat()');
  assert.equal(calls, paused, 'hidden pages must stop polling');
  console.log('PASS: search timeout, transient failures, sustained disconnect, reconnect, single polling loop, hidden-page pause');
})().catch(error => { console.error(error); process.exitCode = 1; });
