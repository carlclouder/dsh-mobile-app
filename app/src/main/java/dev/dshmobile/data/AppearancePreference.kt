package dev.dshmobile.data

/** Stable preference IDs; visual treatment belongs solely to the UI layer. */
object AppearancePreference {
    const val ICE = "ice_blue"
    const val AURORA = "aurora"
    const val DEFAULT = ICE

    /** All retired (default, eyeblue, eyegreen), unknown, and missing values resolve to Ice. */
    fun normalize(key: String?): String = when (key) {
        AURORA -> AURORA
        else -> ICE
    }
}
