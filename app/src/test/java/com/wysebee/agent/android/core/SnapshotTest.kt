package com.wysebee.agent.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTest {
    @Test fun containersAreLiftedAndHiddenSkipped() {
        val root = FakeNode(className = "android.widget.FrameLayout").add(
            FakeNode(className = "android.widget.LinearLayout").add(
                FakeNode(text = "Hello", bounds = Bounds(0, 0, 10, 10)),
                FakeNode(text = "Hidden", visible = false),
            ),
            FakeNode(className = "android.widget.ScrollView", scrollable = true, bounds = Bounds(0, 0, 0, 0)).add(
                FakeNode(className = "android.widget.CheckBox", text = "Agree", checkable = true, checked = true, viewId = "com.example:id/agree", bounds = Bounds(0, 0, 10, 10)),
            ),
        )
        val snap = Snapshots.take(root, "H\n")
        assertEquals(
            "H\n[1] TextView \"Hello\" @5,5\n[2] ScrollView scroll\n  [3] CheckBox \"Agree\" #agree checked @5,5\n",
            snap.text,
        )
        assertEquals(3, snap.refs.size)
    }

    @Test fun passwordsAreNotReadAndTextIsCleaned() {
        val root = FakeNode().add(
            FakeNode(className = "android.widget.EditText", text = "hunter2", editable = true, password = true),
            FakeNode(text = "a\n\"b\"", description = "desc", selected = true, focused = true, enabled = false, longClickable = true),
            FakeNode(className = null, description = "x".repeat(400)),
        )
        val text = Snapshots.take(root, "").text
        assertTrue(text, text.contains("\"(password: not read)\" input"))
        assertTrue(!text.contains("hunter2"))
        assertTrue(text, text.contains("\"a\\n\\\"b\\\"\" (desc) long-tap selected focused disabled"))
        assertTrue(text, text.contains("[3] View (" + "x".repeat(300) + "…)"))
    }

    @Test fun emptyAndCut() {
        assertTrue(Snapshots.take(FakeNode(), "").text.contains("nothing on this screen"))
        val big = FakeNode()
        repeat(Snapshots.MAX_ENTRIES + 5) { big.add(FakeNode(text = "$it", bounds = Bounds(0, 0, 0, 0))) }
        val snap = Snapshots.take(big, "")
        assertEquals(Snapshots.MAX_ENTRIES, snap.refs.size)
        assertTrue(snap.text.endsWith("…[cut at ${Snapshots.MAX_ENTRIES} elements]\n"))
        val long = FakeNode()
        repeat(400) { long.add(FakeNode(text = "y".repeat(250))) }
        assertTrue(Snapshots.take(long, "").text.endsWith("…[cut at ${Snapshots.MAX_CHARS} characters]"))
    }
}
