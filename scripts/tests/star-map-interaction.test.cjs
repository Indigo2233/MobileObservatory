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

function integrationFixture() {
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
    const doc = {getElementById: id => elements.get(id), documentElement: {clientWidth: 400, clientHeight: 600}};
    const window = {
        innerWidth: 400, innerHeight: 600, location: {href: 'https://example.test/index.html'},
        addEventListener() {}, requestAnimationFrame: fn => fn(),
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
    ready(engine);
    window.MercStarMap.setObserver(31, 121, now);
    return {window, engine, messages, looksAt, frames,
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

test('an unset location cannot advertise a local horizon or optical follow', () => {
    const f = integrationFixture();
    f.window.MercStarMap.setSkyAppearance(false, true, true, false, false, false, false, false);
    assert.equal(f.engine.core.lines.azimuthal.visible, true);
    f.window.MercStarMap.clearObserver();
    assert.equal(f.engine.core.lines.azimuthal.visible, false);
    assert.equal(f.engine.core.lines.meridian.visible, false);
    assert.equal(f.engine.core.landscapes.visible, false);
    f.window.MercStarMap.setPointingCoordinates(5, 20, 'ICRF', f.now, f.now);
    f.window.MercStarMap.setFollowMount(true);
    assert.equal(f.window.MercStarMap.centerOnPointing(0), false);
    assert.equal(f.looks, 0);
    assert.equal(f.window.MercStarMap.setObserver(91, 0, f.now), false);
    f.window.MercStarMap.setObserver(31, 121, f.now);
    f.window.MercStarMap.setFollowMount(true);
    assert.equal(f.looks, 1);
});
