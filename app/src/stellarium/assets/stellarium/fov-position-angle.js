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

    function clamp(value, minimum, maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    function add(a, b) {
        return [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
    }

    function scale(vector, factor) {
        return [vector[0] * factor, vector[1] * factor, vector[2] * factor];
    }

    function unit(vector) {
        const length = Math.hypot(vector[0], vector[1], vector[2]);
        return scale(vector, 1 / length);
    }

    function skyVector(raHours, decDegrees) {
        const longitude = Number(raHours) * Math.PI / 12;
        const latitude = Number(decDegrees) * DEG_TO_RAD;
        const cosLatitude = Math.cos(latitude);
        return [
            cosLatitude * Math.cos(longitude),
            cosLatitude * Math.sin(longitude),
            Math.sin(latitude)
        ];
    }

    function vectorToSky(vector) {
        const normalized = unit(vector);
        return {
            raHours: normalizeDegrees(Math.atan2(normalized[1], normalized[0]) * RAD_TO_DEG) / 15,
            decDegrees: Math.asin(clamp(normalized[2], -1, 1)) * RAD_TO_DEG
        };
    }

    /**
     * Converts a coordinate on the framing tangent plane to a sky position.
     * right/up are dimensionless gnomonic offsets. The position angle is the
     * image-up direction measured from celestial north through east.
     */
    function tangentPlanePoint(raHours, decDegrees, positionAngleDeg, right, up) {
        const longitude = Number(raHours) * Math.PI / 12;
        const latitude = Number(decDegrees) * DEG_TO_RAD;
        const angle = Number(positionAngleDeg) * DEG_TO_RAD;
        const center = skyVector(raHours, decDegrees);
        const east = [-Math.sin(longitude), Math.cos(longitude), 0];
        const north = [
            -Math.sin(latitude) * Math.cos(longitude),
            -Math.sin(latitude) * Math.sin(longitude),
            Math.cos(latitude)
        ];
        // A north-up astronomical image has east on the left. This keeps the
        // sensor image axes right-handed in pixel coordinates (Y points down).
        const sensorRight = add(scale(east, -Math.cos(angle)), scale(north, Math.sin(angle)));
        const sensorUp = add(scale(north, Math.cos(angle)), scale(east, Math.sin(angle)));
        return vectorToSky(
            add(center, add(scale(sensorRight, Number(right)), scale(sensorUp, Number(up))))
        );
    }

    /**
     * Builds a row-major sensor mosaic in one gnomonic framing plane. Keeping
     * all panel edges in the same plane preserves the requested overlap while
     * the sky coordinates remain valid across the poles and RA wraparound.
     */
    function sensorMosaicPanels(
        raHours,
        decDegrees,
        widthDeg,
        heightDeg,
        positionAngleDeg,
        rows,
        columns,
        overlapPercent,
        traversal,
        startCorner
    ) {
        const rowCount = clamp(Math.round(Number(rows) || 1), 1, 10);
        const columnCount = clamp(Math.round(Number(columns) || 1), 1, 10);
        const overlap = clamp(Number(overlapPercent) || 0, 0, 90) / 100;
        const halfWidth = Math.tan(clamp(Number(widthDeg), 0.000001, 179) * DEG_TO_RAD / 2);
        const halfHeight = Math.tan(clamp(Number(heightDeg), 0.000001, 179) * DEG_TO_RAD / 2);
        const stepRight = 2 * halfWidth * (1 - overlap);
        const stepUp = 2 * halfHeight * (1 - overlap);
        const topFirst = String(startCorner || "TOP_LEFT").indexOf("TOP_") === 0;
        const leftFirst = String(startCorner || "TOP_LEFT").slice(-4) === "LEFT";
        const rowOrder = Array.from({ length: rowCount }, function (_, index) {
            return topFirst ? index : rowCount - 1 - index;
        });
        const columnOrder = Array.from({ length: columnCount }, function (_, index) {
            return leftFirst ? index : columnCount - 1 - index;
        });
        const cells = [];
        if (traversal === "COLUMNS") {
            columnOrder.forEach(function (column) {
                rowOrder.forEach(function (row) { cells.push([row, column]); });
            });
        } else {
            rowOrder.forEach(function (row, rowIndex) {
                const scan = traversal === "SNAKE" && rowIndex % 2 === 1
                    ? columnOrder.slice().reverse()
                    : columnOrder;
                scan.forEach(function (column) { cells.push([row, column]); });
            });
        }
        const sequenceNumbers = {};
        cells.forEach(function (cell, index) {
            sequenceNumbers[cell[0] + ":" + cell[1]] = index + 1;
        });
        const panels = [];
        for (let row = 0; row < rowCount; row += 1) {
            const centerUp = ((rowCount - 1) / 2 - row) * stepUp;
            for (let column = 0; column < columnCount; column += 1) {
                const centerRight = (column - (columnCount - 1) / 2) * stepRight;
                const cornerOffsets = [
                    [centerRight - halfWidth, centerUp + halfHeight],
                    [centerRight + halfWidth, centerUp + halfHeight],
                    [centerRight + halfWidth, centerUp - halfHeight],
                    [centerRight - halfWidth, centerUp - halfHeight]
                ];
                panels.push({
                    number: sequenceNumbers[row + ":" + column],
                    row: row,
                    column: column,
                    center: tangentPlanePoint(
                        raHours,
                        decDegrees,
                        positionAngleDeg,
                        centerRight,
                        centerUp
                    ),
                    corners: cornerOffsets.map(function (offset) {
                        return tangentPlanePoint(
                            raHours,
                            decDegrees,
                            positionAngleDeg,
                            offset[0],
                            offset[1]
                        );
                    })
                });
            }
        }
        return panels;
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
        projectedSensorRotationDeg,
        tangentPlanePoint,
        sensorMosaicPanels
    };
}));
