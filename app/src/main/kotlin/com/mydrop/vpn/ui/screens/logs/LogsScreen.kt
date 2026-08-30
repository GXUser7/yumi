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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import android.widget.Toast
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.R
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
    val context = LocalContext.current
    val subject = stringResource(R.string.logs_send_subject)
    val hint = stringResource(R.string.logs_send_hint, TELEGRAM_FOR_LOGS)
    val nothingToSend = stringResource(R.string.logs_send_nothing)
    val copiedTemplate = stringResource(R.string.logs_copied)
    val nothingToCopy = stringResource(R.string.logs_nothing_to_copy)

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
            title = stringResource(R.string.logs_title),
            subtitle = stringResource(R.string.logs_subtitle, visible.size, entries.size),
            modifier = Modifier.padding(bottom = 10.dp),
            actions = {
                TonalIconButton(Icons.Rounded.ArrowBack, stringResource(R.string.action_back), onBack)
                Spacer(Modifier.width(8.dp))
                TonalIconButton(
                    Icons.Rounded.ContentCopy,
                    stringResource(R.string.action_copy_logs),
                    // What is on screen, not everything held in memory: the filter above is the
                    // reader's way of saying which lines they are interested in, and a copy that
                    // ignored it would paste a thousand trace lines around the one they wanted.
                    onClick = {
                        copyToClipboard(context, visible, copiedTemplate, nothingToCopy)
                    },
                )
                Spacer(Modifier.width(8.dp))
                TonalIconButton(
                    Icons.Rounded.Share,
                    stringResource(R.string.action_send_logs),
                    onClick = { shareJournalFile(context, subject, hint, nothingToSend) },
                )
                Spacer(Modifier.width(8.dp))
                TonalIconButton(
                    Icons.Rounded.DeleteSweep,
                    stringResource(R.string.action_clear),
                    onClear,
                )
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
                Text(stringResource(R.string.logs_empty), style = MaterialTheme.typography.titleMedium)
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

/**
 * Puts the visible log on the clipboard, in the same shape it has on screen.
 *
 * Plain text rather than anything structured, because the only thing anyone does with this is
 * paste it into a message to ask what went wrong.
 */
private fun copyToClipboard(
    context: Context,
    entries: List<LogEntry>,
    copiedTemplate: String,
    nothingToCopy: String,
) {
    if (entries.isEmpty()) {
        Toast.makeText(context, nothingToCopy, Toast.LENGTH_SHORT).show()
        return
    }

    val text = entries.joinToString("\n") { entry ->
        "${timeFormatter.format(Date(entry.timestampMillis))} ${entry.level.name.uppercase()} " +
            entry.message
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        Toast.makeText(context, nothingToCopy, Toast.LENGTH_SHORT).show()
        return
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("Yumi", text))

    // Android 13 and up shows its own confirmation for every copy, and a toast on top of it is
    // the same news twice.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, copiedTemplate.format(entries.size), Toast.LENGTH_SHORT).show()
    }
}

/**
 * Hands the journal file to whatever the user picks to send it with.
 *
 * The file itself rather than the text on screen, and deliberately: the on-screen journal is the
 * short human-readable one, while the file underneath carries the probe-by-probe trace and the
 * core's own output — which is the half that answers "what happened at four in the morning". It is
 * also fifty megabytes at its largest, so it is passed by reference through a FileProvider and
 * never read into memory here.
 *
 * The destination is put in the message text rather than the chooser, because a plain share intent
 * cannot pre-select a chat. The reader picks the app; the text says where it goes.
 */
private fun shareJournalFile(
    context: Context,
    subject: String,
    hint: String,
    nothingToSend: String,
) {
    val file = File(File(context.filesDir, "diagnostics"), "yumi.log")
    if (!file.isFile || file.length() == 0L) {
        Toast.makeText(context, nothingToSend, Toast.LENGTH_LONG).show()
        return
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
    }.getOrNull() ?: run {
        Toast.makeText(context, nothingToSend, Toast.LENGTH_LONG).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, hint)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, subject)) }
}

/** Where the journal is asked to be sent. Named here so it is one edit, not a search. */
private const val TELEGRAM_FOR_LOGS = "https://t.me/BBnov22"
