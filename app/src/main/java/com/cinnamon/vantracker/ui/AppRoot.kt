package com.cinnamon.vantracker.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.cinnamon.vantracker.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinnamon.vantracker.data.AppSettings
import com.cinnamon.vantracker.data.Backend
import com.cinnamon.vantracker.data.DEPARTMENTS
import com.cinnamon.vantracker.data.FbConfig
import com.cinnamon.vantracker.data.Repo
import com.cinnamon.vantracker.data.Role
import com.cinnamon.vantracker.data.UserProfile
import com.cinnamon.vantracker.notify.WatchWorker
import com.cinnamon.vantracker.tracking.DevicePrefs
import com.cinnamon.vantracker.tracking.TrackingService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Scope for work that must finish even if the screen that started it goes away. */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

// ------------------------------------------------------------------ navigation

sealed interface Screen {
    data object Home : Screen
    data class TripDetail(val id: String) : Screen
    data class NewRequest(val joinTarget: String = "", val joinLabel: String = "") : Screen
    data object ActiveTrip : Screen
    data object Reasons : Screen
    data object FuelEntry : Screen
    data object DriverSetup : Screen
    data object LiveMap : Screen
    data object Reports : Screen
    data object AdminSettings : Screen
    data object Users : Screen
    data object Schedules : Screen
    data object FuelLogs : Screen
    data object ShareSetup : Screen
    data object Alerts : Screen
    data object Approvals : Screen
    data object MyRequests : Screen
    data object Trips : Screen
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val top get() = stack.last()
    var homeTab by mutableStateOf(0)
    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

/** Everything a screen needs to know about who is using the app. */
class Session(val me: UserProfile, val settings: AppSettings, val nav: Nav) {
    val canApprove get() = me.isManager || me.isAdmin
    val seesEverything get() = me.isManager || me.isAdmin || me.isDriver
}

private sealed interface ProfileState {
    data object Loading : ProfileState
    data class Loaded(val p: UserProfile?) : ProfileState
}

private fun authFlow() = callbackFlow<FirebaseUser?> {
    val l = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
    Backend.auth.addAuthStateListener(l)
    awaitClose { Backend.auth.removeAuthStateListener(l) }
}

@Composable
fun AppRoot(openRequest: String, onOpenHandled: () -> Unit) {
    val ctx = LocalContext.current
    var configured by remember { mutableStateOf(Backend.init(ctx)) }
    if (!configured) {
        SetupScreen(onDone = { configured = Backend.init(ctx) })
        return
    }
    val user by remember { authFlow() }.collectAsState(initial = Backend.auth.currentUser)
    val u = user
    if (u == null) {
        LoginScreen()
        return
    }
    val profileState by remember(u.uid) { Repo.profileFlow(u.uid).map { ProfileState.Loaded(it) } }
        .collectAsState(initial = ProfileState.Loading)
    val settings by remember { Repo.settingsFlow() }.collectAsState(initial = AppSettings())

    when (val ps = profileState) {
        ProfileState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ProfileState.Loaded -> {
            val p = ps.p
            when {
                p == null -> RegisterScreen(existingUser = u)
                !p.active -> MessageScreen("Account disabled", "Your account has been disabled by the administrator.")
                p.role == Role.PENDING -> MessageScreen(
                    "Waiting for approval",
                    "Hi ${p.name}, your account was created. The administrator must approve it and assign your role " +
                        "(requested: ${p.requestedRole.label}). This screen will update automatically.",
                )
                else -> {
                    val nav = remember(p.uid) { Nav() }
                    val session = Session(p, settings, nav)
                    LaunchedEffect(p.uid, p.role) {
                        WatchWorker.schedule(ctx)
                        DevicePrefs.setDriverPhone(ctx, p.isDriver)
                        if (p.isDriver && DriverPermissions.allGranted(ctx)) TrackingService.start(ctx)
                    }
                    LaunchedEffect(openRequest) {
                        if (openRequest.isNotBlank()) {
                            nav.home()
                            when (openRequest) {
                                "approvals" -> if (session.canApprove) nav.go(Screen.Approvals)
                                "alerts" -> if (session.canApprove) nav.go(Screen.Alerts)
                                "reasons" -> nav.go(Screen.Reasons)
                                "requests" -> nav.go(Screen.MyRequests)
                            }
                            onOpenHandled()
                        }
                    }
                    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
                    ScreenHost(session)
                }
            }
        }
    }
}

@Composable
private fun ScreenHost(s: Session) {
    when (val screen = s.nav.top) {
        Screen.Home -> if (s.me.isDriver) DriverHome(s) else StaffHome(s)
        is Screen.TripDetail -> TripDetailScreen(s, screen.id)
        is Screen.NewRequest -> NewRequestScreen(s, screen.joinTarget, screen.joinLabel)
        Screen.ActiveTrip -> ActiveTripScreen(s)
        Screen.Reasons -> ReasonsScreen(s)
        Screen.FuelEntry -> FuelEntryScreen(s)
        Screen.DriverSetup -> DriverSetupScreen(s)
        Screen.LiveMap -> LiveMapScreen(s)
        Screen.Reports -> ReportsScreen(s)
        Screen.AdminSettings -> AdminSettingsScreen(s)
        Screen.Users -> UsersScreen(s)
        Screen.Schedules -> SchedulesScreen(s)
        Screen.FuelLogs -> FuelLogsScreen(s)
        Screen.ShareSetup -> ShareSetupScreen(s)
        Screen.Alerts -> AlertsScreen(s)
        Screen.Approvals -> ApprovalsScreen(s)
        Screen.MyRequests -> MyRequestsScreen(s)
        Screen.Trips -> TripsScreen(s)
    }
}

// ------------------------------------------------------------------ setup / auth screens

@Composable
private fun Header(title: String, subtitle: String) {
    // Brand cover: purple gradient with the magenta quarter-curve (brand kit, page 01).
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 20.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(BrandGradient)
            .height(220.dp)
    ) {
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(130.dp, 90.dp)
                .clip(RoundedCornerShape(topStart = 90.dp))
                .background(Color(0xFF9A2580))
        )
        Column(Modifier.padding(24.dp)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_foreground), null, modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("KANDY MYST BY CINNAMON", style = MaterialTheme.typography.bodySmall, letterSpacing = 2.sp,
                color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(subtitle, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Header("Van Tracker", "First-time setup")
            Text(
                "Connect this phone to the hotel's Firebase project (free plan).\n\n" +
                    "• Administrator: paste the full contents of google-services.json downloaded from the Firebase console.\n" +
                    "• Everyone else: paste the setup code (starts with VT1:) the administrator shared with you.",
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it; error = "" },
                label = { Text("Setup code or google-services.json") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
            if (error.isNotBlank()) Text(error, color = DangerRed)
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                val cfg = FbConfig.parse(text)
                if (cfg == null) error = "That doesn't look like a valid setup code or google-services.json."
                else { Backend.saveConfig(ctx, cfg); onDone() }
            }, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
        }
    }
}

