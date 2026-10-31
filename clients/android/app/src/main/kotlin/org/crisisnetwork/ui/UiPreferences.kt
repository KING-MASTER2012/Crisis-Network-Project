package org.crisisnetwork.android.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.crisisnetwork.android.ui.theme.ThemeMode
import org.crisisnetwork.android.ui.theme.ThemeSettings

/**
 * What the interface remembers (phase A4): the appearance choices, whether the introduction was
 * completed and the public parts of the identity (safety number, node id), so that screens can
 * show them without opening the node. Nothing secret is stored here: the seed and the database
 * key live in the Keystore-backed vault of `:core:runtime`.
 *
 * Plain `SharedPreferences` in the app's private storage (the app does not allow backups). Reads
 * are in memory; writes are asynchronous.
 */
object UiPreferences {
    data class State(
        val theme: ThemeSettings = ThemeSettings(),
        val onboardingDone: Boolean = false,
        val identityCreated: Boolean = false,
        val safetyNumber: String? = null,
        val nodeIdHex: String? = null,
        /** The contact card as text (public): what the QR code of this phone holds. Known once the identity exists. */
        val cardText: String? = null,
        /** Keep the app out of screenshots and the recent-apps preview (phase A7). On unless the person turns it off. */
        val screenProtection: Boolean = true,
    )

    private const val PREFS = "ui"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_DYNAMIC = "theme_dynamic"
    private const val KEY_CONTRAST = "theme_high_contrast"
    private const val KEY_TEXT = "theme_text_scale"
    private const val KEY_ONBOARDED = "onboarding_done"
    private const val KEY_IDENTITY = "identity_created"
    private const val KEY_SAFETY = "identity_safety_number"
    private const val KEY_NODE = "identity_node_id"
    private const val KEY_CARD = "identity_card"
    private const val KEY_SCREEN_PROTECTION = "screen_protection"

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads the stored values once. Call it before the first screen is composed. */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        val mode = p.getString(KEY_MODE, null)?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.System
        mutableState.value =
            State(
                theme =
                    ThemeSettings(
                        mode = mode,
                        dynamicColor = p.getBoolean(KEY_DYNAMIC, true),
                        highContrast = p.getBoolean(KEY_CONTRAST, false),
                        textScale = ThemeSettings.nearestTextScale(p.getFloat(KEY_TEXT, 1.0f)),
                    ),
                onboardingDone = p.getBoolean(KEY_ONBOARDED, false),
                identityCreated = p.getBoolean(KEY_IDENTITY, false),
                safetyNumber = p.getString(KEY_SAFETY, null),
                nodeIdHex = p.getString(KEY_NODE, null),
                cardText = p.getString(KEY_CARD, null),
                screenProtection = p.getBoolean(KEY_SCREEN_PROTECTION, true),
            )
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        mutableState.update { it.copy(theme = it.theme.copy(mode = mode)) }
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        mutableState.update { it.copy(theme = it.theme.copy(dynamicColor = enabled)) }
    }

    fun setHighContrast(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONTRAST, enabled).apply()
        mutableState.update { it.copy(theme = it.theme.copy(highContrast = enabled)) }
    }

    fun setTextScale(context: Context, scale: Float) {
        val value = ThemeSettings.nearestTextScale(scale)
        prefs(context).edit().putFloat(KEY_TEXT, value).apply()
        mutableState.update { it.copy(theme = it.theme.copy(textScale = value)) }
    }

    fun setScreenProtection(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCREEN_PROTECTION, enabled).apply()
        mutableState.update { it.copy(screenProtection = enabled) }
    }

    /** Remembers that the identity exists, with the public values the screens show. */
    fun saveIdentity(context: Context, safetyNumber: String, nodeIdHex: String, cardText: String? = null) {
        val edit = prefs(context).edit().putBoolean(KEY_IDENTITY, true).putString(KEY_SAFETY, safetyNumber).putString(KEY_NODE, nodeIdHex)
        if (cardText != null) edit.putString(KEY_CARD, cardText)
        edit.apply()
        mutableState.update { it.copy(identityCreated = true, safetyNumber = safetyNumber, nodeIdHex = nodeIdHex, cardText = cardText ?: it.cardText) }
    }

    /** Remembers the card of an identity that was created before the card was stored (phase A5). */
    fun saveCard(context: Context, cardText: String) {
        prefs(context).edit().putString(KEY_CARD, cardText).apply()
        mutableState.update { it.copy(cardText = cardText) }
    }

    /** The identity was deleted (explicit reset): forget what was shown of it. */
    fun clearIdentity(context: Context) {
        prefs(context).edit().remove(KEY_IDENTITY).remove(KEY_SAFETY).remove(KEY_NODE).remove(KEY_CARD).apply()
        mutableState.update { it.copy(identityCreated = false, safetyNumber = null, nodeIdHex = null, cardText = null) }
    }

    fun completeOnboarding(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, true).apply()
        mutableState.update { it.copy(onboardingDone = true) }
    }

    /** Shows the introduction again (a developer convenience in the settings). */
    fun resetOnboarding(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, false).apply()
        mutableState.update { it.copy(onboardingDone = false) }
    }
}
