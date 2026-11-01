package com.uaftv.multiai.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uaftv.multiai.data.AppLauncher
import com.uaftv.multiai.data.ShareTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    var prompt by rememberSaveable { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { state.tabs.size })

    LaunchedEffect(state.sendCount) {
        if (state.tabs.isNotEmpty()) pagerState.scrollToPage(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Multi-AI Launcher") },
                actions = {
                    IconButton(onClick = { vm.showSettings(true) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        placeholder = { Text("Ask all selected AIs…") },
                        maxLines = 6,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = prompt.isNotBlank() && state.anySelected,
                        onClick = {
                            val sent = prompt
                            val firstApp = vm.send(sent)
                            if (firstApp != null) {
                                if (AppLauncher.open(context, firstApp, sent.trim())) {
                                    vm.markOpened(firstApp.id)
                                } else {
                                    Toast.makeText(context, "Couldn't open ${firstApp.label}.", Toast.LENGTH_LONG).show()
                                }
                            }
                            prompt = ""
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to selected AIs")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            ToggleBar(state, vm, onOther = { showPicker = true })
            HorizontalDivider()
            if (state.tabs.isEmpty()) {
                EmptyHint(state)
            } else {
                Results(state, pagerState, vm)
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            onDismiss = { showPicker = false },
            onPick = {
                vm.addApp(it)
                showPicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToggleBar(state: UiState, vm: MainViewModel, onOther: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.apis.forEach { a ->
            FilterChip(
                selected = a.enabled,
                onClick = { vm.toggleApi(a.provider) },
                label = { Text(if (a.ready) a.provider.displayName else "${a.provider.displayName} (set up)") },
            )
        }
        state.apps.forEach { app ->
            FilterChip(
                selected = app.enabled,
                onClick = { vm.toggleApp(app.id) },
                label = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Opens the app",
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
        AssistChip(
            onClick = onOther,
            label = { Text("Other +") },
            leadingIcon = {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
            },
        )
    }
}

@Composable
private fun EmptyHint(state: UiState) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Select AIs above, type once below, and each answer appears in its own tab named after the model.",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (state.apis.none { it.ready }) {
                Text(
                    "To get answers here, add API keys and choose models in Settings (gear icon). " +
                        "Use Other + for any installed AI app; it opens with your prompt filled in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Results(state: UiState, pagerState: PagerState, vm: MainViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selected = pagerState.currentPage.coerceIn(0, (state.tabs.size - 1).coerceAtLeast(0))

    Column(Modifier.fillMaxSize()) {
        Text(
            "“${state.lastPrompt}”",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
            state.tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = index == selected,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(tabLabel(tab), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (val tab = state.tabs.getOrNull(page)) {
                is ApiTab -> ApiTabContent(tab)
                is AppTab -> AppTabContent(tab) {
                    if (AppLauncher.open(context, tab.app, state.lastPrompt)) {
                        vm.markOpened(tab.app.id)
                    } else {
                        Toast.makeText(context, "Couldn't open ${tab.title}. Is it still installed?", Toast.LENGTH_LONG).show()
                    }
                }
                null -> Unit
            }
        }
    }
}

private fun tabLabel(tab: ResultTab): String = when (tab) {
    is ApiTab -> when (tab.status) {
        Status.Loading -> "${tab.title} …"
        is Status.Failed -> "${tab.title} ⚠"
        is Status.Done -> tab.title
    }
    is AppTab -> "${tab.title} ↗"
}

@Composable
private fun ApiTabContent(tab: ApiTab) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        when (val status = tab.status) {
            Status.Loading -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text("Waiting for ${tab.provider.displayName}…")
            }
            is Status.Done -> SelectionContainer(Modifier.fillMaxSize()) {
                Text(
                    status.text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
            is Status.Failed -> Text(
                status.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun AppTabContent(tab: AppTab, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (tab.opened) {
                "Your prompt was sent to ${tab.title}. Its answer is in that app."
            } else {
                "${tab.title} hasn't been opened yet. Android shows one app at a time, so open it here when you're ready."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Your prompt is also on the clipboard, in case ${tab.title} didn't fill it in.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpen) {
            Text(if (tab.opened) "Open ${tab.title} again" else "Open ${tab.title}")
        }
    }
}

@Composable
private fun AppPickerDialog(onDismiss: () -> Unit, onPick: (ShareTarget) -> Unit) {
    val context = LocalContext.current
    var targets by remember { mutableStateOf<List<ShareTarget>?>(null) }
    LaunchedEffect(Unit) {
        targets = withContext(Dispatchers.Default) { AppLauncher.queryShareTargets(context, withIcons = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add an installed AI app") },
        text = {
            val list = targets
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("No installed apps accept shared text.")
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list) { target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(target) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val icon = target.icon
                            if (icon != null) {
                                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(32.dp))
                            } else {
                                Spacer(Modifier.size(32.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(target.label)
                        }
                    }
                }
            }
        },
    )
}
