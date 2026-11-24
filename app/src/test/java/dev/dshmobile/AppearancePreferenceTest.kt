package dev.dshmobile

import dev.dshmobile.data.AppearancePreference
import kotlin.test.Test
import kotlin.test.assertEquals

class AppearancePreferenceTest {
    @Test
    fun iceIsDefaultForNewUsersAndRetiredThemes() {
        assertEquals(AppearancePreference.ICE, AppearancePreference.DEFAULT)
        listOf(null, "", "default", "eyeblue", "eyegreen", "unknown").forEach { retired ->
            assertEquals(AppearancePreference.ICE, AppearancePreference.normalize(retired))
        }
    }

    @Test
    fun currentChoicesRoundTripThroughThePreferenceResolver() {
        listOf(AppearancePreference.ICE, AppearancePreference.AURORA).forEach { choice ->
            val encoded = AppearancePreference.normalize(choice)
            assertEquals(choice, AppearancePreference.normalize(encoded))
        }
    }
}
