/**
 * OzForensics Liveness Injection Script
 * Equivalent of background.js INJECT_OZ action
 * Parameters are substituted by Android before injection
 */
(function(uid, tid, ip, jsonFilename, githubSha, githubData, doneUrl, userAgent) {
    console.log('[CrystalVisa] inject_oz.js starting', { uid: uid ? uid.substr(0,8)+'...' : 'N/A' });

    // ── Listeners (ISOLATED world equivalent) ─────────
    window.addEventListener('oz_complete', function(event) {
        var detail = event.detail;
        var livenessId = detail.livenessId;
        var jf = detail.jsonFilename;
        var sha = detail.githubSha;
        var gd = detail.githubData;
        var u = detail.uid;
        var t = detail.tid;
        var i = detail.ip;
        var done = detail.doneUrl;

        console.log('[CrystalVisa] oz_complete:', livenessId);

        if (jf && sha && gd && livenessId) {
            chrome.runtime.sendMessage({
                action: 'UPDATE_GITHUB_JSON',
                filename: jf,
                sha: sha,
                livenessId: livenessId,
                originalData: gd
            });
        }

        var msg1 =
            '✅ *Test Liveness Terminé*\n\n' +
            '🆔 *Liveness ID:* `' + livenessId + '`\n' +
            '👤 *User ID:* `' + u + '`\n' +
            '📋 *Transaction ID:* `' + t + '`\n' +
            '🌐 *IP:* ' + (i || 'N/A') + '\n' +
            '📁 *Fichier:* ' + (jf || 'N/A') + '\n' +
            '💾 *GitHub:* ✅ Mis à jour\n' +
            '📅 *Date:* ' + new Date().toLocaleString();

        chrome.runtime.sendMessage({ action: 'SEND_TELEGRAM', text: msg1 });

        setTimeout(function() {
            var cmd =
                'document.getElementById(\'LivenessId\').value = "' + livenessId + '";\n' +
                'document.getElementById(\'formLiveness\').submit();';
            var msg2 =
                '✅ *Test Liveness Terminé*\n' +
                '*Commande :* \n```javascript\n' + cmd + '\n```';
            chrome.runtime.sendMessage({ action: 'SEND_TELEGRAM', text: msg2 });
        }, 500);

        document.body.innerHTML =
            '<div style="position:fixed;inset:0;width:100vw;height:100vh;display:flex;justify-content:center;align-items:center;' +
            'background:linear-gradient(135deg,#07111f,#0f2a44,#d4af37);font-family:Arial,sans-serif;text-align:center;' +
            'z-index:999999;padding:20px;box-sizing:border-box">' +
            '<div style="max-width:520px;width:100%;background:rgba(0,0,0,0.42);backdrop-filter:blur(10px);' +
            'border-radius:28px;padding:25px;box-shadow:0 0 40px rgba(212,175,55,0.35);' +
            'border:1px solid rgba(255,255,255,0.15)">' +
            '<img src="' + done + '" alt="Done" style="width:100%;max-height:75vh;object-fit:contain;display:block;margin:auto">' +
            '</div></div>';
    });

    window.addEventListener('oz_error', function(event) {
        console.error('[CrystalVisa] oz_error:', event.detail);
        chrome.runtime.sendMessage({
            action: 'SEND_TELEGRAM',
            text: '❌ Erreur: ' + JSON.stringify(event.detail).substring(0, 200)
        });
        document.body.innerHTML =
            '<div style="display:flex;justify-content:center;align-items:center;min-height:100vh;' +
            'background:linear-gradient(135deg,#667eea,#764ba2);font-family:Arial;color:white;text-align:center">' +
            '<div>' +
            '<div style="font-size:60px">❌</div>' +
            '<h2>Erreur</h2>' +
            '<p>' + JSON.stringify(event.detail) + '</p>' +
            '<button onclick="location.reload()" style="margin-top:20px;padding:10px 30px;background:white;' +
            'color:#667eea;border:none;border-radius:5px;cursor:pointer;font-size:16px">🔄 Réessayer</button>' +
            '</div></div>';
    });

    // ── Inject CSS ─────────────────────────────────────
    var css = document.createElement('link');
    css.rel = 'stylesheet';
    css.href = 'https://web-sdk.prod.cdn.spain.ozforensics.com/blsinternational/plugin/liveness-81ab90655a.css?ver=1.8.1-10';
    document.head.appendChild(css);

    // ── Load scripts & launch OzLiveness ──────────────
    function loadScript(url) {
        return new Promise(function(resolve, reject) {
            var s = document.createElement('script');
            s.src = url;
            s.onload = resolve;
            s.onerror = function(e) { reject(new Error('Load failed: ' + url)); };
            document.head.appendChild(s);
        });
    }

    (async function() {
        try {
            await loadScript('https://code.jquery.com/jquery-3.6.0.min.js');
            await new Promise(function(r) { setTimeout(r, 500); });
            await loadScript('https://web-sdk.prod.cdn.spain.ozforensics.com/blsinternational/plugin/ozliveness_main.js?ver=1.8.1-10');
            await new Promise(function(r) { setTimeout(r, 500); });
            await loadScript('https://web-sdk.prod.cdn.spain.ozforensics.com/blsinternational/plugin_liveness.php?ver=1.8.1-10');

            console.log('[CrystalVisa] All OZ scripts loaded');

            var attempts = 0;
            var check = setInterval(function() {
                attempts++;
                if (typeof OzLiveness !== 'undefined' && typeof OzLiveness.open === 'function') {
                    clearInterval(check);
                    console.log('[CrystalVisa] OzLiveness ready');

                    var config = {
                        lang: 'en',
                        meta: { user_id: uid, transaction_id: tid },
                        overlay_options: false,
                        action: ['video_selfie_blank'],
                        headers: {}
                    };

                    if (ip && ip !== 'N/A' && ip !== 'undefined' && ip !== '') {
                        config.headers['X-Forwarded-For'] = ip;
                        config.headers['X-Real-IP'] = ip;
                    }
                    if (userAgent && userAgent !== '' && userAgent !== 'undefined') {
                        config.headers['User-Agent'] = userAgent;
                    }
                    if (Object.keys(config.headers).length === 0) delete config.headers;

                    config.on_complete = function(result) {
                        var livenessId = result.event_session_id || result.session_id ||
                                         result.liveness_id || result.LivenessId ||
                                         result.id || result.result ||
                                         (result.data && result.data.liveness_id) ||
                                         (result.meta && result.meta.liveness_id);
                        window.dispatchEvent(new CustomEvent('oz_complete', {
                            detail: { livenessId: livenessId, jsonFilename: jsonFilename,
                                      githubSha: githubSha, githubData: githubData,
                                      uid: uid, tid: tid, ip: ip, doneUrl: doneUrl }
                        }));
                    };

                    config.on_error = function(error) {
                        window.dispatchEvent(new CustomEvent('oz_error', { detail: error }));
                    };

                    OzLiveness.open(config);

                } else if (attempts >= 120) {
                    clearInterval(check);
                    window.dispatchEvent(new CustomEvent('oz_error', { detail: 'Timeout: OzLiveness not loaded' }));
                }
            }, 500);

        } catch (err) {
            console.error('[CrystalVisa] Error loading OZ:', err);
            window.dispatchEvent(new CustomEvent('oz_error', { detail: err.message }));
        }
    })();

})(
    '%%UID%%',
    '%%TID%%',
    '%%IP%%',
    '%%JSON_FILENAME%%',
    '%%GITHUB_SHA%%',
    %%GITHUB_DATA%%,
    '%%DONE_URL%%',
    '%%USER_AGENT%%'
);
