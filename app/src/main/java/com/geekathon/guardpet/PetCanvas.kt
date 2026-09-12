package com.geekathon.guardpet

import android.content.Context
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import kotlin.math.absoluteValue

/** Fixed-size canvas. The child never changes the overlay window dimensions. */
class PetCanvas @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val imageView = ImageView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        adjustViewBounds = false
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        addView(imageView)
    }

    fun show(file: File?) {
        imageView.clearAnimation()
        val drawable = file?.let { decode(it) }
        imageView.setImageDrawable(drawable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }
    }

    /** The home preview fills its allotted space without changing desktop-pet scaling. */
    fun fitPreviewToCanvas() {
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.scaleX = 1f
        imageView.scaleY = 1f
    }

    /** Scales only the image layer; the PetCanvas/window dimensions stay fixed. */
    fun setVisualScale(scale: Float, alpha: Float = 1f) {
        // Keep the canvas fixed while allowing the image to grow beyond its default size.
        imageView.scaleX = scale.coerceIn(0.1f, 2.4f)
        imageView.scaleY = scale.coerceIn(0.1f, 2.4f)
        imageView.alpha = alpha.coerceIn(0.15f, 1f)
    }

    fun setFacingRight(facingRight: Boolean) {
        imageView.scaleX = if (facingRight) imageView.scaleX.absoluteValue else -imageView.scaleX.absoluteValue
    }

    private fun decode(file: File): Drawable? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
        } else {
            Drawable.createFromPath(file.absolutePath)
        }
    }.getOrNull()
}
