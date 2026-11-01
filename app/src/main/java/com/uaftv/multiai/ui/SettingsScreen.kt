package com.uaftv.multiai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.uaftv.multiai.data.ApiSetting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { vm.showSettings(false) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AIs answered here (through their APIs)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Each needs an API key from that company, and API use is paid on that company's API account. " +
                            "OpenAI and Anthropic bill API use separately from ChatGPT and Claude app subscriptions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.apis, key = { it.provider.name }) { setting ->
                ProviderCard(setting, state, vm)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Installed apps (Other +)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "These open with your prompt filled in; their answers stay in their own apps.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.apps.isEmpty()) {
                item { Text("None added yet. Tap Other + on the main screen.") }
            }
            items(state.apps, key = { it.id }) { app ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(app.label, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.removeApp(app.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${app.label}")
                    }
                }
            }
            item {
                Text(
                    "API keys are encrypted on this phone with the Android Keystore and are sent only to the company each key belongs to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(setting: ApiSetting, state: UiState, vm: MainViewModel) {
    val provider = setting.provider
    var keyInput by remember(provider) { mutableStateOf("") }
    var modelInput by remember(provider) { mutableStateOf(setting.model) }
    var menuOpen by remember(provider) { mutableStateOf(false) }
    val models = state.modelLists[provider].orEmpty()
    val loading = provider in state.modelLoading
    val error = state.modelErrors[provider]

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${provider.displayName} (${provider.company})", style = MaterialTheme.typography.titleSmall)
            Text(provider.keyHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(if (setting.hasKey) "Key saved – paste a new one to replace it" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = keyInput.isNotBlank(),
                    onClick = {
                        vm.saveKey(provider, keyInput)
                        keyInput = ""
                    },
                ) { Text("Save key") }
                if (setting.hasKey) {
                    OutlinedButton(onClick = { vm.removeKey(provider) }) { Text("Remove key") }
                }
            }
            Box {
                OutlinedTextField(
                    value = modelInput,
                    onValueChange = {
                        modelInput = it
                        vm.setModel(provider, it)
                    },
                    label = { Text("Model (pick from the list or type one)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            if (models.isEmpty() && !loading) vm.loadModels(provider)
                            menuOpen = true
                        }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose a ${provider.displayName} model")
                        }
                    },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    when {
                        loading -> DropdownMenuItem(text = { Text("Loading models…") }, onClick = {}, enabled = false)
                        models.isEmpty() -> DropdownMenuItem(
                            text = { Text(error ?: "No models loaded yet.") },
                            onClick = {},
                            enabled = false,
                        )
                        else -> models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    modelInput = model
                                    vm.setModel(provider, model)
                                    menuOpen = false
                                },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Reload the model list") },
                        onClick = { vm.loadModels(provider) },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    )
                }
            }
            if (error != null && !menuOpen) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
