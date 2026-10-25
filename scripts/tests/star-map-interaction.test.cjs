const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {install} = require('../../app/src/stellarium/assets/stellarium/sky-tap.js');

function fixture() {
    const listeners = new Map();
    const queue = [];
    let time = 0;
    let taps = 0;
    const canvas = {
        addEventListener(name, fn) {
            if (!listeners.has(name)) listeners.set(name, []);
            listeners.get(name).push(fn);
        }
    };
    install(canvas, () => taps++, () => time, fn => queue.push(fn));
    return {
        send(name, touches = [], changedTouches = touches) {
            for (const fn of listeners.get(name) || []) fn({touches, changedTouches});
        },
        mouse(name, x, y, extra = {}) {
            for (const fn of listeners.get(name) || []) fn({clientX: x, clientY: y, button: 0, buttons: 1, ...extra});
        },
        flush() { while (queue.length) queue.shift()(); },
        advance(ms) { time += ms; },
        get taps() { return taps; }
    };
}
const finger = (x, y) => ({clientX: x, clientY: y});

test('a short blank tap is delivered once after engine processing', () => {
    const f = fixture();
    f.send('touchstart', [finger(20, 30)]);
    f.advance(100);
    f.send('touchend', [], [finger(21, 31)]);
    assert.equal(f.taps, 0);
    f.flush();
    assert.equal(f.taps, 1);
});

test('drag out and back is still a drag', () => {
    const f = fixture();
    f.send('touchstart', [finger(20, 30)]);
    f.send('touchmove', [finger(40, 30)]);
    f.send('touchmove', [finger(20, 30)]);
    f.send('touchend', [], [finger(20, 30)]);
    f.flush();
    assert.equal(f.taps, 0);
});

test('pinch with either lift order cannot dismiss a panel', () => {
    const f = fixture();
    f.send('touchstart', [finger(20, 30)]);
    f.send('touchstart', [finger(20, 30), finger(40, 50)]);
    f.send('touchend', [finger(40, 50)], [finger(20, 30)]);
    f.send('touchend', [], [finger(40, 50)]);
    f.flush();
    assert.equal(f.taps, 0);
});

test('cancelled touches and long presses cannot dismiss a panel', () => {
    const f = fixture();
    f.send('touchstart', [finger(20, 30)]);
    f.send('touchcancel');
    f.send('touchend', [], [finger(20, 30)]);
    f.send('touchstart', [finger(20, 30)]);
    f.advance(700);
    f.send('touchend', [], [finger(20, 30)]);
    f.flush();
    assert.equal(f.taps, 0);
});

test('synthetic mouse events after touch cannot dismiss a second layer', () => {
    const f = fixture();
    f.send('touchstart', [finger(20, 30)]);
    f.send('touchend', [], [finger(20, 30)]);
    f.mouse('mousedown', 20, 30);
    f.mouse('mouseup', 20, 30);
    f.flush();
    assert.equal(f.taps, 1);
});

test('mouse click works but mouse pan does not dismiss', () => {
    const f = fixture();
    f.mouse('mousedown', 20, 30);
    f.mouse('mouseup', 20, 30);
    f.mouse('mousedown', 20, 30);
    f.mouse('mousemove', 80, 30);
    f.mouse('mouseup', 80, 30);
    f.flush();
    assert.equal(f.taps, 1);
});

