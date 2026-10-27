package com.aryansdream.aura

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

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
            if (power.isInteractive && prefs.getBoolean("focus_running", false)) stopService(Intent(this, FocusService::class.java))
        }, 700)
    }
}

@Composable
private fun AuraApp(healthSyncSignal: Int, onRequestSteps: () -> Unit, onUsageAccess: () -> Unit, onStartFocus: (String, String, Int) -> Unit, onStopFocus: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<JSONObject?>(null) }
    var account by remember { mutableStateOf<JSONObject?>(null) }
    var planner by remember { mutableStateOf(JSONObject()) }
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
    var usageAccess by remember { mutableStateOf(false) }
    var deviceScreenMinutes by remember { mutableStateOf<Int?>(null) }
    var syncStatus by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            busy = true; error = ""
            try {
                val (me, plan) = withContext(Dispatchers.IO) {
                    AuraApi.request(context, "/api/me") to AuraApi.request(context, "/api/planner")
                }
                account = me; user = me.optJSONObject("user"); planner = plan.optJSONObject("data") ?: JSONObject()
                val health = planner.optJSONArray("lifestyle")?.lastObjectForDay(LocalDate.now().toString())
                if (health != null) { steps = health.optInt("steps").takeIf { it > 0 }?.toString() ?: ""; screenTime = health.optInt("screenTime").takeIf { it > 0 }?.toString() ?: ""; calories = health.optInt("calories").takeIf { it > 0 }?.toString() ?: "" }
                if (subjectChoice.isBlank()) subjectChoice = planner.optJSONArray("subjects")?.optJSONObject(0)?.optString("id").orEmpty()
            } catch (e: Exception) { user = null; error = e.message ?: "Could not connect to AURA." }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        if (context.getSharedPreferences("aura_native", 0).getString("cookie", null) != null) load()
        while (true) { focusNow = android.os.SystemClock.elapsedRealtime(); delay(1000) }
    }

    LaunchedEffect(page, healthSyncSignal, user?.optString("id")) {
        if (page == "Life" && user != null) {
            val readings = withContext(Dispatchers.IO) {
                val stepsRead = runCatching { DeviceHealth.healthSteps(context) }.getOrNull()
                val screenRead = runCatching { DeviceHealth.todayScreenMinutes(context) }.getOrNull()
                stepsRead to screenRead
            }
            usageAccess = DeviceHealth.hasUsageAccess(context)
            deviceScreenMinutes = readings.second
            val syncedSteps = readings.first?.toString()
            val syncedScreen = readings.second?.toString()
            if (syncedSteps != null) steps = syncedSteps
            if (syncedScreen != null) screenTime = syncedScreen
            if (syncedSteps != null || syncedScreen != null) {
                try {
                    val saved = withContext(Dispatchers.IO) { saveLifeEntry(context, planner, syncedSteps ?: steps, syncedScreen ?: screenTime, calories) }
                    planner = saved
                    syncStatus = "Device activity synced and saved to your AURA account."
                } catch (e: Exception) { syncStatus = e.message ?: "Device data could not sync." }
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

    Scaffold(containerColor = Night, bottomBar = {
        NavigationBar(containerColor = Surface, contentColor = Muted) {
            listOf("Today" to Icons.Default.Home, "Focus" to Icons.Default.Timer, "Study" to Icons.Default.MenuBook, "Life" to Icons.Default.Favorite, "Table" to Icons.Default.Leaderboard, "Info" to Icons.Default.Info).forEach { (label, icon) ->
                NavigationBarItem(selected = page == label, onClick = { page = label; if (label == "Table") scope.launch { leaderboard = withContext(Dispatchers.IO) { AuraApi.request(context, "/api/leaderboard").optJSONArray("entries") ?: JSONArray() } } }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label, fontSize = 10.sp) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Night)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column { Text("A U R A   ∞", color = Purple, fontWeight = FontWeight.Black, letterSpacing = 3.sp, fontSize = 17.sp); Text("DAILY PRACTICE", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp) }
                IconButton(onClick = { load() }) { Icon(Icons.Default.Refresh, "Refresh", tint = Muted) }
            }
            if (error.isNotBlank()) Text(error, Modifier.padding(horizontal = 20.dp), color = Color(0xFFFF8D8D), fontSize = 12.sp)
            when (page) {
                "Today" -> HomePage(account, busy, onComplete = { id -> scope.launch { try { withContext(Dispatchers.IO) { AuraApi.request(context, "/api/complete", JSONObject().put("taskId", id)) }; load() } catch (e: Exception) { error = e.message ?: "Could not save." } } })
                "Focus" -> FocusPage(planner.optJSONArray("subjects") ?: JSONArray(), subjectChoice, { subjectChoice = it }, focusMinutes, { focusMinutes = it }, context.getSharedPreferences("aura_native", 0).getLong("focus_deadline", 0), focusNow, onStartFocus, onStopFocus)
                "Study" -> StudyPage(planner)
                "Life" -> LifePage(steps, { steps = it }, screenTime, { screenTime = it }, calories, { calories = it }, busy, deviceScreenMinutes, usageAccess, syncStatus, onRequestSteps, onUsageAccess) {
                    scope.launch {
                        busy = true
                        try {
                            planner = withContext(Dispatchers.IO) { saveLifeEntry(context, planner, steps, screenTime, calories) }; error = "Saved for today."
                        } catch (e: Exception) { error = e.message ?: "Could not save today's log." }
                        busy = false
                    }
                }
                "Table" -> LeaderboardPage(leaderboard)
                else -> InfoPage()
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

@Composable private fun HomePage(account: JSONObject?, busy: Boolean, onComplete: (String) -> Unit) {
    val stats = account?.optJSONObject("stats") ?: JSONObject()
    val missions = account?.optJSONArray("today") ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("TODAY · ${LocalDate.now()}", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp) }
        item { Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(20.dp)) { Text("YOUR AURA", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp); Text(stats.optInt("total").toString(), fontSize = 42.sp, fontWeight = FontWeight.Black, color = Purple); Text("${stats.optInt("pending")} AP waiting for validation", color = Muted, fontSize = 12.sp) } } }
        item { SectionTitle("Today's missions", "${missions.length()} selected") }
        if (missions.length() == 0) item { EmptyCard("Your daily missions will appear here after you add them on AURA.") }
        items((0 until missions.length()).mapNotNull { missions.optJSONObject(it) }) { task ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(task.optString("title"), fontWeight = FontWeight.SemiBold); Text("${task.optString("category")} · ${task.optInt("points")} AP", color = Muted, fontSize = 11.sp) }
                    if (task.optBoolean("done")) Text("DONE", color = Color(0xFF83D6C4), fontSize = 10.sp, fontWeight = FontWeight.Bold) else Button(onClick = { onComplete(task.optString("task_id")) }, enabled = !busy, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text("Done", fontSize = 11.sp) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FocusPage(subjects: JSONArray, selected: String, select: (String) -> Unit, minutes: Int, setMinutes: (Int) -> Unit, deadline: Long, focusNow: Long, start: (String, String, Int) -> Unit, stop: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isRunning = context.getSharedPreferences("aura_native", 0).getBoolean("focus_running", false)
    val remaining = ((deadline - focusNow).coerceAtLeast(0) / 1000).toInt()
    Column(Modifier.fillMaxSize().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("FOCUS STUDIO", color = Muted, letterSpacing = 2.sp, fontSize = 10.sp)
        Box(Modifier.padding(26.dp).size(250.dp).background(Surface, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (isRunning) "%02d:%02d".format(remaining / 60, remaining % 60) else "%02d:00".format(minutes), fontSize = 48.sp, fontWeight = FontWeight.Light, color = Purple)
        }
        if (isRunning) {
            Text(context.getSharedPreferences("aura_native", 0).getString("focus_subject", "Study session").orEmpty(), color = Muted)
            Button(onClick = stop, modifier = Modifier.fillMaxWidth().padding(top = 25.dp)) { Text("End focus") }
            Text("Timer continues while your screen is off.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
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
            Text("AURA remains in a native background timer with a notification.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable private fun StudyPage(planner: JSONObject) {
    val subjects = planner.optJSONArray("subjects") ?: JSONArray()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { SectionTitle("Study space", "Your saved subjects") }
        if (subjects.length() == 0) item { EmptyCard("Add subjects from AURA on the web first. They sync to this native app.") }
        items((0 until subjects.length()).mapNotNull { subjects.optJSONObject(it) }) { subject ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(runCatching { Color(android.graphics.Color.parseColor(subject.optString("color", "#B5A9FF"))) }.getOrDefault(Purple), CircleShape))
                Column(Modifier.padding(start = 12.dp)) { Text(subject.optString("name"), fontWeight = FontWeight.SemiBold); if (subject.optString("teacher").isNotBlank()) Text(subject.optString("teacher"), color = Muted, fontSize = 11.sp) }
            } }
        }
        item { EmptyCard("Routine and subject editing will be added as native controls in the next build.") }
    }
}

@Composable private fun LifePage(steps: String, setSteps: (String) -> Unit, screen: String, setScreen: (String) -> Unit, calories: String, setCalories: (String) -> Unit, busy: Boolean, deviceScreenMinutes: Int?, usageAccess: Boolean, syncStatus: String, connectSteps: () -> Unit, usageSettings: () -> Unit, save: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Daily check-in", "${LocalDate.now()}")
        Text("Your device readings and entries stay synced to your AURA account.", color = Muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = connectSteps, modifier = Modifier.weight(1f)) { Text("Connect steps", fontSize = 11.sp) }
            OutlinedButton(onClick = usageSettings, modifier = Modifier.weight(1f)) { Text(if (usageAccess) "Refresh screen time" else "Allow screen time", fontSize = 11.sp) }
        }
        if (deviceScreenMinutes != null) Text("Today’s device use: ${deviceScreenMinutes / 60}h ${deviceScreenMinutes % 60}m", color = Color(0xFF83D6C4), fontSize = 11.sp)
        if (syncStatus.isNotBlank()) Text(syncStatus, color = Muted, fontSize = 11.sp)
        AuraField("Steps", steps, setSteps, KeyboardType.Number)
        AuraField("Screen time · minutes", screen, setScreen, KeyboardType.Number)
        AuraField("Calories · kcal", calories, setCalories, KeyboardType.Number)
        Button(onClick = save, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(if (busy) "Saving…" else "Save today's log") }
        Card(colors = CardDefaults.cardColors(containerColor = Surface)) { Text("Steps sync through Health Connect. Screen time uses Android Usage Access. Turn on each permission above; AURA only reads daily totals. Calories can be entered manually.", Modifier.padding(16.dp), color = Muted, fontSize = 12.sp) }
    }
}

@Composable private fun LeaderboardPage(entries: JSONArray) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { SectionTitle("Common table", "AURA community") }
        items((0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.mapIndexed { i, value -> i + 1 to value }) { (rank, entry) ->
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%02d".format(rank), color = Purple, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp))
                Column(Modifier.weight(1f)) { Text(entry.optString("name"), fontWeight = FontWeight.SemiBold); Text(entry.optString("title"), color = Muted, fontSize = 10.sp) }
                Text("${entry.optInt("points")} AP", color = Purple, fontWeight = FontWeight.Bold)
            } }
        }
        if (entries.length() == 0) item { EmptyCard("The community table is empty right now.") }
    }
}

