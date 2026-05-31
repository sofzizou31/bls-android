(function() {
    'use strict';

    console.log('📍 CrystalVisa - Extraction du nom du fichier...');

    // Récupérer le nom du fichier depuis l'URL
    const pathname = window.location.pathname;
    const parts = pathname.split('/').filter(p => p);
    
    let filename = null;
    
    if (parts.length >= 2 && parts[0] === 'Loading') {
        filename = parts[1];
    } else if (parts.length === 1) {
        filename = parts[0];
    }

    if (!filename) {
        console.error('❌ Impossible d\'extraire le nom du fichier de l\'URL');
        return;
    }

    const cleanFilename = filename.replace('.json', '');
    
    console.log('📁 Fichier détecté:', cleanFilename);

    // URLs des ressources locales
    const logoUrl = chrome.runtime.getURL('logo.jpg');
    const heroBgUrl = chrome.runtime.getURL('hero-bg.png');

    // Afficher la page de loading
    document.documentElement.innerHTML = `
        <!DOCTYPE html>
        <html lang="fr">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Crystal Visa Loading</title>
            <style>
                body {
                    margin: 0;
                    height: 100vh;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    font-family: Arial, sans-serif;
                    background: linear-gradient(rgba(0,0,0,.5), rgba(0,0,0,.7)), url('${heroBgUrl}');
                    background-size: cover;
                    background-position: center;
                    overflow: hidden;
                }
                .box {
                    width: 90%;
                    max-width: 420px;
                    background: rgba(0,0,0,.45);
                    backdrop-filter: blur(10px);
                    padding: 35px;
                    border-radius: 25px;
                    text-align: center;
                    box-shadow: 0 0 30px rgba(255,215,0,.2);
                }
                .logo {
                    width: 160px;
                    height: 160px;
                    object-fit: cover;
                    border-radius: 50%;
                    margin-bottom: 20px;
                }
                h1 {
                    color: #f5d27a;
                    margin-bottom: 10px;
                    font-size: 28px;
                }
                p {
                    color: white;
                    opacity: .9;
                    margin-bottom: 25px;
                }
                .loader {
                    width: 70px;
                    height: 70px;
                    border: 6px solid rgba(255,255,255,.2);
                    border-top: 6px solid #f5d27a;
                    border-radius: 50%;
                    animation: spin 1s linear infinite;
                    margin: auto;
                    margin-bottom: 25px;
                }
                @keyframes spin {
                    100% { transform: rotate(360deg); }
                }
                .progress {
                    height: 8px;
                    background: rgba(255,255,255,.2);
                    border-radius: 20px;
                    overflow: hidden;
                }
                .progress-bar {
                    height: 100%;
                    width: 0%;
                    background: linear-gradient(90deg, #f5d27a, #fff);
                    animation: load 4s forwards;
                }
                @keyframes load {
                    100% { width: 100%; }
                }
                .status-text {
                    color: #aaa;
                    font-size: 13px;
                    margin-top: 15px;
                    min-height: 20px;
                }
                .error-text {
                    color: #ff6b6b;
                    font-size: 13px;
                    margin-top: 10px;
                }
            </style>
        </head>
        <body>
            <div class="box">
                <img src="${logoUrl}" class="logo" alt="Logo">
                <h1>Préparation en cours...</h1>
                <p>Chargement de la vérification Liveness, veuillez patienter.</p>
                <div class="loader"></div>
                <div class="progress">
                    <div class="progress-bar"></div>
                </div>
                <div class="status-text" id="status">📁 ${cleanFilename}.json</div>
            </div>
            <script>
                // Empêcher la redirection automatique
            </script>
        </body>
        </html>
    `;

    // Mettre à jour le statut
    const updateStatus = (text) => {
        const statusEl = document.getElementById('status');
        if (statusEl) statusEl.textContent = text;
    };

    // Récupérer les données depuis GitHub
    updateStatus('⏳ Connexion à GitHub...');

    chrome.runtime.sendMessage({
        action: "FETCH_GITHUB_JSON",
        filename: cleanFilename
    }, (response) => {
        if (!response || !response.success) {
            console.error('❌ Erreur GitHub:', response?.error);
            updateStatus('');
            
            const box = document.querySelector('.box');
            if (box) {
                const errorDiv = document.createElement('div');
                errorDiv.className = 'error-text';
                errorDiv.textContent = '❌ ' + (response?.error || 'Erreur GitHub');
                box.appendChild(errorDiv);
            }
            return;
        }

        const { data, sha } = response;
        const uid = data.user_id;
        const tid = data.transaction_id;
        const ip = data.x_forwarded_for || data.ip || 'N/A';
        const userAgent = data.user_agent || '';
        const xForwardedFor = data.x_forwarded_for || '';

        console.log('✅ Données récupérées:', { 
            uid, 
            tid, 
            ip, 
            userAgent: userAgent.substring(0, 50) + '...',
            xForwardedFor 
        });

        updateStatus('✅ Données trouvées ! Configuration...');

        // Tracking : notifier l'opérateur que le client a ouvert le lien (fire and forget)
        chrome.runtime.sendMessage({
            action: 'SEND_TELEGRAM',
            text: `👁️ *Lien ouvert par le client*\n📁 Fichier: \`${cleanFilename}\`\n📅 ${new Date().toLocaleString('fr-FR')}`
        });

        // Mettre à jour GitHub avec status "viewed", puis récupérer le nouveau SHA
        // IMPORTANT : le SHA doit être mis à jour avant la redirection sinon
        // UPDATE_GITHUB_JSON utilise un SHA périmé → 409 Conflict → livenessId jamais écrit
        chrome.runtime.sendMessage({
            action: 'UPDATE_GITHUB_STATUS',
            filename: cleanFilename,
            sha: sha,
            status: 'viewed',
            originalData: data
        }, function(statusRes) {
            const updatedSha = (statusRes && statusRes.newSha) ? statusRes.newSha : sha;
            console.log('💾 SHA après viewed:', updatedSha);

        // Stocker les données AVANT la redirection (avec le SHA à jour)
        chrome.storage.local.set({
            oz_uid: uid,
            oz_tid: tid,
            oz_ip: ip,
            oz_jsonFilename: cleanFilename,
            oz_githubSha: updatedSha,
            oz_githubData: data,
            oz_userAgent: userAgent,
            oz_xForwardedFor: xForwardedFor
        }, () => {
            console.log('💾 Données stockées avec User-Agent et X-Forwarded-For');
            
            updateStatus('⚙️ Configuration des headers...');
            
            // Configurer les headers AVANT la redirection
            chrome.runtime.sendMessage({
                action: "SETUP_HEADERS_FOR_REDIRECT",
                ip: xForwardedFor || ip,
                userAgent: userAgent
            }, (response) => {
                console.log('✅ Headers configurés pour la redirection');
                updateStatus('🔄 Redirection vers le service...');
                
                // Attendre un peu pour s'assurer que les règles sont appliquées
                setTimeout(() => {
                    window.location.replace('https://algeria.blsspainglobal.com/assets/images/favicon.png');
                }, 800);
            });
        });
        }); // fin UPDATE_GITHUB_STATUS callback
    });

})();