package com.aryansdream.aura

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
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
    private var healthSyncSignal by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val healthPermission = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { healthSyncSignal++ }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Night, surface = Surface, onBackground = Color(0xFFF4F2FC), onSurface = Color(0xFFF4F2FC), secondary = Color(0xFF83D6C4))) {
                AuraApp(
                    healthSyncSignal = healthSyncSignal,
                    onRequestSteps = {
                        if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) healthPermission.launch(setOf(DeviceHealth.stepPermission))
                        else startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                    },
                    onUsageAccess = { startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                    onRefreshDeviceData = { healthSyncSignal++ },
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

    override fun onResume() { super.onResume(); healthSyncSignal++ }

    override fun onStop() {
        super.onStop()
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val prefs = getSharedPreferences("aura_native", MODE_PRIVATE)
        // Screen-off is the one background case where the timer must keep running.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (power.isInteractive && prefs.getBoolean("focus_running", false) && FocusAppPolicy.shouldEndFocus(this)) {
                startService(Intent(this, FocusService::class.java).setAction(FocusService.ACTION_STOP))
            }
        }, 900)
    }
}

@Composable
private fun AuraApp(healthSyncSignal: Int, onRequestSteps: () -> Unit, onUsageAccess: () -> Unit, onRefreshDeviceData: () -> Unit, onStartFocus: (String, String, Int) -> Unit, onStopFocus: () -> Unit) {
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
    var steps by remember { mutableStateOf("") }
    var screenTime by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var showMissions by remember { mutableStateOf(context.getSharedPreferences("aura_native", 0).getBoolean("show_missions", true)) }
    var usageAccess by remember { mutableStateOf(false) }
    var stepPermissionGranted by remember { mutableStateOf(false) }
    var deviceScreenMinutes by remember { mutableStateOf<Int?>(null) }
    var syncStatus by remember { mutableStateOf("") }
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
                AuraWidgetProvider.sync(context, me)
                localPrefs.edit().putString("account_cache", me.toString()).putString("planner_cache", planner.toString()).apply()
                ClassReminderScheduler.sync(context, planner)
                val health = planner.optJSONArray("lifestyle")?.lastObjectForDay(LocalDate.now().toString())
                if (health != null) { steps = health.optInt("steps").takeIf { it > 0 }?.toString() ?: ""; screenTime = health.optInt("screenTime").takeIf { it > 0 }?.toString() ?: ""; calories = health.optInt("calories").takeIf { it > 0 }?.toString() ?: "" }
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

    LaunchedEffect(page, healthSyncSignal, user?.optString("id")) {
        if (page == "Life" && user != null) {
            stepPermissionGranted = withContext(Dispatchers.IO) { runCatching { DeviceHealth.hasStepPermission(context) }.getOrDefault(false) }
            val readings = withContext(Dispatchers.IO) {
                val stepsRead = if (stepPermissionGranted) runCatching { DeviceHealth.healthSteps(context) } else Result.success(null)
                val screenRead = runCatching { DeviceHealth.todayScreenMinutes(context) }.getOrNull()
                Triple(stepsRead, screenRead, DeviceHealth.hasUsageAccess(context))
            }
            usageAccess = readings.third
            deviceScreenMinutes = readings.second
            val syncedSteps = readings.first.getOrNull()?.toString()
            val syncedScreen = readings.second?.toString()
            if (syncedSteps != null) steps = syncedSteps
            if (syncedScreen != null) screenTime = syncedScreen
            syncStatus = buildList {
                if (!stepPermissionGranted) add("Connect Health Connect and allow AURA to read Steps.")
                else if (readings.first.isFailure) add("Health Connect step read failed: ${readings.first.exceptionOrNull()?.message ?: "check its permissions"}")
                else if (syncedSteps == null || syncedSteps == "0") add("No step records have reached Health Connect today. Check that your phone or health app is writing steps there.")
                if (!readings.third) add("Allow Usage Access for AURA in Android Settings to read screen use.")
                else if (syncedScreen == null) add("Android did not return screen-use data. Reopen Life and refresh device data.")
                else add("Screen use is estimated from Android foreground-app activity; Android may count it differently from Digital Wellbeing.")
            }.joinToString(" ")
            if (syncedSteps != null || syncedScreen != null) {
                try {
                    val saved = saveLifeEntry(context, planner, syncedSteps ?: steps, syncedScreen ?: screenTime, calories, user?.optString("id").orEmpty())
                    planner = saved
                    syncStatus = "$syncStatus Saved on this device.".trim()
                    try {
                        withContext(Dispatchers.IO) { AuraApi.syncPendingPlanner(context, user?.optString("id").orEmpty()) }
                        syncStatus = "$syncStatus Synced to your AURA account.".trim()
                    } catch (_: Exception) { syncStatus = "$syncStatus Will retry when connected.".trim() }
                } catch (e: Exception) { syncStatus = e.message ?: "Device data could not save locally." }
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

    Scaffold(containerColor = if (focusFullScreen) Color.Black else Night, bottomBar = {
        if (!focusFullScreen) NavigationBar(containerColor = Surface, contentColor = Muted) {
            listOf("Today" to Icons.Default.Home, "Focus" to Icons.Default.Timer, "Schedule" to Icons.Default.CalendarMonth, "Life" to Icons.Default.Favorite, "Table" to Icons.Default.Leaderboard, "Info" to Icons.Default.Info).forEach { (label, icon) ->
                NavigationBarItem(selected = page == label, onClick = { page = label; if (label == "Table") scope.launch { leaderboard = withContext(Dispatchers.IO) { AuraApi.request(context, "/api/leaderboard").optJSONArray("entries") ?: JSONArray() } } }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label, fontSize = 10.sp) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(if (focusFullScreen) Color.Black else Night)) {
            if (!focusFullScreen) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("A U R A   ∞", color = Purple, fontWeight = FontWeight.Black, letterSpacing = 3.sp, fontSize = 17.sp); Text("DAILY PRACTICE", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp) }
                IconButton(onClick = { load() }) { Icon(Icons.Default.Refresh, "Refresh", tint = Muted) }
            }
            if (!focusFullScreen && error.isNotBlank()) Text(error, Modifier.padding(horizontal = 20.dp), color = Color(0xFFFF8D8D), fontSize = 12.sp)
            if (!focusFullScreen && syncMessage.isNotBlank() && error.isBlank()) Text(syncMessage, Modifier.padding(horizontal = 20.dp), color = Color(0xFF83D6C4), fontSize = 11.sp)
            when (page) {
                "Today" -> HomePage(account, planner, busy, showMissions, { showMissions = !showMissions; context.getSharedPreferences("aura_native", 0).edit().putBoolean("show_missions", showMissions).apply() }, onOpenStudy = { page = "Schedule" }, onAddMission = { title, difficulty -> scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) {
                            val created = AuraApi.request(context, "/api/custom", JSONObject().put("title", title).put("difficulty", difficulty)).optJSONObject("task") ?: error("Could not create mission.")
                            AuraApi.request(context, "/api/select", JSONObject().put("taskId", created.optString("id")))
                        }
                        syncMessage = "Daily mission added."; load()
                    } catch (e: Exception) { busy = false; error = e.message ?: "Could not add the mission." }
                } }, onRemoveMission = { id -> scope.launch {
                    busy = true
                    try { withContext(Dispatchers.IO) { AuraApi.request(context, "/api/select", JSONObject().put("action", "remove").put("taskId", id)) }; syncMessage = "Daily mission removed."; load() }
                    catch (e: Exception) { busy = false; error = e.message ?: "Could not remove the mission." }
                } }, onComplete = { id -> scope.launch {
                    val body = JSONObject().put("taskId", id)
                    try { withContext(Dispatchers.IO) { AuraApi.request(context, "/api/complete", body) }; load() }
                    catch (_: Exception) { AuraApi.queueAction(context, "/api/complete", body, user?.optString("id").orEmpty()); syncMessage = "Mission saved on this device; it will sync when connected." }
                } })
                "Focus" -> FocusPage(planner.optJSONArray("subjects") ?: JSONArray(), subjectChoice, { subjectChoice = it }, focusMinutes, { focusMinutes = it }, context.getSharedPreferences("aura_native", 0).getLong("focus_deadline", 0), focusNow, onStartFocus, onStopFocus)
                "Schedule" -> StudyPage(planner) { next ->
                    planner = next
                    AuraApi.savePlannerDraft(context, next, user?.optString("id").orEmpty())
                    ClassReminderScheduler.sync(context, next)
                    scope.launch {
                        busy = true
                        try { withContext(Dispatchers.IO) { AuraApi.syncPendingPlanner(context, user?.optString("id").orEmpty()) }; syncMessage = "Study space saved and synced." }
                        catch (_: Exception) { syncMessage = "Study space saved on this device. It will sync when connected." }
                        busy = false
                    }
                }
                "Life" -> LifePage(steps, { steps = it }, screenTime, { screenTime = it }, calories, { calories = it }, busy, deviceScreenMinutes, stepPermissionGranted, usageAccess, syncStatus,
                    connectSteps = { if (stepPermissionGranted) onRefreshDeviceData() else onRequestSteps() },
                    usageSettings = { if (usageAccess) onRefreshDeviceData() else onUsageAccess() },
                    refreshDeviceData = onRefreshDeviceData) {
                    scope.launch {
                        busy = true
                        try {
                            planner = saveLifeEntry(context, planner, steps, screenTime, calories, user?.optString("id").orEmpty())
                            try {
                                withContext(Dispatchers.IO) { AuraApi.syncPendingPlanner(context, user?.optString("id").orEmpty()) }
                                syncMessage = "Today's log saved and synced."
                            } catch (_: Exception) { syncMessage = "Today's log is saved on this device and will sync when connected." }
                        } catch (e: Exception) { error = e.message ?: "Could not save today's log." }
                        busy = false
                    }
                }
                "Table" -> LeaderboardPage(leaderboard)
                else -> InfoPage(onSignOut = {
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

@Composable private fun HomePage(account: JSONObject?, planner: JSONObject, busy: Boolean, showMissions: Boolean, toggleMissions: () -> Unit, onOpenStudy: () -> Unit, onAddMission: (String, String) -> Unit, onRemoveMission: (String) -> Unit, onComplete: (String) -> Unit) {
    val stats = account?.optJSONObject("stats") ?: JSONObject()
    val missions = account?.optJSONArray("today") ?: JSONArray()
    val earnedDays = (0 until (account?.optJSONArray("days")?.length() ?: 0)).mapNotNull { account?.optJSONArray("days")?.optString(it) }.toSet()
    var addMissionDialog by remember { mutableStateOf(false) }
    var missionTitle by remember { mutableStateOf("") }
    var missionDifficulty by remember { mutableStateOf("Moderate") }
    var streak = 0
    var date = LocalDate.now()
    while (earnedDays.contains(date.toString())) { streak++; date = date.minusDays(1) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("TODAY · ${LocalDate.now()}", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp) }
        item { Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp)) { Text("YOUR AURA", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp); Text(formatAuraPoints(stats.optInt("total")), fontSize = 42.sp, fontWeight = FontWeight.Black, color = Purple); Text("${formatAuraPoints(stats.optInt("pending"))} AP waiting for validation", color = Muted, fontSize = 12.sp); Spacer(Modifier.height(14.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("DAY STREAK", "$streak ∞"); Metric("COMPLETED", "${stats.optInt("completed")}") } } } }
        item { HomeTimetable(planner, onOpenStudy) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { SectionTitle("Today's missions", if (showMissions) "${missions.length()} selected" else "Hidden from home") }; TextButton(onClick = { missionTitle = ""; missionDifficulty = "Moderate"; addMissionDialog = true }) { Text("+ Add") }; TextButton(onClick = toggleMissions) { Text(if (showMissions) "Hide" else "Show") } } }
        if (showMissions && missions.length() == 0) item { EmptyCard("Your daily missions will appear here after you add them on AURA.") }
        if (showMissions) items((0 until missions.length()).mapNotNull { missions.optJSONObject(it) }) { task ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(task.optString("title"), fontWeight = FontWeight.SemiBold); Text("${task.optString("category")} · ${formatAuraPoints(task.optInt("points"))} AP", color = Muted, fontSize = 11.sp) }
                    if (task.optBoolean("done")) Text("DONE", color = Color(0xFF83D6C4), fontSize = 10.sp, fontWeight = FontWeight.Bold) else Button(onClick = { onComplete(task.optString("task_id")) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("Done", fontSize = 11.sp) }
                    IconButton(onClick = { onRemoveMission(task.optString("task_id")) }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, "Remove mission", tint = Color(0xFFFF8D8D)) }
                }
            }
        }
    }
    if (addMissionDialog) AlertDialog(onDismissRequest = { addMissionDialog = false }, title = { Text("Add daily mission") }, text = {
        Column {
            AuraField("Mission name", missionTitle, { missionTitle = it })
            Text("Difficulty", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf("Easy", "Moderate", "Hard", "Challenging").forEach { level -> FilterChip(selected = missionDifficulty == level, onClick = { missionDifficulty = level }, label = { Text(level, fontSize = 10.sp) }) }
            }
        }
    }, confirmButton = { TextButton(onClick = { onAddMission(missionTitle.trim(), missionDifficulty); addMissionDialog = false }, enabled = missionTitle.trim().length >= 3 && !busy) { Text("Add to today") } }, dismissButton = { TextButton(onClick = { addMissionDialog = false }) { Text("Cancel") } })
}

