package com.wysebee.agent.android.core

/** A rectangle on the screen, in screen pixels. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = left + width / 2
    val centerY get() = top + height / 2
    val isEmpty get() = width <= 0 || height <= 0
}

enum class Direction(val wire: String) {
    UP("up"), DOWN("down"), LEFT("left"), RIGHT("right");
    companion object { fun of(text: String?) = entries.firstOrNull { it.wire == text } }
}

/** The phone's own buttons and shades, as global actions. */
enum class Key(val wire: String) {
    BACK("back"), HOME("home"), RECENTS("recents"), NOTIFICATIONS("notifications"), QUICK_SETTINGS("quick_settings");
    companion object { fun of(text: String?) = entries.firstOrNull { it.wire == text } }
}

/** A picture of the screen, JPEG, scaled to fit. */
class Shot(val jpeg: ByteArray, val width: Int, val height: Int)

data class App(val pkg: String, val label: String)

/** Something the phone could not do, said in English for the model. */
class DeviceException(message: String) : Exception(message)

/** One element on the screen (an AccessibilityNodeInfo on a phone, a fake in tests). */
interface UiNode {
    val packageName: String?
    val className: String?
    /** The element's text; null while a field shows only its hint. */
    val text: String?
    val description: String?
    val hint: String?
    val viewId: String?
    val bounds: Bounds
    val visible: Boolean
    val clickable: Boolean
    val longClickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val checkable: Boolean
    val checked: Boolean
    val enabled: Boolean
    val focused: Boolean
    val selected: Boolean
    val password: Boolean
    fun children(): List<UiNode>
    fun parent(): UiNode?
    /** Reads the element again; false when it is gone from the screen. */
    fun refresh(): Boolean
    fun click(): Boolean
    fun longClick(): Boolean
    fun focus(): Boolean
    fun setText(text: String): Boolean
    fun scroll(direction: Direction): Boolean
    fun imeEnter(): Boolean
}

/** The phone, as the accessibility service sees and drives it. */
interface Device {
    /** Whether the lock screen is up (the keyguard), whatever app is behind it. */
    fun locked(): Boolean
    /** The package of the app in the active window. */
    fun activeApp(): String?
    fun root(): UiNode?
    fun focusedInput(): UiNode?
    fun screenWidth(): Int
    fun screenHeight(): Int
    fun keyboardShown(): Boolean
    fun appLabel(pkg: String): String?
    fun launch(pkg: String): Boolean
    fun global(key: Key): Boolean
    suspend fun tap(x: Int, y: Int, durationMs: Long): Boolean
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean
    /** Throws DeviceException when the screen cannot be captured. */
    suspend fun screenshot(maxSide: Int): Shot
    /** Waits until the screen has stopped changing, at most [maxMs]. */
    suspend fun settle(maxMs: Long)
}
