package com.geekathon.guardpet.friend

import android.content.Context
import android.util.Log
import com.geekathon.guardpet.PetAssetRepository
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 局域网好友房 TCP 客户端（JSONL）。在后台单线程读写，UI 通过监听器拿房间成员。
 */
object FriendClient {
    private const val TAG = "FriendClient"
    private val io = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(FriendSnapshot) -> Unit>()
    private val appContext = AtomicReference<Context?>(null)
    private val lastPushedHash = AtomicReference("")

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
                writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))
                writeRaw(FriendProtocol.hello(prefs.userId, prefs.displayName, prefs.roomCode))
                publish(snapshot.copy(connected = true, status = "已连接", room = prefs.roomCode, selfId = prefs.userId))
                pushLocalAvatarNow(app, force = true)
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
        lastPushedHash.set("")
        closeQuietly()
        publish(FriendSnapshot(status = "未连接"))
    }

    fun sendPetState(pet: FriendPetSnapshot) {
        if (!snapshot.connected) return
        io.execute {
            runCatching { writeRaw(FriendProtocol.state(pet)) }
        }
    }

    fun pushLocalAvatar(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        appContext.set(app)
        io.execute { pushLocalAvatarNow(app, force) }
    }

    private fun pushLocalAvatarNow(app: Context, force: Boolean) {
        if (!snapshot.connected) return
        val payload = PetAssetRepository(app).appearancePayload() ?: return
        val (hash, bytes) = payload
        if (!force && hash == lastPushedHash.get()) return
        if (bytes.size > FriendAvatarCache.MAX_BYTES) {
            Log.w(TAG, "avatar too large: ${bytes.size}")
            return
        }
        val prefs = FriendPrefs(app)
        val b64 = FriendAvatarCache.encodeBase64(bytes)
        runCatching {
            writeRaw(FriendProtocol.avatar(prefs.userId, hash, b64))
            lastPushedHash.set(hash)
            FriendAvatarCache.putBytes(app, hash, bytes)
        }.onFailure { Log.w(TAG, "push avatar failed", it) }
    }

    fun ping() {
        if (!snapshot.connected) return
        io.execute { runCatching { writeRaw(FriendProtocol.ping()) } }
    }

    fun otherMembers(): List<FriendMember> =
        snapshot.members.filter { it.userId != snapshot.selfId }

    private fun handleLine(app: Context, line: String) {
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "welcome" -> publish(
                snapshot.copy(
                    connected = true,
                    status = "已入房",
                    room = obj.optString("room", snapshot.room),
                    selfId = obj.optString("userId", snapshot.selfId)
                )
            )
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
                val hash = obj.optString("hash")
                val data = obj.optString("data")
                if (hash.isNotBlank() && data.isNotBlank()) {
                    FriendAvatarCache.putBase64(app, hash, data)
                    publish(snapshot.copy(avatarTick = snapshot.avatarTick + 1))
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

    private fun writeRaw(payload: String) {
        val w = writer ?: return
        w.write(payload)
        w.flush()
    }

    private fun closeQuietly() {
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
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
