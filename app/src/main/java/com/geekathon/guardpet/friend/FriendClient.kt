package com.geekathon.guardpet.friend

import android.content.Context
import android.util.Log
import com.geekathon.guardpet.PetAssetRepository
import com.geekathon.guardpet.PetSettings
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 局域网好友房 TCP 客户端（JSONL）。
 *
 * 读循环独占 [io] 线程；写必须 [writeRaw] 同步写出，**不能**再丢回 [io] 队列，
 * 否则会永远卡在 readLine 后面，state/avatar 心跳发不出去。
 */
object FriendClient {
    private const val TAG = "FriendClient"
    private val io = Executors.newSingleThreadExecutor()
    private val prep = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(FriendSnapshot) -> Unit>()
    private val appContext = AtomicReference<Context?>(null)
    private val lastPushedHash = AtomicReference("")
    private val liveAnimState = AtomicReference("idle")
    private val writeLock = Any()
    private var stateHeartbeat: ScheduledFuture<*>? = null

    @Volatile
    var snapshot: FriendSnapshot = FriendSnapshot()
        private set

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var writer: BufferedWriter? = null

    data class FriendSnapshot(
        val connected: Boolean = false,
        val status: String = "未连接",
        val room: String = "",
        val members: List<FriendMember> = emptyList(),
        val selfId: String = "",
        val avatarTick: Int = 0
    )

    fun observe(listener: (FriendSnapshot) -> Unit): () -> Unit {
        listeners += listener
        listener(snapshot)
        return { listeners.remove(listener) }
    }

    /** 桌宠当前动作（walk/sleep/…），供心跳带上。 */
    fun reportAnimState(stateKey: String) {
        liveAnimState.set(stateKey.ifBlank { "idle" })
    }

