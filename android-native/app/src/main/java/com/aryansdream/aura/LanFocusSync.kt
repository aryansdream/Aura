package com.aryansdream.aura

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Executors.newSingleThreadScheduledExecutor
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Signed, same-Wi-Fi focus-state beacons. No focus state is sent to the cloud. */
object LanFocusSync {
    private const val PORT = 41331
    private val executor = Executors.newSingleThreadExecutor()
    private val beaconExecutor = newSingleThreadScheduledExecutor()
    @Volatile private var receiving = false
    const val ACTION_CLIPBOARD_CHANGED = "com.aryansdream.aura.SYNCED_CLIPBOARD_CHANGED"

    fun start(context: Context) {
        if (receiving) return
        synchronized(this) {
            if (receiving) return
            receiving = true
            val app = context.applicationContext
            LanShare.start(app)
            Thread({ receiveLoop(app) }, "Aura-LAN-focus-receiver").apply { isDaemon = true; start() }
            beaconExecutor.scheduleAtFixedRate({ sendHello(app) }, 0, 2, TimeUnit.SECONDS)
        }
    }

    fun publish(context: Context, sessionId: String, active: Boolean) {
        val app = context.applicationContext
        executor.execute {
            runCatching {
                if (!isWifiConnected(app)) return@runCatching
                val prefs = app.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
                val account = JSONObject(prefs.getString("account_cache", "{}") ?: "{}")
                val userId = account.optJSONObject("user")?.optString("id").orEmpty()
                val key = account.optJSONObject("profile")?.optString("lanSyncKey").orEmpty()
                if (userId.isBlank() || key.length < 32) return@runCatching
                val payload = JSONObject()
                    .put("kind", "focus")
                    .put("accountId", userId)
                    .put("deviceId", deviceId(prefs))
                    .put("sentAt", System.currentTimeMillis())
                    .put("active", active)
                    .put("sessionId", sessionId).put("port", LanShare.serverPort())
                if (active) {
                    payload.put("subject", prefs.getString("focus_subject", "").orEmpty())
                        .put("subjectId", prefs.getString("focus_subject_id", "").orEmpty())
                        .put("startedAt", prefs.getLong("focus_started_wall", System.currentTimeMillis()))
                        .put("endsAt", prefs.getLong("focus_wall_deadline", System.currentTimeMillis()))
                        .put("blockApps", prefs.getBoolean("block_social_apps", false))
                        .put("blockNotifications", prefs.getBoolean("block_social_notifications", false))
                        .put("waterInterval", prefs.getInt("water_interval_minutes", 30).coerceIn(5, 90))
                        .put("blockedPackages", org.json.JSONArray(FocusAppPolicy.blockedPackages(app).take(40)))
                }
                val envelope = JSONObject().put("payload", payload.toString()).put("signature", sign(key, payload.toString()))
                val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > 4096) return@runCatching
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.send(DatagramPacket(bytes, bytes.size, broadcastAddress(app), PORT))
                }
            }
        }
    }

    /** Share one idempotent focus-point event to signed-in Aura devices on this Wi-Fi. */
    fun hasSeenPointEvent(context: Context, eventId: String): Boolean =
        context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
            .getStringSet("lan_seen_point_events", emptySet()).orEmpty().contains(eventId)

    fun publishPoint(context: Context, eventId: String, day: String, points: Int = 10) {
        if (eventId.isBlank() || eventId.length > 100 || points !in 1..10 || !day.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
        val seen = HashSet(prefs.getStringSet("lan_seen_point_events", emptySet()).orEmpty())
        if (!seen.add(eventId)) return
        prefs.edit().putStringSet("lan_seen_point_events", seen.takeLast(4000).toSet()).apply()
        executor.execute {
            runCatching {
                if (!isWifiConnected(app)) return@runCatching
                val account = JSONObject(prefs.getString("account_cache", "{}") ?: "{}")
                val userId = account.optJSONObject("user")?.optString("id").orEmpty()
                val key = account.optJSONObject("profile")?.optString("lanSyncKey").orEmpty()
                if (userId.isBlank() || key.length < 32) return@runCatching
                val payload = JSONObject().put("kind", "point").put("accountId", userId)
                    .put("deviceId", deviceId(prefs)).put("sentAt", System.currentTimeMillis())
                    .put("eventId", eventId).put("day", day).put("points", points)
                val body = payload.toString()
                val envelope = JSONObject().put("payload", body).put("signature", sign(key, body))
                val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    repeat(3) { attempt ->
                        socket.send(DatagramPacket(bytes, bytes.size, broadcastAddress(app), PORT))
                        if (attempt < 2) Thread.sleep(180)
                    }
                }
            }
        }
    }

    private fun sendHello(context: Context) {
        runCatching {
            if (!isWifiConnected(context)) return@runCatching
            val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
            val account = JSONObject(prefs.getString("account_cache", "{}") ?: "{}")
            val userId = account.optJSONObject("user")?.optString("id").orEmpty()
            val key = account.optJSONObject("profile")?.optString("lanSyncKey").orEmpty()
            val port = LanShare.serverPort()
            if (userId.isBlank() || key.length < 32 || port == 0) return@runCatching
            val payload = JSONObject().put("kind", "hello").put("accountId", userId)
                .put("deviceId", deviceId(prefs)).put("deviceName", Build.MODEL.take(40))
                .put("port", port).put("sentAt", System.currentTimeMillis())
            val body = payload.toString()
            val envelope = JSONObject().put("payload", body).put("signature", sign(key, body))
            val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(DatagramPacket(bytes, bytes.size, broadcastAddress(context), PORT))
            }
        }
    }

    fun syncClipboard(context: Context, text: String) {
        val safe = text.take(6000)
        if (safe.isBlank()) return
        val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
        if (prefs.getString("lan_clipboard_text", null) == safe) return
        val deviceId = deviceId(prefs)
        val timestamp = System.currentTimeMillis()
        prefs.edit().putString("lan_clipboard_text", safe).putLong("lan_clipboard_at", timestamp)
            .putString("lan_clipboard_origin", deviceId).apply()
        broadcastClipboard(context, safe, timestamp, deviceId)
    }

    private fun broadcastClipboard(context: Context, text: String, timestamp: Long, origin: String) {
        val app = context.applicationContext
        executor.execute {
            runCatching {
                if (!isWifiConnected(app)) return@runCatching
                val prefs = app.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
                val account = JSONObject(prefs.getString("account_cache", "{}") ?: "{}")
                val userId = account.optJSONObject("user")?.optString("id").orEmpty()
                val key = account.optJSONObject("profile")?.optString("lanSyncKey").orEmpty()
                if (userId.isBlank() || key.length < 32) return@runCatching
                val payload = JSONObject().put("kind", "clipboard").put("accountId", userId)
                    .put("deviceId", deviceId(prefs)).put("sentAt", System.currentTimeMillis())
                    .put("text", text).put("clipAt", timestamp).put("origin", origin)
                val envelope = JSONObject().put("payload", payload.toString()).put("signature", sign(key, payload.toString()))
                val bytes = envelope.toString().toByteArray(Charsets.UTF_8)
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.send(DatagramPacket(bytes, bytes.size, broadcastAddress(app), PORT))
                }
            }
        }
    }

    private fun receiveLoop(context: Context) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = runCatching { wifi.createMulticastLock("AuraLocalFocusSync").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            while (receiving) {
                try {
                    DatagramSocket(null).use { socket ->
                        socket.reuseAddress = true
                        socket.bind(InetSocketAddress(PORT))
                        val buffer = ByteArray(32_768)
                        while (receiving) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val envelope = runCatching { JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                    val body = envelope.optString("payload")
                    val message = runCatching { JSONObject(body) }.getOrNull() ?: continue
                    val kind = message.optString("kind")
                    val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
                    val account = runCatching { JSONObject(prefs.getString("account_cache", "{}") ?: "{}") }.getOrDefault(JSONObject())
                    val currentUser = account.optJSONObject("user")?.optString("id").orEmpty()
                    val key = account.optJSONObject("profile")?.optString("lanSyncKey").orEmpty()
                    val messageAccount = message.optString("accountId")
                    if (currentUser.isBlank() || messageAccount.isBlank() || key.length < 32 || messageAccount != currentUser || message.optString("deviceId") == deviceId(prefs)) continue
                    if (!constantTimeEquals(envelope.optString("signature"), sign(key, body))) continue
                    val age = System.currentTimeMillis() - message.optLong("sentAt", 0L)
                    if (age !in 0..8_000L) continue
                    if (kind == "hello") {
                        if (message.optInt("port") in 1..65535) updatePeer(context, message, packet.address.hostAddress.orEmpty())
                        continue
                    }
                    if (kind == "clipboard") {
                        if (!prefs.getBoolean("lan_clipboard_sync", true)) continue
                        val text = message.optString("text")
                        val clipAt = message.optLong("clipAt")
                        if (text.isBlank() || text.length > 6000 || clipAt <= prefs.getLong("lan_clipboard_at", 0L)) continue
                        prefs.edit().putString("lan_clipboard_text", text).putLong("lan_clipboard_at", clipAt)
                            .putString("lan_clipboard_origin", message.optString("origin")).apply()
                        context.sendBroadcast(Intent(ACTION_CLIPBOARD_CHANGED).setPackage(context.packageName))
                        continue
                    }
                    if (kind == "point") {
                        val eventId = message.optString("eventId")
                        val day = message.optString("day")
                        val points = message.optInt("points")
                        if (eventId.isBlank() || eventId.length > 100 || !day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || points !in 1..10) continue
                        val seen = HashSet(prefs.getStringSet("lan_seen_point_events", emptySet()).orEmpty())
                        if (!seen.add(eventId)) continue
                        val cached = runCatching { JSONObject(prefs.getString("account_cache", "{}") ?: "{}") }.getOrDefault(JSONObject())
                        val stats = cached.optJSONObject("stats") ?: JSONObject()
                        stats.put("total", stats.optInt("total") + points)
                        stats.put("completed", stats.optInt("completed") + 1)
                        if (day == auraDay().toString()) stats.put("today", stats.optInt("today") + 1)
                        cached.put("stats", stats)
                        val days = cached.optJSONArray("days") ?: org.json.JSONArray()
                        if ((0 until days.length()).none { days.optString(it) == day }) {
                            val updatedDays = (0 until days.length()).map { days.optString(it) }.plus(day).distinct().sortedDescending()
                            cached.put("days", org.json.JSONArray(updatedDays))
                        }
                        prefs.edit().putStringSet("lan_seen_point_events", seen.takeLast(4000).toSet())
                            .putString("account_cache", cached.toString()).apply()
                        AuraWidgetProvider.sync(context, cached)
                        continue
                    }
                    if (kind != "focus") continue
                    val session = message.optString("sessionId")
                    if (session.isBlank()) continue
                    if (message.optBoolean("active", false)) {
                        if (prefs.getString("focus_session", null) == session && prefs.getBoolean("focus_running", false)) continue
                        val endsAt = message.optLong("endsAt")
                        val remaining = (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
                        if (remaining <= 0L) continue
                        val start = Intent(context, FocusService::class.java)
                            .putExtra("subjectId", message.optString("subjectId"))
                            .putExtra("subject", message.optString("subject"))
                            .putExtra("duration", remaining)
                            .putExtra("startedAt", message.optLong("startedAt"))
                            .putExtra("endsAt", endsAt)
                            .putExtra("blockApps", message.optBoolean("blockApps"))
                            .putExtra("blockNotifications", message.optBoolean("blockNotifications"))
                            .putExtra("waterInterval", message.optInt("waterInterval", 30))
                            .putExtra("blockedPackages", message.optJSONArray("blockedPackages")?.toString())
                            .putExtra("session", session)
                            .putExtra("remoteSync", true)
                        runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start) else context.startService(start) }
                    } else if (prefs.getBoolean("focus_running", false) && prefs.getString("focus_session", null) == session) {
                        runCatching { context.startService(Intent(context, FocusService::class.java).setAction(FocusService.ACTION_STOP_REMOTE)) }
                    }
                        }
                    }
                } catch (_: Exception) {
                    if (receiving) try { Thread.sleep(1_000L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
                }
            }
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun updatePeer(context: Context, message: JSONObject, ip: String) {
        val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
        val existing = runCatching { org.json.JSONArray(prefs.getString("lan_peers", "[]")) }.getOrDefault(org.json.JSONArray())
        val peers = org.json.JSONArray()
        val id = message.optString("deviceId")
        for (i in 0 until existing.length()) existing.optJSONObject(i)?.takeIf { it.optString("deviceId") != id && System.currentTimeMillis() - it.optLong("seenAt") < 15_000L }?.let(peers::put)
        peers.put(JSONObject().put("deviceId", id).put("name", message.optString("deviceName", "Aura device"))
            .put("ip", ip).put("port", message.optInt("port")).put("seenAt", System.currentTimeMillis()))
        prefs.edit().putString("lan_peers", peers.toString()).apply()
    }

    private fun isWifiConnected(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Look for a connected Wi-Fi transport directly. A hotspot with no WAN has no
        // validated internet route, and may not be ConnectivityManager.activeNetwork.
        return manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun broadcastAddress(context: Context): InetAddress {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifi.dhcpInfo
        val ip = dhcp.ipAddress
        if (ip != 0) {
            val mask = if (dhcp.netmask == 0) 0x00ffffff else dhcp.netmask
            val broadcast = (ip and mask) or mask.inv()
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(broadcast).array()
            return InetAddress.getByAddress(bytes)
        }
        // Android's tethering host can report an empty DhcpInfo. Use the local Wi-Fi
        // interface broadcast address so a phone hotspot still works without WAN data.
        val interfaceBroadcast = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.isUp && (it.name.startsWith("wlan") || it.name.startsWith("ap")) }
                .flatMap { it.interfaceAddresses.asSequence() }
                .mapNotNull { it.broadcast }
                .firstOrNull()
        }.getOrNull()
        return interfaceBroadcast ?: InetAddress.getByName("255.255.255.255")
    }

    private fun deviceId(prefs: android.content.SharedPreferences): String {
        prefs.getString("lan_device_id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString("lan_device_id", id).apply()
        return id
    }

    private fun sign(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var diff = 0
        for (i in left.indices) diff = diff or (left[i].code xor right[i].code)
        return diff == 0
    }
}
