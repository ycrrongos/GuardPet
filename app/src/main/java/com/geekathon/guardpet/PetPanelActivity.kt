package com.geekathon.guardpet

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pranav.reef.ui.ReefTheme

class PetPanelActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReefTheme {
                PetPanelScreen(this)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PetPanelScreen(activity: PetPanelActivity) {
    val settings = remember { PetSettings(activity) }
    val todos = remember {
        mutableStateListOf<String>().apply { addAll(loadTodos(activity)) }
    }
    var walk by remember { mutableStateOf(settings.edgeWalkEnabled) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun persist() {
        val editor = activity.getSharedPreferences(TODO_PREFERENCES, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
        val kept = todos.map { it.trim() }.filter { it.isNotEmpty() }
        editor.putInt(KEY_COUNT, kept.size)
        kept.forEachIndexed { index, text -> editor.putString("todo_$index", text) }
        editor.apply()
    }

    DisposableEffect(Unit) {
        onDispose { persist() }
    }
    BackHandler {
        persist()
        activity.finish()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.todo_title) + " · ${todos.size}",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        persist()
                        activity.finish()
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
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            Text(
                stringResource(R.string.todo_reward_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (todos.isEmpty()) {
                Text(
                    stringResource(R.string.todo_empty),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(todos) { index, text ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = {
                                    todos[index] = it
                                    persist()
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            TextButton(onClick = {
                                if (todos[index].isBlank()) {
                                    Toast.makeText(activity, R.string.todo_empty_warning, Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                settings.foodCount += 1
                                settings.mood += TODO_MOOD_REWARD
                                todos.removeAt(index)
                                persist()
                                Toast.makeText(activity, R.string.todo_reward, Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.todo_done)) }
                            TextButton(onClick = {
                                todos.removeAt(index)
                                persist()
                            }) { Text(stringResource(R.string.flash_note_delete)) }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    todos.add("")
                    persist()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.add_todo)) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.free_walk), modifier = Modifier.weight(1f))
                Switch(
                    checked = walk,
                    onCheckedChange = {
                        walk = it
                        settings.edgeWalkEnabled = it
                        if (PetService.isRunning) {
                            activity.startService(
                                Intent(activity, PetService::class.java)
                                    .setAction(PetService.ACTION_REFRESH_SETTINGS)
                            )
                        }
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (PetService.isRunning) {
                            activity.startService(
                                Intent(activity, PetService::class.java)
                                    .setAction(PetService.ACTION_OPEN_TIMER)
                            )
                            persist()
                            activity.finish()
                        } else {
                            Toast.makeText(activity, R.string.start_pet_first, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.focus_timer)) }
                OutlinedButton(
                    onClick = {
                        activity.stopService(Intent(activity, PetService::class.java))
                        persist()
                        activity.finish()
                    },
                    enabled = PetService.isRunning,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.pause_short)) }
            }
        }
    }
}

private fun loadTodos(activity: PetPanelActivity): List<String> {
    val preferences = activity.getSharedPreferences(TODO_PREFERENCES, android.content.Context.MODE_PRIVATE)
    return List(preferences.getInt(KEY_COUNT, 0)) { index ->
        preferences.getString("todo_$index", "").orEmpty()
    }
}

private const val TODO_PREFERENCES = "todos"
private const val KEY_COUNT = "count"
private const val TODO_MOOD_REWARD = 8
