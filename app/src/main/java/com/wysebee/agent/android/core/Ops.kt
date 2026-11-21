package com.wysebee.agent.android.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** An op's answer: `ok: true` with a result, or `ok: false` with an English error. */
sealed interface Outcome {
    data class Ok(val result: Any) : Outcome
    data class Failed(val error: String) : Outcome
}

/** What the person decided on the phone, read at each call. */
data class Policy(val paused: Boolean, val allowed: Set<String>, val systemUi: Boolean = false) {
    fun may(pkg: String, own: String) = pkg != own && (pkg in allowed || (systemUi && pkg == SYSTEM_UI))
}

private class Refusal(message: String) : Exception(message)

/**
 * The ops of an Android link (the link protocol, "Operating the phone"). **This app decides**:
 * nothing is read or touched while paused or in an app the person did not allow, never this
 * app itself, and never a password field. One op runs at a time (they are one person's hands).
 */
class Ops(
    private val device: () -> Device?,
    private val policy: () -> Policy,
    private val own: String,
) {
    private val hands = Mutex()
    @Volatile private var refs: Map<Int, UiNode> = emptyMap()

    suspend fun run(op: String, args: JSONObject): Outcome {
        if (op == "ping") return Outcome.Ok(JSONObject().put("pong", true))
        if (op !in OPS) return Outcome.Failed("Wysebee Agent for Android does not know the operation '$op'.")
        return hands.withLock {
            try {
                val p = policy()
                if (p.paused) throw Refusal(PAUSED)
                val d = device() ?: throw Refusal(NO_SERVICE)
                // Locked, the screen is the lock screen (System UI): never read or touched, so the
                // agent can never try a PIN, whatever is allowed.
                if (d.locked()) throw Refusal(LOCKED)
                Outcome.Ok(dispatch(op, args, d, p))
            } catch (e: Refusal) {
                Outcome.Failed(e.message!!)
            } catch (e: DeviceException) {
                Outcome.Failed(e.message!!)
            }
        }
    }

    private suspend fun dispatch(op: String, a: JSONObject, d: Device, p: Policy): JSONObject = when (op) {
        "android_apps" -> apps(d, p)
        "android_launch" -> launch(a, d, p)
        "android_snapshot" -> snapshot(a, d, p)
        "android_screenshot" -> screenshot(d, p)
        "android_tap" -> tap(a, d, p)
        "android_type" -> type(a, d, p)
        "android_swipe" -> swipe(a, d, p)
        "android_scroll" -> scroll(a, d, p)
        "android_key" -> key(a, d)
        else -> throw Refusal("Unknown operation '$op'.")
    }

    private fun apps(d: Device, p: Policy): JSONObject {
        val list = p.allowed.filter { it != own }.map { App(it, d.appLabel(it) ?: it) }
        return JSONObject().put("apps", JSONArray().apply {
            list.sortedBy { it.label.lowercase() }.forEach { put(JSONObject().put("package", it.pkg).put("label", it.label)) }
        }).put("system_ui", p.systemUi)
    }

    private suspend fun launch(a: JSONObject, d: Device, p: Policy): JSONObject {
        val pkg = a.optString("package").trim()
        if (pkg.isEmpty()) throw Refusal("Say which app to open: its package, from android_apps.")
        if (!p.may(pkg, own)) throw Refusal("Wysebee may not open $pkg: it is not one of the apps the person allowed in Wysebee Agent on the phone.")
        if (!d.launch(pkg)) throw Refusal("$pkg cannot be opened: it is not installed, or has no screen to open.")
        d.settle(SETTLE_LAUNCH)
        return JSONObject().put("opened", pkg).put("app", d.activeApp() ?: JSONObject.NULL)
    }

    private suspend fun snapshot(a: JSONObject, d: Device, p: Policy): JSONObject {
        val wait = a.optLong("wait_ms", 0).coerceIn(0, MAX_WAIT)
        if (wait > 0) delay(wait)
        d.settle(SETTLE_READ)
        val app = gate(d, p)
        val root = d.root() ?: throw Refusal(NOTHING)
        val label = d.appLabel(app) ?: app
        val private = Sensitive.isSensitive(app, d.appLabel(app))
        val header = buildString {
            append("App: ").append(label).append(" (").append(app).append(")\n")
            if (private && a.optBoolean("with_image", false)) append(NO_PICTURE).append('\n')
            append("Screen: ").append(d.screenWidth()).append('x').append(d.screenHeight())
            append(" pixels; @x,y is an element's centre in these pixels.\n")
            if (d.keyboardShown()) append("The keyboard is showing.\n")
            append('\n')
        }
        val snap = Snapshots.take(root, header)
        refs = snap.refs
        val out = JSONObject().put("app", app).put("label", label).put("screen", screen(d)).put("snapshot", snap.text)
        if (a.optBoolean("with_image", false) && !private) {
            runCatching { d.screenshot(MAX_SIDE) }.getOrNull()?.let { picture(out, it) }
        }
        return out
    }

    private suspend fun screenshot(d: Device, p: Policy): JSONObject {
        val app = gate(d, p)
        if (Sensitive.isSensitive(app, d.appLabel(app))) throw Refusal("$NO_PICTURE Read it with android_snapshot instead.")
        val shot = d.screenshot(MAX_SIDE)
        return picture(JSONObject().put("app", app).put("screen", screen(d)), shot)
    }

    private suspend fun tap(a: JSONObject, d: Device, p: Policy): JSONObject {
        gate(d, p)
        val long = a.optBoolean("long", false)
        val press = if (long) LONG_PRESS else TAP
        val how: String
        if (a.has("ref")) {
            val node = node(a, p)
            how = if (long) {
                if (node.longClickable && node.longClick()) "long-pressed"
                else ancestor(node) { it.longClickable }?.takeIf { it.longClick() }?.let { "long-pressed" }
                    ?: gesture(d, node.bounds.centerX, node.bounds.centerY, press)
            } else {
                if (node.clickable && node.click()) "tapped"
                else ancestor(node) { it.clickable }?.takeIf { it.click() }?.let { "tapped" }
                    ?: gesture(d, node.bounds.centerX, node.bounds.centerY, press)
            }
        } else {
            val (x, y) = point(a, "x", "y", d)
            how = gesture(d, x, y, press)
        }
        d.settle(SETTLE_ACT)
        return JSONObject().put("done", how).put("app", d.activeApp() ?: JSONObject.NULL)
    }

    private suspend fun type(a: JSONObject, d: Device, p: Policy): JSONObject {
        gate(d, p)
        if (!a.has("text")) throw Refusal("Say what to type: text.")
        val text = a.optString("text")
        if (text.length > MAX_TYPE) throw Refusal("At most $MAX_TYPE characters can be typed at once.")
        val field = if (a.has("ref")) node(a, p) else d.focusedInput()
            ?: throw Refusal("No text field has focus: give the field's ref, from android_snapshot.")
        if (!field.editable) throw Refusal("That element is not a text field.")
        if (field.password) throw Refusal("Wysebee Agent does not type into password fields: the person types those on the phone.")
        if (!field.focused) { field.focus(); field.click() }
        val clear = a.optBoolean("clear", true)
        var target = field
        var value = if (clear) text else (field.text ?: "") + text
        if (!field.setText(value)) {
            // Some fields only stand in for the real one (a search bar that opens a search box
            // when tapped): type where the tap put the focus, as a person would.
            d.settle(SETTLE_ACT)
            val moved = d.focusedInput()?.takeIf { it.editable && !it.password }
                ?: throw Refusal("The text field did not take the text.")
            if (moved.packageName != null && !p.may(moved.packageName!!, own)) throw Refusal("The text field did not take the text.")
            target = moved
            value = if (clear) text else (moved.text ?: "") + text
            if (!moved.setText(value)) throw Refusal("The text field did not take the text.")
        }
        var submitted = false
        if (a.optBoolean("submit", false)) {
            submitted = target.imeEnter()
            if (!submitted) throw Refusal("The text was typed, but the field has no Enter action: tap the button that sends it.")
        }
        d.settle(SETTLE_ACT)
        return JSONObject().put("typed", value.length).put("submitted", submitted).put("app", d.activeApp() ?: JSONObject.NULL)
    }

    private suspend fun swipe(a: JSONObject, d: Device, p: Policy): JSONObject {
        gate(d, p)
        val (x1, y1) = point(a, "x1", "y1", d)
        val (x2, y2) = point(a, "x2", "y2", d)
        val ms = a.optLong("duration_ms", 300).coerceIn(50, 5000)
        if (!d.swipe(x1, y1, x2, y2, ms)) throw Refusal("The phone did not take the swipe.")
        d.settle(SETTLE_ACT)
        return JSONObject().put("done", "swiped").put("app", d.activeApp() ?: JSONObject.NULL)
    }

    private suspend fun scroll(a: JSONObject, d: Device, p: Policy): JSONObject {
        gate(d, p)
        val dir = Direction.of(a.optString("direction"))
            ?: throw Refusal("Say which way to scroll: direction up, down, left or right.")
        val area = if (a.has("ref")) node(a, p) else null
        val target = area?.let { if (it.scrollable) it else ancestor(it) { n -> n.scrollable } }
        var how = "scrolled"
        if (target == null || !target.scroll(dir)) {
            // No list says it can scroll that way: swipe across it (or the screen) instead.
            val b = (target ?: area)?.bounds?.takeIf { !it.isEmpty } ?: Bounds(0, 0, d.screenWidth(), d.screenHeight())
            val (x1, y1, x2, y2) = when (dir) {
                Direction.DOWN -> listOf(b.centerX, b.top + b.height * 3 / 4, b.centerX, b.top + b.height / 4)
                Direction.UP -> listOf(b.centerX, b.top + b.height / 4, b.centerX, b.top + b.height * 3 / 4)
                Direction.RIGHT -> listOf(b.left + b.width * 3 / 4, b.centerY, b.left + b.width / 4, b.centerY)
                Direction.LEFT -> listOf(b.left + b.width / 4, b.centerY, b.left + b.width * 3 / 4, b.centerY)
            }
            if (!d.swipe(x1, y1, x2, y2, 400)) throw Refusal("The phone did not take the scroll.")
            how = "swiped"
        }
        d.settle(SETTLE_ACT)
        return JSONObject().put("done", how).put("direction", dir.wire).put("app", d.activeApp() ?: JSONObject.NULL)
    }

    private suspend fun key(a: JSONObject, d: Device): JSONObject {
        val key = Key.of(a.optString("key"))
            ?: throw Refusal("Say which key: back, home, recents, notifications or quick_settings.")
        if (!d.global(key)) throw Refusal("The phone did not take the ${key.wire} key.")
        d.settle(SETTLE_ACT)
        return JSONObject().put("pressed", key.wire).put("app", d.activeApp() ?: JSONObject.NULL)
    }

    /** The active app, when the agent may operate it; else the op is refused. */
    private fun gate(d: Device, p: Policy): String {
        val app = d.activeApp() ?: throw Refusal(NOTHING)
        if (app == own) throw Refusal("The phone is showing Wysebee Agent itself, which the agent never operates. Open an allowed app with android_launch, or press home.")
        if (app == SYSTEM_UI && !p.may(app, own)) {
            throw Refusal("The phone is showing System UI (the notification shade or quick settings), which Wysebee may not operate. Press back or home, or ask the person to turn on System UI in Wysebee Agent on the phone.")
        }
        if (!p.may(app, own)) {
            val who = d.appLabel(app)?.let { "$it ($app)" } ?: app
            throw Refusal("The phone is showing $who, which Wysebee may not operate. Open an allowed app with android_launch, press back or home, or ask the person to allow this app in Wysebee Agent on the phone.")
        }
        return app
    }

    private fun node(a: JSONObject, p: Policy): UiNode {
        val ref = a.optInt("ref", -1)
        val n = refs[ref] ?: throw Refusal("There is no element [$ref]: take a new android_snapshot.")
        if (!n.refresh() || !n.visible) throw Refusal("Element [$ref] is no longer on the screen: take a new android_snapshot.")
        val pkg = n.packageName
        if (pkg != null && !p.may(pkg, own)) throw Refusal("Element [$ref] belongs to $pkg, which Wysebee may not operate.")
        return n
    }

    private fun ancestor(n: UiNode, test: (UiNode) -> Boolean): UiNode? {
        var at = n.parent()
        var hops = 0
        while (at != null && hops < 20) {
            if (test(at)) return at
            at = at.parent(); hops++
        }
        return null
    }

    private suspend fun gesture(d: Device, x: Int, y: Int, ms: Long): String {
        if (!d.tap(x, y, ms)) throw Refusal("The phone did not take the tap at $x,$y.")
        return if (ms >= LONG_PRESS) "long-pressed at $x,$y" else "tapped at $x,$y"
    }

    private fun point(a: JSONObject, kx: String, ky: String, d: Device): Pair<Int, Int> {
        if (!a.has(kx) || !a.has(ky)) throw Refusal("Say where: $kx and $ky, in screen pixels (or a ref).")
        val x = a.optInt(kx, -1)
        val y = a.optInt(ky, -1)
        if (x !in 0 until d.screenWidth() || y !in 0 until d.screenHeight()) {
            throw Refusal("$x,$y is off the screen, which is ${d.screenWidth()}x${d.screenHeight()} pixels.")
        }
        return x to y
    }

    private fun screen(d: Device) = JSONObject().put("width", d.screenWidth()).put("height", d.screenHeight())

    private fun picture(out: JSONObject, shot: Shot): JSONObject =
        out.put("image", Base64.getEncoder().encodeToString(shot.jpeg)).put("mime", "image/jpeg")
            .put("width", shot.width).put("height", shot.height)

    companion object {
        val OPS = setOf(
            "ping", "android_apps", "android_launch", "android_snapshot", "android_screenshot",
            "android_tap", "android_type", "android_swipe", "android_scroll", "android_key",
        )
        const val PAUSED = "Wysebee Agent is paused on the phone."
        const val LOCKED = "The phone is locked: the agent can do nothing on it until the person unlocks it."
        const val NO_PICTURE = "No picture: Wysebee Agent never takes screenshots of banking, payment, investing or password apps."
        const val NO_SERVICE = "Wysebee Agent cannot see or touch the screen: its accessibility service is off. The person turns it on in the phone's Settings › Accessibility."
        const val NOTHING = "Nothing can be read on the screen: it may be off or locked."
        const val MAX_SIDE = 1280
        const val MAX_WAIT = 10_000L
        const val MAX_TYPE = 10_000
        const val TAP = 60L
        const val LONG_PRESS = 800L
        const val SETTLE_ACT = 2_000L
        const val SETTLE_READ = 1_500L
        const val SETTLE_LAUNCH = 4_000L
    }
}