@Composable private fun HomeTimetable(planner: JSONObject, onOpenStudy: () -> Unit) {
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
            if (blocks.isEmpty()) {
                Text("No activities planned for this day.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                blocks.forEach { entry ->
                    val subject = (0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }.firstOrNull { it.optString("id") == entry.optString("subjectId") }
                    val accent = runCatching { Color(android.graphics.Color.parseColor(subject?.optString("color", "#B5A9FF") ?: "#B5A9FF")) }.getOrDefault(Purple)
                    Row(Modifier.fillMaxWidth().background(Night, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                }
            }
            TextButton(onClick = onOpenStudy, modifier = Modifier.align(Alignment.End), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Open task scheduler →", fontSize = 10.sp) }
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
            Text("Timer continues with the screen off. ChatGPT, Gemini, YouTube, and other non-social apps are allowed; focus ends if you open social media or go home. Usage Access is needed.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp))
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
            Text("AURA runs a native timer with a notification. ChatGPT, Gemini, YouTube, and other non-social apps are allowed during focus. Usage Access is needed to detect social apps and the home screen.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 12.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun StudyPage(planner: JSONObject, save: (JSONObject) -> Unit) {
    val subjects = planner.optJSONArray("subjects") ?: JSONArray()
    val schedule = planner.optJSONArray("schedule") ?: JSONArray()
    var subjectDialog by remember { mutableStateOf(false) }
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
    var routineKind by remember { mutableStateOf("Study") }
    var routineReminder by remember { mutableStateOf(false) }
    var routineReminderInterval by remember { mutableIntStateOf(60) }
    var reminderMenu by remember { mutableStateOf(false) }
    var routineSubject by remember { mutableStateOf("") }
    var subjectMenu by remember { mutableStateOf(false) }
    var logStudyDialog by remember { mutableStateOf(false) }
    var studyLogSubject by remember { mutableStateOf(subjects.optJSONObject(0)?.optString("id").orEmpty()) }
    var studyLogMinutes by remember { mutableStateOf("30") }
    var studyLogTopic by remember { mutableStateOf("") }
    val studyLogs = planner.optJSONArray("studyLogs") ?: JSONArray()
    fun saveSubjects(nextSubjects: JSONArray) { save(JSONObject(planner.toString()).put("subjects", nextSubjects)) }
    fun saveSchedule(nextSchedule: JSONArray) { save(JSONObject(planner.toString()).put("schedule", nextSchedule)) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { SectionTitle("Task scheduler", "Classes, study blocks, routines & reminders") }; TextButton(onClick = { routineToEdit = null; routineTitle = ""; routineStart = "09:00"; routineEnd = "10:00"; routineDate = ""; routineDays = (LocalDate.now().dayOfWeek.value % 7).toString(); routineKind = "Study"; routineSubject = ""; routineReminder = false; routineReminderInterval = 60; routineDialog = true }) { Text("+ Schedule task") } } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { SectionTitle("Study space", "Subjects and study time") }; TextButton(onClick = { subjectToEdit = null; subjectName = ""; subjectTeacher = ""; subjectColor = "#8B7CF6"; subjectDialog = true }) { Text("+ Subject") } } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { SectionTitle("Study time", "Per subject · focus sessions + manual logs") }; TextButton(onClick = { studyLogSubject = subjects.optJSONObject(0)?.optString("id").orEmpty(); studyLogMinutes = "30"; studyLogTopic = ""; logStudyDialog = true }, enabled = subjects.length() > 0) { Text("+ Log time") } } }
        items((0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }) { subject ->
            val subjectLogs = (0 until studyLogs.length()).mapNotNull { studyLogs.optJSONObject(it) }.filter { it.optString("subjectId") == subject.optString("id") }
            val todayMinutes = subjectLogs.filter { it.optString("date") == LocalDate.now().toString() }.sumOf { it.optInt("minutes") }
            val totalMinutes = subjectLogs.sumOf { it.optInt("minutes") }
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(runCatching { Color(android.graphics.Color.parseColor(subject.optString("color", "#B5A9FF"))) }.getOrDefault(Purple), CircleShape))
                Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(subject.optString("name"), fontWeight = FontWeight.SemiBold); Text("Today ${formatStudyTime(todayMinutes)} · Total ${formatStudyTime(totalMinutes)}", color = Muted, fontSize = 11.sp) }
            } }
        }
        if (subjects.length() == 0) item { EmptyCard("Add a subject to track study time by class.") }
        if (subjects.length() == 0) item { EmptyCard("Add your first subject here. It will sync to your AURA account.") }
        items((0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }) { subject ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(runCatching { Color(android.graphics.Color.parseColor(subject.optString("color", "#B5A9FF"))) }.getOrDefault(Purple), CircleShape))
                Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(subject.optString("name"), fontWeight = FontWeight.SemiBold); if (subject.optString("teacher").isNotBlank()) Text(subject.optString("teacher"), color = Muted, fontSize = 11.sp) }
                TextButton(onClick = { subjectToEdit = subject.optString("id"); subjectName = subject.optString("name"); subjectTeacher = subject.optString("teacher"); subjectColor = subject.optString("color", "#8B7CF6"); subjectDialog = true }) { Text("Edit") }
            } }
        }
        item { SectionTitle("Your schedule", "Set the time, days and reminder for each task") }
        if (schedule.length() == 0) item { EmptyCard("Add a recurring class, study block, or personal activity.") }
        items((0 until schedule.length()).mapNotNull { schedule.optJSONObject(it) }) { entry ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(entry.optString("title"), fontWeight = FontWeight.SemiBold); Text("${entry.optString("start")}–${entry.optString("end")} · ${entry.optString("kind")} · ${entry.optString("date").ifBlank { entry.optJSONArray("days")?.weekdayLabels() ?: "" }}${if (entry.optBoolean("reminderEnabled")) " · Reminder on" else ""}", color = Muted, fontSize = 10.sp) }
                TextButton(onClick = { routineToEdit = entry.optString("id"); routineTitle = entry.optString("title"); routineStart = entry.optString("start"); routineEnd = entry.optString("end"); routineDate = entry.optString("date"); routineDays = entry.optJSONArray("days")?.joinInts().orEmpty(); routineKind = entry.optString("kind", "Study"); routineSubject = entry.optString("subjectId"); routineReminder = entry.optBoolean("reminderEnabled"); routineReminderInterval = entry.optInt("reminderIntervalMinutes", 60); routineDialog = true }) { Text("Edit") }
                TextButton(onClick = { val updated = JSONArray(); for (i in 0 until schedule.length()) if (schedule.optJSONObject(i)?.optString("id") != entry.optString("id")) updated.put(schedule.optJSONObject(i)); saveSchedule(updated) }) { Text("Delete", color = Color(0xFFFF8D8D)) }
            } }
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
            updated.put(JSONObject().put("id", "study_${java.util.UUID.randomUUID()}").put("subjectId", studyLogSubject).put("date", LocalDate.now().toString()).put("minutes", minutes).put("topic", studyLogTopic.trim()))
            save(JSONObject(planner.toString()).put("studyLogs", updated)); logStudyDialog = false
        }
    }, enabled = studyLogMinutes.toIntOrNull() in 1..1440 && studyLogSubject.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = { logStudyDialog = false }) { Text("Cancel") } })

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

    if (routineDialog) AlertDialog(onDismissRequest = { routineDialog = false }, title = { Text(if (routineToEdit == null) "Schedule a task" else "Edit scheduled task") }, text = {
        Column {
            AuraField("Activity name", routineTitle, { routineTitle = it })
            ExposedDropdownMenuBox(subjectMenu, { subjectMenu = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                val subjectLabel = (0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }.firstOrNull { it.optString("id") == routineSubject }?.optString("name") ?: "General / no subject"
                OutlinedTextField(value = subjectLabel, onValueChange = {}, readOnly = true, label = { Text("Subject") }, modifier = Modifier.menuAnchor().fillMaxWidth(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(subjectMenu) })
                ExposedDropdownMenu(subjectMenu, { subjectMenu = false }) { DropdownMenuItem(text = { Text("General / no subject") }, onClick = { routineSubject = ""; subjectMenu = false }); (0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }.forEach { item -> DropdownMenuItem(text = { Text(item.optString("name")) }, onClick = { routineSubject = item.optString("id"); subjectMenu = false }) } }
            }
            AuraField("Start · HH:mm", routineStart, { routineStart = it }, keyboard = KeyboardType.Ascii)
            AuraField("End · HH:mm", routineEnd, { routineEnd = it }, keyboard = KeyboardType.Ascii)
            AuraField("One-time date · YYYY-MM-DD (optional)", routineDate, { routineDate = it }, keyboard = KeyboardType.Ascii)
            Text(if (routineDate.isBlank()) "Repeat on" else "One-time task · selected date overrides repeat days", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
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
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 6.dp)) { listOf("Class", "Study", "Personal").forEach { kind -> FilterChip(selected = routineKind == kind, onClick = { routineKind = kind }, label = { Text(kind, fontSize = 10.sp) }) } }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) { Text("Notifications", fontWeight = FontWeight.Medium); Text("For this scheduled activity", color = Muted, fontSize = 11.sp) }
                Switch(checked = routineReminder, onCheckedChange = { routineReminder = it })
            }
            if (routineReminder) {
                val intervalLabel = when (routineReminderInterval) { 0 -> "Once at start"; else -> "Every ${if (routineReminderInterval < 60) "$routineReminderInterval min" else "${routineReminderInterval / 60} hr"} during activity" }
                ExposedDropdownMenuBox(reminderMenu, { reminderMenu = it }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    OutlinedTextField(value = intervalLabel, onValueChange = {}, readOnly = true, label = { Text("Reminder frequency") }, modifier = Modifier.menuAnchor().fillMaxWidth(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(reminderMenu) })
                    ExposedDropdownMenu(reminderMenu, { reminderMenu = false }) {
                        listOf(0, 15, 30, 60, 120).forEach { minutes ->
                            val label = when (minutes) { 0 -> "Once at start"; 15 -> "Every 15 minutes"; 30 -> "Every 30 minutes"; 60 -> "Every hour"; else -> "Every 2 hours" }
                            DropdownMenuItem(text = { Text(label) }, onClick = { routineReminderInterval = minutes; reminderMenu = false })
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        val id = routineToEdit ?: "r_${java.util.UUID.randomUUID()}"
        val days = routineDays.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..6 }.distinct()
        val validDate = routineDate.isBlank() || (routineDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}") ) && runCatching { LocalDate.parse(routineDate) }.isSuccess)
        if ((routineDate.isNotBlank() || days.isNotEmpty()) && validDate && routineStart.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d")) && routineEnd.matches(Regex("([01]\\d|2[0-3]):[0-5]\\d"))) {
            val updated = JSONArray(); for (i in 0 until schedule.length()) { val old = schedule.optJSONObject(i) ?: continue; if (old.optString("id") != id) updated.put(old) }
            updated.put(JSONObject().put("id", id).put("title", routineTitle.trim()).put("subjectId", routineSubject).put("days", JSONArray(days)).put("date", routineDate.trim()).put("overrideWeekday", routineDate.isNotBlank()).put("start", routineStart).put("end", routineEnd).put("kind", routineKind).put("reminderEnabled", routineReminder).put("reminderIntervalMinutes", routineReminderInterval))
            saveSchedule(updated); routineDialog = false
        }
    }, enabled = routineTitle.trim().isNotEmpty() && (routineDate.isNotBlank() || routineDays.split(",").any { it.trim().toIntOrNull()?.let { day -> day in 0..6 } == true })) { Text("Save scheduled task") } }, dismissButton = { TextButton(onClick = { routineDialog = false }) { Text("Cancel") } })
}

