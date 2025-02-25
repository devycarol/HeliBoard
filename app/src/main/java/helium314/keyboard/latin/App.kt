// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package helium314.keyboard.latin

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.keyboard.ColorSetting
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.checkAndConvertCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.LocaleUtils.constructLocale
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.USER_DICTIONARY_SUFFIX
import helium314.keyboard.latin.settings.colorPrefsAndResIds
import helium314.keyboard.latin.utils.CUSTOM_LAYOUT_PREFIX
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.defaultPinnedToolbarPref
import helium314.keyboard.latin.utils.getCustomLayoutFile
import helium314.keyboard.latin.utils.getCustomLayoutFiles
import helium314.keyboard.latin.utils.onCustomLayoutFileListChanged
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import helium314.keyboard.latin.utils.upgradeToolbarPrefs
import helium314.keyboard.latin.utils.writeCustomKeyCodes
import java.io.File
import java.util.EnumMap

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        checkVersionUpgrade(this)
        app = this
        Defaults.initDynamicDefaults(this)
    }

    companion object {
        // used so JniUtils can access application once
        private var app: App? = null
        fun getApp(): App? {
            val application = app
            app = null
            return application
        }
    }
}

fun checkVersionUpgrade(context: Context) {
    val prefs = context.prefs()
    val oldVersion = prefs.getInt(Settings.PREF_VERSION_CODE, 0)
    if (oldVersion == BuildConfig.VERSION_CODE)
        return
    // clear extracted dictionaries, in case updated version contains newer ones
    DictionaryInfoUtils.getCachedDirectoryList(context)?.forEach {
        if (!it.isDirectory) return@forEach
        val files = it.listFiles() ?: return@forEach
        for (file in files) {
            if (!file.name.endsWith(USER_DICTIONARY_SUFFIX))
                file.delete()
        }
    }
    if (oldVersion == 0) // new install or restoring settings from old app name
        upgradesWhenComingFromOldAppName(context)
    if (oldVersion <= 1000) { // upgrade old custom layouts name
        val oldShiftSymbolsFile = getCustomLayoutFile("${CUSTOM_LAYOUT_PREFIX}shift_symbols", context)
        if (oldShiftSymbolsFile.exists()) {
            oldShiftSymbolsFile.renameTo(getCustomLayoutFile("${CUSTOM_LAYOUT_PREFIX}symbols_shifted", context))
        }

        // rename subtype setting, and clean old subtypes that might remain in some cases
        val subtypesPref = prefs.getString("enabled_input_styles", "")!!
            .split(";").filter { it.isNotEmpty() }
            .map {
                val localeAndLayout = it.split(":").toMutableList()
                localeAndLayout[0] = localeAndLayout[0].constructLocale().toLanguageTag()
                localeAndLayout.joinToString(":")
            }.toSet().joinToString(";")
        val selectedSubtype = prefs.getString("selected_input_style", "")
        prefs.edit {
            remove("enabled_input_styles")
            putString(Settings.PREF_ENABLED_SUBTYPES, subtypesPref)
            remove("selected_input_style")
            putString(Settings.PREF_SELECTED_SUBTYPE, selectedSubtype)
        }
    }
    if (oldVersion <= 2000) {
        // upgrade pinned toolbar keys pref
        val oldPinnedKeysPref = prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, "")!!
        val pinnedKeys = oldPinnedKeysPref.split(";").mapNotNull {
            try {
                ToolbarKey.valueOf(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        val newPinnedKeysPref = (pinnedKeys.map { "${it.name},true" } + defaultPinnedToolbarPref.split(";"))
            .distinctBy { it.split(",").first() }
            .joinToString(";")
        prefs.edit { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, newPinnedKeysPref) }

        // enable language switch key if it was enabled previously
        if (prefs.contains(Settings.PREF_LANGUAGE_SWITCH_KEY) && prefs.getString(Settings.PREF_LANGUAGE_SWITCH_KEY, "") != "off")
            prefs.edit { putBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, true) }
    }
    if (oldVersion <= 2100) {
        if (prefs.contains("show_more_colors")) {
            val moreColors = prefs.getInt("show_more_colors", 0)
            prefs.edit {
                putInt("theme_color_show_more_colors", moreColors)
                if (prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, false))
                    putInt("theme_dark_color_show_more_colors", moreColors)
                remove("show_more_colors")
            }
        }
    }
    if (oldVersion <= 2201) {
        val additionalSubtypeString = prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES)!!
        if (additionalSubtypeString.contains(".")) { // means there are custom layouts
            val subtypeStrings = additionalSubtypeString.split(";")
            val newSubtypeStrings = subtypeStrings.mapNotNull {
                if ("." !in it) // not a custom subtype, nothing to do
                    return@mapNotNull it
                val split = it.split(":").toMutableList()
                if (split.size < 2) return@mapNotNull null // should never happen
                val oldName = split[1]
                val newName = oldName.substringBeforeLast(".") + "."
                if (oldName == newName) return@mapNotNull split.joinToString(":") // should never happen
                val oldFile = getCustomLayoutFile(oldName, context)
                val newFile = getCustomLayoutFile(newName, context)
                if (!oldFile.exists()) return@mapNotNull null // should never happen
                if (newFile.exists()) newFile.delete() // should never happen
                oldFile.renameTo(newFile)
                val enabledSubtypes = prefs.getString(Settings.PREF_ENABLED_SUBTYPES, "")!!
                if (enabledSubtypes.contains(oldName))
                    prefs.edit { putString(Settings.PREF_ENABLED_SUBTYPES, enabledSubtypes.replace(oldName, newName)) }
                val selectedSubtype = prefs.getString(Settings.PREF_SELECTED_SUBTYPE, "")!!
                if (selectedSubtype.contains(oldName))
                    prefs.edit { putString(Settings.PREF_SELECTED_SUBTYPE, selectedSubtype.replace(oldName, newName)) }
                split[1] = newName
                split.joinToString(":")
            }
            Settings.writePrefAdditionalSubtypes(prefs, newSubtypeStrings.joinToString(";"))
        }
        // rename other custom layouts
        onCustomLayoutFileListChanged()
        getCustomLayoutFiles(context).forEach {
            val newFile = getCustomLayoutFile(it.name.substringBeforeLast(".") + ".", context)
            if (newFile.name == it.name) return@forEach
            if (newFile.exists()) newFile.delete() // should never happen
            it.renameTo(newFile)
        }
    }
    if (oldVersion <= 2301) {
        // upgrade and remove old color prefs
        fun readAllColorsMap(isNight: Boolean): EnumMap<ColorType, Int> {
            val prefPrefix = if (isNight) "theme_dark_color_" else "theme_color_"
            val colorsString = prefs.getString(prefPrefix + "all_colors", "") ?: ""
            val colorMap = EnumMap<ColorType, Int>(ColorType::class.java)
            colorsString.split(";").forEach {
                val ct = try {
                    ColorType.valueOf(it.substringBefore(",").uppercase())
                } catch (_: Exception) {
                    return@forEach
                }
                val i = it.substringAfter(",").toIntOrNull() ?: return@forEach
                colorMap[ct] = i
            }
            return colorMap
        }
        // day colors
        val themeNameDay = context.getString(R.string.theme_name_user)
        val colorsDay = colorPrefsAndResIds.map {
            val pref = "theme_color_" + it.first
            val color = if (prefs.contains(pref)) prefs.getInt(pref, 0) else null
            val result = ColorSetting(it.first, prefs.getBoolean(pref + "_auto", true), color)
            prefs.edit().remove(pref).remove(pref + "_auto").apply()
            result
        }
        if (colorsDay.any { it.color != null }) {
            KeyboardTheme.writeUserColors(prefs, themeNameDay, colorsDay)
        }
        val moreColorsDay = prefs.getInt("theme_color_show_more_colors", 0)
        prefs.edit().remove("theme_color_show_more_colors").apply()
        KeyboardTheme.writeUserMoreColors(prefs, themeNameDay, moreColorsDay)
        if (prefs.contains("theme_color_all_colors")) {
            val allColorsDay = readAllColorsMap(false)
            prefs.edit().remove("theme_color_all_colors").apply()
            KeyboardTheme.writeUserAllColors(prefs, themeNameDay, allColorsDay)
        }
        if (prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == "user")
            prefs.edit().putString(Settings.PREF_THEME_COLORS, themeNameDay).apply()

        // same for night colors
        val themeNameNight = context.getString(R.string.theme_name_user_night)
        val colorsNight = colorPrefsAndResIds.map {
            val pref = "theme_dark_color_" + it.first
            val color = if (prefs.contains(pref)) prefs.getInt(pref, 0) else null
            val result = ColorSetting(it.first, prefs.getBoolean(pref + "_auto", true), color)
            prefs.edit().remove(pref).remove(pref + "_auto").apply()
            result
        }
        if (colorsNight.any { it.color!= null }) {
            KeyboardTheme.writeUserColors(prefs, themeNameNight, colorsNight)
        }
        val moreColorsNight = prefs.getInt("theme_dark_color_show_more_colors", 0)
        prefs.edit().remove("theme_dark_color_show_more_colors").apply()
        KeyboardTheme.writeUserMoreColors(prefs, themeNameNight, moreColorsNight)
        if (prefs.contains("theme_dark_color_all_colors")) {
            val allColorsNight = readAllColorsMap(false)
            prefs.edit().remove("theme_dark_color_all_colors").apply()
            KeyboardTheme.writeUserAllColors(prefs, themeNameNight, allColorsNight)
        }
        if (prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT) == "user_night")
            prefs.edit().putString(Settings.PREF_THEME_COLORS_NIGHT, themeNameNight).apply()
    }
    if (oldVersion <= 2302) {
        fun readCustomKeyCodes(setting: String) =
            prefs.getString(setting, "")!!
                .split(";").filter { it.isNotEmpty()}.associate {
                    val code = runCatching { it.substringAfter(",").toIntOrNull()?.checkAndConvertCode() }.getOrNull()
                    it.substringBefore(",") to code
                }
        val customCodes = readCustomKeyCodes("toolbar_custom_key_codes")
        val customLongpressCodes = readCustomKeyCodes("toolbar_custom_longpress_codes")
        prefs.edit().remove("toolbar_custom_longpress_codes").remove("toolbar_custom_key_codes").apply()
        val combined = EnumMap<ToolbarKey, Pair<Int?, Int?>>(ToolbarKey::class.java)
        customCodes.forEach { runCatching {
            val key = ToolbarKey.valueOf(it.key)
            combined[key] = (combined[key] ?: (null to null)).copy(first = it.value)
        } }
        customLongpressCodes.forEach { runCatching {
            val key = ToolbarKey.valueOf(it.key)
            combined[key] = (combined[key] ?: (null to null)).copy(second = it.value)
        } }
        writeCustomKeyCodes(prefs, combined)
    }
    upgradeToolbarPrefs(prefs)
    onCustomLayoutFileListChanged() // just to be sure
    prefs.edit { putInt(Settings.PREF_VERSION_CODE, BuildConfig.VERSION_CODE) }
}

