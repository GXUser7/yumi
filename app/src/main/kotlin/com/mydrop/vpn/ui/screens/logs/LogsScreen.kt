package com.mydrop.vpn.ui.screens.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.core.model.LogEntry
import com.mydrop.vpn.ui.components.ScreenHeader
import com.mydrop.vpn.ui.components.TonalIconButton
import com.mydrop.vpn.ui.theme.LocalSemanticColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    entries: List<LogEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var minimumLevel by remember { mutableStateOf(LogEntry.Level.Debug) }
    val listState = rememberLazyListState()

    val visible = remember(entries, minimumLevel) {
        entries.filter { it.level.ordinal >= minimumLevel.ordinal }
    }

    // Follow the tail only while the user is already at the bottom; otherwise scrolling back
    // through history would be yanked away by every new line.
    val pinnedToBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(visible.size) {
        if (pinnedToBottom && visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
        ScreenHeader(
            title = "Журнал",
            subtitle = "${visible.size} строк из ${entries.size}",
            modifier = Modifier.padding(bottom = 10.dp),
            actions = {
                TonalIconButton(Icons.Rounded.ArrowBack, "Назад", onBack)
                Spacer(Modifier.width(8.dp))
                TonalIconButton(Icons.Rounded.DeleteSweep, "Очистить", onClear)
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogEntry.Level.entries.forEach { level ->
                FilterChip(
                    selected = minimumLevel == level,
                    onClick = { minimumLevel = level },
                    label = { Text(level.name) },
                )
            }
        }

        if (visible.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Записей нет", style = MaterialTheme.typography.titleMedium)
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(visible) { entry -> LogRow(entry) }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val semantic = LocalSemanticColors.current
    val color = when (entry.level) {
        LogEntry.Level.Trace, LogEntry.Level.Debug -> MaterialTheme.colorScheme.onSurfaceVariant
        LogEntry.Level.Info -> MaterialTheme.colorScheme.onSurface
        LogEntry.Level.Warn -> semantic.latencyMedium
        LogEntry.Level.Error -> MaterialTheme.colorScheme.error
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = timeFormatter.format(Date(entry.timestampMillis)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}

private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
