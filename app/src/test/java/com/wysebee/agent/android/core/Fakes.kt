package com.wysebee.agent.android.core

/** A screen element in a test: what it says, what it can do, and a log of what was done to it. */
class FakeNode(
    override var packageName: String? = "com.example",
    override var className: String? = "android.widget.TextView",
    override var text: String? = null,
    override var description: String? = null,
    override var hint: String? = null,
    override var viewId: String? = null,
    override var bounds: Bounds = Bounds(0, 0, 100, 100),
    override var visible: Boolean = true,
    override var clickable: Boolean = false,
    override var longClickable: Boolean = false,
    override var editable: Boolean = false,
    override var scrollable: Boolean = false,
    override var checkable: Boolean = false,
    override var checked: Boolean = false,
    override var enabled: Boolean = true,
    override var focused: Boolean = false,
    override var selected: Boolean = false,
    override var password: Boolean = false,
    val kids: MutableList<FakeNode> = mutableListOf(),
) : UiNode {
    var up: FakeNode? = null
    var gone = false
    var acceptsText = true
    var hasEnter = true
    var scrolls = true
    val done = mutableListOf<String>()

    fun add(vararg n: FakeNode) = apply { n.forEach { it.up = this; kids += it } }

    override fun children(): List<UiNode> = kids
    override fun parent(): UiNode? = up
    override fun refresh() = !gone
    override fun click() = clickable.also { done += "click" }
    override fun longClick() = longClickable.also { done += "longClick" }
    override fun focus() = true.also { done += "focus"; focused = true }
    override fun setText(text: String): Boolean {
        done += "setText:$text"
        if (acceptsText) this.text = text
        return acceptsText
    }
    override fun scroll(direction: Direction) = scrolls.also { done += "scroll:${direction.wire}" }
    override fun imeEnter() = hasEnter.also { done += "enter" }
}

class FakeDevice(var app: String? = "com.example", var tree: FakeNode? = FakeNode()) : Device {
    val gestures = mutableListOf<String>()
    var launched = mutableListOf<String>()
    var installed = mutableListOf(App("com.example", "Example"), App("com.other", "Other"))
    var shotFails = false
    var focusedField: FakeNode? = null

    var isLocked = false
    override fun locked() = isLocked
    override fun activeApp() = app
    override fun root(): UiNode? = tree
    override fun focusedInput(): UiNode? = focusedField
    override fun screenWidth() = 1080
    override fun screenHeight() = 2400
    override fun keyboardShown() = focusedField != null
    override fun appLabel(pkg: String) = installed.firstOrNull { it.pkg == pkg }?.label
    override fun launch(pkg: String): Boolean {
        if (installed.none { it.pkg == pkg }) return false
        launched += pkg; app = pkg; return true
    }
    override fun global(key: Key) = true.also { gestures += "key:${key.wire}" }
    override suspend fun tap(x: Int, y: Int, durationMs: Long) = true.also { gestures += "tap:$x,$y,$durationMs" }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true.also { gestures += "swipe:$x1,$y1>$x2,$y2" }
    override suspend fun screenshot(maxSide: Int): Shot {
        if (shotFails) throw DeviceException("This screen does not allow screenshots (the app protects it).")
        return Shot(byteArrayOf(1, 2, 3), 576, 1280)
    }
    override suspend fun settle(maxMs: Long) {}
}
