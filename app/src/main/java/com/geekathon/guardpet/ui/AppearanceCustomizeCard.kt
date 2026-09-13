package com.geekathon.guardpet.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.geekathon.guardpet.PetAppearanceAgent
import com.geekathon.guardpet.PetAppearanceGenerator
import com.geekathon.guardpet.PetAssetRepository
import com.geekathon.guardpet.PetCanvas
import com.geekathon.guardpet.PetService
import com.geekathon.guardpet.PetSettings
import com.geekathon.guardpet.PetState
import com.geekathon.guardpet.R
import com.geekathon.guardpet.friend.FriendClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 完整定制形象面板（对齐 1103-jun/AI- MikuUI）：
 * 主体 / 风格 / 特征 / 快速·精细 / 文生图 / 参考图改绘 / 直传 / 进度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceCustomizeCard(
    assets: PetAssetRepository,
    settings: PetSettings,
    modifier: Modifier = Modifier,
    onAppearanceChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subjects = stringArrayResource(R.array.appearance_subject_types)
    val styles = stringArrayResource(R.array.appearance_art_styles)

    var subject by remember { mutableStateOf(subjects.getOrElse(1) { subjects.first() }) }
    var customSubject by remember { mutableStateOf("") }
    var artStyle by remember { mutableStateOf(styles.getOrElse(1) { styles.first() }) }
    var customStyle by remember { mutableStateOf("") }
    var features by remember { mutableStateOf("") }
    var freeIdea by remember {
        mutableStateOf(
            assets.customIdea().orEmpty()
                .takeIf { it != "上传图片" && it != "direct-upload" && it != "upload" }
                .orEmpty()
        )
    }
    var quality by remember {
        mutableStateOf(settings.appearanceMode == PetAppearanceGenerator.GenerationMode.QUALITY)
    }
    var dashKey by remember { mutableStateOf(settings.dashScopeApiKey) }
    var generating by remember { mutableStateOf(false) }
    var statusText by remember {
        mutableStateOf(
            if (assets.hasCustomAppearance()) {
                context.getString(R.string.appearance_status_custom, assets.customIdea().orEmpty())
            } else {
                context.getString(R.string.appearance_status_idle)
            }
        )
    }
    var pendingUpload by remember { mutableStateOf<ByteArray?>(null) }
    var pendingMime by remember { mutableStateOf("image/png") }
    var previewTick by remember { mutableStateOf(0) }
    var subjectMenu by remember { mutableStateOf(false) }
    var styleMenu by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            Toast.makeText(context, R.string.appearance_failed_decode, Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        pendingUpload = bytes
        pendingMime = context.contentResolver.getType(uri) ?: "image/png"
        statusText = context.getString(R.string.appearance_status_upload_ready)
    }

    fun refreshStatusAfterSave(idea: String) {
        previewTick++
        statusText = if (assets.hasCustomAppearance()) {
            context.getString(R.string.appearance_status_custom, idea)
        } else {
            context.getString(R.string.appearance_status_idle)
        }
        context.startService(
            Intent(context, PetService::class.java).setAction(PetService.ACTION_REFRESH)
        )
        FriendClient.pushLocalAvatar(context, force = true)
        onAppearanceChanged()
    }

    fun progressLabel(stage: String): String = when {
        stage == "understanding" -> context.getString(R.string.appearance_progress_understanding)
        stage == "looking" -> context.getString(R.string.appearance_progress_looking)
        stage == "fallback" -> context.getString(R.string.appearance_progress_fallback)
        stage == "planned_local" -> context.getString(R.string.appearance_progress_planned)
        stage == "local" -> context.getString(R.string.appearance_progress_local)
        stage == "done" -> context.getString(R.string.appearance_progress_done)
        stage.startsWith("traits:") ->
            context.getString(R.string.appearance_progress_traits, stage.removePrefix("traits:"))
        stage.startsWith("drawing:") -> {
            val n = stage.removePrefix("drawing:").toIntOrNull() ?: 1
            context.getString(R.string.appearance_progress_drawing, n)
        }
        stage.startsWith("checking:") -> context.getString(R.string.appearance_progress_checking)
        stage.startsWith("revising:") -> context.getString(R.string.appearance_progress_revising)
        else -> stage
    }

    fun currentGuide(): PetAppearanceAgent.PromptGuide {
        val subjectValue = if (subject.contains("自定义")) {
            customSubject.trim().ifBlank { subject }
        } else {
            subject
        }
        val styleValue = if (artStyle.contains("自定义")) {
            customStyle.trim().ifBlank { artStyle }
        } else {
            artStyle
        }
        return PetAppearanceAgent.PromptGuide(
            subjectType = subjectValue,
            artStyle = styleValue,
            coreFeatures = features,
            freeIdea = freeIdea
        )
    }

    fun runGenerate(block: (onProgress: (String) -> Unit) -> PetAppearanceGenerator.Result) {
        if (generating) return
        generating = true
        statusText = context.getString(R.string.appearance_generating)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    block { stage ->
                        scope.launch(Dispatchers.Main) {
                            statusText = progressLabel(stage)
                        }
                    }
                }
            }
            generating = false
            outcome.onSuccess { result ->
                val label = when (result.source) {
                    PetAppearanceGenerator.Source.UPLOAD ->
                        context.getString(R.string.appearance_label_upload)
                    else -> result.refinedPrompt.ifBlank { result.prompt }.ifBlank { "自定义形象" }
                }.take(120)
                assets.installGeneratedAppearance(result.pngBytes, label)
                Toast.makeText(
                    context,
                    when (result.source) {
                        PetAppearanceGenerator.Source.QWEN ->
                            context.getString(R.string.appearance_success_qwen)
                        PetAppearanceGenerator.Source.UPLOAD ->
                            context.getString(R.string.appearance_success_upload)
                        PetAppearanceGenerator.Source.LOCAL ->
                            context.getString(R.string.appearance_success_local)
                        PetAppearanceGenerator.Source.PLANNED ->
                            context.getString(R.string.appearance_success_planned)
                        PetAppearanceGenerator.Source.CHATGPT ->
                            context.getString(
                                R.string.appearance_success_chatgpt,
                                result.attempts,
                                result.score
                            )
                        PetAppearanceGenerator.Source.ONLINE ->
                            context.getString(R.string.appearance_success_online)
                    },
                    Toast.LENGTH_LONG
                ).show()
                refreshStatusAfterSave(label)
            }.onFailure {
                statusText = context.getString(
                    R.string.appearance_failed,
                    it.message ?: it.javaClass.simpleName
                )
                Toast.makeText(context, statusText, Toast.LENGTH_LONG).show()
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.appearance_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.appearance_guide_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 当前形象预览
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PetCanvas(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(200, 200)
                            fitPreviewToCanvas()
                        }
                    },
                    update = { canvas ->
                        @Suppress("UNUSED_EXPRESSION")
                        previewTick
                        canvas.show(assets.fileFor(PetState.IDLE) ?: assets.randomFileFor(PetState.IDLE))
                        canvas.fitPreviewToCanvas()
                    },
                    modifier = Modifier.size(88.dp)
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            if (generating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 主体
            ExposedDropdownMenuBox(
                expanded = subjectMenu,
                onExpandedChange = { subjectMenu = !subjectMenu }
            ) {
                OutlinedTextField(
                    value = subject,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.appearance_subject_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(subjectMenu) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = subjectMenu, onDismissRequest = { subjectMenu = false }) {
                    subjects.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                subject = option
                                subjectMenu = false
                            }
                        )
                    }
                }
            }
            if (subject.contains("自定义")) {
                OutlinedTextField(
                    value = customSubject,
                    onValueChange = { customSubject = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.appearance_custom_subject_hint)) },
                    singleLine = true
                )
            }

            // 风格
            ExposedDropdownMenuBox(
                expanded = styleMenu,
                onExpandedChange = { styleMenu = !styleMenu }
            ) {
                OutlinedTextField(
                    value = artStyle,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.appearance_style_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(styleMenu) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                    styles.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                artStyle = option
                                styleMenu = false
                            }
                        )
                    }
                }
            }
            if (artStyle.contains("自定义")) {
                OutlinedTextField(
                    value = customStyle,
                    onValueChange = { customStyle = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.appearance_custom_style_hint)) },
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = features,
                onValueChange = { features = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.appearance_features_label)) },
                placeholder = { Text(stringResource(R.string.appearance_features_hint)) },
                minLines = 2
            )
            OutlinedTextField(
                value = freeIdea,
                onValueChange = { freeIdea = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.appearance_free_label)) },
                placeholder = { Text(stringResource(R.string.appearance_hint)) },
                minLines = 2
            )

            Text(
                stringResource(R.string.appearance_mode_label),
                style = MaterialTheme.typography.labelLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !quality,
                    onClick = {
                        quality = false
                        settings.appearanceMode = PetAppearanceGenerator.GenerationMode.FAST
                    },
                    label = { Text(stringResource(R.string.appearance_mode_fast_short)) }
                )
                FilterChip(
                    selected = quality,
                    onClick = {
                        quality = true
                        settings.appearanceMode = PetAppearanceGenerator.GenerationMode.QUALITY
                    },
                    label = { Text(stringResource(R.string.appearance_mode_quality_short)) }
                )
            }
            Text(
                stringResource(R.string.appearance_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (quality) {
                OutlinedTextField(
                    value = dashKey,
                    onValueChange = {
                        dashKey = it
                        settings.dashScopeApiKey = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.appearance_dashscope_hint)) },
                    singleLine = true
                )
            }

            // 文生图
            Button(
                onClick = {
                    val guide = currentGuide()
                    if (guide.composeIdeaOrEmpty().isBlank()) {
                        Toast.makeText(context, R.string.appearance_empty, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val mode = if (quality) {
                        PetAppearanceGenerator.GenerationMode.QUALITY
                    } else {
                        PetAppearanceGenerator.GenerationMode.FAST
                    }
                    val creds = settings.appearanceCredentials()
                    if (mode == PetAppearanceGenerator.GenerationMode.QUALITY && !creds.hasQwen()) {
                        Toast.makeText(context, R.string.dashscope_key_missing, Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    runGenerate { onProgress ->
                        PetAppearanceGenerator.generateFromIdea(guide, creds, mode, onProgress)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !generating
            ) {
                Text(stringResource(R.string.appearance_generate))
            }

            // 参考图区
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { pickImage.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    enabled = !generating
                ) {
                    Text(stringResource(R.string.appearance_pick_image))
                }
                pendingUpload?.let { bytes ->
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.appearance_upload_preview),
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val upload = pendingUpload
                        if (upload == null) {
                            Toast.makeText(context, R.string.appearance_need_upload, Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        val creds = settings.appearanceCredentials()
                        val mode = if (quality && creds.hasQwen()) {
                            PetAppearanceGenerator.GenerationMode.QUALITY
                        } else {
                            PetAppearanceGenerator.GenerationMode.FAST
                        }
                        if (quality && !creds.hasQwen()) {
                            Toast.makeText(context, R.string.dashscope_key_missing, Toast.LENGTH_LONG).show()
                            // 仍可用快速：直接贴图
                        }
                        runGenerate { onProgress ->
                            PetAppearanceGenerator.generateFromUpload(
                                upload,
                                pendingMime,
                                currentGuide(),
                                creds,
                                mode,
                                onProgress
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !generating
                ) {
                    Text(stringResource(R.string.appearance_generate_from_upload))
                }
                OutlinedButton(
                    onClick = {
                        val upload = pendingUpload
                        if (upload == null) {
                            Toast.makeText(context, R.string.appearance_need_upload, Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        runGenerate { _ -> PetAppearanceGenerator.applyUploadDirect(upload) }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !generating
                ) {
                    Text(stringResource(R.string.appearance_apply_upload))
                }
            }

            OutlinedButton(
                onClick = {
                    assets.clearCustomAppearance()
                    pendingUpload = null
                    Toast.makeText(context, R.string.appearance_restored, Toast.LENGTH_SHORT).show()
                    refreshStatusAfterSave("")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !generating
            ) {
                Text(stringResource(R.string.appearance_restore))
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
