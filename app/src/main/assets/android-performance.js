(function () {
    'use strict';
    if (window.PvzPerformance) return;

    var targetFps = window.PvzTargetFps === 30 ? 30 : 60;
    var game = null;
    var requestedFps = 60;
    var paused = false;

    function applyFrameRate() {
        if (game) game.frameRate = requestedFps;
    }

    window.PvzPerformance = {
        install: function (engine) {
            if (game || !engine || !engine.game) return;
            var candidate = engine.game;
            var owner = candidate;
            var descriptor;
            while (owner && !descriptor) {
                descriptor = Object.getOwnPropertyDescriptor(owner, 'frameRate');
                owner = Object.getPrototypeOf(owner);
            }
            if (!descriptor || !descriptor.get || !descriptor.set) {
                console.warn('PvZ performance: unsupported engine frame-rate API');
                return;
            }
            requestedFps = Number(candidate.frameRate) || 60;
            Object.defineProperty(candidate, 'frameRate', {
                configurable: true,
                get: function () { return descriptor.get.call(this); },
                set: function (value) {
                    var fps = Number(value);
                    requestedFps = Number.isFinite(fps) && fps > 0 ? fps : 60;
                    descriptor.set.call(this, Math.min(requestedFps, targetFps));
                }
            });
            game = candidate;
            applyFrameRate();
            if (paused && game.pauseByEngine) game.pauseByEngine();
        },
        setTargetFps: function (fps) {
            targetFps = fps === 30 ? 30 : 60;
            applyFrameRate();
        },
        setPaused: function (value) {
            paused = !!value;
            if (!game) return;
            if (paused && game.pauseByEngine) game.pauseByEngine();
            if (!paused && game.resumeByEngine) game.resumeByEngine();
        }
    };
})();