function integrationFixture({beforeReady = () => {}, observer = true, landscape = null, deferFrames = false} = {}) {
    let now = 1700000000000;
    const ClockDate = class extends Date { constructor(...args) { super(...(args.length ? args : [now])); } static now() { return now; } };
    const elements = new Map();
    const listeners = new Map();
    const canvas = {style: {}, addEventListener(name, fn) {
        if (!listeners.has(name)) listeners.set(name, []);
        listeners.get(name).push(fn);
    }};
    const messages = [];
    for (const id of ['engine-status', 'mount-position', 'fov-current', 'fov-target']) {
        elements.set(id, {style: {}, classList: {toggle() {}, remove() {}}});
    }
    elements.set('stel-canvas', canvas);
    let ready;
    let changed;
    const deferred = [];
    const animationFrames = [];
    const doc = {getElementById: id => elements.get(id), documentElement: {clientWidth: 400, clientHeight: 600}};
    const window = {
        innerWidth: 400, innerHeight: 600, location: {href: 'https://example.test/index.html' +
            (landscape == null ? '' : '?landscape=' + (landscape ? '1' : '0'))},
        addEventListener() {}, requestAnimationFrame: fn => deferFrames ? animationFrames.push(fn) : fn(),
        IndigoSkyTap: {install: (element, callback) => install(element, callback, Date.now, fn => deferred.push(fn))},
        AndroidStarMap: new Proxy({}, {get: (_, method) => value => messages.push({method, value})}),
        StelWebEngine: options => { ready = options.onReady; }
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../app/src/stellarium/assets/stellarium/app.js'), 'utf8'),
        {window, document: doc, URL, Date: ClockDate, Math, Set, Map, JSON, isFinite});
    const source = {addDataSource() {}};
    let ra = 1;
    let looks = 0;
    const looksAt = [];
    const frames = [];
    const star = {getInfo: () => [ra, 0.2, 1], designations: () => ['NAME Vega']};
    const engine = {
        D2R: Math.PI / 180, R2D: 180 / Math.PI,
        core: {atmosphere: {}, stars: {...source}, skycultures: source, dsos: source, landscapes: source,
            milkyway: source, minor_planets: source, planets: source, comets: source,
            lines: {azimuthal: {}, meridian: {}}, observer: {yaw: 0, pitch: 0}, fov: 1},
        getObj: () => star, pointAndLock() {}, lookAt(value) { looks++; looksAt.push(value); },
        convertFrame: (_, from, to, v) => { frames.push({from, to}); return v; }, s2c: (a, b) => [a, b, 1], c2s: v => [v[0], v[1]],
        anp: x => x, anpm: x => x, date2MJD: date => Number(date) / 86400000,
        change: callback => { changed = callback; }
    };
    engine.observer = engine.core.observer;
    beforeReady(window.MercStarMap);
    ready(engine);
    if (observer) window.MercStarMap.setObserver(31, 121, now);
    return {window, engine, messages, looksAt, frames, elements,
        renderFrame() { const callbacks = animationFrames.splice(0); callbacks.forEach(fn => fn()); },
        get now() { return now; }, advance(ms) { now += ms; },
        get looks() { return looks; },
        touch(name, touches, changedTouches = touches) {
            for (const fn of listeners.get(name) || []) fn({touches, changedTouches});
        },
        refresh() { changed(); }, moveStar() { ra += 0.001; },
        tap() {
            for (const fn of listeners.get('touchstart') || []) fn({touches: [finger(20, 30)]});
            for (const fn of listeners.get('touchend') || []) fn({touches: [], changedTouches: [finger(20, 30)]});
            while (deferred.length) deferred.shift()();
        }
    };
}

test('sky overlays refresh after the renderer updates its view matrix without another property change', () => {
    const f = integrationFixture({deferFrames: true});
    let matrixRa = 5 * Math.PI / 12;
    let matrixDec = 20 * Math.PI / 180;
    f.engine.convertFrame = (_, from, to, v) => to === 'VIEW'
        ? [v[0] - matrixRa, v[1] - matrixDec, 1] : v;
    f.window.MercStarMap.setCurrentCircleFovOverlay(1, false, '', 5, 20, 'JNOW');
    f.window.MercStarMap.setTargetCircleFovOverlay(1, '', 5.1, 20, 'JNOW');
    const current = f.elements.get('fov-current');
    const target = f.elements.get('fov-target');
    const before = [current.style.left, target.style.left];
    // The engine publishes yaw/pitch changes before its next _core_render updates VIEW.
    f.engine.core.observer.yaw = .1;
    f.refresh();
    f.renderFrame();
    matrixRa += .1;
    matrixDec += .05;
    f.renderFrame();
    assert.notEqual(current.style.left, before[0]);
    assert.notEqual(target.style.left, before[1]);
    assert.equal(current.style.display, 'block');
    assert.equal(target.style.display, 'block');
});

test('engine coordinate refreshes never become explicit target selections', () => {
    const f = integrationFixture();
    assert.equal(f.messages.filter(message => message.method === 'onEngineError').length, 0, JSON.stringify(f.messages));
    assert.equal(f.window.MercStarMap.selectByDesignations('["NAME Vega"]'), true);
    f.refresh();
    f.moveStar();
    f.refresh();
    assert.equal(f.messages.filter(message => message.method === 'onTargetSelected').length, 1);
    assert.equal(f.messages.filter(message => message.method === 'onTargetUpdated').length, 1);
});

test('blank canvas taps emit one dismissal, while reselecting the same star opens its card', () => {
    const f = integrationFixture();
    f.window.MercStarMap.selectByDesignations('["NAME Vega"]');
    f.tap();
    assert.equal(f.messages.filter(message => message.method === 'onTargetSelected').length, 2);
    f.engine.core.selection = null;
    f.refresh();
    assert.equal(f.messages.filter(message => message.method === 'onBlankTapped').length, 0);
    f.tap();
    assert.equal(f.messages.filter(message => message.method === 'onBlankTapped').length, 1);
    assert.equal(f.messages.filter(message => message.method === 'onSelectionCleared').length, 0);
});

