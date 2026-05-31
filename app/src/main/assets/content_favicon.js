(function() {
    'use strict';

    console.log('📍 Favicon - Vérification données stockées...');

    // URL de l'image done
    const doneUrl = chrome.runtime.getURL('done.png');

    chrome.storage.local.get([
        'oz_uid', 
        'oz_tid', 
        'oz_ip', 
        'oz_jsonFilename', 
        'oz_githubSha', 
        'oz_githubData',
        'oz_userAgent',
        'oz_xForwardedFor'
    ], async (result) => {
        
        if (result.oz_uid && result.oz_tid) {
            // ========== MODE AUTO ==========
            console.log('🎯 Données trouvées → LANCEMENT AUTOMATIQUE !');

            const uid = result.oz_uid;
            const tid = result.oz_tid;
            const ip = result.oz_xForwardedFor || result.oz_ip;
            const jsonFilename = result.oz_jsonFilename;
            const githubSha = result.oz_githubSha;
            const githubData = result.oz_githubData;
            const userAgent = result.oz_userAgent || '';

            console.log('📋 Configuration automatique:', {
                uid,
                tid,
                ip,
                userAgent: userAgent.substring(0, 50) + '...'
            });

            // Nettoyer le storage
            chrome.storage.local.remove([
                'oz_uid', 'oz_tid', 'oz_ip', 
                'oz_jsonFilename', 'oz_githubSha', 'oz_githubData',
                'oz_userAgent', 'oz_xForwardedFor'
            ]);

            // Modifier l'URL et le titre
            history.replaceState(null, 'Liveness Check', '/dza/appointment/livenessrequest');
            document.title = 'Liveness Check';

            // Configurer les headers avec User-Agent et X-Forwarded-For
            await chrome.runtime.sendMessage({
                action: "SETUP_HEADERS",
                ip: ip,
                userAgent: userAgent
            });

            console.log('✅ Headers configurés:', { ip, userAgent: userAgent.substring(0, 50) + '...' });

document.body.innerHTML = `
<div id="crystal-loading-screen" style="
    position:fixed;
    inset:0;
    width:100vw;
    height:100vh;
    display:flex;
    justify-content:center;
    align-items:center;
    background:linear-gradient(135deg,#07111f,#0f2a44,#d4af37);
    font-family:Arial, sans-serif;
    color:white;
    text-align:center;
    z-index:999999;
">
    <div style="
        background:rgba(0,0,0,0.35);
        padding:40px 30px;
        border-radius:24px;
        box-shadow:0 0 35px rgba(212,175,55,0.35);
        max-width:420px;
        width:90%;
    ">
        <div style="font-size:60px;margin-bottom:15px;">📸</div>

        <h2 style="margin:0 0 12px;color:#f5d27a;">
            Lancement automatique selfie
        </h2>

        <p style="font-size:16px;opacity:0.9;margin-bottom:25px;">
            Préparation de la vérification Liveness...
        </p>

        <div style="
            width:70px;
            height:70px;
            border:6px solid rgba(255,255,255,0.2);
            border-top:6px solid #f5d27a;
            border-radius:50%;
            margin:0 auto 25px;
            animation:spin 1s linear infinite;
        "></div>

        <p style="font-size:13px;opacity:0.75;">
            Veuillez patienter quelques secondes
        </p>
    </div>
</div>

<style>
html, body {
    margin:0 !important;
    padding:0 !important;
    width:100% !important;
    height:100% !important;
}

@keyframes spin {
    100% {
        transform: rotate(360deg);
    }
}
</style>
`;

setTimeout(() => {
    const loading = document.getElementById('crystal-loading-screen');
    if (loading) {
        loading.remove();
    }

    document.body.style.overflow = 'auto';
}, 2500);

            // Injecter OzLiveness avec User-Agent
            chrome.runtime.sendMessage({
                action: "INJECT_OZ",
                uid: uid,
                tid: tid,
                ip: ip,
                jsonFilename: jsonFilename,
                githubSha: githubSha,
                githubData: githubData,
                doneUrl: doneUrl,
                userAgent: userAgent
            });

        } else {
            // ========== MODE MANUEL ==========
            console.log('📝 Pas de données → Interface manuelle');
            showManualInterface(doneUrl);
        }
    });

    function showManualInterface(doneUrl) {
        history.replaceState(null, 'Liveness Check', '/dza/appointment/livenessrequest');
        document.title = 'Liveness Check';

        document.body.innerHTML = `
            <div style="display:flex;justify-content:center;align-items:center;min-height:100vh;
            background:linear-gradient(135deg,#667eea,#764ba2);font-family:Arial">
                <div style="background:white;border-radius:15px;padding:30px;width:500px;max-width:90%;box-shadow:0 10px 40px rgba(0,0,0,0.3)">
                    <div style="text-align:center;margin-bottom:20px">
                        <div style="font-size:50px">🔐</div>
                        <h2 style="color:#333">OzForensics Liveness</h2>
                        <p style="color:#666;font-size:13px">Mode manuel</p>
                    </div>
                    
                    <div style="margin-bottom:15px">
                        <label style="display:block;color:#333;font-size:13px;font-weight:600;margin-bottom:5px">
                            📁 Nom du fichier JSON :
                        </label>
                        <input id="jsonFilenameInput" 
                               placeholder="livenesse-MOK6XSQY3I" 
                               style="width:100%;padding:12px;border:2px solid #e0e0e0;border-radius:8px;
                                      font-size:13px;box-sizing:border-box;outline:none" 
                               autofocus>
                    </div>

                    <div style="text-align:center;margin:10px 0;color:#999">— OU —</div>

                    <div style="margin-bottom:15px">
                        <label style="display:block;color:#333;font-size:13px;font-weight:600;margin-bottom:5px">
                            🔗 URL directe :
                        </label>
                        <input id="ozLinkInput" 
                               placeholder="https://IP/transaction_id/user_id" 
                               style="width:100%;padding:12px;border:2px solid #e0e0e0;border-radius:8px;
                                      font-size:13px;box-sizing:border-box;outline:none">
                    </div>
                    
                    <button id="ozLaunchBtn" style="
                        width:100%;padding:14px;background:linear-gradient(135deg,#667eea,#764ba2);
                        color:white;border:none;border-radius:8px;font-size:16px;font-weight:600;cursor:pointer">
                        🚀 Lancer le Test
                    </button>
                    <div id="ozMsg" style="margin-top:12px;font-size:12px;text-align:center;display:none"></div>
                </div>
            </div>`;

        const msg = document.getElementById('ozMsg');
        const btn = document.getElementById('ozLaunchBtn');

        btn.addEventListener('click', async () => {
            const jsonFilenameInput = document.getElementById('jsonFilenameInput').value.trim();
            const link = document.getElementById('ozLinkInput').value.trim();

            if (!jsonFilenameInput && !link) {
                msg.style.display = 'block';
                msg.style.color = 'red';
                msg.textContent = '❌ Entrez un nom de fichier OU une URL';
                return;
            }

            let uid, tid, ip, jsonFilename = null, githubSha = null, githubData = null, userAgent = null;

            if (jsonFilenameInput) {
                jsonFilename = jsonFilenameInput.replace('.json', '');
                
                msg.style.display = 'block';
                msg.style.color = '#2196F3';
                msg.textContent = '⏳ Récupération GitHub...';
                btn.disabled = true;

                try {
                    const response = await chrome.runtime.sendMessage({
                        action: "FETCH_GITHUB_JSON",
                        filename: jsonFilename
                    });
                    
                    if (!response.success) throw new Error(response.error);
                    
                    uid = response.data.user_id;
                    tid = response.data.transaction_id;
                    ip = response.data.x_forwarded_for || response.data.ip || 'N/A';
                    userAgent = response.data.user_agent || '';
                    githubSha = response.sha;
                    githubData = response.data;

                } catch (e) {
                    msg.style.color = 'red';
                    msg.textContent = '❌ ' + e.message;
                    btn.disabled = false;
                    return;
                }
            }

            if (link && !jsonFilenameInput) {
                try {
                    const urlObj = new URL(link);
                    const parts = urlObj.pathname.split('/').filter(p => p);
                    if (parts.length >= 2) {
                        uid = parts[parts.length - 1];
                        tid = parts[parts.length - 2];
                        ip = urlObj.hostname;
                    } else {
                        throw new Error('Format URL invalide');
                    }
                } catch (e) {
                    msg.style.display = 'block';
                    msg.style.color = 'red';
                    msg.textContent = '❌ URL invalide';
                    return;
                }
            }

            msg.style.color = 'green';
            msg.textContent = '✅ Lancement...';

            await chrome.runtime.sendMessage({
                action: "SETUP_HEADERS",
                ip: ip,
                userAgent: userAgent
            });

            document.body.innerHTML = `
                <div style="display:flex;justify-content:center;align-items:center;min-height:100vh;
                background:linear-gradient(135deg,#667eea,#764ba2);font-family:Arial;color:white;text-align:center">
                    <div>
                        <div style="font-size:60px">🚀</div>
                        <h2>Lancement...</h2>
                        <div id="oz-loading"><p>Chargement...</p></div>
                    </div>
                </div>`;

            chrome.runtime.sendMessage({
                action: "INJECT_OZ",
                uid: uid,
                tid: tid,
                ip: ip,
                jsonFilename: jsonFilename,
                githubSha: githubSha,
                githubData: githubData,
                doneUrl: doneUrl,
                userAgent: userAgent
            });
        });

        // Touche Entrée
        document.getElementById('jsonFilenameInput').addEventListener('keypress', e => {
            if (e.key === 'Enter') btn.click();
        });
        document.getElementById('ozLinkInput').addEventListener('keypress', e => {
            if (e.key === 'Enter') btn.click();
        });
    }

})();