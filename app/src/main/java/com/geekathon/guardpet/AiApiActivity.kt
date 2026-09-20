package com.geekathon.guardpet

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pranav.reef.ui.ReefTheme

/** 设置里的 AI 接口页。文本接口与千问 Key 分开存，改字段即写入，不必点「重新分析」。 */
class AiApiActivity : AppCompatActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReefTheme {
                AiApiScreen(
                    activity = this,
                    onBack = { finish() },
                    onSaved = {
                        Toast.makeText(this, R.string.ai_api_saved, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiApiScreen(
    activity: AiApiActivity,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val settings = remember { PetSettings(activity) }
    var baseUrl by remember { mutableStateOf(HabitPolicyStore.llmBaseUrl) }
    var apiKey by remember {
        mutableStateOf(HabitPolicyStore.llmApiKey.ifBlank { settings.deepSeekApiKey })
    }
    var model by remember { mutableStateOf(HabitPolicyStore.llmModel) }
    var qwenKey by remember { mutableStateOf(settings.dashScopeApiKey) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    fun persist(
        url: String = baseUrl,
        key: String = apiKey,
        modelName: String = model,
        qwen: String = qwenKey
    ) {
        val trimmedKey = key.trim()
        HabitPolicyStore.llmBaseUrl = url.trim()
        HabitPolicyStore.llmApiKey = trimmedKey
        HabitPolicyStore.llmModel = modelName.trim()
        settings.deepSeekApiKey = trimmedKey
        settings.dashScopeApiKey = qwen.trim()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.ai_api_title),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        persist()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                scrollBehavior = scroll
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.ai_api_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.ai_api_text_section),
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    persist()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.habit_llm_base_url)) },
                singleLine = true
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    persist()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_api_deepseek_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            OutlinedTextField(
                value = model,
                onValueChange = {
                    model = it
                    persist()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.habit_llm_model)) },
                singleLine = true
            )
            Text(
                stringResource(R.string.ai_api_qwen_section),
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(
                value = qwenKey,
                onValueChange = {
                    qwenKey = it
                    persist()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.appearance_dashscope_hint)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Text(
                stringResource(R.string.ai_api_qwen_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    persist()
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.ai_api_save))
            }
        }
    }
}
