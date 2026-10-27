package com.aryansdream.aura

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.app.AlarmManager
import android.Manifest
import android.provider.Settings
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime

private val Night = Color(0xFF11131A)
private val Surface = Color(0xFF1B1E28)
private val Purple = Color(0xFFB5A9FF)
private val Muted = Color(0xFFA3A6B5)

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Night, surface = Surface, onBackground = Color(0xFFF4F2FC), onSurface = Color(0xFFF4F2FC), secondary = Color(0xFF83D6C4))) {
                AuraApp(
                    onStartFocus = { subjectId, subject, minutes ->
                        val intent = Intent(this, FocusService::class.java).putExtra("subjectId", subjectId).putExtra("subject", subject).putExtra("duration", minutes * 60_000L)
                            .putExtra("session", java.util.UUID.randomUUID().toString())
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                    },
                    onStopFocus = { startService(Intent(this, FocusService::class.java).setAction(FocusService.ACTION_STOP)) }
                )
            }
        }
    }

}

@Composable
private fun AuraApp(onStartFocus: (String, String, Int) -> Unit, onStopFocus: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val localPrefs = remember { context.getSharedPreferences("aura_native", 0) }
    var account by remember { mutableStateOf(runCatching { localPrefs.getString("account_cache", null)?.let(::JSONObject) }.getOrNull()) }
    var user by remember { mutableStateOf(account?.optJSONObject("user")) }
    var planner by remember { mutableStateOf(runCatching { localPrefs.getString("planner_cache", null)?.let(::JSONObject) ?: JSONObject() }.getOrDefault(JSONObject())) }
    var leaderboard by remember { mutableStateOf(JSONArray()) }
    var page by remember { mutableStateOf("Today") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var loginEmail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var subjectChoice by remember { mutableStateOf("") }
    var focusMinutes by remember { mutableIntStateOf(25) }
    var focusNow by remember { mutableLongStateOf(0L) }
    var syncMessage by remember { mutableStateOf("") }
    val focusFullScreen = page == "Focus" && localPrefs.getBoolean("focus_running", false)
    BackHandler(enabled = focusFullScreen) {
        onStopFocus()
        page = "Today"
    }
    SideEffect {
        val activity = context as? ComponentActivity ?: return@SideEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (focusFullScreen) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    fun load() {
        scope.launch {
            busy = true; error = ""
            try {
                val (me, plan) = withContext(Dispatchers.IO) {
                    // Refresh Better Auth's persistent session cookie before asking for account data.
                    runCatching { AuraApi.request(context, "/api/auth/get-session") }
                    val account = AuraApi.request(context, "/api/me")
                    val accountId = account.optJSONObject("user")?.optString("id").orEmpty()
                    if (accountId.isNotBlank()) runCatching { AuraApi.syncPendingPlanner(context, accountId) }
                    account to AuraApi.request(context, "/api/planner")
                }
                account = me; user = me.optJSONObject("user")
                val accountId = user?.optString("id").orEmpty()
                val pendingDraft = if (localPrefs.getString("pending_owner", null) == accountId) localPrefs.getString("pending_planner", null) else null
                planner = pendingDraft?.let(::JSONObject) ?: plan.optJSONObject("data") ?: JSONObject()
                val existingSchedule = planner.optJSONArray("schedule") ?: JSONArray()
                val classesOnly = JSONArray(); for (i in 0 until existingSchedule.length()) existingSchedule.optJSONObject(i)?.takeIf { it.optString("kind", "Class") == "Class" }?.let(classesOnly::put)
                planner.put("schedule", classesOnly)
                AuraWidgetProvider.sync(context, me)
                localPrefs.edit().putString("account_cache", me.toString()).putString("planner_cache", planner.toString()).apply()
                ClassReminderScheduler.sync(context, planner)
                TaskReminderScheduler.sync(context, planner)
                if (subjectChoice.isBlank()) subjectChoice = planner.optJSONArray("subjects")?.optJSONObject(0)?.optString("id").orEmpty()
            } catch (e: Exception) {
                // Keep the signed-in account and its last synced data visible during outages or expired connections.
                error = "Could not refresh AURA right now. Your saved account is still here; check your connection and tap refresh."
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (context.getSharedPreferences("aura_native", 0).getString("cookie", null) != null) load()
        while (true) { focusNow = android.os.SystemClock.elapsedRealtime(); delay(1000) }
    }

    LaunchedEffect(user?.optString("id")) {
        val accountId = user?.optString("id").orEmpty()
        if (accountId.isNotBlank()) while (true) {
            delay(3 * 60 * 60 * 1000L)
            try {
                val latest = withContext(Dispatchers.IO) {
                    AuraApi.syncPendingPlanner(context, accountId)
                    AuraApi.request(context, "/api/planner")
                }.optJSONObject("data") ?: JSONObject()
                planner = latest
                localPrefs.edit().putString("planner_cache", latest.toString()).apply()
                syncMessage = "AURA data synced."
            } catch (_: Exception) {
                syncMessage = "Saved on this device. AURA will retry the sync later."
            }
        }
    }

    if (user == null) {
        AuthScreen(creating, { creating = !creating }, loginEmail, { loginEmail = it }, password, { password = it }, displayName, { displayName = it }, busy, error) {
            scope.launch {
                busy = true; error = ""
                try { withContext(Dispatchers.IO) { AuraApi.signIn(context, loginEmail.trim(), password, creating, displayName.trim()) }; load() }
                catch (e: Exception) { busy = false; error = e.message ?: "Could not sign in." }
            }
        }
        return
    }

    fun saveNativePlanner(next: JSONObject, successMessage: String) {
        planner = next
        AuraApi.savePlannerDraft(context, next, user?.optString("id").orEmpty())
        // Keep task data safe even if a device-specific AlarmManager call fails.
        runCatching { ClassReminderScheduler.sync(context, next) }
        runCatching { TaskReminderScheduler.sync(context, next) }
        scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { AuraApi.syncPendingPlanner(context, user?.optString("id").orEmpty()) }; syncMessage = successMessage }
            catch (_: Exception) { syncMessage = "Saved on this device. AURA will sync when connected." }
            busy = false
        }
    }

    Scaffold(containerColor = if (focusFullScreen) Color.Black else Night, bottomBar = {
        if (!focusFullScreen) NavigationBar(containerColor = Surface, contentColor = Muted) {
            listOf("Today" to Icons.Default.Home, "Focus" to Icons.Default.Timer, "Subjects" to Icons.Default.MenuBook, "Classes" to Icons.Default.CalendarMonth, "Tasks" to Icons.Default.Alarm, "More" to Icons.Default.MoreVert).forEach { (label, icon) ->
                NavigationBarItem(selected = page == label || (label == "More" && page in setOf("Table", "Info")), onClick = { page = label; if (label == "More") { } }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label, fontSize = 10.sp) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(if (focusFullScreen) Color.Black else Night)) {
            if (!focusFullScreen) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { Text("∞", color = Purple, fontSize = 39.sp, fontWeight = FontWeight.Bold); Text("AURA", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = .5.sp) }; Column(Modifier.padding(start = 6.dp)) { Text("AURA", color = Purple, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 15.sp); Text("Aura-Coz you need it!", color = Muted, fontSize = 10.sp) } }
                IconButton(onClick = { load() }) { Icon(Icons.Default.Refresh, "Refresh", tint = Muted) }
            }
            if (!focusFullScreen && error.isNotBlank()) Text(error, Modifier.padding(horizontal = 20.dp), color = Color(0xFFFF8D8D), fontSize = 12.sp)
            if (!focusFullScreen && syncMessage.isNotBlank() && error.isBlank()) Text(syncMessage, Modifier.padding(horizontal = 20.dp), color = Color(0xFF83D6C4), fontSize = 11.sp)
            when (page) {
                "Today" -> HomePage(account, planner, onOpenStudy = { page = "Classes" }, saveAttendance = { next -> saveNativePlanner(next, "Attendance saved and synced.") })
                "Focus" -> FocusPage(planner.optJSONArray("subjects") ?: JSONArray(), subjectChoice, { subjectChoice = it }, focusMinutes, { focusMinutes = it }, context.getSharedPreferences("aura_native", 0).getLong("focus_deadline", 0), focusNow, onStartFocus, onStopFocus)
                "Subjects" -> StudyPage(planner, "Subjects") { next -> saveNativePlanner(next, "Subjects saved and synced.") }
                "Classes" -> StudyPage(planner, "Classes") { next -> saveNativePlanner(next, "Class timetable saved and synced.") }
                "Tasks" -> TasksPage(context, planner) { next ->
                    planner = next
                    AuraApi.savePlannerDraft(context, next, user?.optString("id").orEmpty())
                    TaskReminderScheduler.sync(context, next)
                    scope.launch {
                        busy = true
                        try { withContext(Dispatchers.IO) { AuraApi.syncPendingPlanner(context, user?.optString("id").orEmpty()) }; syncMessage = "Task saved and reminder scheduled." }
                        catch (_: Exception) { syncMessage = "Task saved on this device. It will sync when connected." }
                        busy = false
                    }
                }
                "More" -> MorePage(
                    onTable = {
                        page = "Table"
                        scope.launch { leaderboard = withContext(Dispatchers.IO) { AuraApi.request(context, "/api/leaderboard").optJSONArray("entries") ?: JSONArray() } }
                    },
                    onInfo = { page = "Info" }
                )
                "Table" -> LeaderboardPage(leaderboard)
                "Info" -> InfoPage(onSignOut = {
                    scope.launch {
                        busy = true
                        withContext(Dispatchers.IO) { AuraApi.signOut(context) }
                        account = null; user = null; planner = JSONObject(); leaderboard = JSONArray()
                        error = ""; busy = false
                    }
                })
            }
        }
    }
}

@Composable private fun AuthScreen(creating: Boolean, toggle: () -> Unit, email: String, setEmail: (String) -> Unit, password: String, setPassword: (String) -> Unit, name: String, setName: (String) -> Unit, busy: Boolean, error: String, submit: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Night).padding(26.dp), verticalArrangement = Arrangement.Center) {
        Text("A U R A", color = Purple, fontWeight = FontWeight.Black, letterSpacing = 5.sp, fontSize = 28.sp)
        Text("YOUR DAY, WITH INTENTION", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 5.dp, bottom = 32.dp))
        Text(if (creating) "Create your account" else "Welcome back", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        if (creating) AuraField("Display name", name, setName)
        AuraField("Email", email, setEmail, keyboard = KeyboardType.Email)
        AuraField("Password", password, setPassword, password = true)
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF9999), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = submit, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 15.dp), shape = RoundedCornerShape(12.dp)) { Text(if (busy) "Please wait…" else if (creating) "Create account" else "Sign in") }
        TextButton(onClick = toggle, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (creating) "Already a member? Sign in" else "New to AURA? Create an account") }
    }
}

