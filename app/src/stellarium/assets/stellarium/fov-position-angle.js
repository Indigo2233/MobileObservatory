(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) {
        module.exports = api;
    }
    root.MercFovPositionAngle = api;
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
    "use strict";

    const DEG_TO_RAD = Math.PI / 180;
    const RAD_TO_DEG = 180 / Math.PI;
    const REFERENCE_DISTANCE_DEG = 0.01;

    function normalizeDegrees(value) {
        return ((value % 360) + 360) % 360;
    }

    /**
     * Returns a nearby point reached along the astronomical position angle.
     * Position angle is measured from celestial north through east, matching
     * NINA and plate-solve WCS conventions.
     */
    function positionAngleReference(
        raHours,
        decDegrees,
        positionAngleDeg,
        distanceDeg
    ) {
        const longitude = Number(raHours) * Math.PI / 12;
        const latitude = Number(decDegrees) * DEG_TO_RAD;
        const bearing = Number(positionAngleDeg) * DEG_TO_RAD;
        const distance = Number.isFinite(Number(distanceDeg))
            ? Number(distanceDeg) * DEG_TO_RAD
            : REFERENCE_DISTANCE_DEG * DEG_TO_RAD;
        const referenceLatitude = Math.asin(
            Math.sin(latitude) * Math.cos(distance)
            + Math.cos(latitude) * Math.sin(distance) * Math.cos(bearing)
        );
        const referenceLongitude = longitude + Math.atan2(
            Math.sin(bearing) * Math.sin(distance) * Math.cos(latitude),
            Math.cos(distance) - Math.sin(latitude) * Math.sin(referenceLatitude)
        );
        return {
            raHours: normalizeDegrees(referenceLongitude * RAD_TO_DEG) / 15,
            decDegrees: referenceLatitude * RAD_TO_DEG
        };
    }

    /**
     * Projects the sensor long edge. At position angle 0 the image vertical
     * axis points north, so the sensor long edge follows the local declination
     * line. CSS rotation is clockwise in screen coordinates with Y downward.
     */
    function projectedSensorRotationDeg(
        raHours,
        decDegrees,
        positionAngleDeg,
        project
    ) {
        if (typeof project !== "function") return null;
        const center = project(Number(raHours), Number(decDegrees));
        const referenceSky = positionAngleReference(
            raHours,
            decDegrees,
            positionAngleDeg
        );
        const reference = project(referenceSky.raHours, referenceSky.decDegrees);
        if (!center || !reference) return null;
        const dx = Number(reference.x) - Number(center.x);
        const dy = Number(reference.y) - Number(center.y);
        if (!Number.isFinite(dx) || !Number.isFinite(dy) || Math.hypot(dx, dy) < 1e-9) {
            return null;
        }
        return normalizeDegrees(Math.atan2(dy, dx) * RAD_TO_DEG + 90);
    }

    return {
        normalizeDegrees,
        positionAngleReference,
        projectedSensorRotationDeg
    };
}));