@Composable
fun LoginScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    if (register) {
        RegisterScreen(existingUser = null, onCancel = { register = false })
        return
    }
    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Header("Van Tracker", "Staff transport management")
            OutlinedTextField(
                email, { email = it.trim() }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
            if (msg.isNotBlank()) Text(msg, color = DangerRed, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; msg = ""
                scope.launch {
                    runCatching { Backend.auth.signInWithEmailAndPassword(email, password).await() }
                        .onFailure { msg = it.message ?: "Sign in failed" }
                    busy = false
                }
            }) { Text(if (busy) "Signing in…" else "Sign in") }
            OutlinedButton(onClick = { register = true }, modifier = Modifier.fillMaxWidth()) { Text("Create an account") }
            TextButton(onClick = {
                if (email.isBlank()) msg = "Enter your email first"
                else scope.launch {
                    msg = runCatching { Backend.auth.sendPasswordResetEmail(email).await(); "Password reset email sent." }
                        .getOrElse { it.message ?: "Failed" }
                }
            }) { Text("Forgot password?") }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = {
                Backend.clearConfig(ctx)
                (ctx as? Activity)?.finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }) { Text("Change backend connection", color = TextSecondary) }
        }
    }
}

@Composable
fun RegisterScreen(existingUser: FirebaseUser?, onCancel: (() -> Unit)? = null) {
    // Creating the auth user swaps this screen out, so the work must outlive the composable.
    val scope = AppScope
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(existingUser?.email ?: "") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var department by remember { mutableStateOf(DEPARTMENTS.first()) }
    var role by remember { mutableStateOf(Role.HOD) }
    var admin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    Scaffold(topBar = { AppBar("Create account", onBack = onCancel) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Full name") }, modifier = Modifier.fillMaxWidth())
            if (existingUser == null) {
                OutlinedTextField(email, { email = it.trim() }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(password, { password = it }, label = { Text("Password (min 6)") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation())
            }
            OutlinedTextField(phone, { phone = it }, label = { Text("Mobile number") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            Picker("Department", department, DEPARTMENTS) { department = it }
            Picker("I am a", role.label, listOf(Role.HOD, Role.DRIVER, Role.GM, Role.OM, Role.ADMIN).map { it.label }) { l ->
                role = Role.entries.first { it.label == l }
                if (role == Role.ADMIN) admin = true
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(admin, { admin = it })
                Text("First-time setup: make me the system administrator (only works for the very first account)",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            if (msg.isNotBlank()) Text(msg, color = DangerRed)
            Button(
                enabled = !busy && name.isNotBlank() && email.isNotBlank() && (existingUser != null || password.length >= 6),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true; msg = ""
                    scope.launch {
                        runCatching {
                            val fu = existingUser ?: Backend.auth.createUserWithEmailAndPassword(email, password).await().user!!
                            val base = UserProfile(
                                uid = fu.uid, name = name.trim(), email = email, phone = phone.trim(),
                                department = department, role = Role.PENDING, requestedRole = role,
                            )
                            if (admin) {
                                val ok = runCatching { Repo.createProfile(base.copy(role = role, isAdmin = true), true).await() }.isSuccess
                                if (!ok) {
                                    Repo.createProfile(base, false).await()
                                    msg = "An administrator already exists – your account is waiting for approval."
                                }
                            } else {
                                Repo.createProfile(base, false).await()
                            }
                        }.onFailure { msg = it.message ?: "Registration failed" }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Creating…" else "Create account") }
            if (existingUser != null) TextButton(onClick = { Backend.auth.signOut() }) { Text("Sign out") }
        }
    }
}

@Composable
fun MessageScreen(title: String, text: String) {
    Scaffold { pad ->
        Column(Modifier.padding(pad).padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Cinnamon)
            Spacer(Modifier.height(12.dp))
            Text(text)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { Backend.auth.signOut() }) { Text("Sign out") }
        }
    }
}

fun signOut(ctx: android.content.Context) {
    TrackingService.stop(ctx)
    DevicePrefs.setDriverPhone(ctx, false)
    WatchWorker.cancel(ctx)
    Backend.auth.signOut()
}
