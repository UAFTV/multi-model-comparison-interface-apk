package com.uaftv.multiai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uaftv.multiai.data.ApiSetting
import com.uaftv.multiai.data.AppLauncher
import com.uaftv.multiai.data.OtherApp
import com.uaftv.multiai.data.SettingsStore
import com.uaftv.multiai.data.ShareTarget
import com.uaftv.multiai.net.AiClient
import com.uaftv.multiai.net.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface Status {
    data object Loading : Status
    data class Done(val text: String) : Status
    data class Failed(val message: String) : Status
}

sealed interface ResultTab {
    val id: String
    val title: String
}

/** One tab per API-backed AI; the title names the model the answer came from. */
data class ApiTab(
    override val id: String,
    override val title: String,
    val provider: Provider,
    val status: Status,
) : ResultTab

/** One tab per installed app; its answer stays inside that app. */
data class AppTab(
    override val id: String,
    override val title: String,
    val app: OtherApp,
    val opened: Boolean,
) : ResultTab

data class UiState(
    val apis: List<ApiSetting> = emptyList(),
    val apps: List<OtherApp> = emptyList(),
    val tabs: List<ResultTab> = emptyList(),
    val lastPrompt: String = "",
    val sendCount: Int = 0,
    val showSettings: Boolean = false,
    val modelLists: Map<Provider, List<String>> = emptyMap(),
    val modelLoading: Set<Provider> = emptySet(),
    val modelErrors: Map<Provider, String> = emptyMap(),
) {
    val anySelected: Boolean get() = apis.any { it.enabled } || apps.any { it.enabled }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore(application)
    private val client = AiClient()
    private val inFlight = mutableListOf<Job>()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (!store.firstRunDone) {
            addSuggestedApps()
            store.firstRunDone = true
        }
        reload()
    }

    /** Copilot and Magica have no public chat API, so they are added as apps if installed. */
    private fun addSuggestedApps() {
        val suggested = listOf("copilot", "magica")
        val targets = runCatching { AppLauncher.queryShareTargets(getApplication<Application>(), withIcons = false) }
            .getOrDefault(emptyList())
        val apps = store.otherApps().toMutableList()
        suggested.forEach { word ->
            val match = targets.firstOrNull { it.label.lowercase().contains(word) }
            if (match != null && apps.none { it.packageName == match.packageName }) {
                apps += OtherApp(UUID.randomUUID().toString(), match.label, match.packageName, match.activityName, false)
            }
        }
        store.saveOtherApps(apps)
    }

    private fun reload() {
        _state.update { it.copy(apis = store.apiSettings(), apps = store.otherApps()) }
    }

    fun showSettings(show: Boolean) = _state.update { it.copy(showSettings = show) }

    fun toggleApi(provider: Provider) {
        val current = _state.value.apis.firstOrNull { it.provider == provider } ?: return
        store.setEnabled(provider, !current.enabled)
        reload()
    }

    fun toggleApp(id: String) {
        store.saveOtherApps(_state.value.apps.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
        reload()
    }

    fun addApp(target: ShareTarget) {
        val apps = _state.value.apps
        val existing = apps.firstOrNull { it.packageName == target.packageName && it.activityName == target.activityName }
        val updated = if (existing != null) {
            apps.map { if (it.id == existing.id) it.copy(enabled = true) else it }
        } else {
            apps + OtherApp(UUID.randomUUID().toString(), target.label, target.packageName, target.activityName, true)
        }
        store.saveOtherApps(updated)
        reload()
    }

    fun removeApp(id: String) {
        store.saveOtherApps(_state.value.apps.filterNot { it.id == id })
        reload()
    }

    fun saveKey(provider: Provider, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        store.setApiKey(provider, trimmed)
        store.setEnabled(provider, true)
        _state.update { it.copy(modelErrors = it.modelErrors - provider) }
        reload()
        if (_state.value.modelLists[provider].isNullOrEmpty()) loadModels(provider)
    }

    fun removeKey(provider: Provider) {
        store.removeApiKey(provider)
        store.setEnabled(provider, false)
        _state.update { it.copy(modelLists = it.modelLists - provider) }
        reload()
    }

    fun setModel(provider: Provider, model: String) {
        store.setModel(provider, model.trim())
        reload()
    }

    fun loadModels(provider: Provider) {
        val key = store.apiKey(provider)
        if (key.isEmpty()) {
            _state.update { it.copy(modelErrors = it.modelErrors + (provider to "Save an API key first.")) }
            return
        }
        _state.update { it.copy(modelLoading = it.modelLoading + provider, modelErrors = it.modelErrors - provider) }
        viewModelScope.launch {
            val result = try {
                Result.success(client.listModels(provider.format, provider.baseUrl, key))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _state.update { s ->
                result.fold(
                    onSuccess = { models ->
                        s.copy(
                            modelLoading = s.modelLoading - provider,
                            modelLists = s.modelLists + (provider to models),
                            modelErrors = if (models.isEmpty()) s.modelErrors + (provider to "No chat models were listed.") else s.modelErrors,
                        )
                    },
                    onFailure = { e ->
                        s.copy(
                            modelLoading = s.modelLoading - provider,
                            modelErrors = s.modelErrors + (provider to (e.message ?: "Couldn't load models.")),
                        )
                    },
                )
            }
        }
    }

    /**
     * Sends [prompt] to every selected AI. API answers arrive in their tabs; installed apps
     * get a tab with an Open button. Returns the first selected app so the screen can open it
     * right away (Android shows one app at a time).
     */
    fun send(prompt: String): OtherApp? {
        val text = prompt.trim()
        if (text.isEmpty()) return null
        inFlight.forEach { it.cancel() }
        inFlight.clear()

        val s = _state.value
        val apis = s.apis.filter { it.enabled }
        val apps = s.apps.filter { it.enabled }
        if (apis.isEmpty() && apps.isEmpty()) return null

        val apiTabs = apis.map { a ->
            val title = if (a.model.isNotBlank()) "${a.provider.displayName} · ${a.model}" else a.provider.displayName
            val status = when {
                !a.hasKey -> Status.Failed("Add your ${a.provider.company} API key in Settings (gear icon).")
                a.model.isBlank() -> Status.Failed("Choose a ${a.provider.displayName} model in Settings (gear icon).")
                else -> Status.Loading
            }
            ApiTab("api_${a.provider.name}", title, a.provider, status)
        }
        val appTabs = apps.map { AppTab("app_${it.id}", it.label, it, opened = false) }
        _state.update { it.copy(tabs = apiTabs + appTabs, lastPrompt = text, sendCount = it.sendCount + 1) }

        apis.filter { it.ready }.forEach { a ->
            val key = store.apiKey(a.provider)
            inFlight += viewModelScope.launch {
                val status = try {
                    Status.Done(client.ask(a.provider.format, a.provider.baseUrl, key, a.model, text))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Status.Failed(e.message ?: "Unknown error.")
                }
                setStatus(a.provider, status)
            }
        }
        return apps.firstOrNull()
    }

    fun markOpened(appId: String) {
        _state.update { s ->
            s.copy(tabs = s.tabs.map { if (it is AppTab && it.app.id == appId) it.copy(opened = true) else it })
        }
    }

    private fun setStatus(provider: Provider, status: Status) {
        _state.update { s ->
            s.copy(tabs = s.tabs.map { if (it is ApiTab && it.provider == provider) it.copy(status = status) else it })
        }
    }
}
