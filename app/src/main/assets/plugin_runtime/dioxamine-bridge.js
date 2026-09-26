(function() {
    if (window.__dioxamine_bridge_ready) return;

    window.__dioxamine_callbacks = {};

    window.__dioxamine_resolve = function(id, result) {
        if (window.__dioxamine_callbacks[id]) {
            window.__dioxamine_callbacks[id].resolve(result);
            delete window.__dioxamine_callbacks[id];
        }
    };

    window.__dioxamine_reject = function(id, error) {
        if (window.__dioxamine_callbacks[id]) {
            window.__dioxamine_callbacks[id].reject(new Error(error));
            delete window.__dioxamine_callbacks[id];
        }
    };

    function callNative(method, ...args) {
        return new Promise(function(resolve, reject) {
            var id = 'cb_' + Math.random().toString(36).slice(2) + Date.now();
            window.__dioxamine_callbacks[id] = { resolve: resolve, reject: reject };
            if (window.DioxamineNative && typeof window.DioxamineNative[method] === 'function') {
                window.DioxamineNative[method].apply(window.DioxamineNative, args.concat([id]));
            } else {
                reject(new Error("DioxamineNative interface unavailable or method '" + method + "' not found"));
                delete window.__dioxamine_callbacks[id];
            }
        });
    }

    window.__dioxamine_shell_listeners = {};

    window.__dioxamine_shell_data = function(sessionId, base64Chunk) {
        var listeners = window.__dioxamine_shell_listeners[sessionId];
        if (listeners && listeners.onData) listeners.onData(base64Chunk);
    };

    window.__dioxamine_shell_closed = function(sessionId, errorMessage) {
        var listeners = window.__dioxamine_shell_listeners[sessionId];
        if (listeners && listeners.onClose) listeners.onClose(errorMessage || null);
        delete window.__dioxamine_shell_listeners[sessionId];
    };

    window.__dioxamine_theme_listener = null;
    window.__dioxamine_language_listener = null;
    window.__dioxamine_back_listener = null;
    window.__dioxamine_volume_listener = null;
    window.__dioxamine_button_listener = null;

    window.__dioxamine_on_back_button = function() {
        var event = {
            button: 'back',
            direction: 'back',
            action: 'down',
            key: 'GoBack',
            keyCode: 4,
            repeatCount: 0
        };
        if (typeof window.__dioxamine_back_listener === 'function') {
            try {
                window.__dioxamine_back_listener(event);
            } catch (e) {
                console.error('Error in onBackButton listener:', e);
            }
        }
        if (typeof window.__dioxamine_button_listener === 'function') {
            try {
                window.__dioxamine_button_listener(event);
            } catch (e) {
                console.error('Error in onButton listener:', e);
            }
        }
        try {
            window.dispatchEvent(new CustomEvent('dioxamine-back-button', { detail: event }));
            window.dispatchEvent(new CustomEvent('dioxamine-button', { detail: event }));
        } catch (e) {}
    };

    window.__dioxamine_on_volume_button = function(button, action, keyCode, repeatCount) {
        var direction = button === 'volume_up' ? 'up' : (button === 'volume_down' ? 'down' : 'mute');
        var key = button === 'volume_up' ? 'VolumeUp' : (button === 'volume_down' ? 'VolumeDown' : 'VolumeMute');
        var event = {
            button: button,
            direction: direction,
            action: action,
            key: key,
            keyCode: keyCode,
            repeatCount: repeatCount || 0
        };
        if (typeof window.__dioxamine_volume_listener === 'function') {
            try {
                window.__dioxamine_volume_listener(event);
            } catch (e) {
                console.error('Error in onVolumeButton listener:', e);
            }
        }
        if (typeof window.__dioxamine_button_listener === 'function') {
            try {
                window.__dioxamine_button_listener(event);
            } catch (e) {
                console.error('Error in onButton listener:', e);
            }
        }
        try {
            window.dispatchEvent(new CustomEvent('dioxamine-volume-button', { detail: event }));
            window.dispatchEvent(new CustomEvent('dioxamine-button', { detail: event }));
        } catch (e) {}
    };

    var adb = {
        getActiveDevice: function() { return callNative('getActiveDevice'); },
        shellExec: function(cmd) { return callNative('shellExec', cmd); },
        openInteractiveShell: function() {
            return callNative('openInteractiveShell').then(function(result) {
                var sessionId = result.sessionId;
                window.__dioxamine_shell_listeners[sessionId] = {};
                return {
                    sessionId: sessionId,
                    onData: function(fn) { window.__dioxamine_shell_listeners[sessionId].onData = fn; },
                    onClose: function(fn) { window.__dioxamine_shell_listeners[sessionId].onClose = fn; },
                    write: function(base64Data) { return callNative('writeInteractiveShell', sessionId, base64Data); },
                    resize: function(cols, rows) { return callNative('resizeInteractiveShell', sessionId, Number(cols), Number(rows)); },
                    close: function() { return callNative('closeInteractiveShell', sessionId); }
                };
            });
        },
        pull: function(remotePath, safRequestId) { return callNative('pull', remotePath, safRequestId); },
        push: function(localSafRequestId, remotePath) { return callNative('push', localSafRequestId, remotePath); },
        forwardAdd: function(local, remote) { return callNative('forwardAdd', local, remote); },
        reverseAdd: function(remote, local) { return callNative('reverseAdd', remote, local); },
        forwardRemove: function(local) { return callNative('forwardRemove', local); },
        reverseRemove: function(remote) { return callNative('reverseRemove', remote); }
    };

    window.__dioxamine_fastboot_progress = {};

    window.__dioxamine_on_fastboot_progress = function(opId, current, total, percentage, status) {
        var fn = window.__dioxamine_fastboot_progress[opId];
        if (fn) fn({ current: current, total: total, percentage: percentage, status: status || '' });
    };

    window.__dioxamine_on_fastboot_info = function(opId, info) {
        var fn = window.__dioxamine_fastboot_progress[opId];
        if (fn) fn({ current: 0, total: 0, percentage: 0, status: info || '' });
    };

    var fastboot = {
        getActiveDevice: function() {
            return callNative('fastbootGetActiveDevice');
        },
        getVariable: function(name) {
            return callNative('fastbootGetVariable', name).then(function(res) {
                return res.value;
            });
        },
        getVar: function(name) {
            return this.getVariable(name);
        },
        getAllVariables: function() {
            return callNative('fastbootGetAllVariables');
        },
        getAllVars: function() {
            return this.getAllVariables();
        },
        rawCommand: function(command) {
            return callNative('fastbootRawCommand', command);
        },
        erase: function(partition) {
            return this.rawCommand('erase:' + partition);
        },
        setActiveSlot: function(slot) {
            return this.rawCommand('set_active:' + slot);
        },
        continueBoot: function() {
            return this.rawCommand('continue');
        },
        shutdown: function() {
            return this.rawCommand('shutdown');
        },
        setLockMode: function(mode) {
            return this.rawCommand('flashing ' + mode);
        },
        reboot: function(target) {
            return callNative('fastbootReboot', target || 'system');
        },
        flash: function(partition, safRequestId, onProgress) {
            var opId = 'fb_' + Math.random().toString(36).slice(2) + Date.now();
            if (typeof onProgress === 'function') {
                window.__dioxamine_fastboot_progress[opId] = onProgress;
            }
            return callNative('fastbootFlash', partition, safRequestId, opId).finally(function() {
                delete window.__dioxamine_fastboot_progress[opId];
            });
        },
        boot: function(safRequestId, onProgress) {
            var opId = 'fb_' + Math.random().toString(36).slice(2) + Date.now();
            if (typeof onProgress === 'function') {
                window.__dioxamine_fastboot_progress[opId] = onProgress;
            }
            return callNative('fastbootBoot', safRequestId, opId).finally(function() {
                delete window.__dioxamine_fastboot_progress[opId];
            });
        },
        stage: function(safRequestId, onProgress) {
            var opId = 'fb_' + Math.random().toString(36).slice(2) + Date.now();
            if (typeof onProgress === 'function') {
                window.__dioxamine_fastboot_progress[opId] = onProgress;
            }
            return callNative('fastbootStage', safRequestId, opId).finally(function() {
                delete window.__dioxamine_fastboot_progress[opId];
            });
        },
        stageData: function(dataBase64, onProgress) {
            var opId = 'fb_' + Math.random().toString(36).slice(2) + Date.now();
            if (typeof onProgress === 'function') {
                window.__dioxamine_fastboot_progress[opId] = onProgress;
            }
            return callNative('fastbootStageData', dataBase64, opId).finally(function() {
                delete window.__dioxamine_fastboot_progress[opId];
            });
        }
    };

    window.dioxamine = {
        adb: adb,
        fastboot: fastboot,
        requestFilePicker: function(mode) { return callNative('requestFilePicker', mode); },
        utf8ToBase64: function(str) { return btoa(unescape(encodeURIComponent(str))); },
        base64ToUtf8: function(b64) { return decodeURIComponent(escape(atob(b64))); },
        onThemeChange: function(fn) { window.__dioxamine_theme_listener = fn; },
        getTheme: function() {
            return {
                isDark: document.documentElement.getAttribute('data-dioxamine-theme') === 'dark'
            };
        },
        onLanguageChange: function(fn) { window.__dioxamine_language_listener = fn; },
        getLanguage: function() {
            if (window.DioxamineNative && typeof window.DioxamineNative.getLanguage === 'function') {
                try {
                    return JSON.parse(window.DioxamineNative.getLanguage());
                } catch (e) {}
            }
            if (window.__dioxamine_locale_info) {
                return window.__dioxamine_locale_info;
            }
            var tag = (document.documentElement && (document.documentElement.getAttribute('data-dioxamine-lang') || document.documentElement.getAttribute('lang'))) || 'en';
            return {
                language: tag.split('-')[0].toLowerCase(),
                languageTag: tag,
                isRtl: document.documentElement ? document.documentElement.getAttribute('dir') === 'rtl' : false,
                displayName: tag
            };
        },
        getLocale: function() {
            return this.getLanguage();
        },
        getLanguageAsync: function() {
            return callNative('getLanguageAsync');
        },
        getLocaleAsync: function() {
            return this.getLanguageAsync();
        },
        getAppVersion: function() {
            if (window.DioxamineNative && typeof window.DioxamineNative.getAppVersion === 'function') {
                try {
                    return JSON.parse(window.DioxamineNative.getAppVersion());
                } catch (e) {}
            }
            return {
                versionName: "unknown",
                versionCode: 0,
                version: "unknown",
                appName: "Dioxamine",
                applicationId: "io.github.rhythmcache.dioxamine"
            };
        },
        getVersion: function() {
            return this.getAppVersion();
        },
        getAppVersionAsync: function() {
            return callNative('getAppVersionAsync');
        },
        getVersionAsync: function() {
            return this.getAppVersionAsync();
        },
        showToast: function(message, duration) {
            if (window.DioxamineNative && typeof window.DioxamineNative.showToast === 'function') {
                window.DioxamineNative.showToast(message || '', duration || 'short');
            }
        },
        showDialog: function(options) {
            options = options || {};
            return callNative('showDialog', JSON.stringify({
                title: options.title || '',
                message: options.message || '',
                buttons: options.buttons || ['OK']
            }));
        },
        setFullScreen: function(enable) {
            if (window.DioxamineNative && typeof window.DioxamineNative.setFullScreen === 'function') {
                window.DioxamineNative.setFullScreen(Boolean(enable));
            }
        },
        fullScreen: function(enable) {
            this.setFullScreen(enable);
        },
        exitPlugin: function() {
            if (window.DioxamineNative && typeof window.DioxamineNative.exitPlugin === 'function') {
                window.DioxamineNative.exitPlugin();
            }
        },
        closePlugin: function() {
            this.exitPlugin();
        },
        setInterceptBackButton: function(enable) {
            if (window.DioxamineNative && typeof window.DioxamineNative.setInterceptBackButton === 'function') {
                window.DioxamineNative.setInterceptBackButton(Boolean(enable));
            }
        },
        isInterceptingBackButton: function() {
            if (window.DioxamineNative && typeof window.DioxamineNative.isInterceptingBackButton === 'function') {
                return window.DioxamineNative.isInterceptingBackButton();
            }
            return false;
        },
        onBackButton: function(fn) {
            window.__dioxamine_back_listener = fn;
            if (typeof fn === 'function') {
                this.setInterceptBackButton(true);
            } else if (fn === null || fn === false) {
                this.setInterceptBackButton(false);
            }
        },
        defaultBack: function() {
            if (window.DioxamineNative && typeof window.DioxamineNative.defaultBack === 'function') {
                window.DioxamineNative.defaultBack();
            }
        },
        setInterceptVolumeButtons: function(enable) {
            if (window.DioxamineNative && typeof window.DioxamineNative.setInterceptVolumeButtons === 'function') {
                window.DioxamineNative.setInterceptVolumeButtons(Boolean(enable));
            }
        },
        isInterceptingVolumeButtons: function() {
            if (window.DioxamineNative && typeof window.DioxamineNative.isInterceptingVolumeButtons === 'function') {
                return window.DioxamineNative.isInterceptingVolumeButtons();
            }
            return false;
        },
        onVolumeButton: function(fn) {
            window.__dioxamine_volume_listener = fn;
            if (typeof fn === 'function') {
                this.setInterceptVolumeButtons(true);
            } else if (fn === null || fn === false) {
                this.setInterceptVolumeButtons(false);
            }
        },
        onButton: function(fn) {
            window.__dioxamine_button_listener = fn;
            if (typeof fn === 'function') {
                this.setInterceptBackButton(true);
                this.setInterceptVolumeButtons(true);
            } else if (fn === null || fn === false) {
                this.setInterceptBackButton(false);
                this.setInterceptVolumeButtons(false);
            }
        },
        setInterceptButtons: function(options) {
            options = options || {};
            if (options.back !== undefined) this.setInterceptBackButton(Boolean(options.back));
            if (options.volume !== undefined) this.setInterceptVolumeButtons(Boolean(options.volume));
        },
        getInterceptStatusAsync: function() {
            var self = this;
            return Promise.resolve().then(function() {
                return {
                    back: self.isInterceptingBackButton(),
                    volume: self.isInterceptingVolumeButtons()
                };
            });
        },
        interceptBack: function(enable) { this.setInterceptBackButton(enable); },
        interceptVolume: function(enable) { this.setInterceptVolumeButtons(enable); },
        isInterceptingBack: function() { return this.isInterceptingBackButton(); },
        isInterceptingVolume: function() { return this.isInterceptingVolumeButtons(); },
        buttons: {
            setInterceptBackButton: function(enable) { window.dioxamine.setInterceptBackButton(enable); },
            setInterceptVolumeButtons: function(enable) { window.dioxamine.setInterceptVolumeButtons(enable); },
            setInterceptBack: function(enable) { window.dioxamine.setInterceptBackButton(enable); },
            setInterceptVolume: function(enable) { window.dioxamine.setInterceptVolumeButtons(enable); },
            interceptBack: function(enable) { window.dioxamine.setInterceptBackButton(enable); },
            interceptVolume: function(enable) { window.dioxamine.setInterceptVolumeButtons(enable); },
            setIntercept: function(options) { window.dioxamine.setInterceptButtons(options); },
            isInterceptingBackButton: function() { return window.dioxamine.isInterceptingBackButton(); },
            isInterceptingVolumeButtons: function() { return window.dioxamine.isInterceptingVolumeButtons(); },
            isInterceptingBack: function() { return window.dioxamine.isInterceptingBackButton(); },
            isInterceptingVolume: function() { return window.dioxamine.isInterceptingVolumeButtons(); },
            getStatusAsync: function() { return window.dioxamine.getInterceptStatusAsync(); },
            onBackButton: function(fn) { window.dioxamine.onBackButton(fn); },
            onVolumeButton: function(fn) { window.dioxamine.onVolumeButton(fn); },
            onButton: function(fn) { window.dioxamine.onButton(fn); },
            defaultBack: function() { window.dioxamine.defaultBack(); }
        },
        openBrowser: function(url) {
            if (!url || typeof url !== 'string') {
                return Promise.reject(new Error("URL must be a non-empty string"));
            }
            var trimmed = url.trim();
            if (trimmed.length > 2048) {
                return Promise.reject(new Error("URL exceeds maximum length of 2048 characters"));
            }
            var lower = trimmed.toLowerCase();
            if (!lower.startsWith('http://') && !lower.startsWith('https://')) {
                return Promise.reject(new Error("Unsupported URL scheme: Only http:// and https:// URLs are allowed"));
            }
            return callNative('openBrowser', trimmed);
        },
        openUrl: function(url) {
            return this.openBrowser(url);
        },
        log: {
            v: function(tag, msg) { if (window.DioxamineNative && window.DioxamineNative.logMessage) window.DioxamineNative.logMessage('V', tag, String(msg)); },
            d: function(tag, msg) { if (window.DioxamineNative && window.DioxamineNative.logMessage) window.DioxamineNative.logMessage('D', tag, String(msg)); },
            i: function(tag, msg) { if (window.DioxamineNative && window.DioxamineNative.logMessage) window.DioxamineNative.logMessage('I', tag, String(msg)); },
            w: function(tag, msg) { if (window.DioxamineNative && window.DioxamineNative.logMessage) window.DioxamineNative.logMessage('W', tag, String(msg)); },
            e: function(tag, msg) { if (window.DioxamineNative && window.DioxamineNative.logMessage) window.DioxamineNative.logMessage('E', tag, String(msg)); },
            log: function(msg) { this.d('Plugin', msg); }
        },
        http: {
            fetch: function(url, options) {
                if (!url || typeof url !== 'string') {
                    return Promise.reject(new Error("URL must be a non-empty string"));
                }
                var trimmed = url.trim().toLowerCase();
                if (!trimmed.startsWith('http://') && !trimmed.startsWith('https://')) {
                    return Promise.reject(new Error("Unsupported URL scheme. Only http:// and https:// URLs are allowed."));
                }
                options = options || {};
                var payload = {
                    url: url,
                    method: options.method || 'GET',
                    headers: options.headers || {},
                    body: options.body != null ? String(options.body) : null,
                    timeoutMs: typeof options.timeoutMs === 'number' ? options.timeoutMs : 15000
                };
                return callNative('httpRequest', JSON.stringify(payload)).then(function(res) {
                    return {
                        status: res.status,
                        statusText: res.statusText || (res.status >= 200 && res.status < 300 ? 'OK' : 'Error'),
                        headers: res.headers || {},
                        data: res.data || '',
                        text: function() { return Promise.resolve(res.data || ''); },
                        json: function() {
                            return new Promise(function(resolve, reject) {
                                try {
                                    resolve(JSON.parse(res.data || ''));
                                } catch (e) {
                                    reject(e);
                                }
                            });
                        }
                    };
                });
            }
        }
    };

    var _formatConsoleArgs = function(args) {
        return args.map(function(a) {
            if (typeof a === 'object') {
                try { return JSON.stringify(a); } catch(e) { return String(a); }
            }
            return String(a);
        }).join(' ');
    };

    var _consoleLog = console.log;
    var _consoleWarn = console.warn;
    var _consoleError = console.error;
    var _consoleInfo = console.info;
    var _consoleDebug = console.debug;

    console.log = function(...args) {
        window.dioxamine.log.i('Console', _formatConsoleArgs(args));
        if (_consoleLog) _consoleLog.apply(console, args);
    };
    console.warn = function(...args) {
        window.dioxamine.log.w('Console', _formatConsoleArgs(args));
        if (_consoleWarn) _consoleWarn.apply(console, args);
    };
    console.error = function(...args) {
        window.dioxamine.log.e('Console', _formatConsoleArgs(args));
        if (_consoleError) _consoleError.apply(console, args);
    };
    console.info = function(...args) {
        window.dioxamine.log.i('Console', _formatConsoleArgs(args));
        if (_consoleInfo) _consoleInfo.apply(console, args);
    };
    console.debug = function(...args) {
        window.dioxamine.log.d('Console', _formatConsoleArgs(args));
        if (_consoleDebug) _consoleDebug.apply(console, args);
    };

    try {
        Object.defineProperty(window.dioxamine, 'appVersion', {
            get: function() { return window.dioxamine.getAppVersion(); },
            enumerable: true,
            configurable: true
        });
    } catch (e) {}

    window.__dioxamine_bridge_ready = true;
    window.dispatchEvent(new Event('dioxamine-bridge-ready'));
})();
