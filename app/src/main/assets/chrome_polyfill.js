/**
 * Chrome Extension API Polyfill for Android WebView
 * Bridges chrome.* calls to AndroidBridge Java interface
 */
(function() {
    if (window.__chromeBridgeInstalled) return;
    window.__chromeBridgeInstalled = true;

    window.__chromeCallbacks = {};
    window.__chromeCallbackId = 0;

    // ── chrome.runtime ─────────────────────────────────
    window.chrome = window.chrome || {};
    window.chrome.runtime = {
        sendMessage: function(message, callback) {
            const id = 'cb_' + (++window.__chromeCallbackId);
            window.__chromeCallbacks[id] = function(result) {
                delete window.__chromeCallbacks[id];
                if (callback) callback(result);
            };
            try {
                AndroidBridge.sendMessageAsync(JSON.stringify(message), id);
            } catch(e) {
                console.error('AndroidBridge.sendMessageAsync error:', e);
                if (callback) callback({ success: false, error: e.message });
            }
        },
        getURL: function(path) {
            try {
                return AndroidBridge.getResourceUrl(path);
            } catch(e) {
                return 'file:///android_asset/' + path;
            }
        }
    };

    // ── chrome.storage.local ────────────────────────────
    window.chrome.storage = {
        local: {
            set: function(data, callback) {
                try {
                    AndroidBridge.storageSet(JSON.stringify(data));
                } catch(e) {}
                if (callback) setTimeout(callback, 0);
            },
            get: function(keys, callback) {
                try {
                    const keysArr = Array.isArray(keys) ? keys : Object.keys(keys);
                    const result = AndroidBridge.storageGet(JSON.stringify(keysArr));
                    if (callback) callback(JSON.parse(result));
                } catch(e) {
                    if (callback) callback({});
                }
            },
            remove: function(keys, callback) {
                try {
                    const keysArr = Array.isArray(keys) ? [keys] : keys;
                    AndroidBridge.storageRemove(JSON.stringify(keysArr));
                } catch(e) {}
                if (callback) setTimeout(callback, 0);
            }
        }
    };

    // ── Callback receiver (called by Android) ──────────
    window.__bridgeCallback = function(id, resultJson) {
        const cb = window.__chromeCallbacks[id];
        if (cb) {
            try {
                cb(JSON.parse(resultJson));
            } catch(e) {
                cb({});
            }
        }
    };

    console.log('[CrystalVisa] Chrome Bridge installed');
})();
