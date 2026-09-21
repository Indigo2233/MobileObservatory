(function () {
    "use strict";

    const statusElement = document.getElementById("engine-status");
    const mountPositionElement = document.getElementById("mount-position");
    const fovCurrentElement = document.getElementById("fov-current");
    const fovTargetElement = document.getElementById("fov-target");
    const canvas = document.getElementById("stel-canvas");
    let stel = null;
    let lastSelectionKey = "";
    let pendingObserver = null;
    let pendingMountCoordinates = null;
    let pendingAtmosphereVisible = false;
    let pendingOnlineSurveyEnabled = false;
    let onlineSurveyAdded = false;
    let pendingFovDegrees = null;
    let pendingCurrentFov = null;
    let pendingTargetFov = null;
    let followMount = false;
    // While a finger/mouse is down, mount RA/Dec polls must not yank the view.
    let userPointerActive = false;
    const PAN_PAUSE_FOLLOW_PX = 16;
    let pendingSkyAppearance = {
        equatorialGrid: false,
        azimuthalGrid: false,
        meridian: false,
        ecliptic: false,
        constellationLines: false,
        constellationLabels: false,
        constellationBounds: false,
        starHints: false
    };

    function setChildVisible(parent, id, visible) {
        if (!parent || !parent[id]) return;
        try {
            parent[id].visible = Boolean(visible);
        } catch (_) {
            // Engine build may omit a line id.
        }
    }

    function applySkyAppearance() {
        if (!stel || !stel.core) return;
        const lines = stel.core.lines;
        if (lines) {
            lines.visible = true;
            // Of-date RA/Dec matches the rest of the app (JNOW / 赤道仪坐标).
            setChildVisible(lines, "equatorial_jnow", pendingSkyAppearance.equatorialGrid);
            setChildVisible(lines, "azimuthal", pendingSkyAppearance.azimuthalGrid);
            setChildVisible(lines, "meridian", pendingSkyAppearance.meridian);
            setChildVisible(lines, "ecliptic", pendingSkyAppearance.ecliptic);
        }
        const constellations = stel.core.constellations;
        if (constellations) {
            constellations.lines_visible = Boolean(pendingSkyAppearance.constellationLines);
            constellations.labels_visible = Boolean(pendingSkyAppearance.constellationLabels);
            constellations.bounds_visible = Boolean(pendingSkyAppearance.constellationBounds);
        }
        if (stel.core.stars) {
            stel.core.stars.hints_visible = Boolean(pendingSkyAppearance.starHints);
        }
    }

    function syncCanvasSize() {
        const width = Math.max(
            window.innerWidth || 0,
            document.documentElement.clientWidth || 0,
            1
        );
        const height = Math.max(
            window.innerHeight || 0,
            document.documentElement.clientHeight || 0,
            1
        );
        canvas.style.width = width + "px";
        canvas.style.height = height + "px";
        applyFovOverlay(fovCurrentElement, pendingCurrentFov);
        applyFovOverlay(fovTargetElement, pendingTargetFov);
    }

    function notifyAndroid(method, value) {
        const bridge = window.AndroidStarMap;
        if (!bridge || typeof bridge[method] !== "function") return;
        try {
            bridge[method](value);
        } catch (_) {
            // Android may have destroyed the WebView during navigation.
        }
    }

    function setStatus(message, isError) {
        statusElement.textContent = message;
        statusElement.style.color = isError ? "#ffb4ab" : "#f3f4f6";
        statusElement.style.display = message ? "block" : "none";
    }

    function absoluteUrl(relativePath) {
        return new URL(relativePath, window.location.href).href;
    }

    function addDataSources(engine) {
        const baseUrl = absoluteUrl("./skydata/");
        const core = engine.core;
        core.stars.addDataSource({url: baseUrl + "stars"});
        core.skycultures.addDataSource({url: baseUrl + "skycultures/western", key: "western"});
        core.dsos.addDataSource({url: baseUrl + "dso"});
        core.landscapes.addDataSource({url: baseUrl + "landscapes/guereins", key: "guereins"});
        core.milkyway.addDataSource({url: baseUrl + "surveys/milkyway"});
        core.minor_planets.addDataSource({url: baseUrl + "mpcorb.dat", key: "mpc_asteroids"});
        core.planets.addDataSource({url: baseUrl + "surveys/sso/moon", key: "moon"});
        core.planets.addDataSource({url: baseUrl + "surveys/sso/sun", key: "sun"});
        core.planets.addDataSource({url: baseUrl + "surveys/sso/moon", key: "default"});
        core.comets.addDataSource({url: baseUrl + "CometEls.txt", key: "mpc_comets"});
    }

    function applyObserver(observer) {
        if (!stel || !observer) return;
        stel.observer.latitude = observer.latitudeDeg * stel.D2R;
        stel.observer.longitude = observer.longitudeDeg * stel.D2R;
        stel.observer.utc = stel.date2MJD(new Date(observer.epochMillis));
    }

    function applyMountCoordinates(coordinates) {
        if (!coordinates) {
            mountPositionElement.style.display = "none";
            return;
        }
        mountPositionElement.textContent =
            "赤道仪  RA " + coordinates.raHours.toFixed(5) +
            " h  Dec " + coordinates.decDegrees.toFixed(4) + "°";
        mountPositionElement.style.display = "block";
        if (followMount && stel && !userPointerActive) {
            centerOnRaDec(coordinates.raHours, coordinates.decDegrees, 0);
        }
    }

    function applyAtmosphereVisibility(visible) {
        if (!stel) return;
        stel.core.atmosphere.visible = Boolean(visible);
    }

    function applyOnlineSurveyEnabled(enabled) {
        pendingOnlineSurveyEnabled = Boolean(enabled);
        if (!stel || !stel.core || !stel.core.dss) return;
        if (pendingOnlineSurveyEnabled) {
            if (!onlineSurveyAdded) {
                // Proxied by Android HipsTileCache under /hips/dss.
                const hipsUrl = new URL("/hips/dss", window.location.origin).href;
                stel.core.dss.addDataSource({url: hipsUrl});
                onlineSurveyAdded = true;
            }
            stel.core.dss.visible = true;
        } else {
            stel.core.dss.visible = false;
        }
    }

    function applyFovDegrees(fovDegrees, duration) {
        if (!stel || fovDegrees == null || !(fovDegrees > 0)) return;
        const radians = Math.max(0.01, Number(fovDegrees)) * stel.D2R;
        stel.zoomTo(radians, duration === undefined ? 1 : duration);
        pendingFovDegrees = Number(fovDegrees);
        // zoomTo animates; keep the overlay in sync on every engine tick via
        // refreshFovOverlaysFromEngine, and paint once with the target FOV now.
        applyFovOverlay(fovCurrentElement, pendingCurrentFov);
        applyFovOverlay(fovTargetElement, pendingTargetFov);
    }

    /**
     * Live sky FOV from the engine (radians→degrees). core.fov is the FOV of
     * the smaller viewport axis — see FovOverlayLayout.kt / proj_perspective.
     * Falls back to the last zoomTo target only before the engine is ready.
     */
    function coreFovDegrees() {
        if (stel && stel.core && stel.core.fov != null) {
            const deg = Number(stel.core.fov) * stel.R2D;
            if (deg > 0 && isFinite(deg)) return deg;
        }
        if (pendingFovDegrees != null && pendingFovDegrees > 0) {
            return pendingFovDegrees;
        }
        return null;
    }

    function viewFovsDegrees(viewW, viewH, coreFovDeg) {
        if (!(viewW > 0) || !(viewH > 0) || !(coreFovDeg > 0)) return null;
        const aspect = viewW / viewH;
        const half = (coreFovDeg / 2) * Math.PI / 180;
        if (aspect < 1) {
            return {
                horizontalDeg: coreFovDeg,
                verticalDeg: 2 * Math.atan(Math.tan(half) / aspect) * 180 / Math.PI
            };
        }
        return {
            verticalDeg: coreFovDeg,
            horizontalDeg: 2 * Math.atan(Math.tan(half) * aspect) * 180 / Math.PI
        };
    }

    function skyOffsetToPixels(offsetRightDeg, offsetUpDeg) {
        const viewW = Math.max(window.innerWidth || 1, 1);
        const viewH = Math.max(window.innerHeight || 1, 1);
        const coreFov = coreFovDegrees();
        if (coreFov == null) {
            return {x: viewW * 0.5, y: viewH * 0.5};
        }
        const fovs = viewFovsDegrees(viewW, viewH, coreFov);
        if (!fovs) return {x: viewW * 0.5, y: viewH * 0.5};
        return {
            x: viewW * 0.5 + viewW * (offsetRightDeg / fovs.horizontalDeg),
            y: viewH * 0.5 - viewH * (offsetUpDeg / fovs.verticalDeg)
        };
    }

    function viewForwardSign(observer) {
        if (!stel || !observer) return -1;
        try {
            const yaw = observer.yaw;
            const pitch = observer.pitch;
            if (yaw == null || pitch == null) return -1;
            const look = stel.s2c(yaw, pitch);
            const view = stel.convertFrame(observer, "OBSERVED", "VIEW", look);
            if (!view) return -1;
            return view[2] >= 0 ? 1 : -1;
        } catch (_) {
            return -1;
        }
    }

    function projectRaDecToScreen(raHours, decDegrees, frame) {
        if (!stel) return null;
        const ra = Number(raHours) * Math.PI / 12;
        const dec = Number(decDegrees) * stel.D2R;
        if (!isFinite(ra) || !isFinite(dec)) return null;
        const observer = (stel.core && stel.core.observer) || stel.observer;
        if (!observer || typeof stel.convertFrame !== "function") return null;
        const source = frame === "ICRF" ? "ICRF" : "JNOW";
        let view;
        try {
            view = stel.convertFrame(observer, source, "VIEW", stel.s2c(ra, dec));
        } catch (_) {
            try {
                view = stel.convertFrame(observer, source, "view", stel.s2c(ra, dec));
            } catch (__) {
                return null;
            }
        }
        if (view && typeof view[0] !== "number" && typeof view.x === "number") {
            view = [view.x, view.y, view.z];
        }
        if (!view) return null;
        const depth = view[2] * viewForwardSign(observer);
        if (!(depth > 1e-6)) return null;
        const offsetRightDeg = Math.atan2(view[0], depth) * 180 / Math.PI;
        const offsetUpDeg = Math.atan2(view[1], depth) * 180 / Math.PI;
        return skyOffsetToPixels(offsetRightDeg, offsetUpDeg);
    }

    function overlayCenter(spec) {
        const viewW = Math.max(window.innerWidth || 1, 1);
        const viewH = Math.max(window.innerHeight || 1, 1);
        if (spec.raHours == null || spec.decDegrees == null ||
            !isFinite(spec.raHours) || !isFinite(spec.decDegrees)) {
            return {x: viewW * 0.5, y: viewH * 0.5};
        }
        return projectRaDecToScreen(spec.raHours, spec.decDegrees, spec.frame);
    }

    function parseAnchor(raHours, decDegrees, frame) {
        if (raHours == null || raHours === "" || decDegrees == null || decDegrees === "") {
            return {raHours: null, decDegrees: null, frame: "JNOW"};
        }
        return {
            raHours: Number(raHours),
            decDegrees: Number(decDegrees),
            frame: frame || "JNOW"
        };
    }

    function applyFovOverlay(element, spec) {
        if (!element) return;
        if (!spec) {
            element.style.display = "none";
            return;
        }
        const viewW = Math.max(window.innerWidth || 1, 1);
        const viewH = Math.max(window.innerHeight || 1, 1);
        const coreFov = coreFovDegrees();
        element.classList.toggle("fov-circle", spec.shape === "circle");
        element.classList.toggle("fov-rect", spec.shape === "rect");
        let boxW;
        let boxH;
        if (spec.shape === "circle") {
            const fovDeg = Number(spec.circleDeg);
            if (!(fovDeg > 0)) {
                element.style.display = "none";
                return;
            }
            let diameter;
            if (coreFov != null && coreFov > 0) {
                diameter = Math.min(viewW, viewH) * (fovDeg / coreFov);
            } else {
                diameter = Math.min(viewW, viewH) * 0.72;
            }
            diameter = Math.max(24, Math.min(diameter, Math.min(viewW, viewH) * 0.98));
            boxW = diameter;
            boxH = diameter;
        } else {
            const widthDeg = Number(spec.widthDeg);
            const heightDeg = Number(spec.heightDeg);
            if (!(widthDeg > 0) || !(heightDeg > 0)) {
                element.style.display = "none";
                return;
            }
            const fovs = coreFov != null ? viewFovsDegrees(viewW, viewH, coreFov) : null;
            if (fovs) {
                boxW = viewW * (widthDeg / fovs.horizontalDeg);
                boxH = viewH * (heightDeg / fovs.verticalDeg);
            } else {
                const aspect = widthDeg / heightDeg;
                boxW = viewW * 0.72;
                boxH = boxW / aspect;
                if (boxH > viewH * 0.72) {
                    boxH = viewH * 0.72;
                    boxW = boxH * aspect;
                }
            }
            boxW = Math.max(24, Math.min(boxW, viewW * 0.98));
            boxH = Math.max(24, Math.min(boxH, viewH * 0.98));
        }
        const pos = overlayCenter(spec);
        if (!pos) {
            element.style.display = "none";
            return;
        }
        element.style.width = boxW + "px";
        element.style.height = boxH + "px";
        element.style.left = pos.x + "px";
        element.style.top = pos.y + "px";
        element.style.display = "block";
        element.textContent = spec.label || "";
    }

    function zoomToCurrentFov() {
        if (!pendingCurrentFov) return;
        if (pendingCurrentFov.shape === "circle" && pendingCurrentFov.circleDeg > 0) {
            applyFovDegrees(pendingCurrentFov.circleDeg * 1.05, 1);
            return;
        }
        if (pendingCurrentFov.shape === "rect") {
            const maxDim = Math.max(pendingCurrentFov.widthDeg, pendingCurrentFov.heightDeg);
            if (maxDim > 0) applyFovDegrees(maxDim * 1.35, 1);
        }
    }

    let lastOverlayFovKey = "";
    function fovSpecKey(spec) {
        if (!spec) return "";
        const shape = spec.shape === "circle"
            ? "c" + spec.circleDeg
            : "r" + spec.widthDeg + "x" + spec.heightDeg;
        const ra = spec.raHours != null ? spec.raHours : "";
        const dec = spec.decDegrees != null ? spec.decDegrees : "";
        return shape + "@" + ra + "," + dec + "," + (spec.frame || "");
    }
    function refreshFovOverlaysFromEngine() {
        const coreFov = coreFovDegrees();
        const observer = stel && ((stel.core && stel.core.observer) || stel.observer);
        const yaw = observer && observer.yaw;
        const pitch = observer && observer.pitch;
        const key = String(coreFov) + "|" +
            (yaw != null ? Number(yaw).toFixed(5) : "") + "|" +
            (pitch != null ? Number(pitch).toFixed(5) : "") + "|" +
            fovSpecKey(pendingCurrentFov) + "|" +
            fovSpecKey(pendingTargetFov);
        const hasSkyAnchor =
            (pendingCurrentFov && pendingCurrentFov.raHours != null) ||
            (pendingTargetFov && pendingTargetFov.raHours != null);
        if (key === lastOverlayFovKey && !(hasSkyAnchor && yaw == null)) return;
        lastOverlayFovKey = key;
        if (coreFov != null) pendingFovDegrees = coreFov;
        applyFovOverlay(fovCurrentElement, pendingCurrentFov);
        applyFovOverlay(fovTargetElement, pendingTargetFov);
    }

    function pauseFollowAfterUserPan() {
        if (!followMount) return;
        followMount = false;
        notifyAndroid("onFollowMountChanged", "false");
    }

    /**
     * Follow-mount is for keeping the FOV box on the telescope. Dragging to
     * find a target must pause it; otherwise every mount poll recenters.
     */
    function installFollowPauseOnPan() {
        if (!canvas) return;
        let startX = 0;
        let startY = 0;
        let moved = false;

        function pointerDown(x, y) {
            userPointerActive = true;
            moved = false;
            startX = x;
            startY = y;
        }

        function pointerMove(x, y) {
            if (!userPointerActive || moved) return;
            const dx = x - startX;
            const dy = y - startY;
            if ((dx * dx) + (dy * dy) < PAN_PAUSE_FOLLOW_PX * PAN_PAUSE_FOLLOW_PX) return;
            moved = true;
            pauseFollowAfterUserPan();
        }

        function pointerUp() {
            userPointerActive = false;
        }

        canvas.addEventListener("touchstart", function (event) {
            if (event.touches.length !== 1) return;
            pointerDown(event.touches[0].clientX, event.touches[0].clientY);
        }, {passive: true});
        canvas.addEventListener("touchmove", function (event) {
            if (event.touches.length !== 1) return;
            pointerMove(event.touches[0].clientX, event.touches[0].clientY);
        }, {passive: true});
        canvas.addEventListener("touchend", pointerUp, {passive: true});
        canvas.addEventListener("touchcancel", pointerUp, {passive: true});
        canvas.addEventListener("pointerdown", function (event) {
            if (event.pointerType === "touch") return;
            pointerDown(event.clientX, event.clientY);
        });
        canvas.addEventListener("pointermove", function (event) {
            if (event.pointerType === "touch") return;
            if ((event.buttons & 1) === 0) return;
            pointerMove(event.clientX, event.clientY);
        });
        canvas.addEventListener("pointerup", function (event) {
            if (event.pointerType === "touch") return;
            pointerUp();
        });
        canvas.addEventListener("pointercancel", function (event) {
            if (event.pointerType === "touch") return;
            pointerUp();
        });
    }

    /**
     * The engine binds touchstart/move/end but never touchcancel, so a pointer
     * the browser cancels (or whose touchend is swallowed) stays "down" inside
     * the gesture state machine forever — the next one-finger drag is then read
     * as the second half of a pinch and keeps zooming. Track pointers here and
     * release any the engine can no longer see.
     */
    function installTouchRecovery(engine) {
        if (!canvas || !engine || typeof engine._core_on_mouse !== "function") return;
        const tracked = new Map();

        function record(touches) {
            const rect = canvas.getBoundingClientRect();
            for (let i = 0; i < touches.length; i++) {
                const touch = touches[i];
                tracked.set(touch.identifier, {
                    x: touch.pageX - rect.left,
                    y: touch.pageY - rect.top
                });
            }
        }

        function changedIds(event) {
            const ids = new Set();
            for (let i = 0; i < event.changedTouches.length; i++) {
                ids.add(event.changedTouches[i].identifier);
            }
            return ids;
        }

        function releaseVanished(event, engineAlreadyReleased) {
            const alive = new Set();
            for (let i = 0; i < event.touches.length; i++) {
                alive.add(event.touches[i].identifier);
            }
            tracked.forEach(function (position, id) {
                if (alive.has(id)) return;
                tracked.delete(id);
                if (engineAlreadyReleased.has(id)) return;
                engine._core_on_mouse(id, 0, position.x, position.y, 1);
            });
        }

        canvas.addEventListener("touchstart", function (event) {
            record(event.changedTouches);
        }, {passive: true});
        canvas.addEventListener("touchmove", function (event) {
            record(event.changedTouches);
        }, {passive: true});
        canvas.addEventListener("touchend", function (event) {
            record(event.changedTouches);
            releaseVanished(event, changedIds(event));
        });
        canvas.addEventListener("touchcancel", function (event) {
            record(event.changedTouches);
            releaseVanished(event, new Set());
        });
    }

    /**
     * [frame] is the frame the caller's RA/Dec are expressed in: "JNOW" for
     * mount readouts, "ICRF" for J2000 catalog coordinates.
     */
    function centerOnRaDec(raHours, decDegrees, duration, frame) {
        if (!stel) return false;
        const ra = Number(raHours) * Math.PI / 12;
        const dec = Number(decDegrees) * stel.D2R;
        const source = frame === "ICRF" ? "ICRF" : "JNOW";
        const direction = stel.s2c(ra, dec);
        // core_lookat() consumes an OBSERVED (alt-az) direction, not ICRF:
        // it feeds the vector straight into observer yaw/pitch.
        const observed = stel.convertFrame(stel.core.observer, source, "OBSERVED", direction);
        stel.lookAt(observed, duration === undefined ? 1 : duration);
        return true;
    }

    /**
     * Try to hand the goto over to the engine. core_search() only matches exact
     * designations ("M 31", "NGC 224", "NAME Jupiter") and only for objects in
     * already-loaded tiles, so callers must be ready for a false result and
     * fall back to centering on catalog coordinates.
     */
    function selectByDesignations(designations) {
        if (!stel || !designations) return false;
        for (let i = 0; i < designations.length; i++) {
            const name = String(designations[i]).trim();
            if (!name) continue;
            let obj;
            try {
                obj = stel.getObj(name);
            } catch (_) {
                continue;
            }
            if (!obj) continue;
            stel.core.selection = obj;
            // pointAndLock resolves the position inside the engine, so the view
            // and the reported target cannot drift apart.
            stel.pointAndLock(obj, 1);
            return true;
        }
        return false;
    }

    function centerOnSelection(duration) {
        if (!stel || !stel.core.selection) return false;
        stel.pointAndLock(stel.core.selection, duration === undefined ? 1 : duration);
        return true;
    }

    function selectedTarget() {
        if (!stel || !stel.core.selection) return null;
        const object = stel.core.selection;
        const icrf = object.getInfo("radec");
        if (!icrf) return null;
        const jnowVector = stel.convertFrame(stel.core.observer, "ICRF", "JNOW", icrf);
        const spherical = stel.c2s(jnowVector);
        const raRadians = stel.anp(spherical[0]);
        const decRadians = stel.anpm(spherical[1]);
        const designations = object.designations ? object.designations() : [];
        const name = (designations[0] || "Selected target").replace(/^NAME /, "");
        return {
            name: name,
            raHours: raRadians * 12 / Math.PI,
            decDegrees: decRadians * 180 / Math.PI,
            frame: "JNOW"
        };
    }

    function publishSelection() {
        let target;
        try {
            target = selectedTarget();
        } catch (error) {
            notifyAndroid("onEngineError", String(error));
            return;
        }
        if (!target) {
            if (lastSelectionKey) {
                lastSelectionKey = "";
                notifyAndroid("onSelectionCleared", "");
            }
            return;
        }
        const key = target.name + "|" + target.raHours.toFixed(8) + "|" +
            target.decDegrees.toFixed(8);
        if (key === lastSelectionKey) return;
        lastSelectionKey = key;
        notifyAndroid("onTargetSelected", JSON.stringify(target));
    }

    window.MercStarMap = {
        setObserver: function (latitudeDeg, longitudeDeg, epochMillis) {
            pendingObserver = {
                latitudeDeg: Number(latitudeDeg),
                longitudeDeg: Number(longitudeDeg),
                epochMillis: Number(epochMillis)
            };
            applyObserver(pendingObserver);
        },
        setMountCoordinates: function (raHours, decDegrees) {
            pendingMountCoordinates = {
                raHours: Number(raHours),
                decDegrees: Number(decDegrees)
            };
            applyMountCoordinates(pendingMountCoordinates);
        },
        clearMountCoordinates: function () {
            pendingMountCoordinates = null;
            applyMountCoordinates(null);
        },
        setFollowMount: function (enabled) {
            // A drag already paused follow; ignore a stale Android re-enable
            // until the pointer is up and the toggle callback has landed.
            if (userPointerActive && enabled) return;
            followMount = Boolean(enabled);
            if (followMount && pendingMountCoordinates && !userPointerActive) {
                centerOnRaDec(
                    pendingMountCoordinates.raHours,
                    pendingMountCoordinates.decDegrees,
                    0
                );
            }
        },
        setAtmosphereVisible: function (visible) {
            pendingAtmosphereVisible = Boolean(visible);
            applyAtmosphereVisibility(pendingAtmosphereVisible);
        },
        setSkyAppearance: function (
            equatorialGrid,
            azimuthalGrid,
            meridian,
            ecliptic,
            constellationLines,
            constellationLabels,
            constellationBounds,
            starHints
        ) {
            pendingSkyAppearance = {
                equatorialGrid: Boolean(equatorialGrid),
                azimuthalGrid: Boolean(azimuthalGrid),
                meridian: Boolean(meridian),
                ecliptic: Boolean(ecliptic),
                constellationLines: Boolean(constellationLines),
                constellationLabels: Boolean(constellationLabels),
                constellationBounds: Boolean(constellationBounds),
                starHints: Boolean(starHints)
            };
            applySkyAppearance();
        },
        setOnlineSurveyEnabled: function (enabled) {
            applyOnlineSurveyEnabled(enabled);
        },
        centerOnRaDec: function (raHours, decDegrees, duration, frame) {
            return centerOnRaDec(raHours, decDegrees, duration, frame);
        },
        centerOnSelection: function (duration) {
            return centerOnSelection(duration);
        },
        selectByDesignations: function (designationsJson) {
            let names;
            try {
                names = JSON.parse(String(designationsJson));
            } catch (_) {
                return false;
            }
            return selectByDesignations(names);
        },
        centerOnMount: function (duration) {
            if (!pendingMountCoordinates) return false;
            return centerOnRaDec(
                pendingMountCoordinates.raHours,
                pendingMountCoordinates.decDegrees,
                duration
            );
        },
        setFovDegrees: function (fovDegrees, duration) {
            applyFovDegrees(Number(fovDegrees), duration);
        },
        setCurrentCircleFovOverlay: function (fovDegrees, alsoZoom, label, raHours, decDegrees, frame) {
            const anchor = parseAnchor(raHours, decDegrees, frame);
            pendingCurrentFov = {
                shape: "circle",
                circleDeg: Number(fovDegrees),
                label: label || "",
                raHours: anchor.raHours,
                decDegrees: anchor.decDegrees,
                frame: anchor.frame
            };
            if (alsoZoom) {
                zoomToCurrentFov();
            } else {
                applyFovOverlay(fovCurrentElement, pendingCurrentFov);
            }
        },
        setCurrentRectFovOverlay: function (widthDeg, heightDeg, alsoZoom, label, raHours, decDegrees, frame) {
            const anchor = parseAnchor(raHours, decDegrees, frame);
            pendingCurrentFov = {
                shape: "rect",
                widthDeg: Number(widthDeg),
                heightDeg: Number(heightDeg),
                label: label || "",
                raHours: anchor.raHours,
                decDegrees: anchor.decDegrees,
                frame: anchor.frame
            };
            if (alsoZoom) {
                zoomToCurrentFov();
            } else {
                applyFovOverlay(fovCurrentElement, pendingCurrentFov);
            }
        },
        clearCurrentFovOverlay: function () {
            pendingCurrentFov = null;
            applyFovOverlay(fovCurrentElement, null);
        },
        setTargetCircleFovOverlay: function (fovDegrees, label, raHours, decDegrees, frame) {
            const anchor = parseAnchor(raHours, decDegrees, frame);
            pendingTargetFov = {
                shape: "circle",
                circleDeg: Number(fovDegrees),
                label: label || "",
                raHours: anchor.raHours,
                decDegrees: anchor.decDegrees,
                frame: anchor.frame
            };
            applyFovOverlay(fovTargetElement, pendingTargetFov);
        },
        setTargetRectFovOverlay: function (widthDeg, heightDeg, label, raHours, decDegrees, frame) {
            const anchor = parseAnchor(raHours, decDegrees, frame);
            pendingTargetFov = {
                shape: "rect",
                widthDeg: Number(widthDeg),
                heightDeg: Number(heightDeg),
                label: label || "",
                raHours: anchor.raHours,
                decDegrees: anchor.decDegrees,
                frame: anchor.frame
            };
            applyFovOverlay(fovTargetElement, pendingTargetFov);
        },
        clearTargetFovOverlay: function () {
            pendingTargetFov = null;
            applyFovOverlay(fovTargetElement, null);
        }
    };

    if (window.__stellariumLoadError || typeof window.StelWebEngine !== "function") {
        const message =
            "缺少 Stellarium Web Engine 构建产物。请先运行 tools/Prepare-StellariumWebEngine.ps1。";
        setStatus(message, true);
        notifyAndroid("onEngineError", message);
        return;
    }

    syncCanvasSize();
    installFollowPauseOnPan();
    window.addEventListener("resize", syncCanvasSize);
    if (window.visualViewport) {
        window.visualViewport.addEventListener("resize", syncCanvasSize);
    }

    window.StelWebEngine({
        wasmFile: absoluteUrl("./stellarium-web-engine.wasm"),
        canvas: canvas,
        translateFn: function (_domain, text) {
            return text;
        },
        onReady: function (engine) {
            stel = engine;
            try {
                engine.core.atmosphere.visible = false;
                installTouchRecovery(engine);
                addDataSources(engine);
                applyObserver(pendingObserver);
                applyMountCoordinates(pendingMountCoordinates);
                applyAtmosphereVisibility(pendingAtmosphereVisible);
                applySkyAppearance();
                applyOnlineSurveyEnabled(pendingOnlineSurveyEnabled);
                applyFovDegrees(pendingFovDegrees, 0);
                applyFovOverlay(fovCurrentElement, pendingCurrentFov);
                applyFovOverlay(fovTargetElement, pendingTargetFov);
                engine.change(function () {
                    window.requestAnimationFrame(function () {
                        publishSelection();
                        // Pinch-zoom / zoomTo animations mutate core.fov;
                        // refresh is cheap (deduped) and keeps overlays locked
                        // to a fixed angular size on the sky.
                        refreshFovOverlaysFromEngine();
                    });
                });
                setStatus("", false);
                notifyAndroid("onEngineReady", "");
            } catch (error) {
                const message = "Stellarium 初始化失败：" + String(error);
                setStatus(message, true);
                notifyAndroid("onEngineError", message);
            }
        }
    });
}());
