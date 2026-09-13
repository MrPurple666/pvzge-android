const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const source = readFileSync('app/src/main/java/com/pvzge/gardendless/GameActivity.kt', 'utf8');
const script = source.split('private val gameBridgeHookJs = """')[1].split('""".trimIndent()')[0];

function setup(delayed = false) {
  const fullscreen = [], names = [], calls = [], timers = [];
  function Element() {}
  function Document() {}
  function HTMLAnchorElement() {}
  Element.prototype.requestFullscreen = () => Promise.resolve();
  Document.prototype.exitFullscreen = () => Promise.resolve();
  HTMLAnchorElement.prototype.click = () => {};
  const tauri = { invoke: (...args) => { calls.push(args); return Promise.resolve('original'); } };
  const context = vm.createContext({
    Element, Document, HTMLAnchorElement, Promise,
    document: { addEventListener() {} },
    GardendlessBridge: {
      setFullscreen: value => fullscreen.push(value),
      setExportName: name => names.push(name),
    },
    setTimeout() {}, setInterval: fn => { timers.push(fn); return 1; }, clearInterval() {},
  });
  context.window = context;
  if (!delayed) context.__TAURI_INTERNALS__ = tauri;
  vm.runInContext(script, context);
  return { context, tauri, fullscreen, names, calls, timers };
}

test('startup sync preserves preference; subsequent toggles are forwarded', async () => {
  const s = setup();
  for (const value of [false, true, false]) {
    await s.tauri.invoke('plugin:window|set_fullscreen', { value });
  }
  assert.deepEqual(s.fullscreen, [true, false]);
});

test('save dialogs preserve suggested filenames for both argument shapes', async () => {
  const s = setup();
  assert.equal(await s.tauri.invoke('plugin:dialog|save', { defaultPath: '/saves/player.json' }), 'player.json');
  assert.equal(await s.tauri.invoke('plugin:dialog|save', { options: { defaultPath: 'C:\\keys\\keyboard.txt' } }), 'keyboard.txt');
  assert.deepEqual(s.names, ['player.json', 'keyboard.txt']);
  assert.equal(await s.tauri.invoke('unrelated', { value: 1 }), 'original');
});

test('hook waits for Tauri and does not wrap it twice', async () => {
  const s = setup(true);
  s.context.__TAURI_INTERNALS__ = s.tauri;
  s.timers[0]();
  vm.runInContext(script, s.context);
  await s.tauri.invoke('plugin:window|set_fullscreen', { value: true });
  assert.deepEqual(s.fullscreen, [true]);
});

test('download anchors capture names only for data URLs', () => {
  const s = setup();
  const anchor = new s.context.HTMLAnchorElement();
  anchor.download = 'save.json';
  anchor.getAttribute = () => 'data:application/json,%7B%7D';
  anchor.click();
  anchor.getAttribute = () => 'https://example.com/file';
  anchor.click();
  assert.deepEqual(s.names, ['save.json']);
});

test('standard fullscreen requests reach the native bridge', async () => {
  const s = setup();
  await new s.context.Element().requestFullscreen();
  await new s.context.Document().exitFullscreen();
  assert.deepEqual(s.fullscreen, [true, false]);
});