test('optical pointing updates cannot recenter during a pinch or pause following after either lift order', () => {
    const f = integrationFixture();
    f.window.MercStarMap.setMountCoordinates(1, 0);
    f.window.MercStarMap.setPointingCoordinates(5, 20, 'ICRF', f.now, f.now);
    f.window.MercStarMap.setFollowMount(true);
    const initialLooks = f.looks;
    f.touch('touchstart', [finger(20, 30)]);
    f.touch('touchstart', [finger(20, 30), finger(40, 50)]);
    f.touch('touchend', [finger(40, 50)], [finger(20, 30)]);
    f.touch('touchmove', [finger(80, 90)]);
    f.window.MercStarMap.setMountCoordinates(1.1, 0);
    f.window.MercStarMap.setPointingCoordinates(5.1, 20, 'ICRF', f.now, f.now);
    assert.equal(f.looks, initialLooks);
    f.touch('touchend', [], [finger(40, 50)]);
    f.window.MercStarMap.setPointingCoordinates(5.2, 20, 'ICRF', f.now, f.now);
    assert.equal(f.looks, initialLooks + 1);
    assert.equal(f.messages.filter(message => message.method === 'onFollowMountChanged').length, 0);
});

test('following uses an explicit optical source without a mount and mount polling is diagnostic only', () => {
    const f = integrationFixture();
    f.window.MercStarMap.setPointingCoordinates(5, 20, 'ICRF', f.now, f.now);
    assert.equal(f.looks, 0); // default off
    f.window.MercStarMap.setFollowMount(true);
    assert.equal(f.looks, 1);
    assert.equal(f.frames.at(-1).from, 'ICRF');
    const lastDirection = f.looksAt.at(-1);
    f.window.MercStarMap.setMountCoordinates(12, -50);
    assert.equal(f.looks, 1);
    assert.equal(f.looksAt.at(-1), lastDirection);
    f.window.MercStarMap.setPointingCoordinates(6, 30, 'JNOW', f.now, f.now);
    assert.equal(f.looks, 2);
    assert.equal(f.frames.at(-1).from, 'JNOW');
    assert.equal(f.messages.filter(value => value.method === 'onTargetSelected').length, 0);
});

test('expired or cleared optical sources stop follow without switching to mount or map center', () => {
    const f = integrationFixture();
    f.window.MercStarMap.setMountCoordinates(12, -50);
    f.window.MercStarMap.setPointingCoordinates(5, 20, 'ICRF', f.now, f.now);
    f.window.MercStarMap.setFollowMount(true);
    const count = f.looks;
    f.advance(3001);
    assert.equal(f.window.MercStarMap.centerOnPointing(0), false);
    assert.equal(f.looks, count);
    f.window.MercStarMap.setPointingCoordinates(6, 30, 'ICRF', f.now, f.now);
    assert.equal(f.looks, count); // fresh data cannot silently resume a stopped session
    f.window.MercStarMap.setFollowMount(true);
    assert.equal(f.looks, count + 1);
    f.window.MercStarMap.clearPointingCoordinates();
    f.window.MercStarMap.setMountCoordinates(15, 60);
    assert.equal(f.looks, count + 1);
    assert.equal(f.messages.filter(value => value.method === 'onFollowMountChanged' && value.value === 'false').length, 2);
});

test('a single-finger pan pauses optical follow and coordinate refreshes do not resume it', () => {
    const f = integrationFixture();
    f.window.MercStarMap.setPointingCoordinates(5, 20, 'ICRF', f.now, f.now);
    f.window.MercStarMap.setFollowMount(true);
    const count = f.looks;
    f.touch('touchstart', [finger(20, 30)]);
    f.touch('touchmove', [finger(37, 30)]);
    f.touch('touchend', [], [finger(37, 30)]);
    f.window.MercStarMap.setPointingCoordinates(6, 30, 'ICRF', f.now, f.now);
    assert.equal(f.looks, count);
    assert.equal(f.messages.filter(value => value.method === 'onFollowMountChanged' && value.value === 'false').length, 1);
});

