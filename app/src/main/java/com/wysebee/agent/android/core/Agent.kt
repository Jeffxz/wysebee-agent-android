package com.wysebee.agent.android.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.io.IOException

/** How long things take; tests make them short. */
data class Timing(val wait: Int = 20, val backoff: List<Long> = listOf(2_000, 5_000, 15_000, 30_000))

/** What the window shows. */
data class Status(
    val state: State,
    val connected: Boolean = false,
    val message: String? = null,
    val busy: Boolean = false,
    /** A scanned QR code refused for a name taken by an agent this phone may be reconnected to. */
    val reconnect: Reconnect? = null,
)

/** The agent a scan may reconnect this phone to: one of its type with no phone now. */
data class Reconnect(val typed: String, val qr: PairingQr, val agentName: String, val agentLabel: String)

/**
 * The agent: pairs with Wysebee Cloud, keeps one long poll going, and answers each call with
 * [Ops]. The Mac app's agent.rs, for a phone: no programs, skills or folders — the person allows
 * apps instead, here, and nothing from the cloud changes that.
 */
class Agent(
    private val store: StateStore,
    private val secrets: SecretStore,
    private val link: LinkClient,
    device: () -> Device?,
    private val own: String,
    /** The one Wysebee Cloud this build talks to (BuildConfig.CLOUD_URL). */
    private val cloud: String,
    private val deviceName: String,
    private val version: String,
    private val scope: CoroutineScope,
    private val timing: Timing = Timing(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(Status(store.load()))
    val status: StateFlow<Status> = _status.asStateFlow()
    private val kicks = MutableStateFlow(0)
    private val running = HashMap<String, Job>()

    val ops = Ops(device, { state.let { Policy(it.paused, it.allowed.map(AllowedApp::pkg).toSet(), it.systemUi) } }, own)

    val state: State get() = _status.value.state

    fun cloudUrl(): String = state.link?.cloudUrl ?: state.pending?.cloudUrl ?: cloud

    /** Starts the loop; it runs until [scope] ends. */
    fun start(): Job = scope.launch { loop() }

    // ---- what the person does in the window ----

    /** Pairs by a QR code Wysebee shows (its text, as scanned): paired at once, no code to type.
     *  Returns an English error, or null when paired. */
    suspend fun pairWithQr(typed: String, scanned: String?): String? {
        val qr = PairingQr.parse(scanned) ?: return note("That is not a Wysebee pairing QR code. In Wysebee, open Your devices › Pair a device › Android phone, and scan the code it shows.")
        return startPairing(typed, qr)
    }

    /** Reconnects this phone to the agent a scan's taken name named, with the same QR code. */
    suspend fun reconnect(): String? {
        val r = _status.value.reconnect ?: return note("There is no agent to reconnect to: scan the QR code again.")
        return startPairing(r.typed, r.qr, reconnect = true)
    }

    /** Forgets the offer to reconnect: the person will type another name. */
    fun declineReconnect() = _status.update { it.copy(reconnect = null, message = null) }

    /** Returns an English error, or null when the code is showing (or, with [qr], paired). */
    suspend fun startPairing(typed: String, qr: PairingQr? = null, reconnect: Boolean = false): String? {
        val name = typed.trim()
        if (name.isEmpty() || name.length > 60 || name.any { it == '\n' || it == '\r' }) return note("Type a name for the agent: one line, at most 60 characters.")
        if (state.link != null || state.pending != null) return note("This phone is already pairing or paired.")
        val cloud = cloudUrl()
        val secret = Secrets.newSecret()
        _status.update { it.copy(busy = true, message = null, reconnect = null) }
        try {
            // Kept before the cloud hears of it: a phone that cannot keep it leaves no code waiting.
            try {
                secrets.set(secret)
            } catch (e: Exception) {
                return note(KEEP_FAILED.format(e.message ?: e.javaClass.simpleName))
            }
            val started = try {
                link.start(cloud, KIND, name, Secrets.sha256Hex(secret), deviceName, version, qr?.offer, reconnect)
            } catch (e: CloudRefusal) {
                secrets.clear()
                val taken = e.body.optJSONObject("agent")
                if (qr != null && e.code == "name_taken" && e.body.optBoolean("reconnect", false) && taken != null && taken.optString("name").isNotEmpty()) {
                    // Taken by an agent of this kind with no phone now: this phone may be it again.
                    val handle = taken.optString("name")
                    val label = taken.optString("label").ifEmpty { handle }
                    _status.update { it.copy(reconnect = Reconnect(name, qr, handle, label)) }
                    return note("$label (@$handle) already has that name, and has no phone now. Reconnect this phone to it, keeping its settings — or type another name for a new agent and scan again.")
                }
                return note(when {
                    e.code == "invalid_kind" -> "This Wysebee Cloud does not pair Android agents yet."
                    // A QR code shown by another Wysebee: its offer was never sent anywhere but here.
                    e.code == "offer_unknown" && qr?.from != null && PairingQr.host(qr.from) != PairingQr.host(cloud) ->
                        "This QR code was shown by ${PairingQr.host(qr.from) ?: qr.from}, but this app pairs with ${PairingQr.host(cloud) ?: cloud}. Show the QR code in that Wysebee."
                    else -> e.message
                })
            } catch (e: IOException) {
                secrets.clear()
                return note("Wysebee Cloud could not be reached: ${e.message ?: "no connection"}.")
            }
            try {
                val agent = started.agent
                if (agent != null) {
                    // Paired at once by the QR code's offer.
                    edit { it.copy(link = Link(started.id, cloud, agent.first, agent.second), paused = false) }
                    _status.update { it.copy(message = null) }
                    return null
                }
                edit { it.copy(pending = Pending(started.id, started.code, now() + started.expiresIn * 1000L, name, cloud)) }
            } catch (e: Exception) {
                secrets.clear()
                return note(KEEP_FAILED.format(e.message ?: e.javaClass.simpleName))
            }
            return null
        } finally {
            _status.update { it.copy(busy = false) }
        }
    }

    fun cancelPairing() {
        secrets.clear()
        edit { it.copy(pending = null) }
    }

    /** Unpairs here and tells the cloud (best effort): the agent stays in Wysebee, without a phone. */
    suspend fun unpair() {
        val l = state.link
        val secret = secrets.get()
        stopCalls(null)
        forget(null)
        if (l != null && secret != null) runCatching { link.unpair(l.cloudUrl, l.id, secret) }
    }

    fun setPaused(paused: Boolean) {
        if (paused) stopCalls(Ops.PAUSED)
        edit { it.copy(paused = paused) }
    }

    /** Allows each of [apps] the person ticked, in order; never this app, none twice. */
    fun allow(apps: List<App>) = edit { s ->
        val more = apps.filter { a -> a.pkg != own && s.allowed.none { it.pkg == a.pkg } }.distinctBy { it.pkg }
        if (more.isEmpty()) s else s.copy(allowed = s.allowed + more.map { AllowedApp(it.pkg, it.label) })
    }

    fun allow(app: App) = allow(listOf(app))

    /** The notification shade and quick settings, besides the apps. */
    fun setSystemUi(on: Boolean) = edit { it.copy(systemUi = on) }

    fun disallow(pkg: String) = edit { s -> s.copy(allowed = s.allowed.filter { it.pkg != pkg }) }


    fun dismissMessage() = _status.update { it.copy(message = null, reconnect = null) }

    /** Shows the person a sentence (one the window found, such as a scanner that would not open). */
    fun say(message: String) { note(message) }

    // ---- the loop ----

    private suspend fun loop() {
        var failures = 0
        while (true) {
            val gen = kicks.value
            val s = state
            val ok = try {
                when {
                    s.link != null -> pollPaired(s.link, gen)
                    s.pending != null -> pollPending(s.pending, gen)
                    else -> { _status.update { it.copy(connected = false) }; kicks.first { it != gen }; true }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                false
            }
            if (ok) failures = 0 else {
                _status.update { it.copy(connected = false) }
                val pause = timing.backoff[minOf(failures, timing.backoff.size - 1)]
                failures++
                untilKicked(gen) { delay(pause) }
            }
        }
    }

    private suspend fun pollPending(p: Pending, gen: Int): Boolean {
        val secret = secrets.get() ?: run { edit { it.copy(pending = null) }; return true }
        val answer = untilKicked(gen) { link.poll(p.cloudUrl, p.id, secret, statusBody(), timing.wait) } ?: return true
        if (state.pending?.id != p.id) return true
        when (answer) {
            is LinkClient.Poll.Pending -> {}
            is LinkClient.Poll.Paired -> {
                edit { it.copy(pending = null, link = Link(p.id, p.cloudUrl, answer.agentName, answer.agentLabel), paused = false) }
                _status.update { it.copy(connected = true, message = null) }
                handle(answer)
            }
            is LinkClient.Poll.Refused -> { secrets.clear(); edit { it.copy(pending = null) }; note(answer.message) }
            LinkClient.Poll.Expired -> { secrets.clear(); edit { it.copy(pending = null) }; note("The code ran out before it was typed in Wysebee. Pair again.") }
            LinkClient.Poll.Unpaired -> { secrets.clear(); edit { it.copy(pending = null) }; note("Wysebee no longer knows this pairing. Pair again.") }
        }
        return true
    }

    private suspend fun pollPaired(l: Link, gen: Int): Boolean {
        val secret = secrets.get() ?: run { forget("The pairing's secret is gone from this phone. Pair again."); return true }
        val answer = untilKicked(gen) { link.poll(l.cloudUrl, l.id, secret, statusBody(), timing.wait) } ?: return true
        if (state.link?.id != l.id) return true
        when (answer) {
            is LinkClient.Poll.Paired -> {
                _status.update { it.copy(connected = true) }
                if (answer.agentLabel.isNotEmpty() && (answer.agentLabel != l.agentLabel || answer.agentName != l.agentName)) {
                    edit { it.copy(link = it.link?.copy(agentName = answer.agentName, agentLabel = answer.agentLabel)) }
                }
                handle(answer)
            }
            LinkClient.Poll.Unpaired -> { stopCalls(null); forget("Unpaired in Wysebee.") }
            else -> _status.update { it.copy(connected = true) }
        }
        return true
    }

    private fun handle(answer: LinkClient.Poll.Paired) {
        val l = state.link ?: return
        synchronized(running) { answer.cancelled.forEach { running.remove(it)?.cancel() } }
        for (call in answer.calls) {
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val outcome = ops.run(call.op, call.args)
                val gone = synchronized(running) { running.remove(call.id) == null }
                if (gone) return@launch
                answer(l, call.id, outcome)
            }
            synchronized(running) { running[call.id] = job }
            job.start()
        }
    }

    private suspend fun answer(l: Link, callId: String, outcome: Outcome) {
        val secret = secrets.get() ?: return
        // Twice at most: a lost answer leaves the model waiting until its timeout.
        repeat(2) { attempt ->
            try {
                link.result(l.cloudUrl, l.id, secret, callId, outcome)
                return
            } catch (e: IOException) {
                if (attempt == 0) delay(timing.backoff.first())
            }
        }
    }

    /** Stops what runs; answers each call with [why], or nothing when null. */
    private fun stopCalls(why: String?) {
        val l = state.link
        val stopped = synchronized(running) { running.toMap().also { running.clear() } }
        stopped.forEach { (id, job) ->
            job.cancel()
            if (why != null && l != null) scope.launch { answer(l, id, Outcome.Failed(why)) }
        }
    }

    private fun forget(message: String?) {
        secrets.clear()
        edit { it.copy(link = null, pending = null, paused = false, allowed = emptyList(), systemUi = false) }
        _status.update { it.copy(connected = false, message = message) }
    }

    private fun statusBody(): JSONObject =
        JSONObject().put("paused", state.paused).put("extension_version", version).put("browser", deviceName.take(32))

    private fun note(message: String?): String? {
        _status.update { it.copy(message = message) }
        return message
    }

    private fun edit(change: (State) -> State) {
        synchronized(this) {
            val before = state
            val after = change(before)
            if (after == before) return
            store.save(after)
            _status.update { it.copy(state = after) }
            // Only a new or ended link cuts the poll short. A poll cut short may lose calls the
            // cloud handed out in its answer (each is handed out once), so a pause or an allowed
            // app never does: both are read at each call, and the next poll reports the pause.
            if (before.link?.id != after.link?.id || before.pending?.id != after.pending?.id) kicks.update { it + 1 }
        }
    }

    /** Runs [block], or gives up on it (null) as soon as the state changes. */
    private suspend fun <T> untilKicked(gen: Int, block: suspend () -> T): T? = coroutineScope {
        val work = async { block() }
        val kicked = async { kicks.first { it != gen } }
        select {
            work.onAwait { kicked.cancel(); it }
            kicked.onAwait { work.cancel(); null }
        }
    }

    companion object {
        /** This app's agent type (the link protocol). */
        const val KIND = "wysebee-android"
        /** Something this phone could not keep in its own storage while pairing. */
        const val KEEP_FAILED = "This phone could not keep the pairing in Wysebee Agent's storage: %s. Try again; if it fails again, clear the app's storage in Settings › Apps."
    }
}
