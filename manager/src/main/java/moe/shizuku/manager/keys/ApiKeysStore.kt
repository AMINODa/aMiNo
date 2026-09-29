package moe.shizuku.manager.keys

import android.content.Context

/**
 * High-level provider/key settings. The raw API key is only ever handled as
 * CharArray through [SecureStore] (Android Keystore encrypted). Everything else
 * (provider id, model name, base URL, status) is plain local settings.
 */
object ApiKeysStore {

    const val PROVIDER_GEMINI = "gemini"
    const val PROVIDER_OPENAI_COMPAT = "openai_compat"

    val PROVIDERS = listOf(
        ProviderInfo(PROVIDER_GEMINI, "Google Gemini", "gemini-flash-latest", "https://generativelanguage.googleapis.com/v1beta"),
        ProviderInfo(PROVIDER_OPENAI_COMPAT, "OpenAI-compatible", "gpt-4o-mini", "https://api.openai.com/v1")
    )

    data class ProviderInfo(
        val id: String,
        val label: String,
        val defaultModel: String,
        val defaultBaseUrl: String?
    )

    private const val K_PROVIDER = "ai_provider"
    private const val K_MODEL = "ai_model_"
    private const val K_BASEURL = "ai_baseurl_"
    private const val K_KEY_PREFIX = "apikey_"   // + provider id (encrypted value)
    private const val K_STATUS = "ai_status_"

    private fun prefs(context: Context) =
        context.getSharedPreferences("amino_agent_settings", Context.MODE_PRIVATE)

    fun provider(context: Context): String = prefs(context).getString(K_PROVIDER, PROVIDER_GEMINI) ?: PROVIDER_GEMINI

    fun setProvider(context: Context, id: String) {
        prefs(context).edit().putString(K_PROVIDER, id).apply()
    }

    fun providerInfo(context: Context): ProviderInfo =
        PROVIDERS.firstOrNull { it.id == provider(context) } ?: PROVIDERS[0]

    fun model(context: Context): String =
        prefs(context).getString(K_MODEL + provider(context), providerInfo(context).defaultModel)
            ?: providerInfo(context).defaultModel

    fun setModel(context: Context, value: String) {
        prefs(context).edit().putString(K_MODEL + provider(context), value.trim()).apply()
    }

    fun baseUrl(context: Context): String? =
        prefs(context).getString(K_BASEURL + provider(context), providerInfo(context).defaultBaseUrl)

    fun setBaseUrl(context: Context, value: String?) {
        prefs(context).edit().putString(K_BASEURL + provider(context), value?.trim()?.takeIf { it.isNotEmpty() }).apply()
    }

    fun hasKey(context: Context): Boolean =
        SecureStore.has(context, K_KEY_PREFIX + provider(context))

    /** Masked preview only - never returns the raw key to the UI. */
    fun maskedKey(context: Context): String? {
        val key = SecureStore.get(context, K_KEY_PREFIX + provider(context)) ?: return null
        val len = key.size
        return if (len <= 8) "••••••" else "••••••••" + String(key.copyOfRange(len - 4, len))
    }

    /** Stores the key encrypted; returns false if Keystore encryption failed. */
    fun setKey(context: Context, plain: CharArray): Boolean =
        SecureStore.put(context, K_KEY_PREFIX + provider(context), plain)

    /** Deletes the encrypted local copy of the key for the current provider. */
    fun deleteKey(context: Context) {
        SecureStore.clear(context, K_KEY_PREFIX + provider(context))
        setStatus(context, STATUS_NOT_CONFIGURED)
    }

    fun setStatus(context: Context, status: String) {
        prefs(context).edit().putString(K_STATUS + provider(context), status).apply()
    }

    fun status(context: Context): String =
        prefs(context).getString(K_STATUS + provider(context), STATUS_NOT_CONFIGURED) ?: STATUS_NOT_CONFIGURED

    const val STATUS_NOT_CONFIGURED = "not_configured"
    const val STATUS_READY = "ready"
    const val STATUS_FAILED = "failed"
}