test('an unset location allows simulated landscape but cannot advertise a local horizon or optical follow', () => {
    const f = integrationFixture();
    f.window.MercStarMap.setSkyAppearance(false, true, true, false, false, false, false, false);
    assert.equal(f.engine.core.lines.azimuthal.visible, true);
    f.window.MercStarMap.clearObserver();
    assert.equal(f.engine.core.lines.azimuthal.visible, false);
    assert.equal(f.engine.core.lines.meridian.visible, false);
    assert.equal(f.engine.core.landscapes.visible, true);
    f.window.MercStarMap.setPointingCoordinates(5, 20, 'ICRF', f.now, f.now);
    f.window.MercStarMap.setFollowMount(true);
    assert.equal(f.window.MercStarMap.centerOnPointing(0), false);
    assert.equal(f.looks, 0);
    assert.equal(f.window.MercStarMap.setObserver(91, 0, f.now), false);
    f.window.MercStarMap.setObserver(31, 121, f.now);
    f.window.MercStarMap.setFollowMount(true);
    assert.equal(f.looks, 1);
});

test('simulated landscape preference survives initialization, site loss and observer/time refresh', () => {
    const f = integrationFixture({landscape: false, observer: false});
    assert.equal(f.engine.core.landscapes.visible, false);
    f.window.MercStarMap.setObserver(31, 121, f.now);
    f.window.MercStarMap.setTime(f.now + 60000);
    assert.equal(f.engine.core.landscapes.visible, false);
    f.window.MercStarMap.clearObserver();
    f.window.MercStarMap.setLandscapeVisible(true);
    assert.equal(f.engine.core.landscapes.visible, true);
    assert.equal(f.engine.core.atmosphere.visible, false);
    f.window.MercStarMap.setLandscapeVisible(false);
    assert.equal(f.engine.core.landscapes.visible, false);
    const pending = integrationFixture({beforeReady: api => api.setLandscapeVisible(false), observer: false});
    assert.equal(pending.engine.core.landscapes.visible, false);
});

test('phone browse follow works without a site and never publishes an optical pointing source', () => {
    const f = integrationFixture({observer: false});
    const frameCount = f.frames.length;
    f.window.MercStarMap.setPhoneFollow(true);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(90, 30, f.now), true);
    assert.deepEqual(f.looksAt.at(-1), [Math.PI / 2, Math.PI / 6, 1]);
    assert.equal(f.frames.length, frameCount); // no celestial / optical-source conversion
    assert.equal(f.messages.filter(m => /Pointing|TargetSelected/.test(m.method)).length, 0);
    assert.equal(f.window.MercStarMap.centerOnPointing(0), false);
});

test('phone and optical following are mutually exclusive and stale phone samples pause browsing', () => {
    const f = integrationFixture();
    f.window.MercStarMap.setPointingCoordinates(5, 20, 'ICRF', f.now, f.now);
    f.window.MercStarMap.setFollowMount(true);
    f.window.MercStarMap.setPhoneFollow(true);
    assert.equal(f.messages.filter(m => m.method === 'onFollowMountChanged' && m.value === 'false').length, 1);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(0, 0, f.now), true);
    const before = f.looks;
    f.window.MercStarMap.setPointingCoordinates(6, 30, 'ICRF', f.now, f.now);
    assert.equal(f.looks, before);
    f.window.MercStarMap.setFollowMount(true);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(0, 0, f.now), false);
    assert.equal(f.messages.filter(m => m.method === 'onPhoneFollowChanged' && m.value === 'false').length, 1);
    f.window.MercStarMap.setPhoneFollow(true);
    f.advance(1001);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(0, 0, f.now - 1001), false);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(0, 0, f.now), false);
});

test('phone browse respects pinch protection, pan pause and lifecycle shutdown', () => {
    const f = integrationFixture();
    f.window.MercStarMap.setPhoneFollow(true);
    f.touch('touchstart', [finger(20, 30), finger(60, 60)]);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(0, 0, f.now), false);
    f.touch('touchend', [finger(60, 60)], [finger(20, 30)]);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(0, 0, f.now), false);
    f.touch('touchend', [], [finger(60, 60)]);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(0, 0, f.now), true);
    f.touch('touchstart', [finger(20, 30)]);
    f.touch('touchmove', [finger(37, 30)]);
    f.touch('touchend', [], [finger(37, 30)]);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(0, 0, f.now), false);
    f.window.MercStarMap.setPhoneFollow(true);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(0, 0, f.now), true);
    f.window.MercStarMap.setPhoneFollow(false); // native foreground/lifecycle owner stops
    assert.equal(f.window.MercStarMap.setPhoneOrientation(0, 0, f.now), false);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(NaN, 0, f.now), false);
    assert.equal(f.window.MercStarMap.setPhoneOrientation(0, 91, f.now), false);
});
