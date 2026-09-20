package com.geekathon.guardpet

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.pranav.reef.timer.OverlayFocusTimerView
import dev.pranav.reef.util.isPrefsInitialized
import kotlin.math.min

/**
 * 桌宠番茄钟：透明独立 task，外观对齐提取文字（暗幕 + 圆角窗），
 * 窗内嵌 Reef [OverlayFocusTimerView]。用 Activity 承载 Compose，避免 Service overlay 崩溃。
 */
class FocusTimerActivity : AppCompatActivity() {
    private var panel: OverlayFocusTimerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!isPrefsInitialized) {
            Toast.makeText(this, R.string.focus_not_ready, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val density = resources.displayMetrics.density
        val metrics = resources.displayMetrics
        val shortSide = min(metrics.widthPixels, metrics.heightPixels)
        val frameW = (shortSide * 0.92f).toInt()
        val frameH = min(
            (metrics.heightPixels * 0.78f).toInt(),
            (frameW * 1.35f).toInt()
        ).coerceAtLeast((360 * density).toInt())

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { finish() }
        }

        val focusView = OverlayFocusTimerView(this).apply {
            onRequestClose = { finish() }
            onStartFailed = {
                Toast.makeText(this@FocusTimerActivity, R.string.focus_not_ready, Toast.LENGTH_SHORT).show()
            }
            isClickable = true
            setOnClickListener { }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(Color.WHITE)
                setStroke(
                    (2.5f * density).toInt(),
                    ContextCompat.getColor(this@FocusTimerActivity, R.color.brand_primary)
                )
            }
            elevation = 8f * density
            clipToOutline = true
            // Activity 自身就是 Lifecycle / SavedState / BackDispatcher owner
            bindTreeOwners(this@FocusTimerActivity, this@FocusTimerActivity, this@FocusTimerActivity, this@FocusTimerActivity)
        }
        panel = focusView

        root.addView(
            focusView,
            FrameLayout.LayoutParams(frameW, frameH, Gravity.CENTER)
        )

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        setContentView(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            }
        )
    }

    override fun onDestroy() {
        panel?.release()
        panel = null
        if (PetService.isRunning) {
            startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_SHOW_PET))
        }
        super.onDestroy()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(
                Intent(context, FocusTimerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}