@Composable private fun InfoPage() {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 22.dp)) {
        item { SectionTitle("About AURA", "THE AURA METHOD") }
        item { InfoCard("How points work", "Missions earn half their library value and taper by 3% at chapter milestones. Classes earn 10 AP. Focus earns 1 AP every two focused minutes. Streaks advance whenever you earn points.") }
        item { InfoCard("Daily combos", "4h study + 30m movement earns 1,200 AP. 6h + 1h earns 5,000 AP. The 10h + 1h All-day balance combo earns 11,261 AP—more than all 1,070 mission values combined.") }
        item { InfoCard("Connect with Aryan", "Connect with Aryan across work, projects, and social.") {
            val context = androidx.compose.ui.platform.LocalContext.current
            LinkRow("LinkedIn", "linkedin.com/in/aryan-tiwari-884906281", "https://linkedin.com/in/aryan-tiwari-884906281/", context)
            LinkRow("GitHub", "github.com/aryansdream", "https://github.com/aryansdream", context)
            LinkRow("Public Instagram", "@coderdrinks", "https://www.instagram.com/coderdrinks/", context)
            LinkRow("Personal Instagram", "@aryant1wari", "https://www.instagram.com/aryant1wari/", context)
            LinkRow("Email", "aryantiwari90605@gmail.com", "mailto:aryantiwari90605@gmail.com", context)
        } }
        item { InfoCard("Device features", "The native focus timer continues with the screen off and stops when you leave the app while the display stays on. Steps and screen time can sync after Health Connect and Usage Access are connected. Calories can be logged in Life.") }
    }
}

