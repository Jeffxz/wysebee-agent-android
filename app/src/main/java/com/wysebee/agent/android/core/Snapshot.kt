package com.wysebee.agent.android.core

/** What is on the screen, as text for the model, and each listed element by its ref. */
class Snapshot(val text: String, val refs: Map<Int, UiNode>)

/**
 * The screen's elements as an indented list, one line each:
 * `[ref] Kind "text" (description) #view-id flags @x,y`, where @x,y is the element's centre in
 * screen pixels. Only elements with something to read or do are listed; a plain container's
 * children are lifted to its place. A password field's text is never read.
 */
object Snapshots {
    const val MAX_ENTRIES = 1500
    const val MAX_TEXT = 300
    const val MAX_CHARS = 50_000

    fun take(root: UiNode, header: String): Snapshot {
        val out = StringBuilder(header)
        val refs = LinkedHashMap<Int, UiNode>()
        var cut = false

        fun walk(node: UiNode, depth: Int) {
            if (cut || !node.visible) return
            var childDepth = depth
            if (interesting(node)) {
                if (refs.size >= MAX_ENTRIES) { cut = true; return }
                val ref = refs.size + 1
                refs[ref] = node
                out.append("  ".repeat(depth)).append(line(ref, node)).append('\n')
                childDepth = depth + 1
            }
            for (child in node.children()) walk(child, childDepth)
        }
        walk(root, 0)
        if (refs.isEmpty()) out.append("(nothing on this screen can be read)\n")
        if (cut) out.append("…[cut at $MAX_ENTRIES elements]\n")
        var text = out.toString()
        if (text.length > MAX_CHARS) text = text.take(MAX_CHARS) + "\n…[cut at $MAX_CHARS characters]"
        return Snapshot(text, refs)
    }

    private fun interesting(n: UiNode): Boolean =
        !n.text.isNullOrBlank() || !n.description.isNullOrBlank() || (n.editable && !n.hint.isNullOrBlank()) ||
            n.clickable || n.longClickable || n.editable || n.scrollable || n.checkable

    fun line(ref: Int, n: UiNode): String = buildString {
        append('[').append(ref).append("] ").append(kind(n.className))
        val text = n.text?.takeIf { it.isNotBlank() }
        if (n.password) append(" \"(password: not read)\"")
        else if (text != null) append(" \"").append(clean(text)).append('"')
        val desc = n.description?.takeIf { it.isNotBlank() && it != text }
        if (desc != null) append(" (").append(clean(desc)).append(')')
        if (n.editable && text == null && !n.hint.isNullOrBlank()) append(" hint \"").append(clean(n.hint!!)).append('"')
        n.viewId?.substringAfter(":id/")?.takeIf { it.isNotBlank() }?.let { append(" #").append(it) }
        if (n.clickable) append(" tap")
        if (n.longClickable) append(" long-tap")
        if (n.editable) append(" input")
        if (n.scrollable) append(" scroll")
        if (n.checkable) append(if (n.checked) " checked" else " unchecked")
        if (n.selected) append(" selected")
        if (n.focused) append(" focused")
        if (!n.enabled) append(" disabled")
        val b = n.bounds
        if (!b.isEmpty) append(" @").append(b.centerX).append(',').append(b.centerY)
    }

    private fun kind(className: String?): String =
        className?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "View"

    private fun clean(text: String): String {
        val one = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
        return if (one.length > MAX_TEXT) one.take(MAX_TEXT) + "…" else one
    }
}
