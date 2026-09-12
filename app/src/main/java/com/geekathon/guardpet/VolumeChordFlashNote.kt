package com.geekathon.guardpet

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Simultaneous VOLUME_UP + VOLUME_DOWN opens flash note.
 * Hold both past [LONG_PRESS_MS] → start recording; release both → stop.
 *
 * All volume key events are consumed so DOWN/UP stay balanced (avoids stuck
 * system key state). Solo volume is re-applied via AudioManager after a short
 * chord-detect window — so a real chord never shows the system volume panel.
 *
 * Key-repeat events often stop arriving once we filter keys, so solo long-press
 * uses our own Handler interval instead of KeyEvent.repeatCount.
 */
object VolumeChordFlashNote {
    private const val CHORD_DETECT_MS = 160L
    private const val LONG_PRESS_MS = 450L
    /** Roughly matches framework volume-key repeat cadence. */
    private const val VOLUME_REPEAT_MS = 85L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var volumeUpDown = false
    private var volumeDownDown = false
    private var chordActive = false
    private var longPressFired = false
    private var chordStartedAt = 0L
    /** Solo key confirmed after detect window — keep adjusting while held. */
    private var singleVolumeActive = false
    private var repeatRaise: Boolean? = null

    private val longPressRunnable = Runnable {
        if (!chordActive || !volumeUpDown || !volumeDownDown || longPressFired) return@Runnable
        longPressFired = true
        appContext?.let { FlashNoteHud.beginVolumeHoldRecord(it) }
    }

    private val confirmSingleRunnable = Runnable {
        if (chordActive) return@Runnable
        val raise = when {
            volumeUpDown && !volumeDownDown -> true
            volumeDownDown && !volumeUpDown -> false
            else -> return@Runnable
        }
        singleVolumeActive = true
        appContext?.let { applyVolume(it, raise = raise, showUi = true) }
        startVolumeRepeat(raise)
    }

    private val volumeRepeatRunnable = object : Runnable {
        override fun run() {
            if (chordActive || !singleVolumeActive) return
            val raise = repeatRaise ?: return
            val stillHeld = if (raise) {
                volumeUpDown && !volumeDownDown
            } else {
                volumeDownDown && !volumeUpDown
            }
            if (!stillHeld) {
                stopVolumeRepeat()
                return
            }
            appContext?.let { applyVolume(it, raise = raise, showUi = true) }
            mainHandler.postDelayed(this, VOLUME_REPEAT_MS)
        }
    }

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    fun onKeyEvent(context: Context, event: KeyEvent): Boolean {
        appContext = context.applicationContext
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        // Always consume volume keys once we see them — never pass a partial stream.
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Framework repeats are unreliable after filter; we drive our own loop.
                if (event.repeatCount > 0) return true

                if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                    volumeUpDown = true
                } else {
                    volumeDownDown = true
                }

                if (volumeUpDown && volumeDownDown) {
                    enterChord()
                    return true
                }

                // First key alone: wait briefly for the other (no system volume UI yet).
                stopVolumeRepeat()
                singleVolumeActive = false
                mainHandler.removeCallbacks(confirmSingleRunnable)
                mainHandler.postDelayed(confirmSingleRunnable, CHORD_DETECT_MS)
                return true
            }

            KeyEvent.ACTION_UP -> {
                val wasChord = chordActive
                val otherDown = if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                    volumeDownDown
                } else {
                    volumeUpDown
                }
                val raiseReleased = code == KeyEvent.KEYCODE_VOLUME_UP

                if (code == KeyEvent.KEYCODE_VOLUME_UP) {
                    volumeUpDown = false
                } else {
                    volumeDownDown = false
                }

                if (wasChord) {
                    if (!volumeUpDown && !volumeDownDown) {
                        mainHandler.removeCallbacks(longPressRunnable)
                        finishChord(context.applicationContext)
                        chordActive = false
                    }
                    return true
                }

                mainHandler.removeCallbacks(confirmSingleRunnable)
                if (singleVolumeActive) {
                    stopVolumeRepeat()
                    if (!volumeUpDown && !volumeDownDown) {
                        singleVolumeActive = false
                    }
                } else if (!otherDown) {
                    // Quick solo tap before detect timeout — apply once, show UI.
                    applyVolume(context, raise = raiseReleased, showUi = true)
                }
                return true
            }
        }
        return true
    }

    fun reset() {
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(confirmSingleRunnable)
        stopVolumeRepeat()
        volumeUpDown = false
        volumeDownDown = false
        chordActive = false
        longPressFired = false
        chordStartedAt = 0L
        singleVolumeActive = false
    }

    /** True while both volume keys are still held in an active chord. */
    fun isChordHolding(): Boolean =
        chordActive && volumeUpDown && volumeDownDown

    private fun enterChord() {
        mainHandler.removeCallbacks(confirmSingleRunnable)
        stopVolumeRepeat()
        singleVolumeActive = false
        if (!chordActive) {
            chordActive = true
            longPressFired = false
            chordStartedAt = SystemClock.uptimeMillis()
            mainHandler.removeCallbacks(longPressRunnable)
            mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
        }
    }

    private fun finishChord(context: Context) {
        if (longPressFired) {
            FlashNoteHud.endVolumeHoldRecord(context)
        } else {
            if (SystemClock.uptimeMillis() - chordStartedAt < 40L) return
            FlashNoteHud.startCapture(context, source = "volume_chord")
        }
        longPressFired = false
    }

    private fun startVolumeRepeat(raise: Boolean) {
        stopVolumeRepeat()
        repeatRaise = raise
        mainHandler.postDelayed(volumeRepeatRunnable, VOLUME_REPEAT_MS)
    }

    private fun stopVolumeRepeat() {
        mainHandler.removeCallbacks(volumeRepeatRunnable)
        repeatRaise = null
    }

    private fun applyVolume(context: Context, raise: Boolean, showUi: Boolean) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val direction =
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val flags = if (showUi) AudioManager.FLAG_SHOW_UI else 0
        runCatching {
            am.adjustSuggestedStreamVolume(
                direction,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                flags
            )
        }
    }
}
