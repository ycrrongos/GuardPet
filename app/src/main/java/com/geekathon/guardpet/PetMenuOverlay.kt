package com.geekathon.guardpet

import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.geekathon.guardpet.databinding.OverlayPetMenuBinding

/** 桌宠旁功能列表。展开/收回用闪记同款：错开入场 + Overshoot，收回用 Accelerate。 */
class PetMenuOverlay(
    private val service: PetService,
    private val windowManager: WindowManager,
    private val anchorX: Int,
    private val anchorY: Int,
    private val anchorWidth: Int,
    private val anchorHeight: Int
) {
    private val binding = OverlayPetMenuBinding.inflate(
        LayoutInflater.from(ContextThemeWrapper(service, R.style.Theme_DesktopPet_Overlay))
    )
    private var attached = false
    private var closing = false

    fun show() {
        if (attached) return
        val metrics = service.resources.displayMetrics
        val width = (126 * metrics.density).toInt()
        val height = WindowManager.LayoutParams.WRAP_CONTENT
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (anchorX + anchorWidth + (6 * metrics.density).toInt())
                .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
            y = anchorY.coerceIn(0, (metrics.heightPixels - (210 * metrics.density).toInt()).coerceAtLeast(0))
        }
        binding.extractTextButton.setOnClickListener {
            dismiss()
            service.extractText()
        }
        binding.flashNoteButton.setOnClickListener {
            dismiss()
            service.openFlashNote()
        }
        binding.flashNoteListButton.setOnClickListener {
            dismiss()
            service.openFlashNoteList()
        }
        binding.scheduleButton.setOnClickListener {
            dismiss()
            service.openSchedulePage()
        }
        binding.focusButton.setOnClickListener {
            dismiss()
            service.openFocusTimer()
        }
        binding.panelButton.setOnClickListener {
            dismiss()
            service.openControlPanel()
        }
        binding.closeMenuButton.setOnClickListener { dismiss() }
        binding.menuCard.clipToOutline = false
        binding.menuColumn.clipChildren = false
        windowManager.addView(binding.root, params)
        attached = true
        binding.root.post { playEntrance() }
    }

    fun close() = dismiss()

    /** 服务销毁时立刻摘掉，不等动画。 */
    fun closeNow() {
        menuItems().forEach { it.animate().cancel() }
        binding.menuCard.animate().cancel()
        removeNow()
    }

    private fun dismiss() {
        if (!attached || closing) return
        closing = true
        val items = menuItems()
        if (items.isEmpty()) {
            removeNow()
            return
        }
        val density = service.resources.displayMetrics.density
        val outX = -ENTRANCE_FROM_DP * density
        val lastIndex = items.lastIndex
        binding.menuCard.pivotX = 0f
        binding.menuCard.animate()
            .translationX(outX)
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(EXIT_DURATION_MS + lastIndex * ENTRANCE_STAGGER_MS)
            .setInterpolator(AccelerateInterpolator())
            .start()
        items.forEachIndexed { index, view ->
            val delay = (lastIndex - index) * ENTRANCE_STAGGER_MS
            view.animate().cancel()
            view.animate()
                .translationY(-10f * density)
                .alpha(0f)
                .setStartDelay(delay)
                .setDuration(EXIT_DURATION_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    if (index == 0) removeNow()
                }
                .start()
        }
    }

    private fun playEntrance() {
        if (!attached) return
        val density = service.resources.displayMetrics.density
        val fromX = -ENTRANCE_FROM_DP * density
        val card = binding.menuCard
        card.pivotX = 0f
        card.pivotY = card.height / 2f
        card.translationX = fromX
        card.alpha = 0f
        card.scaleX = 0.92f
        card.scaleY = 0.92f
        card.animate()
            .translationX(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(ENTRANCE_DURATION_MS)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()
        menuItems().forEachIndexed { index, view ->
            view.translationY = 12f * density
            view.alpha = 0f
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(index * ENTRANCE_STAGGER_MS)
                .setDuration(PANEL_EXPAND_MS)
                .setInterpolator(OvershootInterpolator(1.08f))
                .start()
        }
    }

    private fun menuItems(): List<View> =
        List(binding.menuColumn.childCount) { binding.menuColumn.getChildAt(it) }

    private fun removeNow() {
        if (!attached) return
        attached = false
        closing = true
        runCatching { windowManager.removeView(binding.root) }
    }

    companion object {
        private const val ENTRANCE_FROM_DP = 56f
        private const val ENTRANCE_STAGGER_MS = 48L
        private const val ENTRANCE_DURATION_MS = 360L
        private const val EXIT_DURATION_MS = 240L
        private const val PANEL_EXPAND_MS = 280L
    }
}
