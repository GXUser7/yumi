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
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mydrop.vpn.shared.R
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
    val nothingToSend = stringResource(R.string.logs_send_nothing)
    val saveFailed = stringResource(R.string.logs_save_failed)
    val scope = rememberCoroutineScope()
    // The name the file landed under, which is also the flag that the dialog should be up.
    var savedAs by remember { mutableStateOf<String?>(null) }
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
                    Icons.Rounded.Download,
                    stringResource(R.string.action_save_logs),
                    onClick = {
                        // Off the main thread: the journal is fifty megabytes at its largest, and
                        // copying that where the frames are drawn would freeze the screen for the
                        // whole of it.
                        scope.launch {
                            val name = withContext(Dispatchers.IO) { saveJournalToDownloads(context) }
                            when (name) {
                                null -> Toast.makeText(context, nothingToSend, Toast.LENGTH_LONG).show()
                                "" -> Toast.makeText(context, saveFailed, Toast.LENGTH_LONG).show()
                                else -> savedAs = name
                            }
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                TonalIconButton(
                    Icons.Rounded.DeleteSweep,
                    stringResource(R.string.action_clear),
                    onClear,
                )
            },
        )

        savedAs?.let { name ->
            AlertDialog(
                onDismissRequest = { savedAs = null },
                title = { Text(stringResource(R.string.logs_saved_title)) },
                text = { Text(stringResource(R.string.logs_saved_text, name, TELEGRAM_FOR_LOGS)) },
                confirmButton = {
                    TextButton(onClick = {
                        savedAs = null
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_FOR_LOGS)),
                            )
                        }
                    }) { Text(stringResource(R.string.logs_saved_open_telegram)) }
                },
                dismissButton = {
                    TextButton(onClick = { savedAs = null }) {
                        Text(stringResource(R.string.action_close))
                    }
                },
            )
        }

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
 * Writes the journal into the phone's Downloads folder and answers with the name it landed under.
 *
 * The file rather than the text on screen, and deliberately: the on-screen journal is the short
 * human-readable one, while the file underneath carries the probe-by-probe trace and the core's own
 * output — the half that answers "what happened at four in the morning".
 *
 * Downloads rather than a share sheet, because a share sheet is not a reliable way to move fifty
 * megabytes: some apps refuse the size, some re-encode, and on this phone the send simply did not
 * go through. A file on disk can be attached by hand, from any app, as many times as needed.
 *
 * @return the file name on success, `null` when there is no journal to save, and an empty string
 *   when saving itself failed — three outcomes the caller reports differently.
 */
private fun saveJournalToDownloads(context: Context): String? {
    val source = File(File(context.filesDir, "diagnostics"), "yumi.log")
    if (!source.isFile || source.length() == 0L) return null

    // Named by the moment it was taken, so two of them never collide and the useful one is
    // obvious afterwards.
    val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
    val name = "yumi-$stamp.log"

    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val target: Uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching ""
            context.contentResolver.openOutputStream(target)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return@runCatching ""
        } else {
            // Before scoped storage there is no MediaStore entry to insert; the public directory
            // is written to directly, which on these versions is what the permission below allows.
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            source.copyTo(File(dir, name), overwrite = true)
        }
        name
    }.getOrDefault("")
}

/** Where the journal is asked to be sent. Named here so it is one edit, not a search. */
private const val TELEGRAM_FOR_LOGS = "https://t.me/BBnov22"
