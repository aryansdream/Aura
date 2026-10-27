package com.aryansdream.aura

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

fun recordClassAttendance(
    original: JSONObject,
    subjectId: String,
    subjectName: String,
    classId: String,
    date: String,
    status: String
): JSONObject {
    val planner = JSONObject(original.toString())
    val subjects = planner.optJSONArray("subjects") ?: JSONArray()
    val resolvedId = subjectId.ifBlank {
        (0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }
            .firstOrNull { it.optString("name").equals(subjectName, ignoreCase = true) }
            ?.optString("id").orEmpty()
    }.ifBlank { "class_subject_${subjectName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_")}" }
    if ((0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }.none { it.optString("id") == resolvedId }) {
        subjects.put(JSONObject().put("id", resolvedId).put("name", subjectName).put("teacher", "").put("color", "#8B7CF6").put("notes", ""))
    }
    planner.put("subjects", subjects)
    val schedule = planner.optJSONArray("schedule") ?: JSONArray()
    for (i in 0 until schedule.length()) {
        val entry = schedule.optJSONObject(i) ?: continue
        if (entry.optString("id") == classId) entry.put("subjectId", resolvedId)
    }
    planner.put("schedule", schedule)
    val current = planner.optJSONArray("attendance") ?: JSONArray()
    val updated = JSONArray()
    for (i in 0 until current.length()) {
        val row = current.optJSONObject(i) ?: continue
        if (row.optString("classId") != classId || row.optString("date") != date) updated.put(row)
    }
    updated.put(JSONObject().put("id", "attendance_${classId}_$date").put("classId", classId)
        .put("subjectId", resolvedId).put("subjectName", subjectName).put("date", date).put("status", status))
    planner.put("attendance", updated)
    return planner
}

fun persistClassAttendance(context: Context, subjectId: String, subjectName: String, classId: String, date: String, status: String) {
    val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
    val planner = runCatching { JSONObject(prefs.getString("planner_cache", "{}") ?: "{}") }.getOrDefault(JSONObject())
    val updated = recordClassAttendance(planner, subjectId, subjectName, classId, date, status)
    val owner = runCatching {
        JSONObject(prefs.getString("account_cache", "{}") ?: "{}").optJSONObject("user")?.optString("id").orEmpty()
    }.getOrDefault("")
    AuraApi.savePlannerDraft(context, updated, owner)
}
