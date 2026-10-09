const test = require('node:test');
const assert = require('node:assert/strict');

const geometry = require('../../app/src/stellarium/assets/stellarium/fov-position-angle.js');

function angularDistanceDeg(a, b) {
    const ra1 = a.raHours * Math.PI / 12;
    const ra2 = b.raHours * Math.PI / 12;
    const dec1 = a.decDegrees * Math.PI / 180;
    const dec2 = b.decDegrees * Math.PI / 180;
    const cosine = Math.sin(dec1) * Math.sin(dec2)
        + Math.cos(dec1) * Math.cos(dec2) * Math.cos(ra2 - ra1);
    return Math.acos(Math.max(-1, Math.min(1, cosine))) * 180 / Math.PI;
}

function equatorialProjection(centerDecDegrees, viewRotationDeg = 0) {
    const rotation = viewRotationDeg * Math.PI / 180;
    return (raHours, decDegrees) => {
        const x = raHours * 15 * Math.cos(centerDecDegrees * Math.PI / 180);
        const y = -decDegrees;
        return {
            x: x * Math.cos(rotation) - y * Math.sin(rotation),
            y: x * Math.sin(rotation) + y * Math.cos(rotation)
        };
    };
}

test('zero degrees keeps the sensor long edge tangent to declination', () => {
    const rotation = geometry.projectedSensorRotationDeg(
        5,
        20,
        0,
        equatorialProjection(20)
    );
    assert.ok(Math.abs(rotation) < 1e-6 || Math.abs(rotation - 360) < 1e-6);
});

test('ninety degrees turns the sensor long edge along celestial north', () => {
    const rotation = geometry.projectedSensorRotationDeg(
        5,
        20,
        90,
        equatorialProjection(20)
    );
    assert.ok(Math.abs(rotation - 90) < 0.005);
});

test('screen rotation follows a rotated or horizontal sky view', () => {
    const rotation = geometry.projectedSensorRotationDeg(
        5,
        -35,
        0,
        equatorialProjection(-35, 37)
    );
    assert.ok(Math.abs(rotation - 37) < 1e-6);
});

test('great-circle reference remains stable close to the pole', () => {
    const center = {raHours: 23.8, decDegrees: 89.8};
    for (const positionAngleDeg of [0, 45, 90, 180, 270]) {
        const reference = geometry.positionAngleReference(
            center.raHours,
            center.decDegrees,
            positionAngleDeg
        );
        assert.ok(Number.isFinite(reference.raHours));
        assert.ok(Number.isFinite(reference.decDegrees));
        assert.ok(Math.abs(angularDistanceDeg(center, reference) - 0.01) < 1e-8);
    }
});

test('sensor corners remain finite and preserve angular size near the pole', () => {
    const panels = geometry.sensorMosaicPanels(23.9, 88.5, 24, 16, 37, 1, 1, 0);
    assert.equal(panels.length, 1);
    assert.equal(panels[0].corners.length, 4);
    for (const corner of panels[0].corners) {
        assert.ok(Number.isFinite(corner.raHours));
        assert.ok(Number.isFinite(corner.decDegrees));
        assert.ok(corner.raHours >= 0 && corner.raHours < 24);
        assert.ok(corner.decDegrees >= -90 && corner.decDegrees <= 90);
    }
    const topWidth = angularDistanceDeg(panels[0].corners[0], panels[0].corners[1]);
    const sideHeight = angularDistanceDeg(panels[0].corners[0], panels[0].corners[3]);
    assert.ok(topWidth > 20 && topWidth < 25);
    assert.ok(sideHeight > 14 && sideHeight < 17);
});

test('mosaic panels use row-major numbering and exact zero-overlap edges', () => {
    const panels = geometry.sensorMosaicPanels(5, 20, 4, 3, 0, 2, 3, 0);
    assert.deepEqual(panels.map(panel => panel.number), [1, 2, 3, 4, 5, 6]);
    assert.equal(panels[0].row, 0);
    assert.equal(panels[0].column, 0);
    assert.ok(angularDistanceDeg(panels[0].corners[1], panels[1].corners[0]) < 1e-10);
    assert.ok(angularDistanceDeg(panels[0].corners[2], panels[1].corners[3]) < 1e-10);
    assert.ok(angularDistanceDeg(panels[0].corners[3], panels[3].corners[0]) < 1e-10);
});

test('overlap and position angle move mosaic centers on the framing plane', () => {
    const separate = geometry.sensorMosaicPanels(5, 0, 10, 8, 90, 1, 2, 0);
    const overlapped = geometry.sensorMosaicPanels(5, 0, 10, 8, 90, 1, 2, 20);
    assert.ok(angularDistanceDeg(overlapped[0].center, overlapped[1].center)
        < angularDistanceDeg(separate[0].center, separate[1].center));
    assert.ok(separate[0].center.decDegrees < 0);
    assert.ok(separate[1].center.decDegrees > 0);
});
