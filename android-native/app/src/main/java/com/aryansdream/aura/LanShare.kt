package com.aryansdream.aura

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Encrypted peer-to-peer file and image transfer for signed-in Aura devices on one Wi-Fi LAN. */
object LanShare {
    private const val MAX_BYTES = 50L * 1024 * 1024
    @Volatile private var port = 0
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            Thread({ serverLoop(context.applicationContext) }, "Aura-LAN-share-server").apply { isDaemon = true; start() }
        }
    }

    fun serverPort() = port

    fun peers(context: Context): List<JSONObject> {
        val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
        val data = runCatching { org.json.JSONArray(prefs.getString("lan_peers", "[]")) }.getOrDefault(org.json.JSONArray())
        val now = System.currentTimeMillis()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { now - it.optLong("seenAt") < 15_000L && it.optInt("port") > 0 }
    }

    fun sendUri(context: Context, peer: JSONObject, uri: Uri): String {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "Aura-share-${System.currentTimeMillis()}"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val owner = accountId(context)
        val key = secret(context)
        if (owner.isBlank() || key == null) error("Sign in and connect to Aura once to set up secure Wi-Fi sharing.")
        val host = peer.optString("ip")
        val targetPort = peer.optInt("port")
        if (host.isBlank() || targetPort !in 1..65535) error("That Aura device is no longer available on Wi-Fi.")
        val iv = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
        val header = JSONObject().put("accountId", owner).put("deviceId", deviceId(context))
            .put("name", name.take(180)).put("mime", mime.take(100)).put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(header.toString().toByteArray()) }
        Socket(host, targetPort).use { socket ->
            socket.soTimeout = 30_000
            val data = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            data.writeInt(header.toString().toByteArray().size)
            data.write(header.toString().toByteArray())
            data.flush()
            CipherOutputStream(data, cipher).use { encrypted ->
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) error("Aura Wi-Fi sharing supports files up to 50 MB.")
                        encrypted.write(buffer, 0, n)
                    }
                } ?: error("Aura could not open this file.")
            }
        }
        return name
    }

    fun sendText(context: Context, peer: JSONObject, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > 100_000) error("Text shares must be under 100 KB.")
        val owner = accountId(context); val key = secret(context) ?: error("Secure Wi-Fi sharing is not ready.")
        val iv = ByteArray(12).also(java.security.SecureRandom()::nextBytes)
        val header = JSONObject().put("accountId", owner).put("deviceId", deviceId(context)).put("name", "Aura shared text")
            .put("mime", "text/plain").put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)).put("text", true)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(header.toString().toByteArray()) }
        Socket(peer.optString("ip"), peer.optInt("port")).use { socket ->
            socket.soTimeout = 30_000
            val data = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val bytesHeader = header.toString().toByteArray()
            data.writeInt(bytesHeader.size); data.write(bytesHeader); data.flush()
            CipherOutputStream(data, cipher).use { it.write(bytes) }
        }
    }

    private fun serverLoop(context: Context) {
        try {
            ServerSocket(0).use { server ->
                port = server.localPort
                while (true) runCatching { server.accept().use { receive(context, it) } }
            }
        } catch (_: Exception) { started = false; port = 0 }
    }

    private fun receive(context: Context, socket: Socket) {
        socket.soTimeout = 60_000
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val headerLength = input.readInt()
        if (headerLength !in 2..4096) return
        val bytes = ByteArray(headerLength); input.readFully(bytes)
        val header = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        if (header.optString("accountId") != accountId(context) || header.optString("deviceId") == deviceId(context)) return
        val key = secret(context) ?: return
        val iv = runCatching { android.util.Base64.decode(header.optString("iv"), android.util.Base64.NO_WRAP) }.getOrNull() ?: return
        if (iv.size != 12) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); updateAAD(bytes) }
        val safeName = header.optString("name", "Aura shared file").replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "Aura shared file" }
        val isText = header.optBoolean("text", false)
        val temp = File.createTempFile("aura-share-", ".part", context.cacheDir)
        try {
            CipherInputStream(input, cipher).use { encrypted ->
                temp.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) { val n = encrypted.read(buffer); if (n < 0) break; total += n; if (total > MAX_BYTES) error("Aura Wi-Fi sharing supports files up to 50 MB."); out.write(buffer, 0, n) }
                }
            }
            if (temp.length() > MAX_BYTES) return
            if (isText || header.optString("mime") == "text/plain" && temp.length() <= 100_000L) {
                val text = temp.readText(Charsets.UTF_8).take(100_000)
                val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
                prefs.edit().putString("lan_clipboard_text", text).putLong("lan_clipboard_at", System.currentTimeMillis())
                    .putString("lan_clipboard_origin", header.optString("deviceId")).apply()
                context.sendBroadcast(android.content.Intent(LanFocusSync.ACTION_CLIPBOARD_CHANGED).setPackage(context.packageName))
                showReceivedNotification(context, "Text received", "Aura shared text is ready on this device.")
            } else {
                saveToDownloads(context, temp, safeName, header.optString("mime", "application/octet-stream"))
                showReceivedNotification(context, "File received", "$safeName saved to Downloads/Aura Ecosystem")
            }
        } finally { temp.delete() }
    }

    private fun saveToDownloads(context: Context, source: File, name: String, mime: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Aura Ecosystem")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not save the received file.")
            try {
                context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: error("Could not write the received file.")
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (error: Exception) { context.contentResolver.delete(uri, null, null); throw error }
        } else {
            val folder = File(context.getExternalFilesDir(null), "Download/Aura Ecosystem").apply { mkdirs() }
            source.copyTo(File(folder, name), overwrite = true)
        }
    }

    private fun showReceivedNotification(context: Context, title: String, text: String) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(android.app.NotificationChannel("aura_lan_share", "Aura Wi-Fi shares", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val notification = NotificationCompat.Builder(context, "aura_lan_share")
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(title).setContentText(text).setAutoCancel(true).build()
            NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        }
    }

    private fun accountId(context: Context) = runCatching { JSONObject(context.getSharedPreferences("aura_native", Context.MODE_PRIVATE).getString("account_cache", "{}") ?: "{}").optJSONObject("user")?.optString("id").orEmpty() }.getOrDefault("")
    private fun secret(context: Context): SecretKeySpec? = runCatching {
        val account = JSONObject(context.getSharedPreferences("aura_native", Context.MODE_PRIVATE).getString("account_cache", "{}") ?: "{}")
        val hex = account.optJSONObject("profile")?.optString("lanSyncKey").orEmpty()
        if (hex.length < 64) return null
        val raw = ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(raw), "AES")
    }.getOrNull()
    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
        prefs.getString("lan_device_id", null)?.let { return it }
        val id = UUID.randomUUID().toString(); prefs.edit().putString("lan_device_id", id).apply(); return id
    }
}