@Composable private fun LifePage(steps: String, setSteps: (String) -> Unit, screen: String, setScreen: (String) -> Unit, calories: String, setCalories: (String) -> Unit, busy: Boolean, deviceScreenMinutes: Int?, stepsConnected: Boolean, usageAccess: Boolean, syncStatus: String, connectSteps: () -> Unit, usageSettings: () -> Unit, refreshDeviceData: () -> Unit, save: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Daily check-in", "${LocalDate.now()}")
        Text("Your device readings and entries stay synced to your AURA account.", color = Muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = connectSteps, modifier = Modifier.weight(1f)) { Text(if (stepsConnected) "Refresh steps" else "Connect steps", fontSize = 11.sp) }
            OutlinedButton(onClick = usageSettings, modifier = Modifier.weight(1f)) { Text(if (usageAccess) "Refresh screen time" else "Allow screen time", fontSize = 11.sp) }
        }
        OutlinedButton(onClick = refreshDeviceData, modifier = Modifier.fillMaxWidth()) { Text("Refresh device data") }
        if (deviceScreenMinutes != null) Text("Today’s device use: ${deviceScreenMinutes / 60}h ${deviceScreenMinutes % 60}m", color = Color(0xFF83D6C4), fontSize = 11.sp)
        if (syncStatus.isNotBlank()) Text(syncStatus, color = Muted, fontSize = 11.sp)
        AuraField("Steps", steps, setSteps, KeyboardType.Number)
        AuraField("Screen time · minutes", screen, setScreen, KeyboardType.Number)
        AuraField("Calories · kcal", calories, setCalories, KeyboardType.Number)
        Button(onClick = save, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(if (busy) "Saving…" else "Save today's log") }
        Card(colors = CardDefaults.cardColors(containerColor = Surface)) { Text("Steps come from records written to Health Connect. Screen time comes from Android Usage Access. Enable the matching permission, then tap Refresh device data. Calories can be entered manually.", Modifier.padding(16.dp), color = Muted, fontSize = 12.sp) }
    }
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
        item { InfoCard("How points work", "Scores display to two decimal places. Missions earn half their library value and taper by 3% at chapter milestones. Classes earn 0.10 AP. Focus earns 0.01 AP every two focused minutes. Streaks advance whenever you earn points.") }
        item { InfoCard("Daily combos", "4h study + 30m movement earns 12.00 AP. 6h + 1h earns 50.00 AP. The 10h + 1h All-day balance combo earns 112.61 AP—more than all 1,070 mission values combined.") }
        item { InfoCard("Connect with Aryan", "Connect with Aryan across work, projects, and social.") {
            val context = androidx.compose.ui.platform.LocalContext.current
            LinkRow("LinkedIn", "linkedin.com/in/aryan-tiwari-884906281", "https://linkedin.com/in/aryan-tiwari-884906281/", context)
            LinkRow("GitHub", "github.com/aryansdream", "https://github.com/aryansdream", context)
            LinkRow("Public Instagram", "@coderdrinks", "https://www.instagram.com/coderdrinks/", context)
            LinkRow("Personal Instagram", "@aryant1wari", "https://www.instagram.com/aryant1wari/", context)
            LinkRow("Email", "aryantiwari90605@gmail.com", "mailto:aryantiwari90605@gmail.com", context)
        } }
        item { InfoCard("Device features", "Add the AURA points and day-streak widget from your Android home screen: long-press the home screen, choose Widgets, then select AURA. The focus timer continues with the screen off. ChatGPT, Gemini, YouTube, and other non-social apps are allowed during focus; opening social media or the home screen ends the timer. Usage Access enables this app-switch check and screen-time sync. Steps sync through Health Connect, and calories can be logged in Life.") }
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
private fun formatStudyTime(minutes: Int): String = if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
private fun formatAuraPoints(points: Int): String = String.format(java.util.Locale.US, "%.2f", points / 100.0)

private fun saveLifeEntry(context: android.content.Context, planner: JSONObject, steps: String, screenTime: String, calories: String, accountId: String): JSONObject {
    val data = JSONObject(planner.toString())
    val list = data.optJSONArray("lifestyle") ?: JSONArray()
    val date = LocalDate.now().toString()
    val updated = JSONArray()
    for (i in 0 until list.length()) if (list.optJSONObject(i)?.optString("date") != date) updated.put(list.optJSONObject(i))
    updated.put(JSONObject().put("date", date).put("steps", steps.toIntOrNull() ?: 0).put("screenTime", screenTime.toIntOrNull() ?: 0).put("calories", calories.toIntOrNull() ?: 0))
    data.put("lifestyle", updated)
    AuraApi.savePlannerDraft(context, data, accountId)
    return data
}
