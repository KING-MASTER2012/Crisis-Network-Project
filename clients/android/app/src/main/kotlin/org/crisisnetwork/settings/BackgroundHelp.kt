package org.crisisnetwork.android.settings

import java.util.Locale

/**
 * The phone makers whose systems stop background apps beyond what Android itself does (phase A7).
 * Android's own rules (Doze, app standby) are handled by the battery setting every phone has; these
 * makers add their own lists ("autostart", "sleeping apps", "app launch") that decide whether the
 * foreground service that carries the mesh survives with the screen off. Where those lists are
 * differs between makers and versions, so the app only names the right place to look.
 */
enum class PhoneMaker {
    Xiaomi,
    Samsung,
    Huawei,

    /** OPPO and the brands that share its system: OnePlus, realme, and vivo with a similar battery manager. */
    Oppo,

    /** Google and everyone else: the standard battery setting is what there is. */
    Other,
    ;

    companion object {
        /** The maker for `Build.MANUFACTURER` (any case, `null` when unknown). */
        fun of(manufacturer: String?): PhoneMaker {
            val name = manufacturer?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when {
                name.isEmpty() -> Other
                name in XIAOMI -> Xiaomi
                name in SAMSUNG -> Samsung
                name in HUAWEI -> Huawei
                name in OPPO -> Oppo
                else -> Other
            }
        }

        private val XIAOMI = setOf("xiaomi", "redmi", "poco", "blackshark")
        private val SAMSUNG = setOf("samsung")
        private val HUAWEI = setOf("huawei", "honor")
        private val OPPO = setOf("oppo", "oneplus", "realme", "vivo", "iqoo")
    }
}