@Composable private fun HomePage(account: JSONObject?, planner: JSONObject, onOpenStudy: () -> Unit, saveAttendance: (JSONObject) -> Unit) {
    val stats = account?.optJSONObject("stats") ?: JSONObject()
    val earnedDays = (0 until (account?.optJSONArray("days")?.length() ?: 0)).mapNotNull { account?.optJSONArray("days")?.optString(it) }.toSet()
    var streak = 0
    var date = LocalDate.now()
    while (earnedDays.contains(date.toString())) { streak++; date = date.minusDays(1) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("TODAY · ${LocalDate.now()}", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp) }
        item { Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp)) { Text("YOUR AURA", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp); Text(formatAuraPoints(stats.optInt("total")), fontSize = 42.sp, fontWeight = FontWeight.Black, color = Purple); Spacer(Modifier.height(14.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("DAY STREAK", "$streak ∞"); Metric("COMPLETED", "${stats.optInt("completed")}") } } } }
        item { HomeTimetable(planner, onOpenStudy, saveAttendance) }

    }

}

@Composable private fun HomeTimetable(planner: JSONObject, onOpenStudy: () -> Unit, saveAttendance: (JSONObject) -> Unit) {
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    val schedule = planner.optJSONArray("schedule") ?: JSONArray()
    val subjects = planner.optJSONArray("subjects") ?: JSONArray()
    val weekday = selectedDate.dayOfWeek.value % 7
    val blocks = (0 until schedule.length()).mapNotNull { schedule.optJSONObject(it) }
        .filter { entry ->
            val specificDate = entry.optString("date") == selectedDate.toString()
            val scheduledDays = entry.optJSONArray("days")
            val repeatsToday = !entry.optBoolean("overrideWeekday") && (0 until (scheduledDays?.length() ?: 0)).any { scheduledDays?.optInt(it) == weekday }
            specificDate || repeatsToday
        }.sortedBy { it.optString("start") }
    val weekStart = selectedDate.with(DayOfWeek.MONDAY)
    val dateLabel = selectedDate.format(DateTimeFormatter.ofPattern("EEE, d MMM"))

    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("YOUR TIMETABLE", color = Muted, fontSize = 9.sp, letterSpacing = 1.3.sp); Text(dateLabel, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
                TextButton(onClick = { selectedDate = selectedDate.minusDays(1) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹", fontSize = 24.sp) }
                TextButton(onClick = { selectedDate = LocalDate.now() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Today", fontSize = 11.sp) }
                TextButton(onClick = { selectedDate = selectedDate.plusDays(1) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("›", fontSize = 24.sp) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..6).forEach { offset ->
                    val day = weekStart.plusDays(offset.toLong())
                    val isSelected = day == selectedDate
                    Card(onClick = { selectedDate = day }, colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF3A3554) else Night), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.width(43.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(day.dayOfWeek.name.take(3), color = if (isSelected) Purple else Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            Text(day.dayOfMonth.toString(), color = if (isSelected) Color.White else Color(0xFFF4F2FC), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Text("Choose a date, then mark attendance below.", color = Muted, fontSize = 10.sp)
            if (blocks.isEmpty()) {
                Text("No activities planned for this day.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                blocks.forEach { entry ->
                    val subject = (0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }.firstOrNull { it.optString("id") == entry.optString("subjectId") }
                    val accent = runCatching { Color(android.graphics.Color.parseColor(subject?.optString("color", "#B5A9FF") ?: "#B5A9FF")) }.getOrDefault(Purple)
                    Column(Modifier.fillMaxWidth().background(Night, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.width(42.dp), horizontalAlignment = Alignment.End) {
                                Text(entry.optString("start"), color = Color(0xFFF4F2FC), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(entry.optString("end"), color = Muted, fontSize = 9.sp)
                            }
                            Box(Modifier.width(3.dp).height(38.dp).background(accent, RoundedCornerShape(2.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(entry.optString("title"), color = Color(0xFFF4F2FC), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(listOfNotNull(entry.optString("kind").takeIf { it.isNotBlank() }, subject?.optString("name")?.takeIf { it.isNotBlank() }).joinToString(" · "), color = Muted, fontSize = 9.sp)
                            }
                        }
                        if (entry.optString("kind", "Class") == "Class") {
                            val dayKey = selectedDate.toString()
                            val attendance = planner.optJSONArray("attendance") ?: JSONArray()
                            val status = (0 until attendance.length()).mapNotNull { attendance.optJSONObject(it) }
                                .firstOrNull { it.optString("classId") == entry.optString("id") && it.optString("date") == dayKey }
                                ?.optString("status")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Attendance", color = Muted, fontSize = 10.sp)
                                Spacer(Modifier.width(6.dp))
                                FilterChip(selected = status == "present", onClick = { saveAttendance(recordClassAttendance(planner, entry.optString("subjectId"), entry.optString("title"), entry.optString("id"), dayKey, "present")) }, label = { Text("Present", fontSize = 10.sp) })
                                Spacer(Modifier.width(4.dp))
                                FilterChip(selected = status == "absent", onClick = { saveAttendance(recordClassAttendance(planner, entry.optString("subjectId"), entry.optString("title"), entry.optString("id"), dayKey, "absent")) }, label = { Text("Absent", fontSize = 10.sp) })
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onOpenStudy, modifier = Modifier.align(Alignment.End), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Open class timetable →", fontSize = 10.sp) }
        }
    }
}

@Composable private fun Metric(label: String, value: String) { Column { Text(label, color = Muted, fontSize = 8.sp, letterSpacing = 1.sp); Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF83D6C4)) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FocusPage(subjects: JSONArray, selected: String, select: (String) -> Unit, minutes: Int, setMinutes: (Int) -> Unit, deadline: Long, focusNow: Long, start: (String, String, Int) -> Unit, stop: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isRunning = context.getSharedPreferences("aura_native", 0).getBoolean("focus_running", false)
    val remaining = ((deadline - focusNow).coerceAtLeast(0) / 1000).toInt()
    if (isRunning) {
        val clock = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        Column(Modifier.fillMaxSize().background(Color.Black).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            Text(clock, color = Color(0xFFB9B9B9), fontSize = 22.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Light)
            StudyingCharacter()
            Text("%02d:%02d".format(remaining / 60, remaining % 60), color = Color.White, fontSize = 76.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
            TextButton(onClick = stop) { Text("END FOCUS SESSION", color = Color(0xFFFFA6B7), fontSize = 11.sp, letterSpacing = 1.5.sp) }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("FOCUS STUDIO", color = Muted, letterSpacing = 2.sp, fontSize = 10.sp)
        Box(Modifier.padding(26.dp).size(250.dp).background(Surface, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (isRunning) "%02d:%02d".format(remaining / 60, remaining % 60) else "%02d:00".format(minutes), fontSize = 48.sp, fontWeight = FontWeight.Light, color = Purple)
        }
        if (isRunning) {
            Text(context.getSharedPreferences("aura_native", 0).getString("focus_subject", "Study session").orEmpty(), color = Muted)
            Button(onClick = stop, modifier = Modifier.fillMaxWidth().padding(top = 25.dp)) { Text("End focus") }
            Text("The timer continues while AURA is in the background or the screen is off. AURA does not monitor which apps you open.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp))
        } else {
            Text("Choose a subject", color = Muted, modifier = Modifier.align(Alignment.Start))
            var expanded by remember { mutableStateOf(false) }
            val subjectList = (0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }
            ExposedDropdownMenuBox(expanded, { expanded = it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                OutlinedTextField(value = subjectList.firstOrNull { it.optString("id") == selected }?.optString("name") ?: "Select a saved subject", onValueChange = {}, readOnly = true, modifier = Modifier.menuAnchor().fillMaxWidth(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                ExposedDropdownMenu(expanded, { expanded = false }) { subjectList.forEach { item -> DropdownMenuItem(text = { Text(item.optString("name")) }, onClick = { select(item.optString("id")); expanded = false }) } }
            }
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { setMinutes((minutes - 5).coerceAtLeast(5)) }) { Icon(Icons.Default.Remove, "Shorter") }
                Text("$minutes min", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp))
                IconButton(onClick = { setMinutes((minutes + 5).coerceAtMost(180)) }) { Icon(Icons.Default.Add, "Longer") }
            }
            Button(onClick = { subjectList.firstOrNull { it.optString("id") == selected }?.let { start(selected, it.optString("name"), minutes) } }, enabled = selected.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Start focus session") }
            Text("AURA runs a native timer with a notification and does not monitor app usage during focus.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable private fun StudyingCharacter() {
    val motion = rememberInfiniteTransition(label = "student motion")
    val bob by motion.animateFloat(-3f, 3f, infiniteRepeatable(tween(1050, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "reading bob")
    val pageTurn by motion.animateFloat(-2f, 2f, infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "book motion")
    Canvas(Modifier.size(230.dp)) {
        val sx = size.width / 300f
        val sy = size.height / 300f
        withTransform({ scale(sx, sy, pivot = androidx.compose.ui.geometry.Offset.Zero); translate(top = bob * sy) }) {
            val skin = Color(0xFFE6B38A)
            val hair = Color(0xFF322923)
            val shirt = Color(0xFF8F83D8)
            val desk = Color(0xFF6E5A48)
            val paper = Color(0xFFE9E4D8)
            drawRoundRect(Color(0xFF26222D), androidx.compose.ui.geometry.Offset(54f, 135f), androidx.compose.ui.geometry.Size(192f, 95f), androidx.compose.ui.geometry.CornerRadius(40f))
            drawRoundRect(shirt, androidx.compose.ui.geometry.Offset(107f, 122f), androidx.compose.ui.geometry.Size(86f, 106f), androidx.compose.ui.geometry.CornerRadius(28f))
            drawRoundRect(skin, androidx.compose.ui.geometry.Offset(136f, 104f), androidx.compose.ui.geometry.Size(28f, 32f), androidx.compose.ui.geometry.CornerRadius(10f))
            drawCircle(skin, 35f, androidx.compose.ui.geometry.Offset(150f, 78f))
            drawArc(hair, 180f, 180f, false, androidx.compose.ui.geometry.Offset(115f, 42f), androidx.compose.ui.geometry.Size(70f, 60f), style = Stroke(16f))
            drawRoundRect(hair, androidx.compose.ui.geometry.Offset(117f, 48f), androidx.compose.ui.geometry.Size(26f, 18f), androidx.compose.ui.geometry.CornerRadius(9f))
            drawCircle(Color(0xFF382C27), 3f, androidx.compose.ui.geometry.Offset(163f, 79f))
            drawLine(Color(0xFF8C5945), androidx.compose.ui.geometry.Offset(162f, 93f), androidx.compose.ui.geometry.Offset(171f, 92f), 2f)
            drawLine(skin, androidx.compose.ui.geometry.Offset(118f, 156f), androidx.compose.ui.geometry.Offset(84f, 198f), 13f)
            drawLine(skin, androidx.compose.ui.geometry.Offset(184f, 156f), androidx.compose.ui.geometry.Offset(216f, 192f), 13f)
            drawLine(desk, androidx.compose.ui.geometry.Offset(28f, 218f), androidx.compose.ui.geometry.Offset(272f, 218f), 8f)
            drawLine(desk, androidx.compose.ui.geometry.Offset(70f, 222f), androidx.compose.ui.geometry.Offset(63f, 270f), 8f)
            drawLine(desk, androidx.compose.ui.geometry.Offset(230f, 222f), androidx.compose.ui.geometry.Offset(237f, 270f), 8f)
            withTransform({ translate(left = pageTurn * sx) }) {
                val leftPage = Path().apply { moveTo(92f, 204f); quadraticTo(121f, 188f, 150f, 203f); lineTo(150f, 230f); quadraticTo(119f, 215f, 92f, 229f); close() }
                val rightPage = Path().apply { moveTo(150f, 203f); quadraticTo(180f, 188f, 208f, 204f); lineTo(208f, 229f); quadraticTo(180f, 215f, 150f, 230f); close() }
                drawPath(leftPage, paper); drawPath(rightPage, paper)
                drawLine(Color(0xFFB4AFA3), androidx.compose.ui.geometry.Offset(104f, 211f), androidx.compose.ui.geometry.Offset(139f, 208f), 2f)
                drawLine(Color(0xFFB4AFA3), androidx.compose.ui.geometry.Offset(161f, 208f), androidx.compose.ui.geometry.Offset(196f, 211f), 2f)
                drawLine(Color(0xFFB4AFA3), androidx.compose.ui.geometry.Offset(104f, 218f), androidx.compose.ui.geometry.Offset(139f, 215f), 2f)
                drawLine(Color(0xFFB4AFA3), androidx.compose.ui.geometry.Offset(161f, 215f), androidx.compose.ui.geometry.Offset(196f, 218f), 2f)
            }
        }
    }
}

@Composable private fun MorePage(onTable: () -> Unit, onInfo: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("More", "Community and AURA")
        Card(onClick = onTable, colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Leaderboard, null, tint = Purple); Column(Modifier.weight(1f).padding(start = 14.dp)) { Text("Common table", fontWeight = FontWeight.Bold); Text("See the community leaderboard", color = Muted, fontSize = 12.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Muted) } }
        Card(onClick = onInfo, colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Info, null, tint = Purple); Column(Modifier.weight(1f).padding(start = 14.dp)) { Text("About AURA", fontWeight = FontWeight.Bold); Text("Point rules, app details and connect", color = Muted, fontSize = 12.sp) }; Icon(Icons.Default.ChevronRight, null, tint = Muted) } }
    }
}

@Composable private fun TasksPage(context: android.content.Context, planner: JSONObject, save: (JSONObject) -> Unit) {
    var title by remember { mutableStateOf("") }
    var reminderType by remember { mutableStateOf("notification") }
    var scheduledAt by remember { mutableStateOf(LocalDateTime.now().plusHours(1).withSecond(0).withNano(0)) }
    val tasks = planner.optJSONArray("tasks") ?: JSONArray()
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    val hasExactPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    var taskError by remember { mutableStateOf("") }
    val formatter = DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a")
    fun saveTasks(next: JSONArray) { save(JSONObject(planner.toString()).put("tasks", next)) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle("Task scheduler", "One-time tasks · Android reminders") }
        if (!notificationsEnabled && reminderType != "none") item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) { Column(Modifier.fillMaxWidth().padding(14.dp)) { Text("Enable AURA notifications", fontWeight = FontWeight.SemiBold); Text("Android is blocking AURA notifications. Enable them to receive scheduled reminders.", color = Muted, fontSize = 11.sp); TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) } }) { Text("Open notification settings") } } }
        }
        item { AuraField("Task name", title, { title = it }) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("WHEN", color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp)
                    Text(scheduledAt.format(formatter), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            DatePickerDialog(context, { _, year, month, day -> scheduledAt = scheduledAt.withYear(year).withMonth(month + 1).withDayOfMonth(day) }, scheduledAt.year, scheduledAt.monthValue - 1, scheduledAt.dayOfMonth).show()
                        }) { Text("Choose date") }
                        OutlinedButton(onClick = {
                            TimePickerDialog(context, { _, hour, minute -> scheduledAt = scheduledAt.withHour(hour).withMinute(minute) }, scheduledAt.hour, scheduledAt.minute, false).show()
                        }) { Text("Choose time") }
                    }
                }
            }
        }
        item {
            Text("REMINDER", color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("none" to "None", "notification" to "Notification", "alarm" to "Alarm").forEach { (value, label) ->
                    FilterChip(selected = reminderType == value, onClick = { reminderType = value }, label = { Text(label) })
                }
            }
            Text(if (reminderType == "alarm") "Uses your phone’s alarm tone and vibration at the scheduled time." else if (reminderType == "notification") "Plays a short notification beep at the scheduled time." else "No reminder will be sent.", color = Muted, fontSize = 11.sp)
            if (taskError.isNotBlank()) Text(taskError, color = Color(0xFFFF8D8D), fontSize = 11.sp)
        }
        if (reminderType == "notification" && !hasExactPermission) item {
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Allow exact reminders", fontWeight = FontWeight.SemiBold)
                    Text("Android may delay notifications until you allow AURA to use Alarms & reminders.", color = Muted, fontSize = 11.sp)
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}"))) }
                    }) { Text("Open Android settings") }
                }
            }
        }
        item { Button(onClick = {
            if (title.trim().isEmpty()) return@Button
            if (!scheduledAt.isAfter(LocalDateTime.now())) { taskError = "Choose a time in the future."; return@Button }
            if (reminderType != "none" && !notificationsEnabled) { taskError = "Enable notifications in Android settings first."; return@Button }
            if (reminderType == "notification" && !hasExactPermission) { taskError = "Allow exact alarms in Android settings to schedule this notification."; return@Button }
            taskError = ""
            val updated = JSONArray(); for (i in 0 until tasks.length()) tasks.optJSONObject(i)?.let(updated::put)
            val dateTime = scheduledAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
            updated.put(JSONObject().put("id", "task_${java.util.UUID.randomUUID()}").put("title", title.trim()).put("deadline", dateTime).put("reminderType", reminderType).put("done", false).put("createdAt", System.currentTimeMillis()))
            saveTasks(updated); title = ""; taskError = ""
        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Save scheduled task") } }
        item { SectionTitle("Your tasks", "Tasks stay separate from subjects and classes") }
        if (tasks.length() == 0) item { EmptyCard("Your scheduled tasks will appear here.") }
        items((0 until tasks.length()).mapNotNull { tasks.optJSONObject(it) }.sortedBy { it.optString("deadline") }) { task ->
            val date = runCatching { LocalDateTime.parse(task.optString("deadline"), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")) }.getOrNull()
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(task.optString("title"), fontWeight = FontWeight.SemiBold)
                        Text(date?.format(formatter) ?: task.optString("deadline"), color = Muted, fontSize = 11.sp)
                        val type = task.optString("reminderType", "none")
                        if (type != "none") Text(if (type == "alarm") "Alarm set" else "Notification set", color = Purple, fontSize = 10.sp)
                        if (task.optBoolean("done")) Text("Completed", color = Color(0xFF83D6C4), fontSize = 10.sp)
                    }
                    TextButton(onClick = {
                        val updated = JSONArray(); for (i in 0 until tasks.length()) { val old = tasks.optJSONObject(i) ?: continue; updated.put(if (old.optString("id") == task.optString("id")) JSONObject(old.toString()).put("done", !old.optBoolean("done")) else old) }; saveTasks(updated)
                    }) { Text(if (task.optBoolean("done")) "Reopen" else "Done") }
                    IconButton(onClick = { val updated = JSONArray(); for (i in 0 until tasks.length()) tasks.optJSONObject(i)?.takeIf { it.optString("id") != task.optString("id") }?.let(updated::put); saveTasks(updated) }) { Icon(Icons.Default.DeleteOutline, "Delete task", tint = Color(0xFFFF8D8D)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun StudyPage(planner: JSONObject, mode: String, save: (JSONObject) -> Unit) {
    val subjects = planner.optJSONArray("subjects") ?: JSONArray()
    val schedule = planner.optJSONArray("schedule") ?: JSONArray()
    var subjectDialog by remember { mutableStateOf(false) }
    var subjectToDelete by remember { mutableStateOf<JSONObject?>(null) }
    var subjectToEdit by remember { mutableStateOf<String?>(null) }
    var subjectName by remember { mutableStateOf("") }
    var subjectTeacher by remember { mutableStateOf("") }
    var subjectColor by remember { mutableStateOf("#8B7CF6") }
    var routineDialog by remember { mutableStateOf(false) }
    var routineToEdit by remember { mutableStateOf<String?>(null) }
    var routineTitle by remember { mutableStateOf("") }
    var routineStart by remember { mutableStateOf("09:00") }
    var routineEnd by remember { mutableStateOf("10:00") }
    var routineDate by remember { mutableStateOf("") }
    var routineDays by remember { mutableStateOf((LocalDate.now().dayOfWeek.value % 7).toString()) }
    var routineReminder by remember { mutableStateOf(false) }
    var routineReminderInterval by remember { mutableIntStateOf(60) }
    val expandedClassSubjects = remember { mutableStateListOf<String>() }
    var logStudyDialog by remember { mutableStateOf(false) }
    var studyLogSubject by remember { mutableStateOf(subjects.optJSONObject(0)?.optString("id").orEmpty()) }
    var studyLogMinutes by remember { mutableStateOf("30") }
    var studyLogTopic by remember { mutableStateOf("") }
    val studyLogs = planner.optJSONArray("studyLogs") ?: JSONArray()
    val attendance = planner.optJSONArray("attendance") ?: JSONArray()
    fun saveSubjects(nextSubjects: JSONArray) { save(JSONObject(planner.toString()).put("subjects", nextSubjects)) }
    fun saveSchedule(nextSchedule: JSONArray) { save(JSONObject(planner.toString()).put("schedule", nextSchedule)) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        if (mode == "Subjects") {
            item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { SectionTitle("Subjects", "Study time tracked by subject") }; TextButton(onClick = { subjectToEdit = null; subjectName = ""; subjectTeacher = ""; subjectColor = "#8B7CF6"; subjectDialog = true }) { Text("+ Subject") } } }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { SectionTitle("Study hours", "Focus timer + manual logs") }; TextButton(onClick = { studyLogSubject = subjects.optJSONObject(0)?.optString("id").orEmpty(); studyLogMinutes = "30"; studyLogTopic = ""; logStudyDialog = true }, enabled = subjects.length() > 0) { Text("+ Log time") } } }
            item { Text("Only Focus timer sessions earn Aura Points. Manually logged hours are saved for your study record only.", color = Muted, fontSize = 11.sp) }
            item { SubjectStudyChart(subjects, studyLogs) }
            if (subjects.length() == 0) item { EmptyCard("Add your first subject to start tracking study time.") }
            items((0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }) { subject ->
                val logs = (0 until studyLogs.length()).mapNotNull { studyLogs.optJSONObject(it) }.filter { it.optString("subjectId") == subject.optString("id") }
                val todayMinutes = logs.filter { it.optString("date") == LocalDate.now().toString() }.sumOf { it.optInt("minutes") }
                val totalMinutes = logs.sumOf { it.optInt("minutes") }
                val subjectAttendance = (0 until attendance.length()).mapNotNull { attendance.optJSONObject(it) }
                    .filter { it.optString("subjectId") == subject.optString("id") || it.optString("subjectName").equals(subject.optString("name"), true) }
                val presentCount = subjectAttendance.count { it.optString("status") == "present" }
                val markedCount = subjectAttendance.count { it.optString("status") == "present" || it.optString("status") == "absent" }
                val attendancePercent = if (markedCount > 0) (presentCount * 100f / markedCount).toInt() else 0
                Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(runCatching { Color(android.graphics.Color.parseColor(subject.optString("color", "#B5A9FF"))) }.getOrDefault(Purple), CircleShape))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(subject.optString("name"), fontWeight = FontWeight.SemiBold); Text("Today ${formatStudyTime(todayMinutes)} · Total ${formatStudyTime(totalMinutes)}", color = Muted, fontSize = 11.sp); Text(if (markedCount == 0) "Attendance · no classes marked" else "Attendance · $attendancePercent% ($presentCount/$markedCount)", color = Muted, fontSize = 10.sp); if (subject.optString("teacher").isNotBlank()) Text(subject.optString("teacher"), color = Muted, fontSize = 10.sp) }
                    TextButton(onClick = { subjectToEdit = subject.optString("id"); subjectName = subject.optString("name"); subjectTeacher = subject.optString("teacher"); subjectColor = subject.optString("color", "#8B7CF6"); subjectDialog = true }) { Text("Edit") }
                    IconButton(onClick = { subjectToDelete = JSONObject(subject.toString()) }) { Icon(Icons.Default.DeleteOutline, "Delete subject", tint = Color(0xFFFF8D8D)) }
                } }
            }
        }
        if (mode == "Classes") {
            item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { SectionTitle("Class timetable", "Open a subject to manage times · attendance check after every class") }; TextButton(onClick = { routineToEdit = null; routineTitle = ""; routineStart = "09:00"; routineEnd = "10:00"; routineDate = ""; routineDays = (LocalDate.now().dayOfWeek.value % 7).toString(); routineReminder = false; routineReminderInterval = 0; routineDialog = true }) { Text("+ Add subject") } } }
            val classEntries = (0 until schedule.length()).mapNotNull { schedule.optJSONObject(it) }.filter { it.optString("kind", "Class") == "Class" }
            val classGroups = classEntries.groupBy { it.optString("title").ifBlank { "Untitled subject" } }.toSortedMap()
            if (classGroups.isEmpty()) item { EmptyCard("Add a subject and its class times to see your timetable.") }
            items(classGroups.entries.toList(), key = { it.key }) { (subjectName, entries) ->
                val expanded = subjectName in expandedClassSubjects
                Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) { Column(Modifier.fillMaxWidth().clickable { if (expanded) expandedClassSubjects.remove(subjectName) else expandedClassSubjects.add(subjectName) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(subjectName, fontWeight = FontWeight.SemiBold); Text("${entries.size} class time${if (entries.size == 1) "" else "s"}${if (entries.any { it.optBoolean("reminderEnabled") }) " · start beep on" else ""}", color = Muted, fontSize = 10.sp) }; Text(if (expanded) "⌃" else "⌄", color = Purple, fontSize = 20.sp) }
                    if (expanded) {
                        entries.sortedWith(compareBy({ it.optJSONArray("days")?.joinInts().orEmpty() }, { it.optString("start") })).forEach { entry ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text("${entry.optJSONArray("days")?.weekdayLabels() ?: entry.optString("date")} · ${entry.optString("start")}–${entry.optString("end")}", fontSize = 12.sp); if (entry.optString("date").isNotBlank()) Text(entry.optString("date"), color = Muted, fontSize = 10.sp) }
                                TextButton(onClick = { routineToEdit = entry.optString("id"); routineTitle = subjectName; routineStart = entry.optString("start"); routineEnd = entry.optString("end"); routineDate = entry.optString("date"); routineDays = entry.optJSONArray("days")?.joinInts().orEmpty(); routineReminder = entry.optBoolean("reminderEnabled"); routineReminderInterval = 0; routineDialog = true }) { Text("Edit") }
                                IconButton(onClick = { val updated = JSONArray(); for (i in 0 until schedule.length()) schedule.optJSONObject(i)?.takeIf { it.optString("id") != entry.optString("id") }?.let(updated::put); saveSchedule(updated) }) { Icon(Icons.Default.DeleteOutline, "Delete class time", tint = Color(0xFFFF8D8D)) }
                            }
                        }
                        TextButton(onClick = { routineToEdit = null; routineTitle = subjectName; routineStart = "09:00"; routineEnd = "10:00"; routineDate = ""; routineDays = (LocalDate.now().dayOfWeek.value % 7).toString(); routineReminder = false; routineReminderInterval = 0; routineDialog = true }) { Text("+ Add class time") }
                    }
                } }
            }
        }
    }

    if (logStudyDialog) AlertDialog(onDismissRequest = { logStudyDialog = false }, title = { Text("Log study time") }, text = {
        Column {
            Text("Choose a subject", color = Muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }.forEach { subject ->
                    FilterChip(selected = studyLogSubject == subject.optString("id"), onClick = { studyLogSubject = subject.optString("id") }, label = { Text(subject.optString("name"), fontSize = 10.sp) })
                }
            }
            AuraField("Minutes", studyLogMinutes, { studyLogMinutes = it }, KeyboardType.Number)
            AuraField("Topic (optional)", studyLogTopic, { studyLogTopic = it })
        }
    }, confirmButton = { TextButton(onClick = {
        val minutes = studyLogMinutes.toIntOrNull()
        if (minutes != null && minutes in 1..1440 && studyLogSubject.isNotBlank()) {
            val updated = JSONArray(); for (i in 0 until studyLogs.length()) studyLogs.optJSONObject(i)?.let(updated::put)
            updated.put(JSONObject().put("id", "study_${java.util.UUID.randomUUID()}").put("subjectId", studyLogSubject).put("date", LocalDate.now().toString()).put("minutes", minutes).put("topic", studyLogTopic.trim()).put("source", "manual"))
            save(JSONObject(planner.toString()).put("studyLogs", updated)); logStudyDialog = false
        }
    }, enabled = studyLogMinutes.toIntOrNull() in 1..1440 && studyLogSubject.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { logStudyDialog = false }) { Text("Cancel") } })

    subjectToDelete?.let { doomed ->
        AlertDialog(
            onDismissRequest = { subjectToDelete = null },
            title = { Text("Delete ${doomed.optString("name")}?" ) },
            text = { Text("This also removes its class times, study logs, and saved attendance from this planner.") },
            confirmButton = { TextButton(onClick = {
                val id = doomed.optString("id")
                val name = doomed.optString("name")
                val next = JSONObject(planner.toString())
                fun filtered(key: String, keep: (JSONObject) -> Boolean): JSONArray {
                    val source = planner.optJSONArray(key) ?: JSONArray()
                    val result = JSONArray()
                    for (i in 0 until source.length()) source.optJSONObject(i)?.takeIf(keep)?.let(result::put)
                    return result
                }
                next.put("subjects", filtered("subjects") { it.optString("id") != id })
                next.put("schedule", filtered("schedule") { entry ->
                    entry.optString("subjectId") != id && !(entry.optString("subjectId").isBlank() && entry.optString("title").equals(name, true))
                })
                next.put("studyLogs", filtered("studyLogs") { it.optString("subjectId") != id })
                next.put("attendance", filtered("attendance") { it.optString("subjectId") != id && !it.optString("subjectName").equals(name, true) })
                save(next)
                subjectToDelete = null
            }) { Text("Delete", color = Color(0xFFFF8D8D)) } },
            dismissButton = { TextButton(onClick = { subjectToDelete = null }) { Text("Cancel") } }
        )
    }

    if (subjectDialog) AlertDialog(onDismissRequest = { subjectDialog = false }, title = { Text(if (subjectToEdit == null) "Add subject" else "Edit subject") }, text = {
        Column {
            AuraField("Subject name", subjectName, { subjectName = it })
            AuraField("Teacher (optional)", subjectTeacher, { subjectTeacher = it })
            AuraField("Color · hex code", subjectColor, { subjectColor = it }, keyboard = KeyboardType.Ascii)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(top = 10.dp)) { listOf("#8B7CF6", "#54B8A7", "#E8886C", "#6E9CE7", "#D393C5", "#D2AE5F").forEach { color -> Box(Modifier.size(27.dp).background(Color(android.graphics.Color.parseColor(color)), CircleShape).clickable { subjectColor = color }) } }
        }
    }, confirmButton = { TextButton(onClick = {
        val id = subjectToEdit ?: "s_${java.util.UUID.randomUUID()}"
        val updated = JSONArray()
        for (i in 0 until subjects.length()) { val old = subjects.optJSONObject(i) ?: continue; if (old.optString("id") != id) updated.put(old) }
        updated.put(JSONObject().put("id", id).put("name", subjectName.trim()).put("teacher", subjectTeacher.trim()).put("color", subjectColor).put("notes", ""))
        saveSubjects(updated); subjectDialog = false
    }, enabled = subjectName.trim().isNotEmpty()) { Text("Save") } }, dismissButton = { TextButton(onClick = { subjectDialog = false }) { Text("Cancel") } })

    if (routineDialog) AlertDialog(onDismissRequest = { routineDialog = false }, title = { Text(if (routineToEdit == null) "Add class time" else "Edit class time") }, text = {
        Column {
            AuraField("Subject", routineTitle, { routineTitle = it })
            AuraField("Start · HH:mm", routineStart, { routineStart = it }, keyboard = KeyboardType.Ascii)
            AuraField("End · HH:mm", routineEnd, { routineEnd = it }, keyboard = KeyboardType.Ascii)
            AuraField("One-time date · YYYY-MM-DD (optional)", routineDate, { routineDate = it }, keyboard = KeyboardType.Ascii)
            Text(if (routineDate.isBlank()) "Class days" else "One-time class date · selected date overrides weekdays", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { day, label ->
                    val chosen = routineDays.split(",").mapNotNull { it.trim().toIntOrNull() }.contains(day)
                    FilterChip(selected = chosen, onClick = {
                        val days = routineDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toMutableSet()
                        if (chosen) days.remove(day) else days.add(day)
                        routineDays = days.sorted().joinToString(",")
                    }, label = { Text(label, fontSize = 10.sp) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("Beep at class start", fontWeight = FontWeight.Medium); Text("Play a beep at the start; attendance check after class is always on", color = Muted, fontSize = 11.sp) }
                Switch(checked = routineReminder, onCheckedChange = { routineReminder = it })
            }

        }
    }, confirmButton = { TextButton(onClick = {
        val id = routineToEdit ?: "r_${java.util.UUID.randomUUID()}"
        val days = routineDays.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..6 }.distinct()
        val validDate = routineDate.isBlank() || (routineDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}") ) && runCatching { LocalDate.parse(routineDate) }.isSuccess)
        if ((routineDate.isNotBlank() || days.isNotEmpty()) && validDate && routineStart.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d")) && routineEnd.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d"))) {
            val updated = JSONArray(); for (i in 0 until schedule.length()) { val old = schedule.optJSONObject(i) ?: continue; if (old.optString("id") != id) updated.put(old) }
            updated.put(JSONObject().put("id", id).put("title", routineTitle.trim()).put("subjectId", "").put("days", JSONArray(days)).put("date", routineDate.trim()).put("overrideWeekday", routineDate.isNotBlank()).put("start", routineStart).put("end", routineEnd).put("kind", "Class").put("reminderEnabled", routineReminder).put("reminderIntervalMinutes", routineReminderInterval))
            saveSchedule(updated); routineDialog = false
        }
    }, enabled = routineTitle.trim().isNotEmpty() && (routineDate.isNotBlank() || routineDays.split(",").any { it.trim().toIntOrNull()?.let { day -> day in 0..6 } == true })) { Text("Save class") } }, dismissButton = { TextButton(onClick = { routineDialog = false }) { Text("Cancel") } })
}

