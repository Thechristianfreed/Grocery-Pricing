package com.freed.grocery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** Fixed colour per store so chart lines and dots stay recognisable between screens. */
private val storePalette = listOf(
    Color(0xFFD32F2F), Color(0xFF1976D2), Color(0xFF388E3C), Color(0xFFF57C00), Color(0xFF7B1FA2),
    Color(0xFF00838F), Color(0xFFC2185B), Color(0xFF5D4037), Color(0xFF689F38), Color(0xFF303F9F),
    Color(0xFF455A64), Color(0xFFFBC02D),
)

private fun storeColor(snap: Snapshot, id: String): Color =
    storePalette[snap.stores.indexOfFirst { it.id == id }.coerceAtLeast(0) % storePalette.size]

@Composable
private fun StoreDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

// ---------------------------------------------------------------- Staples

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StaplesScreen(snap: Snapshot, visible: Set<String>, onHistory: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val byCategory = remember(snap) { snap.staples.groupBy { it.category } }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
        byCategory.forEach { (category, staples) ->
            stickyHeader(key = "h-$category") {
                Text(
                    category,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(staples, key = { it.id }) { staple ->
                StapleCard(snap, staple, visible, expanded == staple.id,
                    onToggle = { expanded = if (expanded == staple.id) null else staple.id },
                    onHistory = { onHistory(staple.id) })
            }
        }
        item {
            Text(
                snap.source,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StapleCard(
    snap: Snapshot, staple: Staple, visible: Set<String>, expanded: Boolean,
    onToggle: () -> Unit, onHistory: () -> Unit,
) {
    val ranked = staple.ranked(visible)
    val best = ranked.firstOrNull()
    val shown = staple.offers.filterKeys { it in visible }
    val onAd = shown.count { it.value.current }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(staple.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            onAd == 0 && shown.isEmpty() -> "No ad prices seen yet"
                            onAd == 0 -> "Not on ad this week"
                            onAd == 1 -> "On ad at 1 store"
                            else -> "On ad at $onAd stores"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (best != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            Fmt.unitPrice(best.second.unitPrice!!, staple.unit),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(snap.storeName(best.first), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    // Comparable running ads first, then running ads with no stated size, then old ads.
                    val rest = shown.toList().filter { it !in ranked }
                        .sortedWith(compareBy({ !it.second.current }, { it.second.price }))
                    (ranked + rest).forEachIndexed { i, (storeId, offer) ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        OfferRow(snap, staple, storeId, offer, isBest = i == 0 && best != null)
                    }
                    val missing = snap.stores.filter { it.id in visible && it.id !in shown.keys }
                    if (missing.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            "No ad price: " + missing.joinToString { it.name },
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onHistory, modifier = Modifier.align(Alignment.End)) {
                        Text("Price history")
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferRow(snap: Snapshot, staple: Staple, storeId: String, offer: Offer, isBest: Boolean) {
    val faded = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp)) { StoreDot(storeColor(snap, storeId)) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                snap.storeName(storeId), style = MaterialTheme.typography.titleSmall,
                color = if (offer.current) MaterialTheme.colorScheme.onSurface else faded,
            )
            Text(offer.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                if (offer.current) "through ${Fmt.day(offer.to)}" else "last advertised ${Fmt.day(offer.to)}",
                offer.note.takeIf { it.isNotBlank() },
                if (offer.more > 0) "+${offer.more} more in ad" else null,
            ).joinToString(" · ")
            Text(detail, style = MaterialTheme.typography.bodySmall, color = faded, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val up = offer.unitPrice
            Text(
                if (up != null) Fmt.unitPrice(up, staple.unit) else Fmt.shelf(offer.price, offer.per),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !offer.current -> faded
                    isBest -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                when {
                    up == null -> "size not in ad"
                    staple.unit == "each" && up == offer.price -> ""
                    else -> Fmt.shelf(offer.price, offer.per)
                },
                style = MaterialTheme.typography.bodySmall, color = faded,
            )
        }
    }
}

// ---------------------------------------------------------------- Deals

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealsScreen(snap: Snapshot, visible: Set<String>) {
    var query by rememberSaveable { mutableStateOf("") }
    var staplesOnly by rememberSaveable { mutableStateOf(true) }
    var store by rememberSaveable { mutableStateOf<String?>(null) }
    val withAds = snap.stores.filter { it.hasAds && it.id in visible && it.adItems > 0 }
    val deals = remember(snap, visible, query, staplesOnly, store) {
        snap.deals.asSequence()
            .filter { it.store in visible }
            .filter { store == null || it.store == store }
            .filter { !staplesOnly || it.staple != null }
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .sortedBy { it.price }
            .toList()
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search this week's ads") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = staplesOnly, onClick = { staplesOnly = !staplesOnly }, label = { Text("Staples only") })
            withAds.forEach { s ->
                FilterChip(selected = store == s.id, onClick = { store = if (store == s.id) null else s.id },
                    label = { Text(s.name) })
            }
        }
        Text(
            "${deals.size} advertised prices, cheapest first",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(deals) { d ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp)) { StoreDot(storeColor(snap, d.store)) }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(snap.storeName(d.store), "through ${Fmt.day(d.to)}",
                                d.note.takeIf { it.isNotBlank() }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(Fmt.shelf(d.price, d.per), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- History

@Composable
fun HistoryScreen(
    snap: Snapshot, history: Map<String, List<HistoryEntry>>, visible: Set<String>,
    selected: String?, onSelect: (String?) -> Unit,
) {
    val staple = snap.staples.firstOrNull { it.id == selected }
    if (staple == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Text(
                    "Every advertised price is saved each day. Pick an item to see how its price has moved.",
                    modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(snap.staples, key = { it.id }) { s ->
                val n = history[s.id].orEmpty().count { it.store in visible }
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(s.id) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(if (n == 1) "1 ad" else "$n ads", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        return
    }

    val entries = history[staple.id].orEmpty().filter { it.store in visible }
    val comparable = entries.filter { it.unitPrice != null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSelect(null) }) { Text("‹ All items") }
            }
            Text(staple.name, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall)
            if (comparable.isEmpty()) {
                Text(
                    "No comparable ad prices recorded for this item yet.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val low = comparable.minBy { it.unitPrice!! }
                Text(
                    "Lowest seen: ${Fmt.unitPrice(low.unitPrice!!, staple.unit)} at ${snap.storeName(low.store)}, ${Fmt.day(low.from)}",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium,
                )
                PriceChart(snap, staple, comparable)
                Legend(snap, comparable.map { it.store }.distinct())
                if (comparable.map { it.from }.distinct().size < 2) {
                    Text(
                        "Only one ad week recorded so far. The chart fills in as new weekly ads come out.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        items(entries.sortedByDescending { it.from }) { e ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 5.dp)) { StoreDot(storeColor(snap, e.store)) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${snap.storeName(e.store)} · ${Fmt.day(e.from)} to ${Fmt.day(e.to)}", style = MaterialTheme.typography.titleSmall)
                    Text(e.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    e.unitPrice?.let { Fmt.unitPrice(it, staple.unit) } ?: Fmt.shelf(e.price, e.per),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun Legend(snap: Snapshot, stores: List<String>) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        stores.forEach { id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoreDot(storeColor(snap, id))
                Spacer(Modifier.width(4.dp))
                Text(snap.storeName(id), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Lowest unit price per store per ad start date, plotted over time. */
@Composable
private fun PriceChart(snap: Snapshot, staple: Staple, entries: List<HistoryEntry>) {
    val points = remember(entries) {
        entries.mapNotNull { e ->
            runCatching { Triple(e.store, LocalDate.parse(e.from).toEpochDay(), e.unitPrice!!) }.getOrNull()
        }.groupBy { it.first }.mapValues { (_, list) ->
            list.groupBy { it.second }.map { (day, same) -> day to same.minOf { it.third } }.sortedBy { it.first }
        }
    }
    val all = points.values.flatten()
    if (all.isEmpty()) return
    val minDay = all.minOf { it.first }
    val maxDay = all.maxOf { it.first }
    val maxPrice = all.maxOf { it.second }
    val minPrice = all.minOf { it.second }
    val axis = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(Fmt.unitPrice(maxPrice, staple.unit), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val padX = 8.dp.toPx()
            val padY = 8.dp.toPx()
            val w = size.width - 2 * padX
            val h = size.height - 2 * padY
            drawLine(axis, Offset(0f, 0f), Offset(size.width, 0f))
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height))
            fun x(day: Long) = padX + if (maxDay == minDay) w / 2 else w * (day - minDay) / (maxDay - minDay)
            fun y(p: Double) = padY + if (maxPrice == minPrice) h / 2 else (h * (1 - (p - minPrice) / (maxPrice - minPrice))).toFloat()
            points.forEach { (store, series) ->
                val c = storeColor(snap, store)
                series.zipWithNext { a, b ->
                    drawLine(c, Offset(x(a.first), y(a.second)), Offset(x(b.first), y(b.second)),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
                series.forEach { (day, p) -> drawCircle(c, 4.dp.toPx(), Offset(x(day), y(p))) }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Fmt.unitPrice(minPrice, staple.unit), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (minDay == maxDay) Fmt.day(LocalDate.ofEpochDay(minDay).toString())
                else "${Fmt.day(LocalDate.ofEpochDay(minDay).toString())} to ${Fmt.day(LocalDate.ofEpochDay(maxDay).toString())}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
