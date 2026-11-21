package com.wysebee.agent.android.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wysebee.agent.android.AgentApp
import com.wysebee.agent.android.R
import com.wysebee.agent.android.core.Agent
import com.wysebee.agent.android.core.App
import com.wysebee.agent.android.core.Reconnect
import com.wysebee.agent.android.core.Sensitive
import com.wysebee.agent.android.device.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext

private val Purple = Color(0xFF7B5CD6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AgentApp
        // Play services downloads its QR code scanner on first use, so a first scan would fail
        // while it does: ask for it now, while the phone is not paired yet. Nothing if it is there.
        if (app.agent.state.link == null) {
            val scanner = GmsBarcodeScanning.getClient(this)
            runCatching { ModuleInstall.getClient(this).installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build()) }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Color(0xFF15121C), surface = Color(0xFF1F1A2A))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Screen(app, openAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
                }
            }
        }
    }
}

@Composable
private fun Screen(app: AgentApp, openAccessibility: () -> Unit) {
    val agent = app.agent
    val status by agent.status.collectAsStateWithLifecycle()
    val serviceOn by app.serviceOn.collectAsStateWithLifecycle()
    val s = status.state
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // This build's own name ("Wysebee Agent Dev", "… Local"): it is also the name to find in
        // Settings › Accessibility.
        val name = stringResource(R.string.app_name)
        Text(name, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text("Lets one Wysebee agent see this phone's screen and tap, type, swipe and open apps, in the apps you allow here.", color = Color.LightGray, fontSize = 14.sp)

        status.message?.let { msg ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f), fontSize = 14.sp)
                    TextButton(onClick = agent::dismissMessage) { Text("OK") }
                }
            }
        }

        Section("Accessibility service") {
            if (serviceOn) {
                Text("On: the agent can see and touch the screen (in allowed apps only).", fontSize = 14.sp)
            } else {
                Text("Off: the agent can do nothing on this phone until you turn on $name in Settings › Accessibility.", fontSize = 14.sp)
                Button(onClick = openAccessibility) { Text("Open accessibility settings") }
            }
        }

        when {
            s.link != null -> Paired(agent, status.connected)
            s.pending != null -> Section("Pairing") {
                Text("In Wysebee, under Devices, type this code:", fontSize = 14.sp)
                Text(s.pending.code.chunked(4).joinToString("-"), fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("For a new agent named \"${s.pending.name}\". The code works for 10 minutes.", color = Color.LightGray, fontSize = 13.sp)
                OutlinedButton(onClick = agent::cancelPairing) { Text("Cancel") }
            }
            else -> Unpaired(agent, status.busy, status.reconnect)
        }

        if (s.link != null) Apps(app, agent)

        Text("Wysebee Cloud: ${agent.cloudUrl()}", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        content()
    }
}

@Composable
private fun Unpaired(agent: Agent, busy: Boolean, reconnect: Reconnect?) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    Section("Pair with Wysebee") {
        Text("Type a name for a new agent. Then scan the QR code Wysebee shows (Your devices › Pair a device › Android phone), or tap Pair and type the code this phone shows in Wysebee.", fontSize = 14.sp)
        OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Agent name") })
        if (reconnect != null) {
            // The scan's name is taken by an agent of this kind with no phone now.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !busy, onClick = { scope.launch { agent.reconnect() } }) { Text("Reconnect to ${reconnect.agentLabel}") }
                OutlinedButton(enabled = !busy, onClick = agent::declineReconnect) { Text("Use another name") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !busy && name.isNotBlank(), onClick = {
                scope.launch {
                    when (val scanned = scanQr(context)) {
                        is Scan.Read -> agent.pairWithQr(name, scanned.text)
                        is Scan.Failed -> agent.say(scanned.why)
                        Scan.Cancelled -> {}
                    }
                }
            }) { Text(if (busy) "Pairing…" else "Scan QR code") }
            OutlinedButton(enabled = !busy && name.isNotBlank(), onClick = { scope.launch { agent.startPairing(name) } }) {
                Text("Pair with a code")
            }
        }
    }
}

private sealed interface Scan {
    data class Read(val text: String?) : Scan
    data class Failed(val why: String) : Scan
    data object Cancelled : Scan
}

