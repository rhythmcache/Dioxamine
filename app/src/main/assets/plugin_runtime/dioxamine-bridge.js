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

    window.dioxamine = {
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
                    close: function() { return callNative('closeInteractiveShell', sessionId); }
                };
            });
        },
        requestFilePicker: function(mode) { return callNative('requestFilePicker', mode); },
        pull: function(remotePath, safRequestId) { return callNative('pull', remotePath, safRequestId); },
        push: function(localSafRequestId, remotePath) { return callNative('push', localSafRequestId, remotePath); },
        forwardAdd: function(local, remote) { return callNative('forwardAdd', local, remote); },
        reverseAdd: function(remote, local) { return callNative('reverseAdd', remote, local); },
        forwardRemove: function(local) { return callNative('forwardRemove', local); },
        reverseRemove: function(remote) { return callNative('reverseRemove', remote); },
        utf8ToBase64: function(str) { return btoa(unescape(encodeURIComponent(str))); },
        base64ToUtf8: function(b64) { return decodeURIComponent(escape(atob(b64))); },
        onThemeChange: function(fn) { window.__dioxamine_theme_listener = fn; },
        getTheme: function() {
            return {
                isDark: document.documentElement.getAttribute('data-dioxamine-theme') === 'dark'
            };
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
        log: {
            v: function(tag, msg) { if (window.DioxamineNative && window.DioxamineNative.logMessage) window.DioxamineNative.logMessage('V', tag, String(msg)); },
            d: function(tag, msg) { if (window.DioxamineNative && window.DioxamineNative.logMessage) window.DioxamineNative.logMessage('D', tag, String(msg)); },
            i: function(tag, msg) { if (window.DioxamineNative && window.DioxamineNative.logMessage) window.DioxamineNative.logMessage('I', tag, String(msg)); },
            w: function(tag, msg) { if (window.DioxamineNative && window.DioxamineNative.logMessage) window.DioxamineNative.logMessage('W', tag, String(msg)); },
            e: function(tag, msg) { if (window.DioxamineNative && window.DioxamineNative.logMessage) window.DioxamineNative.logMessage('E', tag, String(msg)); },
            log: function(msg) { this.d('Plugin', msg); }
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

    window.__dioxamine_bridge_ready = true;
    window.dispatchEvent(new Event('dioxamine-bridge-ready'));
})();
