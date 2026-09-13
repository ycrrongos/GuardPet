package com.geekathon.guardpet

import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import com.geekathon.guardpet.databinding.OverlayPetMenuBinding

class PetMenuOverlay(
    private val service: PetService,
    private val windowManager: WindowManager,
    private val anchorX: Int,
    private val anchorY: Int,
    private val anchorWidth: Int,
    private val anchorHeight: Int
) {
    private val binding = OverlayPetMenuBinding.inflate(
        LayoutInflater.from(ContextThemeWrapper(service, R.style.Theme_DesktopPet))
    )
    private var attached = false

    fun show() {
        if (attached) return
        val metrics = service.resources.displayMetrics
        val width = (168 * metrics.density).toInt()
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
            y = anchorY.coerceIn(0, (metrics.heightPixels - (280 * metrics.density).toInt()).coerceAtLeast(0))
        }
        binding.extractTextButton.setOnClickListener {
            close()
            service.extractText()
        }
        binding.flashNoteButton.setOnClickListener {
            close()
            service.openFlashNote()
        }
        binding.flashNoteListButton.setOnClickListener {
            close()
            service.openFlashNoteList()
        }
        binding.scheduleButton.setOnClickListener {
            close()
            service.openSchedulePage()
        }
        binding.focusButton.setOnClickListener {
            close()
            service.openFocusTimer()
        }
        binding.panelButton.setOnClickListener {
            close()
            service.openControlPanel()
        }
        binding.closeMenuButton.setOnClickListener { close() }
        windowManager.addView(binding.root, params)
        attached = true
    }

    fun close() {
        if (!attached) return
        runCatching { windowManager.removeView(binding.root) }
        attached = false
    }
}
