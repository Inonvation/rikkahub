package me.rerere.rikkahub

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSerializationTest {

    @Test
    fun settingModesHasSerializer() {
        val json = Json.encodeToString(Screen.SettingModes)
        assertTrue(json.isNotBlank())
    }
}