// todo (later): remove it when most users probably have upgraded
private fun upgradesWhenComingFromOldAppName(context: Context) {
    // move layout files
    try {
        File(context.filesDir, "layouts").listFiles()?.forEach {
            it.copyTo(getCustomLayoutFile(it.name, context), true)
            it.delete()
        }
    } catch (_: Exception) {}
    // move background images
    try {
        val bgDay = File(context.filesDir, "custom_background_image")
        if (bgDay.isFile) {
            bgDay.copyTo(Settings.getCustomBackgroundFile(context, false, false), true)
            bgDay.delete()
        }
        val bgNight = File(context.filesDir, "custom_background_image_night")
        if (bgNight.isFile) {
            bgNight.copyTo(Settings.getCustomBackgroundFile(context, true, false), true)
            bgNight.delete()
        }
    } catch (_: Exception) {}
    // upgrade prefs
    val prefs = context.prefs()
    if (prefs.all.containsKey("theme_variant")) {
        prefs.edit().putString(Settings.PREF_THEME_COLORS, prefs.getString("theme_variant", "")).apply()
        prefs.edit().remove("theme_variant").apply()
    }
    if (prefs.all.containsKey("theme_variant_night")) {
        prefs.edit().putString(Settings.PREF_THEME_COLORS_NIGHT, prefs.getString("theme_variant_night", "")).apply()
        prefs.edit().remove("theme_variant_night").apply()
    }
    prefs.all.toMap().forEach {
        if (it.key.startsWith("pref_key_") && it.key != "pref_key_longpress_timeout") {
            var remove = true
            when (val value = it.value) {
                is Boolean -> prefs.edit().putBoolean(it.key.substringAfter("pref_key_"), value).apply()
                is Int -> prefs.edit().putInt(it.key.substringAfter("pref_key_"), value).apply()
                is Long -> prefs.edit().putLong(it.key.substringAfter("pref_key_"), value).apply()
                is String -> prefs.edit().putString(it.key.substringAfter("pref_key_"), value).apply()
                is Float -> prefs.edit().putFloat(it.key.substringAfter("pref_key_"), value).apply()
                else -> remove = false
            }
            if (remove)
                prefs.edit().remove(it.key).apply()
        } else if (it.key.startsWith("pref_")) {
            var remove = true
            when (val value = it.value) {
                is Boolean -> prefs.edit().putBoolean(it.key.substringAfter("pref_"), value).apply()
                is Int -> prefs.edit().putInt(it.key.substringAfter("pref_"), value).apply()
                is Long -> prefs.edit().putLong(it.key.substringAfter("pref_"), value).apply()
                is String -> prefs.edit().putString(it.key.substringAfter("pref_"), value).apply()
                is Float -> prefs.edit().putFloat(it.key.substringAfter("pref_"), value).apply()
                else -> remove = false
            }
            if (remove)
                prefs.edit().remove(it.key).apply()
        }
    }
    // change more_keys to popup_keys
    if (prefs.contains("more_keys_order")) {
        prefs.edit().putString(Settings.PREF_POPUP_KEYS_ORDER, prefs.getString("more_keys_order", "")?.replace("more_", "popup_")).apply()
        prefs.edit().remove("more_keys_order").apply()
    }
    if (prefs.contains("more_keys_labels_order")) {
        prefs.edit().putString(Settings.PREF_POPUP_KEYS_LABELS_ORDER, prefs.getString("more_keys_labels_order", "")?.replace("more_", "popup_")).apply()
        prefs.edit().remove("more_keys_labels_order").apply()
    }
    if (prefs.contains("more_more_keys")) {
        prefs.edit().putString(Settings.PREF_MORE_POPUP_KEYS, prefs.getString("more_more_keys", "")).apply()
        prefs.edit().remove("more_more_keys").apply()
    }
    if (prefs.contains("spellcheck_use_contacts")) {
        prefs.edit().putBoolean(Settings.PREF_USE_CONTACTS, prefs.getBoolean("spellcheck_use_contacts", false)).apply()
        prefs.edit().remove("spellcheck_use_contacts").apply()
    }
    // upgrade additional subtype locale strings
    val additionalSubtypes = mutableListOf<String>()
    prefs.getString(Settings.PREF_ADDITIONAL_SUBTYPES, Defaults.PREF_ADDITIONAL_SUBTYPES)!!.split(";").forEach {
        val localeString = it.substringBefore(":")
        additionalSubtypes.add(it.replace(localeString, localeString.constructLocale().toLanguageTag()))
    }
    Settings.writePrefAdditionalSubtypes(prefs, additionalSubtypes.joinToString(";"))
    // move pinned clips to credential protected storage if device is not locked (should never happen)
    if (!prefs.contains(Settings.PREF_PINNED_CLIPS)) return
    try {
        val defaultProtectedPrefs = context.protectedPrefs()
        defaultProtectedPrefs.edit { putString(Settings.PREF_PINNED_CLIPS, prefs.getString(Settings.PREF_PINNED_CLIPS, "")) }
        prefs.edit { remove(Settings.PREF_PINNED_CLIPS) }
    } catch (_: IllegalStateException) {
        // SharedPreferences in credential encrypted storage are not available until after user is unlocked
    }
}
