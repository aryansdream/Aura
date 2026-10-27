package com.aryansdream.aura

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AuraApi {
    private const val base = "https://aura-day-tracker.vercel.app"
    private fun cookies(context: Context) = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
    fun clearSession(context: Context) = cookies(context).edit().remove("cookie").apply()

    fun savePlannerDraft(context: Context, data: JSONObject, accountId: String) {
        cookies(context).edit()
            .putString("planner_cache", data.toString())
            .putString("pending_planner", data.toString())
            .putString("pending_owner", accountId)
            .apply()
    }

    fun queueAction(context: Context, path: String, body: JSONObject, accountId: String) {
        val prefs = cookies(context)
        val queue = runCatching { org.json.JSONArray(prefs.getString("pending_actions", "[]")) }.getOrDefault(org.json.JSONArray())
        queue.put(JSONObject().put("path", path).put("body", body).put("owner", accountId))
        prefs.edit().putString("pending_actions", queue.toString()).apply()
    }

    fun syncPendingPlanner(context: Context, accountId: String): Boolean {
        val prefs = cookies(context)
        val queued = runCatching { org.json.JSONArray(prefs.getString("pending_actions", "[]")) }.getOrDefault(org.json.JSONArray())
        val remaining = org.json.JSONArray()
        for (i in 0 until queued.length()) {
            val action = queued.optJSONObject(i) ?: continue
            if (action.optString("owner") != accountId) { remaining.put(action); continue }
            try {
                request(context, action.optString("path"), action.optJSONObject("body"))
            } catch (error: HttpFailure) {
                if (error.status in 400..499 && error.status != 401 && error.status != 429) continue
                remaining.put(action)
                for (j in i + 1 until queued.length()) queued.optJSONObject(j)?.let(remaining::put)
                prefs.edit().putString("pending_actions", remaining.toString()).apply()
                throw error
            } catch (error: Exception) {
                remaining.put(action)
                for (j in i + 1 until queued.length()) queued.optJSONObject(j)?.let(remaining::put)
                prefs.edit().putString("pending_actions", remaining.toString()).apply()
                throw error
            }
        }
        prefs.edit().putString("pending_actions", remaining.toString()).apply()
        val snapshot = prefs.getString("pending_planner", null) ?: return true
        if (prefs.getString("pending_owner", null) != accountId) return false
        request(context, "/api/planner", JSONObject().put("data", JSONObject(snapshot)))
        // A newer edit may have arrived while this request was in flight; keep that draft queued.
        if (prefs.getString("pending_planner", null) == snapshot) {
            prefs.edit().remove("pending_planner").remove("pending_owner").apply()
        }
        return true
    }

    class HttpFailure(val status: Int, message: String) : IllegalStateException(message)

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
        val status = connection.responseCode
        saveCookies(context, connection)
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("error", "AURA returned an unreadable response.") }
        connection.disconnect()
        if (status !in 200..299) throw HttpFailure(status, json.optString("message", json.optString("error", "Request failed ($status).")))
        return json
    }

    private fun saveCookies(context: Context, connection: HttpURLConnection) {
        val headers = connection.headerFields.entries
            .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
            .flatMap { it.value.orEmpty() }
        if (headers.isEmpty()) return
        val prefs = cookies(context)
        val jar = linkedMapOf<String, String>()
        prefs.getString("cookie", null).orEmpty().split(';').forEach { part ->
            val pair = part.trim()
            val key = pair.substringBefore('=', "").trim()
            if (key.isNotEmpty() && pair.contains('=')) jar[key] = pair
        }
        headers.forEach { header ->
            val pair = header.substringBefore(';').trim()
            val key = pair.substringBefore('=', "").trim()
            if (key.isEmpty() || !pair.contains('=')) return@forEach
            val removed = header.split(';').any { it.trim().equals("Max-Age=0", ignoreCase = true) }
            if (removed) jar.remove(key) else jar[key] = pair
        }
        val edit = prefs.edit()
        if (jar.isEmpty()) edit.remove("cookie") else edit.putString("cookie", jar.values.joinToString("; "))
        edit.apply()
    }

    fun signOut(context: Context) {
        runCatching { request(context, "/api/auth/sign-out", JSONObject()) }
        clearSession(context)
        cookies(context).edit().remove("account_cache").apply()
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
