const { readFileSync } = require('node:fs');
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const script = readFileSync('app/src/main/assets/android-performance.js', 'utf8');

function setup(fps = 30) {
    const warnings = [];
    const context = vm.createContext({ PvzTargetFps: fps, console: { warn: text => warnings.push(text) } });
    context.window = context;
    vm.runInContext(script, context);
    class Game {
        constructor() { this._fps = 60; this.paused = false; this.pausedByEngine = false; }
        get frameRate() { return this._fps; }
        set frameRate(fps) { this._fps = fps; this.frameTime = 1000 / fps; }
        setFrameRate(fps) { this.frameRate = fps; }
        pauseByEngine() {
            if (!this.paused) { this.pausedByEngine = true; this.paused = true; }
        }
        resumeByEngine() {
            if (this.pausedByEngine) { this.pausedByEngine = false; this.paused = false; }
        }
    }
    return { context, controller: context.PvzPerformance, game: new Game(), warnings };
}

test('30 FPS target uses the engine setter and survives game startup settings', () => {
    const s = setup();
    s.controller.install({ game: s.game });
    assert.equal(s.game.frameRate, 30);
    assert.equal(s.game.frameTime, 1000 / 30);
    s.game.setFrameRate(60);
    assert.equal(s.game.frameRate, 30);
});

test('switching profiles restores the requested rate without reloading', () => {
    const s = setup();
    s.controller.install({ game: s.game });
    s.controller.setTargetFps(60);
    assert.equal(s.game.frameRate, 60);
    s.controller.setTargetFps(30);
    assert.equal(s.game.frameRate, 30);
});

test('lower game-requested frame rates are preserved', () => {
    const s = setup();
    s.controller.install({ game: s.game });
    s.game.frameRate = 15;
    s.controller.setTargetFps(60);
    assert.equal(s.game.frameRate, 15);
});

test('high refresh rates are capped and invalid requests do not poison timing', () => {
    const s = setup(60);
    s.controller.install({ game: s.game });
    for (const value of [120, Infinity, NaN, 0, -1, 'invalid']) {
        s.game.frameRate = value;
        assert.equal(s.game.frameRate, 60);
    }
    s.game.frameRate = '30';
    assert.equal(s.game.frameRate, 30);
});

test('profile can be chosen before the engine loads; installation is idempotent', () => {
    const s = setup();
    s.controller.setTargetFps(60);
    s.controller.install({ game: s.game });
    s.controller.install({ game: s.game });
    vm.runInContext(script, s.context);
    assert.equal(s.context.PvzPerformance, s.controller);
    assert.equal(s.game.frameRate, 60);
});

test('unsupported engines are left intact and report the unavailable FPS hook', () => {
    const s = setup();
    const unsupported = { frameRate: 60 };
    s.controller.install({ game: unsupported });
    assert.equal(unsupported.frameRate, 60);
    assert.equal(s.warnings.length, 1);
    s.controller.install({ game: s.game });
    assert.equal(s.game.frameRate, 30);
});

test('background pause resumes only games paused by the engine', () => {
    const s = setup();
    s.controller.install({ game: s.game });
    s.controller.setPaused(true);
    assert.equal(s.game.paused, true);
    s.controller.setPaused(false);
    assert.equal(s.game.paused, false);
    s.game.paused = true;
    s.controller.setPaused(true);
    s.controller.setPaused(false);
    assert.equal(s.game.paused, true);
});
