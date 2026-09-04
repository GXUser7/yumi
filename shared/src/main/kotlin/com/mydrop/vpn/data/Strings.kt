package com.mydrop.vpn.data

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.mydrop.vpn.core.model.AppLanguage
import java.util.Locale

/**
 * Resolves string resources against the language the user chose, for the places that have no
 * composition to read `LocalContext` from: the VPN service, the quick settings tile, the boot
 * receiver and everything under `data/`.
 *
 * Compose screens do not need this — `stringResource` already reads the activity's context, which
 * [com.mydrop.vpn.MainActivity] wraps in the same locale. This exists because a notification, a
 * tile subtitle and a journal line are written far away from any activity, and were the last
 * things left speaking whatever language the phone happens to be set to.
 *
 * The wrapped context is cached: a status update writes a journal line every second, and building
 * a `Configuration` for each of them would be a waste. The cache is keyed by the tag, so changing
 * the language invalidates it on the next call.
 */
class Strings(context: Context, private val language: () -> AppLanguage) {

    private val base = context.applicationContext
    private val lock = Any()

    private var cachedTag: String? = null
    private var cached: Context? = null

    /**
     * A context whose resources answer in the chosen language.
     *
     * [AppLanguage.System] returns the base context untouched, so Android's own resolution decides
     * — values-ru on a Russian phone, the default English everywhere else.
     */
    fun context(): Context {
        val tag = language().tag ?: return base
        synchronized(lock) {
            cached?.let { if (cachedTag == tag) return it }
            val configuration = Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(tag)))
            }
            return base.createConfigurationContext(configuration).also {
                cachedTag = tag
                cached = it
            }
        }
    }

    /**
     * The empty case is separated because `getString(id, *args)` runs the result through
     * `String.format` whatever it is handed, and a string that legitimately contains a `%` would
     * throw rather than be returned as written.
     */
    fun get(@StringRes id: Int, vararg formatArgs: Any): String =
        if (formatArgs.isEmpty()) context().getString(id) else context().getString(id, *formatArgs)

    /** The count is passed twice on purpose: once to pick the form, once to fill `%1$d`. */
    fun plural(@PluralsRes id: Int, count: Int): String =
        context().resources.getQuantityString(id, count, count)
}