    fun connect(context: Context, hostPort: String, room: String, name: String) {
        val app = context.applicationContext
        appContext.set(app)
        val prefs = FriendPrefs(app)
        prefs.serverHost = hostPort
        prefs.roomCode = room
        prefs.displayName = name
        prefs.autoConnect = true
        disconnect(keepAuto = true)
        running.set(true)
        publish(snapshot.copy(connected = false, status = "连接中…", room = room, selfId = prefs.userId))
        io.execute {
            try {
                val (host, port) = parseHostPort(hostPort)
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 8_000)
                sock.soTimeout = 0
                socket = sock
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                synchronized(writeLock) {
                    writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))
                }
                writeRaw(FriendProtocol.hello(prefs.userId, prefs.displayName, prefs.roomCode))
                publish(snapshot.copy(connected = true, status = "已连接", room = prefs.roomCode, selfId = prefs.userId))
                // 必须在读循环前同步发出（同线程），否则会进 io 队列永远发不出
                pushLocalStateNow(app, force = true)
                pushLocalAvatarNow(app, force = true)
                startStateHeartbeat(app)
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    handleLine(app, line)
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                publish(
                    snapshot.copy(
                        connected = false,
                        status = e.message?.take(80) ?: "连接失败",
                        members = emptyList()
                    )
                )
            } finally {
                stopStateHeartbeat()
                closeQuietly()
                if (running.get()) {
                    publish(snapshot.copy(connected = false, status = "已断开", members = emptyList()))
                }
                running.set(false)
            }
        }
    }

    fun disconnect(keepAuto: Boolean = false) {
        if (!keepAuto) {
            appContext.get()?.let { FriendPrefs(it).autoConnect = false }
        }
        running.set(false)
        stopStateHeartbeat()
        lastPushedHash.set("")
        closeQuietly()
        publish(FriendSnapshot(status = "未连接"))
    }

    fun sendPetState(pet: FriendPetSnapshot) {
        if (!snapshot.connected) return
        liveAnimState.set(pet.state.ifBlank { liveAnimState.get() })
        publishSelfPet(pet)
        runCatching { writeRaw(FriendProtocol.state(pet)) }
            .onFailure { Log.w(TAG, "send state failed", it) }
    }

    fun pushLocalAvatar(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        appContext.set(app)
        prep.execute {
            runCatching { pushLocalAvatarNow(app, force) }
                .onFailure { Log.w(TAG, "push avatar failed", it) }
        }
    }

    /** 立刻用本机 PetSettings 组一包 state（入房/心跳用）。 */
    fun pushLocalState(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        appContext.set(app)
        prep.execute {
            runCatching { pushLocalStateNow(app, force) }
                .onFailure { Log.w(TAG, "push state failed", it) }
        }
    }

    private fun pushLocalStateNow(app: Context, force: Boolean = false) {
        if (!snapshot.connected && !force) return
        if (!running.get() && writer == null) return
        val settings = PetSettings(app)
        val xp = HabitXpStore(app)
        val hash = lastPushedHash.get().ifBlank {
            runCatching { PetAssetRepository(app).appearanceHash() }.getOrDefault("")
        }
        val pet = FriendPetSnapshot(
            mood = settings.mood,
            hunger = settings.hunger,
            food = settings.foodCount,
            level = xp.level,
            xp = xp.xp,
            state = liveAnimState.get().ifBlank { "idle" },
            avatarHash = hash
        )
        Log.i(TAG, "push state mood=${pet.mood} hunger=${pet.hunger} state=${pet.state}")
        publishSelfPet(pet)
        writeRaw(FriendProtocol.state(pet))
    }

    private fun pushLocalAvatarNow(app: Context, force: Boolean) {
        if (writer == null) return
        val payload = PetAssetRepository(app).appearancePayload() ?: return
        val (hash, bytes) = payload
        if (!force && hash == lastPushedHash.get()) return
        if (bytes.size > FriendAvatarCache.MAX_BYTES) {
            Log.w(TAG, "avatar too large: ${bytes.size}")
            return
        }
        val prefs = FriendPrefs(app)
        val b64 = FriendAvatarCache.encodeBase64(bytes)
        writeRaw(FriendProtocol.avatar(prefs.userId, hash, b64))
        lastPushedHash.set(hash)
        FriendAvatarCache.putBytes(app, hash, bytes)
        val selfId = snapshot.selfId.ifBlank { prefs.userId }
        val members = snapshot.members.map { m ->
            if (m.userId == selfId) m.copy(pet = m.pet.copy(avatarHash = hash)) else m
        }
        publish(snapshot.copy(members = members, avatarTick = snapshot.avatarTick + 1))
        Log.i(TAG, "push avatar hash=$hash bytes=${bytes.size}")
    }

    fun ping() {
        if (!snapshot.connected) return
        runCatching { writeRaw(FriendProtocol.ping()) }
    }

    fun otherMembers(): List<FriendMember> =
        snapshot.members.filter { it.userId != snapshot.selfId }

    private fun startStateHeartbeat(app: Context) {
        stopStateHeartbeat()
        stateHeartbeat = scheduler.scheduleAtFixedRate(
            {
                if (!running.get() || writer == null) return@scheduleAtFixedRate
                runCatching { pushLocalStateNow(app, force = false) }
                    .onFailure { Log.w(TAG, "heartbeat state failed", it) }
            },
            1_500L,
            2_000L,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopStateHeartbeat() {
        stateHeartbeat?.cancel(false)
        stateHeartbeat = null
    }

    private fun handleLine(app: Context, line: String) {
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "welcome" -> {
                publish(
                    snapshot.copy(
                        connected = true,
                        status = "已入房",
                        room = obj.optString("room", snapshot.room),
                        selfId = obj.optString("userId", snapshot.selfId)
                    )
                )
                // 入房后再推一次，避免 hello 后服务端尚未登记完
                prep.execute {
                    runCatching {
                        pushLocalStateNow(app, force = true)
                        pushLocalAvatarNow(app, force = false)
                    }
                }
            }
            "room" -> {
                val members = FriendProtocol.parseMembers(obj.optJSONArray("members"))
                publish(
                    snapshot.copy(
                        connected = true,
                        status = "房间 ${members.size} 人",
                        room = obj.optString("room", snapshot.room),
                        members = members
                    )
                )
                requestMissingAvatars(app, members)
            }
            "avatar_data" -> {
                val userId = obj.optString("userId")
                val hash = obj.optString("hash")
                val data = obj.optString("data")
                if (hash.isNotBlank() && data.isNotBlank()) {
                    FriendAvatarCache.putBase64(app, hash, data)
                    val members = snapshot.members.map { m ->
                        if (userId.isNotBlank() && m.userId == userId) {
                            m.copy(pet = m.pet.copy(avatarHash = hash))
                        } else {
                            m
                        }
                    }
                    publish(
                        snapshot.copy(
                            members = members,
                            avatarTick = snapshot.avatarTick + 1
                        )
                    )
                }
            }
            "error" -> publish(snapshot.copy(status = obj.optString("message", "错误")))
            "pong" -> Unit
        }
    }

    private fun requestMissingAvatars(app: Context, members: List<FriendMember>) {
        members.forEach { m ->
            if (m.userId == snapshot.selfId) return@forEach
            val hash = m.pet.avatarHash
            if (hash.isBlank()) return@forEach
            if (FriendAvatarCache.has(app, hash)) return@forEach
            runCatching { writeRaw(FriendProtocol.avatarNeed(m.userId, hash)) }
        }
    }

    private fun publishSelfPet(pet: FriendPetSnapshot) {
        val selfId = snapshot.selfId
        if (selfId.isBlank()) return
        var changed = false
        val members = snapshot.members.map { m ->
            if (m.userId == selfId) {
                if (m.pet != pet) changed = true
                m.copy(pet = pet)
            } else {
                m
            }
        }
        if (changed) publish(snapshot.copy(members = members))
    }

    private fun writeRaw(payload: String) {
        synchronized(writeLock) {
            val w = writer ?: return
            w.write(payload)
            w.flush()
        }
    }

    private fun closeQuietly() {
        synchronized(writeLock) {
            runCatching { writer?.close() }
            writer = null
        }
        runCatching { socket?.close() }
        socket = null
    }

    private fun publish(next: FriendSnapshot) {
        snapshot = next
        listeners.forEach { runCatching { it(next) } }
    }

    private fun parseHostPort(raw: String): Pair<String, Int> {
        val text = raw.trim().removePrefix("http://").removePrefix("https://")
        val parts = text.split(":")
        val host = parts[0].ifBlank { "127.0.0.1" }
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 18765
        return host to port
    }
}