/** Google Play services' QR code scanner: its own camera screen, QR codes only. */
private suspend fun scanQr(context: Context): Scan = suspendCancellableCoroutine { cont ->
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { if (cont.isActive) cont.resume(Scan.Read(it.rawValue)) }
        .addOnCanceledListener { if (cont.isActive) cont.resume(Scan.Cancelled) }
        .addOnFailureListener {
            if (!cont.isActive) return@addOnFailureListener
            cont.resume(
                when ((it as? MlKitException)?.errorCode) {
                    MlKitException.CODE_SCANNER_CANCELLED -> Scan.Cancelled
                    // Play services fetches the scanner on first use (asked for when the app opened).
                    MlKitException.UNAVAILABLE, MlKitException.CODE_SCANNER_UNAVAILABLE ->
                        Scan.Failed("Google Play services is still downloading its QR code scanner. Try again in a minute, or tap Pair with a code.")
                    MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD ->
                        Scan.Failed("Google Play services is too old for the QR code scanner: update it from the Play Store, or tap Pair with a code.")
                    else -> Scan.Failed("The QR code scanner could not open (${it.message ?: "Google Play services"}). Tap Pair with a code instead.")
                },
            )
        }
}

@Composable
private fun Paired(agent: Agent, connected: Boolean) {
    val scope = rememberCoroutineScope()
    val s by agent.status.collectAsStateWithLifecycle()
    val link = s.state.link ?: return
    var confirm by remember { mutableStateOf(false) }
    Section("Paired") {
        Text("@${link.agentName} — ${link.agentLabel}", fontSize = 15.sp)
        Text(if (connected) "Connected to Wysebee" else "Not connected — trying again", color = if (connected) Color(0xFF7BD88F) else Color(0xFFE0A050), fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Let Wysebee operate this phone", Modifier.weight(1f), fontSize = 14.sp)
            Switch(checked = !s.state.paused, onCheckedChange = { agent.setPaused(!it) })
        }
        OutlinedButton(onClick = { confirm = true }) { Text("Unpair") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Unpair this phone?") },
        text = { Text("@${link.agentName} stays in Wysebee, without a phone. The apps you allowed are forgotten.") },
        confirmButton = { TextButton(onClick = { confirm = false; scope.launch { agent.unpair() } }) { Text("Unpair") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Apps(app: AgentApp, agent: Agent) {
    val s by agent.status.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    Section("Apps the agent may operate") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("System UI", fontSize = 14.sp)
                Text(
                    "The notification shade and quick settings. The agent can then read every app's notifications — codes your bank texts you too — and switch Wi-Fi, Bluetooth or airplane mode. Never the lock screen.",
                    color = Color.LightGray, fontSize = 12.sp,
                )
            }
            Switch(checked = s.state.systemUi, onCheckedChange = agent::setSystemUi, modifier = Modifier.padding(start = 12.dp))
        }
        if (s.state.allowed.isEmpty()) Text("None yet. The agent can open, read and touch only the apps listed here.", fontSize = 14.sp, color = Color.LightGray)
        s.state.allowed.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, fontSize = 14.sp)
                    Text(a.pkg, fontSize = 12.sp, color = Color.LightGray)
                    if (Sensitive.isSensitive(a.pkg, a.label)) Text(NO_SCREENSHOTS, fontSize = 12.sp, color = Color(0xFFE0A050))
                }
                TextButton(onClick = { agent.disallow(a.pkg) }) { Text("Remove") }
            }
        }
        Button(onClick = { picking = true }) { Text("Add apps…") }
    }
    if (picking) AppPicker(
        app,
        allowed = s.state.allowed.map { it.pkg }.toSet(),
        onAdd = { agent.allow(it); picking = false },
        onClose = { picking = false },
    )
}

private const val NO_SCREENSHOTS = "Banking, payment or password app: never screenshotted"

/** Every app that can be opened, with a checkbox each: the person ticks several, then adds them. */
@Composable
private fun AppPicker(app: AgentApp, allowed: Set<String>, onAdd: (List<App>) -> Unit, onClose: () -> Unit) {
    var apps by remember { mutableStateOf<List<App>?>(null) }
    var query by remember { mutableStateOf("") }
    var ticked by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AgentAccessibilityService.launchable(app.packageManager).filter { it.pkg != app.packageName } }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Allow apps") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search") })
                Spacer(Modifier.height(8.dp))
                val shown = apps.orEmpty().filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.pkg }) { a ->
                        val already = a.pkg in allowed
                        val on = already || a.pkg in ticked
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = on, enabled = !already, role = Role.Checkbox) {
                                ticked = if (it) ticked + a.pkg else ticked - a.pkg
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The row toggles it: the box itself only shows the state.
                            Checkbox(checked = on, onCheckedChange = null, enabled = !already, modifier = Modifier.padding(end = 12.dp))
                            Column {
                                Text(a.label, fontSize = 15.sp)
                                Text(if (already) "Already allowed" else a.pkg, fontSize = 12.sp, color = Color.LightGray)
                                if (Sensitive.isSensitive(a.pkg, a.label)) Text(NO_SCREENSHOTS, fontSize = 12.sp, color = Color(0xFFE0A050))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ticked.isNotEmpty(), onClick = { onAdd(apps.orEmpty().filter { it.pkg in ticked }) }) {
                Text(if (ticked.isEmpty()) "Add" else "Add ${ticked.size}")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
