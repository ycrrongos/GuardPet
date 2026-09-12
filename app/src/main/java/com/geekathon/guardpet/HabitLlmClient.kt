package com.geekathon.guardpet

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class HabitLlmResult(
    val ok: Boolean,
    val policy: HabitPolicy? = null,
    val json: JSONObject? = null,
    val text: String? = null,
    val error: String? = null,
    val raw: String? = null
)

object HabitLlmClient {
    private val dnsCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private const val DNS_TTL_MS = 5 * 60_000L

    fun chatPolicy(systemPrompt: String, userPrompt: String): HabitLlmResult {
        val base = chatRaw(systemPrompt, userPrompt, jsonMode = true)
        if (!base.ok || base.json == null) return base
        return base.copy(policy = parseAgentPolicy(base.json))
    }

    fun chatJson(systemPrompt: String, userPrompt: String): HabitLlmResult =
        chatRaw(systemPrompt, userPrompt, jsonMode = true)

    /** 自由文本整理（大爆炸 AI）；不强制 JSON。 */
    fun chatText(systemPrompt: String, userPrompt: String): HabitLlmResult =
        chatRaw(systemPrompt, userPrompt, jsonMode = false)

    private fun chatRaw(
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean
    ): HabitLlmResult {
        val base = HabitPolicyStore.llmBaseUrl.trimEnd('/')
        val key = HabitPolicyStore.llmApiKey
        if (key.isBlank()) {
            return HabitLlmResult(ok = false, error = "未配置 API Key")
        }
        return runCatching {
            val url = URL("$base/chat/completions")
            val body = JSONObject()
                .put("model", HabitPolicyStore.llmModel)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPrompt))
                )
                .put("temperature", if (jsonMode) 0.2 else 0.4)
            val conn = openConnection(url).apply {
                requestMethod = "POST"
                connectTimeout = 25_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $key")
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                val brief = text.replace('\n', ' ').take(160)
                return@runCatching HabitLlmResult(
                    ok = false,
                    error = if (brief.isBlank()) "HTTP $code" else "HTTP $code · $brief",
                    raw = text
                )
            }
            val message = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
            val content = messageContent(message).trim()
            if (content.isBlank()) {
                return@runCatching HabitLlmResult(ok = false, error = "模型返回空内容", raw = text)
            }
            if (!jsonMode) {
                return@runCatching HabitLlmResult(ok = true, text = stripFences(content), raw = content)
            }
            val json = extractJsonObject(content)
                ?: return@runCatching HabitLlmResult(ok = false, error = "模型未返回 JSON", raw = content)
            HabitLlmResult(ok = true, json = json, raw = content)
        }.getOrElse {
            HabitLlmResult(ok = false, error = friendlyNetError(it))
        }
    }

    /**
     * 默认走系统默认栈（不 bind Network）。
     * 真机日志：`Network.openConnection` / `bindSocket` 会出现 ENONET、ECONNREFUSED，
     * 而 shell ping 与作息页「应用分类 AI」在默认栈上正常。大爆炸 AI 与之共用本客户端。
     * 系统 DNS 失败时：UDP(223.5.5.5) → DoH → 对 IP 建连并带原域名 SNI。
     */
    private fun openConnection(url: URL): HttpURLConnection {
        android.util.Log.i("HabitLlmClient", "openConnection host=${url.host}")
        val systemDnsOk = runCatching {
            InetAddress.getByName(url.host)
            true
        }.getOrDefault(false)
        if (systemDnsOk || url.protocol != "https" || isLiteralIp(url.host)) {
            return url.openConnection() as HttpURLConnection
        }
        val ip = resolveIpv4(url.host)
            ?: throw java.net.UnknownHostException(url.host)
        android.util.Log.i("HabitLlmClient", "DNS fallback ${url.host} -> $ip")
        val ipUrl = URL(url.protocol, ip, url.port, url.file)
        val conn = ipUrl.openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.setRequestProperty("Host", url.host)
            val defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
            conn.sslSocketFactory = SniSslSocketFactory(defaultFactory, url.host)
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, session)
            }
        }
        return conn
    }

    private fun resolveIpv4(host: String): String? {
        val cached = dnsCache[host]
        if (cached != null && System.currentTimeMillis() - cached.second < DNS_TTL_MS) {
            return cached.first
        }
        val network = activeNetwork()
        val resolved = resolveIpv4ViaUdp(host)
            ?: resolveIpv4ViaOs(host, network)
            ?: resolveIpv4ViaDoh(host)
        if (resolved != null) {
            dnsCache[host] = resolved to System.currentTimeMillis()
        }
        return resolved
    }

    /** 裸 UDP DNS（223.5.5.5:53）。切勿 bindSocket(Network)，真机会 ECONNREFUSED。 */
    private fun resolveIpv4ViaUdp(host: String): String? {
        return runCatching {
            val query = buildDnsQuery(host)
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = 5_000
                val server = InetAddress.getByAddress(byteArrayOf(223.toByte(), 5, 5, 5))
                socket.send(java.net.DatagramPacket(query, query.size, server, 53))
                val buf = ByteArray(512)
                val resp = java.net.DatagramPacket(buf, buf.size)
                socket.receive(resp)
                parseDnsA(buf, resp.length)
            }
        }.onFailure {
            android.util.Log.w("HabitLlmClient", "UDP DNS failed: ${it.message}")
        }.getOrNull()
    }

    private fun buildDnsQuery(host: String): ByteArray {
        val labels = host.trimEnd('.').split('.')
        val nameSize = labels.sumOf { 1 + it.length } + 1
        val out = ByteArray(12 + nameSize + 4)
        out[0] = 0x12
        out[1] = 0x34
        out[2] = 0x01 // recursion desired
        out[5] = 0x01 // qdcount = 1
        var i = 12
        for (label in labels) {
            out[i++] = label.length.toByte()
            for (c in label) out[i++] = c.code.toByte()
        }
        out[i++] = 0
        out[i++] = 0
        out[i++] = 1 // type A
        out[i++] = 0
        out[i] = 1 // class IN
        return out
    }

    private fun parseDnsA(buf: ByteArray, length: Int): String? {
        if (length < 12) return null
        val ancount = ((buf[6].toInt() and 0xff) shl 8) or (buf[7].toInt() and 0xff)
        var i = 12
        // skip question
        while (i < length && buf[i].toInt() != 0) {
            val n = buf[i].toInt() and 0xff
            if (n >= 0xc0) {
                i += 2
                break
            }
            i += 1 + n
        }
        if (i < length && buf[i].toInt() == 0) i++
        i += 4 // type+class
        repeat(ancount) {
            if (i >= length) return null
            if ((buf[i].toInt() and 0xc0) == 0xc0) {
                i += 2
            } else {
                while (i < length && buf[i].toInt() != 0) {
                    val n = buf[i].toInt() and 0xff
                    if (n >= 0xc0) {
                        i += 2
                        break
                    }
                    i += 1 + n
                }
                if (i < length && buf[i].toInt() == 0) i++
            }
            if (i + 10 > length) return null
            val type = ((buf[i].toInt() and 0xff) shl 8) or (buf[i + 1].toInt() and 0xff)
            val rdlength = ((buf[i + 8].toInt() and 0xff) shl 8) or (buf[i + 9].toInt() and 0xff)
            i += 10
            if (type == 1 && rdlength == 4 && i + 4 <= length) {
                return "${buf[i].toInt() and 0xff}.${buf[i + 1].toInt() and 0xff}." +
                    "${buf[i + 2].toInt() and 0xff}.${buf[i + 3].toInt() and 0xff}"
            }
            i += rdlength
        }
        return null
    }

    private fun resolveIpv4ViaOs(host: String, network: Network?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || network == null) return null
        return runCatching {
            val latch = CountDownLatch(1)
            var answer: List<InetAddress> = emptyList()
            DnsResolver.getInstance().query(
                network,
                host,
                DnsResolver.FLAG_EMPTY,
                Executors.newSingleThreadExecutor(),
                CancellationSignal(),
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(ans: List<InetAddress>, rcode: Int) {
                        answer = ans
                        latch.countDown()
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        android.util.Log.w("HabitLlmClient", "DnsResolver error: ${error.message}")
                        latch.countDown()
                    }
                }
            )
            if (!latch.await(8, TimeUnit.SECONDS)) return@runCatching null
            answer.firstOrNull { it.hostAddress?.contains(':') != true }?.hostAddress
        }.getOrNull()
    }

    private fun resolveIpv4ViaDoh(host: String): String? {
        return runCatching {
            // 直连 DoH IP，避免 dns.alidns.com 本身也解析失败；不 bind Network
            val doh = URL("https://223.5.5.5/resolve?name=${host}&type=A")
            val conn = (doh.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Host", "dns.alidns.com")
            }
            if (conn is HttpsURLConnection) {
                val factory = HttpsURLConnection.getDefaultSSLSocketFactory()
                conn.sslSocketFactory = SniSslSocketFactory(factory, "dns.alidns.com")
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                    HttpsURLConnection.getDefaultHostnameVerifier().verify("dns.alidns.com", session)
                }
            }
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val answers = JSONObject(body).optJSONArray("Answer") ?: return@runCatching null
            var ip: String? = null
            for (i in 0 until answers.length()) {
                val a = answers.optJSONObject(i) ?: continue
                if (a.optInt("type") == 1) {
                    val data = a.optString("data").trim()
                    if (isLiteralIp(data)) {
                        ip = data
                        break
                    }
                }
            }
            ip
        }.onFailure {
            android.util.Log.w("HabitLlmClient", "DoH failed: ${it.message}")
        }.getOrNull()
    }

    private fun activeNetwork(): Network? {
        val ctx = HabitPolicyStore.appContextOrNull() ?: return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        fun usable(net: Network): Boolean {
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        cm.activeNetwork?.takeIf(::usable)?.let { return it }
        // 已验证的非 VPN 优先
        cm.allNetworks.firstOrNull { net ->
            usable(net) &&
                (cm.getNetworkCapabilities(net)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true)
        }?.let { return it }
        return cm.allNetworks.firstOrNull(::usable)
    }

    private fun isLiteralIp(host: String): Boolean =
        host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))

    private fun friendlyNetError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("Unable to resolve host", ignoreCase = true) ||
                t is java.net.UnknownHostException ->
                "无法解析 LLM 域名（检查网络/DNS，或在作息页改 Base URL）"
            msg.contains("timeout", ignoreCase = true) ||
                t is java.net.SocketTimeoutException ->
                "LLM 请求超时，请稍后重试"
            else -> msg.ifBlank { "网络错误" }
        }
    }

    /** OpenAI 兼容接口：content 可能是 string，也可能是多段 array。 */
    private fun messageContent(message: JSONObject): String {
        if (!message.has("content") || message.isNull("content")) return ""
        return when (val raw = message.get("content")) {
            is String -> raw
            is JSONArray -> buildString {
                for (i in 0 until raw.length()) {
                    when (val part = raw.opt(i)) {
                        is String -> append(part)
                        is JSONObject -> append(part.optString("text"))
                    }
                }
            }
            else -> raw.toString()
        }
    }

    private fun stripFences(content: String): String {
        val fence = Regex("```(?:\\w+)?\\s*([\\s\\S]*?)```")
        return fence.find(content)?.groupValues?.getOrNull(1)?.trim() ?: content.trim()
    }

    private fun extractJsonObject(content: String): JSONObject? {
        val trimmed = content.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val fenced = fence.find(trimmed)?.groupValues?.getOrNull(1)?.trim()
        val candidate = fenced ?: trimmed
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(candidate.substring(start, end + 1)) }.getOrNull()
    }

    private fun parseAgentPolicy(obj: JSONObject): HabitPolicy {
        val rulesArr = obj.optJSONArray("packageRules") ?: JSONArray()
        val rules = buildList {
            for (i in 0 until rulesArr.length()) {
                val r = rulesArr.optJSONObject(i) ?: continue
                val pkg = r.optString("packageName").trim()
                if (pkg.isBlank()) continue
                val surfacesArr = r.optJSONArray("surfaces") ?: JSONArray()
                val surfaces = buildList {
                    for (j in 0 until surfacesArr.length()) {
                        val s = surfacesArr.optJSONObject(j) ?: continue
                        val patternsArr = s.optJSONArray("patterns") ?: JSONArray()
                        val patterns = buildList {
                            for (k in 0 until patternsArr.length()) {
                                val p = patternsArr.optString(k).trim()
                                if (p.isNotEmpty()) add(p)
                            }
                        }
                        if (patterns.isEmpty()) continue
                        add(
                            SurfaceRule(
                                id = s.optString("id").ifBlank { "s_${pkg}_$j" },
                                label = s.optString("label").ifBlank { "娱乐面" },
                                match = SurfaceMatchMode.fromKey(s.optString("match")),
                                patterns = patterns,
                                severity = SurfaceSeverity.fromKey(
                                    s.optString("severity").ifBlank { "BLOCK" }
                                )
                            )
                        )
                    }
                }
                val modeRaw = PackageRuleMode.fromKey(r.optString("mode"))
                val kind = if (r.has("kind")) {
                    AppGuardKind.fromKey(r.optString("kind"))
                } else {
                    AppGuardKind.fromMode(modeRaw)
                }
                val mode = when {
                    kind == AppGuardKind.VIDEO -> PackageRuleMode.SEARCH_ONLY
                    else -> modeRaw
                }
                val nextSurfaces = if (kind == AppGuardKind.VIDEO && surfaces.isEmpty()) {
                    HabitPolicyStore.defaultVideoSurfaces(pkg.substringAfterLast('.').take(12))
                } else {
                    surfaces
                }
                add(
                    PackageRule(
                        packageName = pkg,
                        mode = mode,
                        surfaces = nextSurfaces,
                        reason = r.optString("reason"),
                        manualOverride = false,
                        kind = kind
                    )
                )
            }
        }
        return HabitPolicy(
            summary = obj.optString("summary"),
            generatedAt = System.currentTimeMillis(),
            packageRules = rules.ifEmpty { HabitPolicyStore.seedRules() }
        )
    }
}

/** 连 IP 时仍对原域名做 SNI / 证书校验。 */
private class SniSslSocketFactory(
    private val delegate: SSLSocketFactory,
    private val peerHost: String
) : SSLSocketFactory() {
    private fun tune(socket: Socket): Socket {
        if (socket is SSLSocket) {
            val params = socket.sslParameters
            params.serverNames = listOf(SNIHostName(peerHost))
            socket.sslParameters = params
        }
        return socket
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        tune(delegate.createSocket(s, peerHost, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        tune(delegate.createSocket(InetAddress.getByName(host), port))

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = tune(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(address: InetAddress, port: Int): Socket =
        tune(delegate.createSocket(address, port))

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = tune(delegate.createSocket(address, port, localAddress, localPort))
}
