package com.mydrop.vpn.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.core.model.Release
import com.mydrop.vpn.core.model.UpdateState
import com.mydrop.vpn.core.model.Version
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Checking for, fetching and handing over a new version of the app.
 *
 * The app is distributed outside any store, which means nothing updates it unless it updates
 * itself. Until now that meant reading a Telegram channel and remembering — so the people most
 * likely to be running an old build are the ones who most need a fix, and the release that
 * repaired a broken failover shipped to a user still living with it.
 *
 * What this does not do is install anything by itself. The last step is Android's own package
 * installer, with its own confirmation, from a file the user can see the size and name of. An app
 * that could silently replace its own code would be a worse thing to run than an out-of-date one.
 */
class UpdateRepository private constructor(
    private val context: Context?,
    private val filesDir: File,
    private val service: UpdateClient,
    private val settings: SettingsRepository,
    private val logs: LogRepository,
    private val onUpdateAlert: (String) -> Unit,
    private val strings: Strings,
    private val currentVersion: String,
    private val scope: CoroutineScope,
    private val abiProvider: () -> String,
    private val ioDispatcher: CoroutineDispatcher,
) {

    constructor(
        context: Context,
        service: UpdateService,
        settings: SettingsRepository,
        logs: LogRepository,
        alerts: AlertNotifier,
        strings: Strings,
        currentVersion: String,
        scope: CoroutineScope,
    ) : this(
        context = context,
        filesDir = context.filesDir,
        service = service,
        settings = settings,
        logs = logs,
        onUpdateAlert = alerts::updateAvailable,
        strings = strings,
        currentVersion = currentVersion,
        scope = scope,
        abiProvider = ::deviceAbi,
        ioDispatcher = Dispatchers.IO,
    )

    internal constructor(
        filesDir: File,
        service: UpdateClient,
        settings: SettingsRepository,
        logs: LogRepository,
        strings: Strings,
        currentVersion: String,
        scope: CoroutineScope,
        onUpdateAlert: (String) -> Unit = {},
        abiProvider: () -> String = { "arm64-v8a" },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        context = null,
        filesDir = filesDir,
        service = service,
        settings = settings,
        logs = logs,
        onUpdateAlert = onUpdateAlert,
        strings = strings,
        currentVersion = currentVersion,
        scope = scope,
        abiProvider = abiProvider,
        ioDispatcher = ioDispatcher,
    )

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var working: Job? = null

    /**
     * @param manual true when a person pressed the button. The difference is entirely in what is
     *   said afterwards: an automatic check that finds nothing must leave no trace, and a manual
     *   one that finds nothing has to say so or the button looks broken.
     */
    fun check(manual: Boolean) {
        if (working?.isActive == true) return
        working = scope.launch {
            _state.value = UpdateState.Checking
            settings.update { it.copy(lastUpdateCheckEpochMillis = System.currentTimeMillis()) }

            val result = withContext(ioDispatcher) {
                runCatching { service.latest(abiProvider()) }
                    .onFailure { if (it is CancellationException) throw it }
            }
            result
                .onSuccess { release ->
                    if (Version.isNewer(release.version, currentVersion)) {
                        logs.info(R.string.log_update_available, release.version)
                        // Only ever from a check the user did not ask for. Telling somebody who
                        // just pressed "check" that there is an update, in a notification, over
                        // the screen already showing it, is noise.
                        if (!manual) onUpdateAlert(release.version)
                        _state.value = UpdateState.Available(release)
                    } else {
                        if (manual) logs.info(R.string.log_update_none, currentVersion)
                        _state.value = UpdateState.UpToDate(currentVersion)
                    }
                }
                .onFailure { error ->
                    val message = error.message ?: strings.get(R.string.error_update_unreadable)
                    // Quietly for the scheduler: GitHub being unreachable for one tick is not
                    // something to write into the user's journal every twelve hours.
                    if (manual) logs.warn(R.string.log_update_failed, message)
                    _state.value = if (manual) UpdateState.Failed(message) else UpdateState.Idle
                }
        }
    }

    fun download() {
        val known = (_state.value as? UpdateState.Available)?.release ?: return
        if (working?.isActive == true) return
        working = scope.launch {
            _state.value = UpdateState.Downloading(known, 0, known.sizeBytes)
            val currentAbi = abiProvider()
            var active = known
            val result = withContext(ioDispatcher) {
                // Hidden re-check: if a newer release shipped since the user (or 12h scheduler)
                // last checked, silently switch to it; on failure or stale CDN, keep [known].
                val fresh = runCatching { service.latest(currentAbi) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                if (fresh != null) {
                    settings.update { it.copy(lastUpdateCheckEpochMillis = System.currentTimeMillis()) }
                    if (Version.isNewer(fresh.version, known.version)) {
                        active = fresh
                        logs.info(R.string.log_update_available, active.version)
                        _state.value = UpdateState.Downloading(active, 0, active.sizeBytes)
                    }
                }
                val target = File(File(filesDir, DIRECTORY), fileNameFor(active, currentAbi))
                runCatching {
                    // Anything left from a previous version is dead weight: the installer has
                    // already had it, or the user walked away from it.
                    target.parentFile?.listFiles()?.forEach { if (it != target) it.delete() }
                    service.download(active, target) { done, total ->
                        _state.value = UpdateState.Downloading(active, done, total)
                    }
                }.onFailure { if (it is CancellationException) throw it }
            }
            result
                .onSuccess {
                    logs.info(R.string.log_update_downloaded, active.version)
                    _state.value = UpdateState.Ready(active, it)
                }
                .onFailure { error ->
                    val message = error.message ?: strings.get(R.string.error_update_unreadable)
                    logs.warn(R.string.log_update_failed, message)
                    _state.value = UpdateState.Failed(message)
                }
        }
    }

    /** Dismisses whatever the last check said, so the row goes back to offering another one. */
    fun clear() {
        if (working?.isActive == true) return
        _state.value = UpdateState.Idle
    }

    /**
     * What is wrong with the downloaded file, or null when nothing is.
     *
     * Three questions, cheapest first: is it an APK at all, is it *this* app, and is it signed by
     * the key the running copy was signed with. The last is what Android will check anyway; asking
     * here turns a silent refusal into a sentence.
     */
    private fun verify(file: File): String? {
        val appContext = context ?: return null
        val flags = signatureFlags()
        val candidate = runCatching {
            appContext.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }.getOrNull() ?: return strings.get(R.string.error_update_not_an_apk)

        if (candidate.packageName != appContext.packageName) {
            return strings.get(R.string.error_update_wrong_package, candidate.packageName.orEmpty())
        }

        val installed = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, flags)
        }.getOrNull() ?: return null

        val ours = signersOf(installed)
        val theirs = signersOf(candidate)
        // Null on either side means the platform would not tell us, which is not evidence of a
        // mismatch — and refusing on "do not know" would block every update on a phone that
        // answers differently.
        if (ours.isNullOrEmpty() || theirs.isNullOrEmpty()) return null
        if (ours != theirs) return strings.get(R.string.error_update_wrong_signature)
        return null
    }

    /**
     * Which question to ask the package manager about signatures, by platform version.
     *
     * `GET_SIGNING_CERTIFICATES` and the `signingInfo` it fills in arrived in Android 9; this app
     * runs from Android 8. Asking the newer question on an older phone does not degrade — the
     * field is simply not on the class there, and reading it throws `NoSuchFieldError` at the
     * moment somebody tries to install an update, which is the worst possible moment for it.
     */
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /**
     * The certificates a package is signed with, or null when the platform did not say.
     *
     * The pre-9 answer is the deprecated `signatures` array, and deprecated is the right word for
     * it rather than wrong: it reports the signers of the APK the same way, and its replacement
     * exists to describe key *rotation*, which an app distributed as a file outside a store has no
     * way to perform anyway.
     */
    private fun signersOf(info: PackageInfo): Set<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures?.mapNotNull { it?.toCharsString() }?.toSet()
    }

    /**
     * Hands the downloaded file to Android's package installer.
     *
     * On Android 8 and later an app may only do this once the user has allowed it to install
     * packages, and that permission lives in a settings screen rather than a dialog we can raise.
     * So when it is missing this opens that screen instead — refusing silently would leave a
     * button that does nothing, which is the one outcome worth avoiding.
     *
     * @return false when the user was sent to settings rather than to the installer.
     */
    fun install(activity: Context): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        val appContext = context ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${appContext.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }

        // Read before it is handed over.
        //
        // Android refuses an update signed by a different key, so this is not the last line of
        // defence — but it is the only one that can say *why*. Without it a truncated download or a
        // file from the wrong release reaches the system installer and comes back as
        // "App not installed", a sentence that names nothing and sends the user to reinstall by
        // hand — which on this app means losing their servers.
        verify(ready.file)?.let { reason ->
            logs.error(R.string.log_update_rejected, reason)
            _state.value = UpdateState.Failed(strings.get(R.string.error_update_rejected, reason))
            return false
        }

        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", ready.file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        return true
    }

    private fun fileNameFor(release: Release, abi: String) = "yumi-${release.version}-$abi.apk"

    private companion object {
        const val DIRECTORY = "updates"

        /**
         * The architecture to fetch a build for.
         *
         * The first entry, not a search: `SUPPORTED_ABIS` is ordered best-first, and an arm64 phone
         * lists the 32-bit ABI too. Taking anything but the first would install the slower build on
         * every modern phone.
         */
        private fun deviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    }
}
