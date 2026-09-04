package com.mydrop.vpn.vpn

import android.os.ParcelFileDescriptor
import android.system.Os
import com.mydrop.vpn.shared.R
import com.mydrop.vpn.data.LogRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Routes the Go runtime's stderr into logcat and the in-app journal, once per process.
 *
 * When the core hits a fatal error it prints the panic and goroutine dump to file descriptor 2 and
 * then raises SIGABRT. Android discards native stderr, so all that survives is a tombstone whose
 * only frame is `runtime.raise` — the place the process was killed, never the place it broke.
 * Without this, every core-level abort looks identical and says nothing.
 *
 * **Why this is an object and not a method on the service.** `dup2` on fd 2 is a change to the
 * process, permanent and global; the reader that drains the other end of that pipe has to live
 * exactly as long. It used to be launched in the service's own scope, which `onDestroy` cancels.
 * The flag guarding the setup was process-wide while the reader it started was not, so after the
 * first service instance died no new reader was ever created — leaving fd 2 pointed at a pipe
 * whose reading end nobody owns. What that costs depends on whether the cancelled coroutine's
 * blocking read holds the descriptor open or lets it close: at best a thread left behind for the
 * life of the process, at worst a `SIGPIPE` the next time the Go runtime prints a warning, killing
 * the app outright and looking exactly like the crash this code exists to explain.
 *
 * So the scope is the process's, the flag and the scope agree about what they cover, and the
 * question stops being interesting.
 */
object NativeStderrCapture {

    private val installed = AtomicBoolean(false)

    /** Never cancelled: fd 2 stays redirected for the life of the process, so the reader must too. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Safe to call from every tunnel start; only the first one does anything. */
    fun install(logs: LogRepository) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            val pipe = ParcelFileDescriptor.createPipe()
            Os.dup2(pipe[1].fileDescriptor, 2)
            pipe[1].close()

            scope.launch {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).bufferedReader()
                    .forEachLine { line ->
                        android.util.Log.e(TAG, line)
                        runCatching { logs.error(R.string.log_core_line, line) }
                    }
            }
        }.onFailure {
            // Losing the panic dumps is bad; failing to start the tunnel because of it is worse.
            android.util.Log.w(TAG, "stderr capture unavailable: ${it.message}")
            installed.set(false)
        }
    }

    private const val TAG = "YumiCore"
}
