package com.geekathon.guardpet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class FlashNotePlayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        shrinkToPixel()
        showing = this
        startFromIntent(intent)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startFromIntent(intent)
    }

    override fun onDestroy() {
        if (showing === this) showing = null
        if (FlashNotePlayer.state != FlashNotePlayer.State.IDLE) {
            FlashNotePlayer.stop()
        }
        super.onDestroy()
    }

    private fun shrinkToPixel() {
        window.setLayout(1, 1)
        val params = window.attributes
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        params.width = 1
        params.height = 1
        params.flags = params.flags or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        window.attributes = params
    }

    private fun startFromIntent(intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        if (path.isBlank()) {
            finish()
            return
        }
        FlashNotePlayer.startTrack(this, path, id) { failed ->
            if (failed) finish()
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_ID = "id"

        @Volatile
        private var showing: FlashNotePlayActivity? = null

        fun intent(context: Context, path: String, id: Long): Intent =
            Intent(context, FlashNotePlayActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_ID, id)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )

        fun finishIfShowing() {
            showing?.let { activity ->
                showing = null
                if (!activity.isFinishing) activity.finish()
            }
        }
    }
}
