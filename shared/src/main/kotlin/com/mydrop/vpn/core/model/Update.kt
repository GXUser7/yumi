package com.mydrop.vpn.core.model

import java.io.File

/** One published release, reduced to what an update needs to know about it. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    /** Zero when the source did not say — the fallback lookup has no size to give. */
    val sizeBytes: Long,
)

/**
 * Where the app is in the business of updating itself.
 *
 * A sealed set rather than a bag of nullable fields, because the screen has to draw exactly one
 * thing at a time and "downloading, and also an error, and also up to date" is not a state the app
 * can be in.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** Checked, and this is already the newest release. */
    data class UpToDate(val version: String) : UpdateState

    data class Available(val release: Release) : UpdateState

    /** [total] is zero when the server did not say how big the file is. */
    data class Downloading(val release: Release, val downloaded: Long, val total: Long) : UpdateState

    /** Downloaded whole; the installer is the next tap, and it is the user who makes it. */
    data class Ready(val release: Release, val file: File) : UpdateState

    data class Failed(val message: String) : UpdateState
}
