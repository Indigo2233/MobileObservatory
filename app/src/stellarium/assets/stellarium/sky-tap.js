(function (root, factory) {
    "use strict";
    const api = factory();
    if (typeof module === "object" && module.exports) module.exports = api;
    else root.IndigoSkyTap = api;
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";

    // Observe completed short taps only. The engine retains every pan/pinch event.
    function install(canvas, onTap, clock, defer) {
        const now = clock || Date.now;
        const enqueue = defer || function (callback) { setTimeout(callback, 0); };
        let gesture = null;
        let lastTouchAt = -Infinity;
        const slopSquared = 8 * 8;

        function start(x, y) { gesture = {x: x, y: y, at: now(), valid: true}; }
        function move(x, y) {
            if (!gesture) return;
            const dx = x - gesture.x;
            const dy = y - gesture.y;
            if (dx * dx + dy * dy > slopSquared) gesture.valid = false;
        }
        function finish(x, y) {
            move(x, y);
            const completed = gesture;
            gesture = null;
            if (completed && completed.valid && now() - completed.at <= 500) {
                // Engine touchend/mouseup processes selection first, even if its listener
                // was installed later. A gesture cancellation never enters this path.
                enqueue(onTap);
            }
        }
        canvas.addEventListener("touchstart", function (event) {
            lastTouchAt = now();
            if (event.touches.length !== 1) { if (gesture) gesture.valid = false; return; }
            if (gesture) return;
            start(event.touches[0].clientX, event.touches[0].clientY);
        }, {passive: true});
        canvas.addEventListener("touchmove", function (event) {
            if (event.touches.length !== 1) { if (gesture) gesture.valid = false; return; }
            move(event.touches[0].clientX, event.touches[0].clientY);
        }, {passive: true});
        canvas.addEventListener("touchend", function (event) {
            lastTouchAt = now();
            if (event.touches.length !== 0 || event.changedTouches.length !== 1) {
                if (gesture) gesture.valid = false;
                return;
            }
            finish(event.changedTouches[0].clientX, event.changedTouches[0].clientY);
        }, {passive: true});
        canvas.addEventListener("touchcancel", function () { gesture = null; }, {passive: true});
        canvas.addEventListener("mousedown", function (event) {
            if (event.button !== 0 || now() - lastTouchAt < 1000) return;
            start(event.clientX, event.clientY);
        });
        canvas.addEventListener("mousemove", function (event) {
            if ((event.buttons & 1) !== 0) move(event.clientX, event.clientY);
        });
        canvas.addEventListener("mouseup", function (event) {
            if (event.button !== 0 || now() - lastTouchAt < 1000) return;
            finish(event.clientX, event.clientY);
        });
        canvas.addEventListener("mouseleave", function () { gesture = null; });
        canvas.addEventListener("pointercancel", function () { gesture = null; });
    }
    return {install: install};
}));
