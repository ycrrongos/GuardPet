package com.geekathon.guardpet

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pranav.reef.ui.ReefTheme

/** 设置里编辑提取文字的 AI 快捷整理条。每一条对应词块页上的一个按钮。 */
class BigBangShortcutsActivity : AppCompatActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReefTheme {
                BigBangShortcutsScreen(activity = this, onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BigBangShortcutsScreen(activity: BigBangShortcutsActivity, onBack: () -> Unit) {
    val items = remember {
        mutableStateListOf<BigBangShortcut>().apply { addAll(BigBangShortcutStore.load(activity)) }
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    fun persist(next: List<BigBangShortcut> = items.toList()) {
        BigBangShortcutStore.save(activity, next)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.bigbang_shortcuts_title),
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
                stringResource(R.string.bigbang_shortcuts_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.bigbang_shortcuts_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            items.forEachIndexed { index, item ->
                key(item.id) {
                    ShortcutCard(
                    item = item,
                    canUp = index > 0,
                    canDown = index < items.lastIndex,
                    onChange = { updated ->
                        items[index] = updated
                        persist()
                    },
                    onUp = {
                        items.swap(index, index - 1)
                        persist()
                    },
                    onDown = {
                        items.swap(index, index + 1)
                        persist()
                    },
                        onDelete = {
                            items.removeAt(index)
                            persist()
                        }
                    )
                }
            }
            Button(
                onClick = {
                    items.add(BigBangShortcutStore.newItem(activity))
                    persist()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = items.size < 8
            ) { Text(stringResource(R.string.bigbang_shortcut_add)) }
            OutlinedButton(
                onClick = {
                    BigBangShortcutStore.reset(activity)
                    items.clear()
                    items.addAll(BigBangShortcutStore.defaults(activity))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.bigbang_shortcut_reset)) }
        }
    }
}

@Composable
private fun ShortcutCard(
    item: BigBangShortcut,
    canUp: Boolean,
    canDown: Boolean,
    onChange: (BigBangShortcut) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = item.title,
                onValueChange = { onChange(item.copy(title = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.bigbang_shortcut_name)) },
                singleLine = true
            )
            OutlinedTextField(
                value = item.prompt,
                onValueChange = { onChange(item.copy(prompt = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.bigbang_shortcut_prompt)) },
                minLines = 3
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onUp, enabled = canUp) {
                    Text(stringResource(R.string.bigbang_shortcut_up))
                }
                TextButton(onClick = onDown, enabled = canDown) {
                    Text(stringResource(R.string.bigbang_shortcut_down))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.bigbang_shortcut_delete))
                }
            }
        }
    }
}

private fun SnapshotStateList<BigBangShortcut>.swap(from: Int, to: Int) {
    if (from !in indices || to !in indices) return
    val item = this[from]
    this[from] = this[to]
    this[to] = item
}
