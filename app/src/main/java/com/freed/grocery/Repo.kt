package com.freed.grocery

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UiState(
    val snapshot: Snapshot? = null,
    val history: Map<String, List<HistoryEntry>> = emptyMap(),
    val loading: Boolean = false,
    /** Set when the last refresh failed; any cached data stays on screen. */
    val error: String? = null,
    val hiddenStores: Set<String> = emptySet(),
)

class GroceryViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("grocery", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UiState(hiddenStores = prefs.getStringSet("hidden", emptySet()).orEmpty().toSet())
    )
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            // Show the last downloaded copy straight away, then fetch a fresh one.
            withContext(Dispatchers.IO) { readCache() }?.let { (snap, hist) ->
                _state.update { it.copy(snapshot = snap, history = hist) }
            }
            refresh()
        }
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val prices = download("prices.json")
                    val snap = Parser.snapshot(prices)
                    // History is secondary: a failure there must not hide today's prices.
                    val histText = runCatching { download("history.json") }.getOrNull()
                    val hist = histText?.let { runCatching { Parser.history(it) }.getOrNull() }
                    cacheFile("prices.json").writeText(prices)
                    if (hist != null) cacheFile("history.json").writeText(histText)
                    snap to hist
                }
            }
            _state.update { s ->
                result.fold(
                    onSuccess = { (snap, hist) ->
                        s.copy(snapshot = snap, history = hist ?: s.history, loading = false)
                    },
                    onFailure = { e ->
                        s.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
                    },
                )
            }
        }
    }

    fun toggleStore(id: String) {
        _state.update { s ->
            val hidden = if (id in s.hiddenStores) s.hiddenStores - id else s.hiddenStores + id
            prefs.edit().putStringSet("hidden", hidden).apply()
            s.copy(hiddenStores = hidden)
        }
    }

    private fun cacheFile(name: String) = File(getApplication<Application>().filesDir, name)

    private fun readCache(): Pair<Snapshot, Map<String, List<HistoryEntry>>>? = runCatching {
        val snap = Parser.snapshot(cacheFile("prices.json").readText())
        val hist = runCatching { Parser.history(cacheFile("history.json").readText()) }
            .getOrDefault(emptyMap())
        snap to hist
    }.getOrNull()

    private fun download(name: String): String {
        val conn = URL(BuildConfig.DATA_BASE_URL + name).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) error("Price server answered ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
