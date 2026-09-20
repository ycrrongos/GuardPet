package com.geekathon.guardpet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.NestedScrollView
import dev.pranav.reef.ui.ReefTheme
import java.util.concurrent.Executors

class BigBangActivity : AppCompatActivity() {
    var onRefreshTokens: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HabitPolicyStore.init(applicationContext)
        intent?.getStringExtra(EXTRA_SEED_TEXT)?.takeIf { it.isNotBlank() }?.let { seed ->
            TextCaptureHolder.tokens = TextTokenizer.splitAll(listOf(seed))
        }
        if (TextCaptureHolder.tokens.isEmpty()) {
            Toast.makeText(this, R.string.bigbang_empty, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContent {
            ReefTheme {
                BigBangScreen(this)
            }
        }
        findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)?.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_SEED_TEXT)?.takeIf { it.isNotBlank() }?.let { seed ->
            TextCaptureHolder.tokens = TextTokenizer.splitAll(listOf(seed))
        }
        onRefreshTokens?.invoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (PetService.isRunning) {
            startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_SHOW_PET))
        }
    }

    companion object {
        const val EXTRA_SEED_TEXT = "seed_text"
        const val EXTRA_AUTO_AI_NOTE = "auto_ai_note"
    }
}

@Composable
private fun BigBangScreen(activity: BigBangActivity) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.setBackgroundColor(Color.TRANSPARENT)
        onDispose { }
    }
    var generation by remember { mutableIntStateOf(0) }
    var tokens by remember { mutableStateOf(TextCaptureHolder.tokens) }
    var animateTokens by remember { mutableStateOf(true) }
    var showingAi by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf(activity.getString(R.string.bigbang_ai_custom_default)) }
    val originalTokens = remember { tokens.toList() }
    val applied = remember { intArrayOf(-1) }
    val flowHolder = remember { arrayOfNulls<TokenFlowView>(1) }
    val io = remember { Executors.newSingleThreadExecutor() }
    val enter = remember { Animatable(0f) }
    val dismiss = Modifier.clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() }
    ) { activity.finish() }
    val consume = Modifier.clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() }
    ) { }

    DisposableEffect(Unit) {
        activity.onRefreshTokens = {
            tokens = TextCaptureHolder.tokens
            animateTokens = true
            generation++
        }
        onDispose {
            activity.onRefreshTokens = null
            io.shutdownNow()
        }
    }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f))
    }
    LaunchedEffect(Unit) {
        if (activity.intent?.getBooleanExtra(BigBangActivity.EXTRA_AUTO_AI_NOTE, false) == true) {
            kotlinx.coroutines.delay(500L)
            runAi(activity, io, tokens, flowHolder, loading) { next, showRestore, busy ->
                loading = busy
                if (next != null) {
                    TextCaptureHolder.tokens = next
                    tokens = next
                    animateTokens = true
                    showingAi = showRestore
                    generation++
                }
            }
        }
    }

    fun applyTokens(next: List<String>, restoreVisible: Boolean) {
        TextCaptureHolder.tokens = next
        tokens = next
        animateTokens = true
        showingAi = restoreVisible
        generation++
    }

    fun startAi(action: BigBangAiAction, hint: String = "") {
        if (loading) return
        runAi(activity, io, tokens, flowHolder, false, action, hint) { next, showRestore, busy ->
            loading = busy
            if (next != null) applyTokens(next, showRestore)
        }
    }

    Box(Modifier.fillMaxSize().then(dismiss)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 10.dp, top = 48.dp, bottom = 8.dp)
                .graphicsLayer {
                    val p = enter.value
                    alpha = p.coerceIn(0f, 1f)
                    translationY = (1f - p) * 80f * density
                    val scale = 0.96f + 0.04f * p
                    scaleX = scale
                    scaleY = scale
                }
                .then(consume),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(Modifier.fillMaxSize().padding(bottom = 12.dp)) {
                Box(
                    Modifier
                        .padding(top = 10.dp)
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.extract_text),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(onClick = { flowHolder[0]?.invertSelection() }) {
                        Text(stringResource(R.string.invert_selection))
                    }
                    IconButton(onClick = { activity.finish() }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close_menu))
                    }
                }
                Text(
                    stringResource(R.string.bigbang_hint),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { startAi(BigBangAiAction.NOTE) },
                        enabled = !loading,
                        label = { Text(stringResource(R.string.bigbang_ai_note)) }
                    )
                    AssistChip(
                        onClick = { startAi(BigBangAiAction.CLEAN) },
                        enabled = !loading,
                        label = { Text(stringResource(R.string.bigbang_ai_clean)) }
                    )
                    AssistChip(
                        onClick = { startAi(BigBangAiAction.TRANSLATE) },
                        enabled = !loading,
                        label = { Text(stringResource(R.string.bigbang_ai_translate)) }
                    )
                    AssistChip(
                        onClick = { customOpen = true },
                        enabled = !loading,
                        label = { Text(stringResource(R.string.bigbang_ai_custom)) }
                    )
                }
                if (loading) {
                    LinearProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                if (showingAi) {
                    TextButton(
                        onClick = { applyTokens(originalTokens, false) },
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text(stringResource(R.string.bigbang_restore_original)) }
                }
                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    factory = { context ->
                        val flow = TokenFlowView(context)
                        flowHolder[0] = flow
                        NestedScrollView(context).apply {
                            isFillViewport = true
                            addView(
                                flow,
                                ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                            )
                        }
                    },
                    update = { scroll ->
                        val flow = scroll.getChildAt(0) as TokenFlowView
                        flowHolder[0] = flow
                        if (applied[0] != generation) {
                            applied[0] = generation
                            flow.setTokens(tokens, animateTokens)
                        }
                    }
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { copySelection(activity, flowHolder[0]) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.copy)) }
                    OutlinedButton(
                        onClick = { searchSelection(activity, flowHolder[0]) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.web_search)) }
                    Button(
                        onClick = { sendFlashNote(activity, flowHolder[0]) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.flash_note)) }
                }
            }
        }
    }

    if (customOpen) {
        AlertDialog(
            onDismissRequest = { customOpen = false },
            title = { Text(stringResource(R.string.bigbang_ai_custom)) },
            text = {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.bigbang_ai_custom_hint)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    customOpen = false
                    startAi(BigBangAiAction.CUSTOM, customText)
                }) { Text(stringResource(R.string.bigbang_ai_run)) }
            },
            dismissButton = {
                TextButton(onClick = { customOpen = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

private fun selectedText(activity: BigBangActivity, flow: TokenFlowView?): String? {
    val selected = flow?.selectedText().orEmpty()
    if (selected.isBlank()) {
        Toast.makeText(activity, R.string.bigbang_select_first, Toast.LENGTH_SHORT).show()
        return null
    }
    return selected
}

private fun copySelection(activity: BigBangActivity, flow: TokenFlowView?) {
    val selected = selectedText(activity, flow) ?: return
    val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("bigbang", selected))
    Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
}

private fun searchSelection(activity: BigBangActivity, flow: TokenFlowView?) {
    val selected = selectedText(activity, flow) ?: return
    activity.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", selected))
}

private fun sendFlashNote(activity: BigBangActivity, flow: TokenFlowView?) {
    val selected = selectedText(activity, flow) ?: return
    if (Settings.canDrawOverlays(activity)) {
        FlashNoteHud.startCapture(activity, prefill = selected, source = "bigbang")
    } else {
        activity.startActivity(
            Intent(activity, FlashNoteActivity::class.java)
                .putExtra(FlashNoteActivity.EXTRA_PREFILL, selected)
                .putExtra(FlashNoteActivity.EXTRA_SOURCE, "bigbang")
        )
    }
    activity.finish()
}

private fun workingText(tokens: List<String>, flow: TokenFlowView?): String {
    val selected = flow?.selectedText().orEmpty()
    if (selected.isNotBlank()) return selected
    return TextTokenizer.joinSelected(tokens, tokens.indices.toSet())
}

private fun runAi(
    activity: BigBangActivity,
    io: java.util.concurrent.ExecutorService,
    tokens: List<String>,
    flowHolder: Array<TokenFlowView?>,
    alreadyLoading: Boolean,
    action: BigBangAiAction = BigBangAiAction.NOTE,
    customHint: String = "",
    onUpdate: (next: List<String>?, showRestore: Boolean, loading: Boolean) -> Unit
) {
    if (alreadyLoading) return
    HabitPolicyStore.init(activity.applicationContext)
    if (HabitPolicyStore.llmApiKey.isBlank()) {
        Toast.makeText(activity, R.string.bigbang_ai_need_key, Toast.LENGTH_LONG).show()
        return
    }
    val source = workingText(tokens, flowHolder[0])
    if (source.isBlank()) {
        Toast.makeText(activity, R.string.bigbang_empty, Toast.LENGTH_SHORT).show()
        return
    }
    onUpdate(null, false, true)
    Toast.makeText(activity, R.string.bigbang_ai_running, Toast.LENGTH_SHORT).show()
    io.execute {
        val result = runCatching {
            BigBangAi.run(action, source, customHint)
        }.getOrElse {
            HabitLlmResult(ok = false, error = it.message ?: "AI 异常")
        }
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            if (!result.ok || result.text.isNullOrBlank()) {
                onUpdate(null, false, false)
                val detail = result.error?.takeIf { it.isNotBlank() }
                    ?: activity.getString(R.string.bigbang_ai_failed)
                Toast.makeText(activity, detail, Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            val next = TextTokenizer.splitAll(listOf(result.text))
            if (next.isEmpty()) {
                onUpdate(null, false, false)
                Toast.makeText(activity, R.string.bigbang_ai_failed, Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            onUpdate(next, true, false)
            Toast.makeText(activity, R.string.bigbang_ai_done, Toast.LENGTH_SHORT).show()
        }
    }
}
