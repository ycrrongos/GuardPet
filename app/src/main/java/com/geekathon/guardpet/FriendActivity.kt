package com.geekathon.guardpet

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.geekathon.guardpet.friend.FriendAvatarCache
import com.geekathon.guardpet.friend.FriendClient
import com.geekathon.guardpet.friend.FriendMember
import com.geekathon.guardpet.friend.FriendPrefs
import com.geekathon.guardpet.friend.HabitXpSettler
import com.geekathon.guardpet.friend.HabitXpStore
import com.geekathon.guardpet.ui.AppearanceCustomizeCard
import dev.pranav.reef.ui.ReefTheme
import java.io.File

class FriendActivity : AppCompatActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = FriendPrefs(this)
        val xpStore = HabitXpStore(this)
        val petSettings = PetSettings(this)
        val assets = PetAssetRepository(this)
        setContent {
            ReefTheme {
                var host by remember { mutableStateOf(prefs.serverHost.ifBlank { "192.168.1.1:18765" }) }
                var room by remember { mutableStateOf(prefs.roomCode) }
                var name by remember { mutableStateOf(prefs.displayName) }
                var snap by remember { mutableStateOf(FriendClient.snapshot) }
                var level by remember { mutableStateOf(xpStore.level) }
                var xp by remember { mutableStateOf(xpStore.xp) }
                var xpNeed by remember { mutableStateOf(xpStore.xpToNext()) }
                var note by remember { mutableStateOf(xpStore.lastSettleNote) }
                var avatarTick by remember { mutableIntStateOf(0) }

                DisposableEffect(Unit) {
                    val stop = FriendClient.observe {
                        snap = it
                        avatarTick = it.avatarTick
                    }
                    onDispose { stop() }
                }

                val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scroll.nestedScrollConnection),
                    topBar = {
                        LargeTopAppBar(
                            title = {
                                Text(
                                    stringResource(R.string.friend_title),
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-1).sp
                                    )
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
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
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.friend_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    stringResource(R.string.friend_level_title, level),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    stringResource(R.string.friend_xp_progress, xp, xpNeed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                LinearProgressIndicator(
                                    progress = { (xp.toFloat() / xpNeed.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (note.isNotBlank()) {
                                    Text(note, style = MaterialTheme.typography.bodySmall)
                                }
                                FilledTonalButton(
                                    onClick = {
                                        val r = HabitXpSettler.settle(this@FriendActivity, force = false)
                                        Toast.makeText(this@FriendActivity, r.message, Toast.LENGTH_LONG).show()
                                        level = HabitXpStore(this@FriendActivity).level
                                        xp = HabitXpStore(this@FriendActivity).xp
                                        xpNeed = HabitXpStore(this@FriendActivity).xpToNext()
                                        note = HabitXpStore(this@FriendActivity).lastSettleNote
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.friend_settle_xp))
                                }
                            }
                        }

                        AppearanceCustomizeCard(
                            assets = assets,
                            settings = petSettings,
                            onAppearanceChanged = { avatarTick++ }
                        )

                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.friend_server_hint)) },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = room,
                            onValueChange = { room = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.friend_room_hint)) },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.friend_name_hint)) },
                            singleLine = true
                        )

                        Text(
                            text = snap.status + if (snap.room.isNotBlank()) " · ${snap.room}" else "",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (snap.connected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = {
                                FriendClient.connect(this@FriendActivity, host, room, name)
                                if (!PetService.isRunning) {
                                    startForegroundService(Intent(this@FriendActivity, PetService::class.java))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !snap.connected
                        ) {
                            Text(stringResource(R.string.friend_connect))
                        }
                        OutlinedButton(
                            onClick = {
                                prefs.autoConnect = false
                                FriendClient.disconnect()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = snap.connected
                        ) {
                            Text(stringResource(R.string.friend_disconnect))
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    stringResource(R.string.friend_members_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                if (snap.members.isEmpty()) {
                                    Text(
                                        stringResource(R.string.friend_members_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        snap.members.forEach { m ->
                                            MemberAvatarCard(
                                                member = m,
                                                selfId = snap.selfId,
                                                assets = assets,
                                                avatarTick = avatarTick
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            stringResource(R.string.friend_server_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(28.dp))
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun MemberAvatarCard(
    member: FriendMember,
    selfId: String,
    assets: PetAssetRepository,
    avatarTick: Int
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isSelf = member.userId == selfId
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(96.dp)
    ) {
        AndroidView(
            factory = { ctx ->
                PetCanvas(ctx).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(220, 220)
                }
            },
            update = { canvas ->
                @Suppress("UNUSED_EXPRESSION")
                avatarTick
                val file: File? = if (isSelf) {
                    assets.fileFor(PetState.IDLE) ?: assets.randomFileFor(PetState.IDLE)
                } else {
                    val hash = member.pet.avatarHash
                    FriendAvatarCache.fileFor(context, hash)
                        ?: assets.fileFor(PetState.fromKey(member.pet.state))
                }
                canvas.show(file)
                canvas.fitPreviewToCanvas()
            },
            modifier = Modifier.size(88.dp)
        )
        Text(
            text = buildString {
                append(if (isSelf) "我 · " else "友 · ")
                append(member.name)
            },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
        Text(
            text = "Lv.${member.pet.level} · 心情${member.pet.mood}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