@Composable private fun LeaderboardPage(entries: JSONArray) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { SectionTitle("Common table", "AURA community") }
        items((0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.mapIndexed { i, value -> i + 1 to value }) { (rank, entry) ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%02d".format(rank), color = Purple, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp))
                Column(Modifier.weight(1f)) { Text(entry.optString("name"), fontWeight = FontWeight.SemiBold); Text(entry.optString("title"), color = Muted, fontSize = 10.sp) }
                Text("${formatAuraPoints(entry.optInt("points"))} AP", color = Purple, fontWeight = FontWeight.Bold)
            } }
        }
        if (entries.length() == 0) item { EmptyCard("The community table is empty right now.") }
    }
}

@Composable private fun InfoPage(onSignOut: () -> Unit) {
    val atmosphere = rememberInfiniteTransition(label = "info atmosphere")
    val infinityGlow by atmosphere.animateFloat(0.12f, 0.32f, infiniteRepeatable(tween(2300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "infinity glow")
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF11111B), Color(0xFF111A22), Night))).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 22.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(242.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF30235A), Color(0xFF171925), Color(0xFF16423F))))) {
                Text("∞", color = Purple.copy(alpha = infinityGlow), fontSize = 168.sp, lineHeight = 168.sp, modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-30).dp))
                Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                    Text("A U R A   /   INFINITE MODE", color = Color(0xFF83D6C4), fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Text("Go beyond\nroutine.", color = Color(0xFFF4F2FC), fontSize = 38.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 12.dp))
                    Text("A DAILY PRACTICE FOR YOUR NEXT ORBIT", color = Color(0xFFCAC6E0), fontSize = 9.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 13.dp))
                }
            }
        }
        item { InfoCard("How points work", "Aura Points display to two decimal places. Only completed Focus timer sessions earn points: 0.01 AP for every two focused minutes. Manually logged study hours, classes and scheduled tasks do not earn points. Your streak advances when a Focus session earns points.") }
        item { InfoCard("Connect with Aryan", "Connect with Aryan across work, projects, and social.") {
            val context = androidx.compose.ui.platform.LocalContext.current
            LinkRow("LinkedIn", "linkedin.com/in/aryan-tiwari-884906281", "https://linkedin.com/in/aryan-tiwari-884906281/", context)
            LinkRow("GitHub", "github.com/aryansdream", "https://github.com/aryansdream", context)
            LinkRow("Public Instagram", "@coderdrinks", "https://www.instagram.com/coderdrinks/", context)
            LinkRow("Personal Instagram", "@aryant1wari", "https://www.instagram.com/aryant1wari/", context)
            LinkRow("Email", "aryantiwari90605@gmail.com", "mailto:aryantiwari90605@gmail.com", context)
        } }
        item { InfoCard("Device features", "Add the AURA points and day-streak widget from your Android home screen: long-press the home screen, choose Widgets, then select AURA. The focus timer continues with the screen off and does not monitor which apps you open. Scheduled task alarms and notifications are set in the Tasks tab. Android may ask for notification permission; exact Notification reminders may also require Alarms & reminders access. Alarms use Android’s alarm service and can ring while AURA is closed.") }
        item { InfoCard("Account", "Your sign-in stays saved on this device. Use Sign out only when you want to remove this account from the app.") {
            Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF392832))) { Text("Sign out of AURA") }
        } }
    }
}