@Composable private fun InfoCard(title: String, body: String, extra: (@Composable () -> Unit)? = null) { Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(title, color = Purple, fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(body, color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp)); extra?.invoke() } } }
@Composable private fun LinkRow(label: String, target: String, url: String, context: android.content.Context) { Text("$label  ·  $target  ↗", color = Purple, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }.padding(top = 12.dp)) }
@Composable private fun SectionTitle(title: String, sub: String) { Column(Modifier.padding(top = 6.dp, bottom = 2.dp)) { Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(sub.uppercase(), color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp) } }
@Composable private fun EmptyCard(text: String) { Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) { Text(text, Modifier.fillMaxWidth().padding(16.dp), color = Muted, fontSize = 12.sp, lineHeight = 18.sp) } }
@Composable private fun AuraField(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().padding(top = 9.dp), label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard), visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}
private fun JSONArray.lastObjectForDay(day: String): JSONObject? { for (i in length() - 1 downTo 0) { val item = optJSONObject(i); if (item?.optString("date") == day) return item }; return null }

private fun saveLifeEntry(context: android.content.Context, planner: JSONObject, steps: String, screenTime: String, calories: String): JSONObject {
    val data = JSONObject(planner.toString())
    val list = data.optJSONArray("lifestyle") ?: JSONArray()
    val date = LocalDate.now().toString()
    val updated = JSONArray()
    for (i in 0 until list.length()) if (list.optJSONObject(i)?.optString("date") != date) updated.put(list.optJSONObject(i))
    updated.put(JSONObject().put("date", date).put("steps", steps.toIntOrNull() ?: 0).put("screenTime", screenTime.toIntOrNull() ?: 0).put("calories", calories.toIntOrNull() ?: 0))
    data.put("lifestyle", updated)
    AuraApi.request(context, "/api/planner", JSONObject().put("data", data))
    return data
}
