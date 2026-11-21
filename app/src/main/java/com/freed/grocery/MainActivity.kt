package com.freed.grocery

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Duration
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GroceryTheme {
                val vm: GroceryViewModel = viewModel()
                val state by vm.state.collectAsState()
                GroceryApp(state, onRefresh = vm::refresh, onToggleStore = vm::toggleStore)
            }
        }
    }
}

@Composable
fun GroceryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        // Follow the phone's wallpaper colours on Android 12+ (Pixel 6 included).
        Build.VERSION.SDK_INT >= 31 -> LocalContext.current.let {
            if (dark) dynamicDarkColorScheme(it) else dynamicLightColorScheme(it)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Tab(val label: String, val glyph: String) {
    Staples("Staples", "🛒"), Deals("Deals", "🏷️"), History("History", "📈")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroceryApp(state: UiState, onRefresh: () -> Unit, onToggleStore: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Staples) }
    var historyItem by rememberSaveable { mutableStateOf<String?>(null) }
    var showStores by rememberSaveable { mutableStateOf(false) }
    val snap = state.snapshot

    BackHandler(enabled = tab != Tab.Staples || historyItem != null) {
        if (tab == Tab.History && historyItem != null) historyItem = null else tab = Tab.Staples
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Sacramento Grocery Prices", style = MaterialTheme.typography.titleMedium)
                        Text(statusLine(state), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (snap != null) TextButton(onClick = { showStores = true }) { Text("Stores") }
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.padding(horizontal = 14.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = onRefresh) { Text("Refresh") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Text(t.glyph) }, label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            state.error?.let { ErrorBanner(it, hasData = snap != null) }
            if (snap == null) {
                EmptyState(state)
            } else {
                val visible = snap.stores.map { it.id }.filter { it !in state.hiddenStores }.toSet()
                when (tab) {
                    Tab.Staples -> StaplesScreen(snap, visible) { historyItem = it; tab = Tab.History }
                    Tab.Deals -> DealsScreen(snap, visible)
                    Tab.History -> HistoryScreen(snap, state.history, visible, historyItem) { historyItem = it }
                }
            }
        }
    }

    if (showStores && snap != null) {
        AlertDialog(
            onDismissRequest = { showStores = false },
            confirmButton = { TextButton(onClick = { showStores = false }) { Text("Done") } },
            title = { Text("Stores to compare") },
            text = {
                Column {
                    snap.stores.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = s.id !in state.hiddenStores, onCheckedChange = { onToggleStore(s.id) })
                            Column {
                                Text(s.name)
                                Text(
                                    when {
                                        !s.hasAds -> "No ad prices available"
                                        s.adItems == 0 -> "No grocery staples in this week's ad"
                                        else -> "${s.adItems} ad prices, through ${Fmt.day(s.adTo ?: "")}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

/** Days since the price file was built, or null if the timestamp can't be read. */
fun ageInDays(updated: String, now: Instant = Instant.now()): Long? =
    runCatching { Duration.between(Instant.parse(updated), now).toDays() }.getOrNull()

private fun statusLine(state: UiState): String {
    val snap = state.snapshot ?: return if (state.loading) "Loading prices…" else "No prices yet"
    val day = Fmt.day(snap.updated.take(10))
    val age = ageInDays(snap.updated)
    return when {
        age != null && age >= 2 -> "Prices are $age days old ($day)"
        else -> "Updated $day · ${snap.area}"
    }
}

@Composable
private fun ErrorBanner(message: String, hasData: Boolean) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            (if (hasData) "Couldn't refresh, showing saved prices. " else "Couldn't load prices. ") + message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun EmptyState(state: UiState) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        if (state.loading) CircularProgressIndicator()
        else Text(
            "No prices downloaded yet. Check your connection and tap Refresh.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
