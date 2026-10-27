package com.aryansdream.aura

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AuraApi {
    private const val base = "https://aura-day-tracker.vercel.app"
    private fun cookies(context: Context) = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
    fun clearSession(context: Context) = cookies(context).edit().remove("cookie").apply()

    fun request(context: Context, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-aura-time-zone", java.util.TimeZone.getDefault().id)
            cookies(context).getString("cookie", null)?.let { setRequestProperty("Cookie", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        connection.headerFields["Set-Cookie"]?.forEach { header ->
            val pair = header.substringBefore(';').trim()
            if (pair.substringBefore('=').endsWith("session_token") || pair.substringBefore('=').endsWith("session")) {
                cookies(context).edit().putString("cookie", pair).apply()
            }
        }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "AURA returned an unreadable response.") }
        if (connection.responseCode !in 200..299) throw IllegalStateException(json.optString("message", json.optString("error", "Request failed (${connection.responseCode}).")))
        return json
    }

    fun signIn(context: Context, email: String, password: String, create: Boolean, name: String): JSONObject {
        if (create) clearSession(context)
        val body = JSONObject().put("email", email).put("password", password)
        val path = if (create) "/api/auth/sign-up/email" else "/api/auth/sign-in/email"
        if (create) body.put("name", name)
        val result = request(context, path, body)
        if (cookies(context).getString("cookie", null) == null) {
            val session = request(context, "/api/auth/get-session")
            if (session.isNull("user")) throw IllegalStateException("AURA could not start a signed-in session.")
        }
        return result
    }
}
