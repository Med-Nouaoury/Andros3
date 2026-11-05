package net.rpcs3.utils

import android.content.Context
import androidx.core.content.edit
import net.rpcs3.RPCS3
import org.json.JSONObject
import java.io.File

object DriverSelection {
    const val DriverPathKey = "Video@@Vulkan@@Custom Driver@@Path"
    const val DriverDataDirKey = "Video@@Vulkan@@Custom Driver@@Internal Data Directory"

    private const val PREFS_NAME = "GpuDriverSelection"
    private const val GLOBAL_KEY = "global"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keyFor(titleId: String) = "game_$titleId"

    fun globalPath(context: Context): String =
        prefs(context).getString(GLOBAL_KEY, null) ?: readSetting("")

    fun hasOverride(context: Context, titleId: String) =
        titleId.isNotEmpty() && prefs(context).contains(keyFor(titleId))

    fun overrideFor(context: Context, titleId: String): String? {
        if (titleId.isEmpty()) {
            return null
        }

        return prefs(context).getString(keyFor(titleId), null)
    }

    fun resolve(context: Context, titleId: String): String =
        overrideFor(context, titleId) ?: globalPath(context)

    fun setGlobal(context: Context, path: String) {
        prefs(context).edit { putString(GLOBAL_KEY, path) }
        writeSetting(context, path, "")
    }

    fun setForGame(context: Context, titleId: String, path: String) {
        if (titleId.isEmpty()) {
            return
        }

        prefs(context).edit { putString(keyFor(titleId), path) }
        writeSetting(context, path, titleId)
    }

    fun clearOverride(context: Context, titleId: String) {
        if (titleId.isEmpty()) {
            return
        }

        prefs(context).edit { remove(keyFor(titleId)) }

        if (customConfigExists(titleId)) {
            writeSetting(context, globalPath(context), titleId)
        }
    }

    fun apply(context: Context, titleId: String) {
        if (titleId.isEmpty()) {
            return
        }

        val override = overrideFor(context, titleId)

        if (override == null && !customConfigExists(titleId)) {
            return
        }

        writeSetting(context, override ?: globalPath(context), titleId)
    }

    fun customConfigExists(titleId: String): Boolean {
        val root = RPCS3.rootDirectory

        if (root.isEmpty() || titleId.isEmpty()) {
            return false
        }

        return File("$root/config/custom_configs/config_$titleId.yml").isFile
    }

    // settingsGet on a leaf returns {"type":"string","value":...,"default":...},
    // not the bare string. Reading it as a string made the fallback path the
    // whole JSON object, which settingsSet then rejected as invalid json, so a
    // game's custom config never received the selected driver.
    private fun readSetting(titleId: String): String = runCatching {
        parseStringSetting(RPCS3.instance.settingsGet(DriverPathKey, titleId))
    }.getOrDefault("")

    fun parseStringSetting(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("{")) {
            return runCatching { JSONObject(text).optString("value", "") }.getOrDefault("")
        }
        return text.trim('"')
    }

    private fun writeSetting(context: Context, path: String, titleId: String) {
        runCatching {
            RPCS3.instance.settingsSet(DriverPathKey, "\"" + path + "\"", titleId)
            RPCS3.instance.settingsSet(
                DriverDataDirKey, "\"" + context.filesDir + "\"", titleId
            )
        }
    }
}