@Composable private fun InfoCard(title: String, body: String, extra: (@Composable () -> Unit)? = null) { Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1A29)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF37334C)), shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(17.dp)) { Text("∞   $title", color = Purple, fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(body, color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp)); extra?.invoke() } } }
@Composable private fun LinkRow(label: String, target: String, url: String, context: android.content.Context) { Text("$label  ·  $target  ↗", color = Purple, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }.padding(top = 12.dp)) }
@Composable private fun SectionTitle(title: String, sub: String) { Column(Modifier.padding(top = 6.dp, bottom = 2.dp)) { Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(sub.uppercase(), color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp) } }
@Composable private fun EmptyCard(text: String) { Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) { Text(text, Modifier.fillMaxWidth().padding(16.dp), color = Muted, fontSize = 12.sp, lineHeight = 18.sp) } }
@Composable private fun AuraField(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard), visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}
private fun JSONArray.lastObjectForDay(day: String): JSONObject? { for (i in length() - 1 downTo 0) { val item = optJSONObject(i); if (item?.optString("date") == day) return item }; return null }
private fun JSONArray.joinInts(): String = (0 until length()).mapNotNull { optInt(it).takeIf { value -> value in 0..6 } }.joinToString(",")
private fun JSONArray.weekdayLabels(): String = (0 until length()).mapNotNull { optInt(it).takeIf { value -> value in 0..6 }?.let { listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[it] } }.joinToString(", ")
@Composable
private fun SubjectStudyChart(subjects: JSONArray, logs: JSONArray) {
    val subjectList = (0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }
    val logList = (0 until logs.length()).mapNotNull { logs.optJSONObject(it) }
    val totals = subjectList.map { subject ->
        val subjectLogs = logList.filter { it.optString("subjectId") == subject.optString("id") }
        Triple(subject, subjectLogs.sumOf { it.optInt("minutes").coerceAtLeast(0) }, subjectLogs.count { it.optString("source") == "focus" })
    }
    val maxMinutes = totals.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column { Text("Study by subject", fontWeight = FontWeight.SemiBold); Text("Recorded Focus sessions and manual logs", color = Muted, fontSize = 11.sp) }
            if (totals.isEmpty()) Text("Add subjects to see your study graph.", color = Muted, fontSize = 12.sp)
            totals.sortedByDescending { it.second }.forEach { (subject, minutes, sessions) ->
                val color = runCatching { Color(android.graphics.Color.parseColor(subject.optString("color", "#B5A9FF"))) }.getOrDefault(Purple)
                val manual = logList.filter { it.optString("subjectId") == subject.optString("id") && it.optString("source") != "focus" }.sumOf { it.optInt("minutes").coerceAtLeast(0) }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(subject.optString("name"), modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(formatStudyTime(minutes), color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(progress = { (minutes.toFloat() / maxMinutes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)), color = color, trackColor = Color(0xFF303340))
                    Text("$sessions Focus session${if (sessions == 1) "" else "s"} · ${formatStudyTime(manual)} manual", color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun formatStudyTime(minutes: Int): String = if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
private fun formatAuraPoints(points: Int): String = String.format(java.util.Locale.US, "%.2f", points / 100.0)
