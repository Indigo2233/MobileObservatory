(function () {
    "use strict";

    const statusElement = document.getElementById("engine-status");
    const mountPositionElement = document.getElementById("mount-position");
    const fovFrameElement = document.getElementById("fov-frame");
    const eyepieceFovElement = document.getElementById("eyepiece-fov");
    const mountReticleElement = document.getElementById("mount-reticle");
    const canvas = document.getElementById("stel-canvas");
    let stel = null;
    let lastSelectionKey = "";
    let pendingObserver = null;
    let pendingMountCoordinates = null;
    let pendingAtmosphereVisible = false;
    let pendingFovDegrees = null;
    let pendingSensorFov = null;
    let pendingEyepieceFovDeg = null;
    let followMount = false;

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
        applySensorFovOverlay(pendingSensorFov);
        applyEyepieceFovOverlay(pendingEyepieceFovDeg);
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

    function applyMountReticle(visible) {
        if (!mountReticleElement) return;
        mountReticleElement.style.display = visible ? "block" : "none";
    }

    function applyMountCoordinates(coordinates) {
        if (!coordinates) {
            mountPositionElement.style.display = "none";
            applyMountReticle(false);
            return;
        }
        mountPositionElement.textContent =
            "赤道仪  RA " + coordinates.raHours.toFixed(5) +
            " h  Dec " + coordinates.decDegrees.toFixed(4) + "°";
        mountPositionElement.style.display = "block";
        // Reticle marks current pointing when the view is locked to the mount.
        applyMountReticle(followMount);
        if (followMount && stel) {
            centerOnRaDec(coordinates.raHours, coordinates.decDegrees, 0);
        }
    }

    function applyAtmosphereVisibility(visible) {
        if (!stel) return;
        stel.core.atmosphere.visible = Boolean(visible);
    }

    function applyFovDegrees(fovDegrees, duration) {
        if (!stel || fovDegrees == null || !(fovDegrees > 0)) return;
        const radians = Math.max(0.01, Number(fovDegrees)) * stel.D2R;
        stel.zoomTo(radians, duration === undefined ? 1 : duration);
        pendingFovDegrees = Number(fovDegrees);
        applySensorFovOverlay(pendingSensorFov);
        applyEyepieceFovOverlay(pendingEyepieceFovDeg);
    }

    function viewFovDegrees() {
        if (pendingFovDegrees != null && pendingFovDegrees > 0) {
            return pendingFovDegrees;
        }
        return null;
    }

    function applySensorFovOverlay(sensorFov) {
        if (!fovFrameElement) return;
        if (!sensorFov || !(sensorFov.widthDeg > 0) || !(sensorFov.heightDeg > 0)) {
            fovFrameElement.style.display = "none";
            return;
        }
        const viewW = Math.max(window.innerWidth || 1, 1);
        const viewH = Math.max(window.innerHeight || 1, 1);
        const viewFov = viewFovDegrees();
        let boxW;
        let boxH;
        if (viewFov != null && viewFov > 0) {
            // Stellarium zoomTo FOV is treated as the vertical field of view.
            boxH = viewH * (sensorFov.heightDeg / viewFov);
            boxW = boxH * (sensorFov.widthDeg / sensorFov.heightDeg);
        } else {
            const aspect = sensorFov.widthDeg / sensorFov.heightDeg;
            boxW = viewW * 0.72;
            boxH = boxW / aspect;
            if (boxH > viewH * 0.72) {
                boxH = viewH * 0.72;
                boxW = boxH * aspect;
            }
        }
        boxW = Math.max(24, Math.min(boxW, viewW * 0.98));
        boxH = Math.max(24, Math.min(boxH, viewH * 0.98));
        fovFrameElement.style.width = boxW + "px";
        fovFrameElement.style.height = boxH + "px";
        fovFrameElement.style.borderRadius = "2px";
        fovFrameElement.style.display = "block";
        fovFrameElement.textContent =
            sensorFov.widthDeg.toFixed(2) + "° × " + sensorFov.heightDeg.toFixed(2) + "°";
    }

    function applyEyepieceFovOverlay(fovDeg) {
        if (!eyepieceFovElement) return;
        if (fovDeg == null || !(fovDeg > 0)) {
            eyepieceFovElement.style.display = "none";
            return;
        }
        const viewW = Math.max(window.innerWidth || 1, 1);
        const viewH = Math.max(window.innerHeight || 1, 1);
        const viewFov = viewFovDegrees();
        let diameter;
        if (viewFov != null && viewFov > 0) {
            diameter = Math.min(viewW, viewH) * (Number(fovDeg) / viewFov);
        } else {
            diameter = Math.min(viewW, viewH) * 0.72;
        }
        diameter = Math.max(24, Math.min(diameter, Math.min(viewW, viewH) * 0.98));
        eyepieceFovElement.style.width = diameter + "px";
        eyepieceFovElement.style.height = diameter + "px";
        eyepieceFovElement.style.display = "block";
        eyepieceFovElement.textContent = Number(fovDeg).toFixed(2) + "°";
    }

    function centerOnRaDec(raHours, decDegrees, duration) {
        if (!stel) return false;
        const ra = Number(raHours) * Math.PI / 12;
        const dec = Number(decDegrees) * stel.D2R;
        const jnow = stel.s2c(ra, dec);
        const icrf = stel.convertFrame(stel.core.observer, "JNOW", "ICRF", jnow);
        stel.lookAt(icrf, duration === undefined ? 1 : duration);
        return true;
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
            followMount = Boolean(enabled);
            applyMountReticle(followMount && pendingMountCoordinates != null);
            if (followMount && pendingMountCoordinates) {
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
        centerOnRaDec: function (raHours, decDegrees, duration) {
            return centerOnRaDec(raHours, decDegrees, duration);
        },
        centerOnSelection: function (duration) {
            return centerOnSelection(duration);
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
        setEyepieceFovOverlay: function (fovDegrees, alsoZoom) {
            pendingEyepieceFovDeg = Number(fovDegrees);
            if (alsoZoom) {
                applyFovDegrees(pendingEyepieceFovDeg * 1.05, 1);
            } else {
                applyEyepieceFovOverlay(pendingEyepieceFovDeg);
            }
        },
        clearEyepieceFovOverlay: function () {
            pendingEyepieceFovDeg = null;
            applyEyepieceFovOverlay(null);
        },
        setSensorFovOverlay: function (widthDeg, heightDeg, alsoZoom) {
            pendingSensorFov = {
                widthDeg: Number(widthDeg),
                heightDeg: Number(heightDeg)
            };
            if (alsoZoom !== false) {
                const maxDim = Math.max(pendingSensorFov.widthDeg, pendingSensorFov.heightDeg);
                applyFovDegrees(maxDim * 1.35, 1);
            } else {
                applySensorFovOverlay(pendingSensorFov);
            }
        },
        clearSensorFovOverlay: function () {
            pendingSensorFov = null;
            applySensorFovOverlay(null);
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
                addDataSources(engine);
                applyObserver(pendingObserver);
                applyMountCoordinates(pendingMountCoordinates);
                applyAtmosphereVisibility(pendingAtmosphereVisible);
                applyFovDegrees(pendingFovDegrees, 0);
                applySensorFovOverlay(pendingSensorFov);
                applyEyepieceFovOverlay(pendingEyepieceFovDeg);
                applyMountReticle(followMount && pendingMountCoordinates != null);
                engine.change(function () {
                    window.requestAnimationFrame(publishSelection);
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
