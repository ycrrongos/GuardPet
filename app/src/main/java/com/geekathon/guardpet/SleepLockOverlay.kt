package com.geekathon.guardpet

import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import com.geekathon.guardpet.databinding.OverlaySleepLockBinding

class SleepLockOverlay(
    private val service: PetService,
    private val windowManager: WindowManager
) {
    private val binding = OverlaySleepLockBinding.inflate(LayoutInflater.from(service))
    private var attached = false

    fun show() {
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        binding.emergencyCallButton.setOnClickListener {
            service.startActivity(
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        windowManager.addView(binding.root, params)
        attached = true
    }

    fun close() {
        if (!attached) return
        runCatching { windowManager.removeView(binding.root) }
        attached = false
    }
}
