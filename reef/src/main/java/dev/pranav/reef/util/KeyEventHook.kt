package dev.pranav.reef.util

import android.content.Context
import android.view.KeyEvent

/**
 * App module registers key filtering so BlockerService can handle hardware chords
 * (e.g. volume up+down → flash note) without depending on GuardPet classes.
 *
 * Return true to consume the event (not delivered to the rest of the system).
 */
object KeyEventHook {
    @Volatile
    var filter: ((Context, KeyEvent) -> Boolean)? = null

    /** Called when accessibility service disconnects; clear chord state etc. */
    @Volatile
    var onServiceDisconnected: (() -> Unit)? = null

    fun onKeyEvent(context: Context, event: KeyEvent): Boolean =
        filter?.invoke(context, event) == true

    fun notifyDisconnected() {
        onServiceDisconnected?.invoke()
    }
}
