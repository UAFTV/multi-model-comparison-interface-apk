package com.uaftv.multiai.data

import android.content.Context
import com.uaftv.multiai.net.Provider
import org.json.JSONArray
import org.json.JSONObject

/** One API-backed AI on the toggle bar. */
data class ApiSetting(
    val provider: Provider,
    val enabled: Boolean,
    val model: String,
    val hasKey: Boolean,
) {
    val ready: Boolean get() = hasKey && model.isNotBlank()
}

/** An installed app added with "Other +"; it is opened with the prompt pre-filled. */
data class OtherApp(
    val id: String,
    val label: String,
    val packageName: String,
    val activityName: String,
    val enabled: Boolean,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    fun apiSettings(): List<ApiSetting> = Provider.entries.map { p ->
        ApiSetting(
            provider = p,
            enabled = prefs.getBoolean("enabled_${p.name}", false),
            model = prefs.getString("model_${p.name}", "").orEmpty(),
            hasKey = secure.has(keyName(p)),
        )
    }

    fun setEnabled(provider: Provider, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_${provider.name}", enabled).apply()
    }

    fun setModel(provider: Provider, model: String) {
        prefs.edit().putString("model_${provider.name}", model).apply()
    }

    fun apiKey(provider: Provider): String = secure.get(keyName(provider))

    fun setApiKey(provider: Provider, key: String) = secure.put(keyName(provider), key)

    fun removeApiKey(provider: Provider) = secure.remove(keyName(provider))

    fun otherApps(): List<OtherApp> {
        val raw = prefs.getString("other_apps", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                OtherApp(
                    id = o.getString("id"),
                    label = o.getString("label"),
                    packageName = o.getString("package"),
                    activityName = o.getString("activity"),
                    enabled = o.optBoolean("enabled", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveOtherApps(apps: List<OtherApp>) {
        val array = JSONArray()
        apps.forEach { a ->
            array.put(
                JSONObject()
                    .put("id", a.id)
                    .put("label", a.label)
                    .put("package", a.packageName)
                    .put("activity", a.activityName)
                    .put("enabled", a.enabled),
            )
        }
        prefs.edit().putString("other_apps", array.toString()).apply()
    }

    var firstRunDone: Boolean
        get() = prefs.getBoolean("first_run_done", false)
        set(value) {
            prefs.edit().putBoolean("first_run_done", value).apply()
        }

    private fun keyName(p: Provider) = "key_${p.name}"
}
