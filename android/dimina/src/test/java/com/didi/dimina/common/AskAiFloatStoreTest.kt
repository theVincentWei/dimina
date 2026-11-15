package com.didi.dimina.common

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the「问AI浮窗」switch persistence contract (2026-11-15):
 *
 * - the global SharedPreferences key is the spec'd literal
 *   `dimina_ask_ai_float_enabled` (host debugging / regressions look it up by name);
 * - the default is ON: entering a mini program hangs the float window until closed;
 * - a full off→on toggle round trip through the REAL store code path persists, using
 *   an in-memory [SharedPreferences] (JVM unit tests cannot instantiate Context,
 *   so the store exposes a SharedPreferences-first overload for exactly this seam).
 */
class AskAiFloatStoreTest {

    /** Minimal in-memory SharedPreferences; the interfaces are pure Java contracts. */
    @Suppress("unused")
    private class FakePrefs : SharedPreferences {
        val map = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map

        override fun getString(key: String, defValue: String?): String? =
            map[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (map[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        inner class FakeEditor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                apply { map[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply { map[key] = values }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor =
                apply { map[key] = value }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor =
                apply { map[key] = value }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
                apply { map[key] = value }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                apply { map[key] = value }

            override fun remove(key: String): SharedPreferences.Editor =
                apply { map.remove(key) }

            override fun clear(): SharedPreferences.Editor = apply { map.clear() }

            override fun commit(): Boolean = true

            override fun apply() = Unit
        }
    }

    @Test
    fun `spec key and default are pinned`() {
        assertEquals("dimina_ask_ai_float_enabled", AskAiFloatStore.KEY_ENABLED)
        assertTrue(AskAiFloatStore.DEFAULT_ENABLED)
    }

    @Test
    fun `fresh prefs read the default - enabled`() {
        val prefs = FakePrefs()
        assertTrue(AskAiFloatStore.readEnabled(prefs))
    }

    @Test
    fun `toggle round trip persists through the real store path`() {
        val prefs = FakePrefs()
        // 默认开 → 用户长按 ✕ 关闭
        AskAiFloatStore.writeEnabled(prefs, false)
        assertFalse(AskAiFloatStore.readEnabled(prefs))
        // ⋯菜单瓦片再打开 → 回到默认态
        AskAiFloatStore.writeEnabled(prefs, true)
        assertTrue(AskAiFloatStore.readEnabled(prefs))
        // 再关一次幂等
        AskAiFloatStore.writeEnabled(prefs, false)
        assertFalse(AskAiFloatStore.readEnabled(prefs))
    }

    @Test
    fun `write lands under the global key only`() {
        val prefs = FakePrefs()
        AskAiFloatStore.writeEnabled(prefs, false)
        assertEquals(
            "the switch must be stored under the single global key",
            false,
            prefs.map[AskAiFloatStore.KEY_ENABLED],
        )
        assertEquals("no other keys may leak into the prefs file", 1, prefs.map.size)
    }
}
