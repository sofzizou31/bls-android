package com.sofzizou.bls

object Constants {
    // ── GitHub ──────────────────────────────────────────────────────────────
    val GITHUB_TOKEN: String  get() = BuildConfig.GITHUB_TOKEN
    const val GITHUB_OWNER  = "sofzizou31"
    const val GITHUB_REPO   = "livenesse"
    const val GITHUB_BRANCH = "main"

    // ── Telegram ─────────────────────────────────────────────────────────────
    val TELEGRAM_BOT: String  get() = BuildConfig.TELEGRAM_BOT
    val TELEGRAM_CHAT: String get() = BuildConfig.TELEGRAM_CHAT

    // ── Cloudflare Worker ────────────────────────────────────────────────────
    val WORKER_URL: String    get() = BuildConfig.WORKER_URL

    // ── BLS / OzForensics ────────────────────────────────────────────────────
    // Base URL injectée dans loadDataWithBaseURL pour tromper Jscrambler
    const val BLS_BASE_URL = "https://algeria.blsspainglobal.com/dza/appointment/livenessrequest"

    // ── Notifications ────────────────────────────────────────────────────────
    const val NOTIF_CHANNEL_ID   = "liveness_alarm"
    const val NOTIF_CHANNEL_NAME = "Selfie BLS Visa"
    const val NOTIF_ID           = 1001

    // ── SharedPreferences ────────────────────────────────────────────────────
    const val PREFS_NAME  = "bls_prefs"
    const val PREF_PHONE  = "registered_phone"
    const val PREF_TOKEN  = "fcm_token"

    // ── Intent extras ────────────────────────────────────────────────────────
    const val EXTRA_FILENAME  = "filename"
    const val EXTRA_PROXY_URL = "proxy_url"
}
