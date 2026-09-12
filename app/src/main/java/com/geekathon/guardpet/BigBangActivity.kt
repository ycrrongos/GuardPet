package com.geekathon.guardpet

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.geekathon.guardpet.databinding.ActivityBigBangBinding
import java.util.concurrent.Executors

class BigBangActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBigBangBinding
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var originalTokens: List<String> = emptyList()
    private var showingAi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HabitPolicyStore.init(applicationContext)
        intent?.getStringExtra(EXTRA_SEED_TEXT)?.takeIf { it.isNotBlank() }?.let { seed ->
            TextCaptureHolder.tokens = TextTokenizer.splitAll(listOf(seed))
        }
        binding = ActivityBigBangBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindTokens(animate = true)
        binding.closeButton.setOnClickListener { finish() }
        binding.bigBangRoot.setOnClickListener { finish() }
        binding.sheet.setOnClickListener { /* keep */ }
        binding.invertButton.setOnClickListener { binding.tokenFlow.invertSelection() }
        binding.copyButton.setOnClickListener { copySelection() }
        binding.searchButton.setOnClickListener { searchSelection() }
        binding.flashNoteButton.setOnClickListener { sendFlashNote() }
        binding.restoreOriginalButton.setOnClickListener { restoreOriginal() }
        wireAiChip(binding.aiNoteChip, BigBangAiAction.NOTE)
        wireAiChip(binding.aiCleanChip, BigBangAiAction.CLEAN)
        wireAiChip(binding.aiTranslateChip, BigBangAiAction.TRANSLATE)
        binding.aiCustomChip.apply {
            isCheckable = false
            isClickable = true
            setOnClickListener { promptCustomAi() }
        }
        if (intent?.getBooleanExtra(EXTRA_AUTO_AI_NOTE, false) == true) {
            binding.root.postDelayed({ runAi(BigBangAiAction.NOTE) }, 500L)
        }
        playSheetEnter()
    }

    private fun wireAiChip(chip: com.google.android.material.chip.Chip, action: BigBangAiAction) {
        chip.isCheckable = false
        chip.isClickable = true
        chip.setOnClickListener { runAi(action) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bindTokens(animate = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (PetService.isRunning) {
            startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_SHOW_PET))
        }
    }

    private fun playSheetEnter() {
        binding.sheet.translationY = 80f * resources.displayMetrics.density
        binding.sheet.alpha = 0f
        binding.sheet.scaleX = 0.96f
        binding.sheet.scaleY = 0.96f
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.sheet, View.TRANSLATION_Y, 0f),
                ObjectAnimator.ofFloat(binding.sheet, View.ALPHA, 1f),
                ObjectAnimator.ofFloat(binding.sheet, View.SCALE_X, 1f),
                ObjectAnimator.ofFloat(binding.sheet, View.SCALE_Y, 1f)
            )
            duration = 460
            interpolator = OvershootInterpolator(1.08f)
            start()
        }
    }

    private fun bindTokens(animate: Boolean) {
        val tokens = TextCaptureHolder.tokens
        if (tokens.isEmpty()) {
            Toast.makeText(this, R.string.bigbang_empty, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (originalTokens.isEmpty()) originalTokens = tokens
        binding.tokenFlow.setTokens(tokens, animateEntrance = animate)
    }

    private fun requireSelection(): String? {
        val selected = binding.tokenFlow.selectedText()
        if (selected.isBlank()) {
            Toast.makeText(this, R.string.bigbang_select_first, Toast.LENGTH_SHORT).show()
            return null
        }
        return selected
    }

    private fun workingText(): String {
        val selected = binding.tokenFlow.selectedText()
        if (selected.isNotBlank()) return selected
        return TextTokenizer.joinSelected(
            TextCaptureHolder.tokens,
            TextCaptureHolder.tokens.indices.toSet()
        )
    }

    private fun copySelection() {
        val selected = requireSelection() ?: return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("bigbang", selected))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun searchSelection() {
        val selected = requireSelection() ?: return
        startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", selected))
    }

    private fun sendFlashNote() {
        val selected = requireSelection() ?: return
        if (Settings.canDrawOverlays(this)) {
            FlashNoteHud.startCapture(this, prefill = selected, source = "bigbang")
        } else {
            startActivity(
                Intent(this, FlashNoteActivity::class.java)
                    .putExtra(FlashNoteActivity.EXTRA_PREFILL, selected)
                    .putExtra(FlashNoteActivity.EXTRA_SOURCE, "bigbang")
            )
        }
        finish()
    }

    private fun promptCustomAi() {
        val input = EditText(this).apply {
            hint = getString(R.string.bigbang_ai_custom_hint)
            setText(R.string.bigbang_ai_custom_default)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bigbang_ai_custom)
            .setView(input)
            .setPositiveButton(R.string.bigbang_ai_run) { _, _ ->
                runAi(BigBangAiAction.CUSTOM, input.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runAi(action: BigBangAiAction, customHint: String = "") {
        HabitPolicyStore.init(applicationContext)
        if (HabitPolicyStore.llmApiKey.isBlank()) {
            Toast.makeText(this, R.string.bigbang_ai_need_key, Toast.LENGTH_LONG).show()
            return
        }
        val source = workingText()
        if (source.isBlank()) {
            Toast.makeText(this, R.string.bigbang_empty, Toast.LENGTH_SHORT).show()
            return
        }
        setAiLoading(true)
        Toast.makeText(this, R.string.bigbang_ai_running, Toast.LENGTH_SHORT).show()
        io.execute {
            val result = runCatching {
                BigBangAi.run(action, source, customHint)
            }.getOrElse {
                HabitLlmResult(ok = false, error = it.message ?: "AI 异常")
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                setAiLoading(false)
                if (!result.ok || result.text.isNullOrBlank()) {
                    val detail = result.error?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.bigbang_ai_failed)
                    Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
                    android.util.Log.e("BigBangAi", "ai fail: $detail raw=${result.raw?.take(200)}")
                    return@post
                }
                applyAiResult(result.text)
                android.util.Log.i("BigBangAi", "ai ok chars=${result.text.length}")
            }
        }
    }

    private fun applyAiResult(text: String) {
        val tokens = TextTokenizer.splitAll(listOf(text))
        if (tokens.isEmpty()) {
            Toast.makeText(this, R.string.bigbang_ai_failed, Toast.LENGTH_SHORT).show()
            return
        }
        TextCaptureHolder.tokens = tokens
        showingAi = true
        binding.restoreOriginalButton.visibility = View.VISIBLE
        binding.tokenFlow.setTokens(tokens, animateEntrance = true)
        Toast.makeText(this, R.string.bigbang_ai_done, Toast.LENGTH_SHORT).show()
    }

    private fun restoreOriginal() {
        if (originalTokens.isEmpty()) return
        TextCaptureHolder.tokens = originalTokens
        showingAi = false
        binding.restoreOriginalButton.visibility = View.GONE
        binding.tokenFlow.setTokens(originalTokens, animateEntrance = true)
    }

    private fun setAiLoading(loading: Boolean) {
        binding.aiProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.aiNoteChip.isEnabled = !loading
        binding.aiCleanChip.isEnabled = !loading
        binding.aiTranslateChip.isEnabled = !loading
        binding.aiCustomChip.isEnabled = !loading
    }

    companion object {
        const val EXTRA_SEED_TEXT = "seed_text"
        const val EXTRA_AUTO_AI_NOTE = "auto_ai_note"
    }
}
