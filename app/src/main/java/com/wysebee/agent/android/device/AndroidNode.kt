package com.wysebee.agent.android.device

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.wysebee.agent.android.core.Bounds
import com.wysebee.agent.android.core.Direction
import com.wysebee.agent.android.core.UiNode

/** An element on the screen, as the accessibility service reads it. */
class AndroidNode(private val info: AccessibilityNodeInfo) : UiNode {
    override val packageName get() = info.packageName?.toString()
    override val className get() = info.className?.toString()
    override val text get() = if (info.isShowingHintText) null else info.text?.toString()
    override val description get() = info.contentDescription?.toString()
    override val hint get() = info.hintText?.toString()
    override val viewId: String? get() = info.viewIdResourceName
    override val bounds: Bounds get() = Rect().also(info::getBoundsInScreen).let { Bounds(it.left, it.top, it.right, it.bottom) }
    override val visible get() = info.isVisibleToUser
    override val clickable get() = info.isClickable
    override val longClickable get() = info.isLongClickable
    override val editable get() = info.isEditable
    override val scrollable get() = info.isScrollable
    override val checkable get() = info.isCheckable
    override val checked get() = info.isChecked
    override val enabled get() = info.isEnabled
    override val focused get() = info.isFocused
    override val selected get() = info.isSelected
    override val password get() = info.isPassword

    override fun children(): List<UiNode> = (0 until info.childCount).mapNotNull { info.getChild(it)?.let(::AndroidNode) }
    override fun parent(): UiNode? = info.parent?.let(::AndroidNode)
    override fun refresh() = info.refresh()
    override fun click() = info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    override fun longClick() = info.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    override fun focus() = info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

    override fun setText(text: String): Boolean = info.performAction(
        AccessibilityNodeInfo.ACTION_SET_TEXT,
        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
    )

    override fun scroll(direction: Direction): Boolean {
        val exact = when (direction) {
            Direction.UP -> AccessibilityAction.ACTION_SCROLL_UP
            Direction.DOWN -> AccessibilityAction.ACTION_SCROLL_DOWN
            Direction.LEFT -> AccessibilityAction.ACTION_SCROLL_LEFT
            Direction.RIGHT -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (info.actionList.any { it.id == exact.id } && info.performAction(exact.id)) return true
        // "Down" shows what is further down: forward, in a list that only knows forward and back.
        val plain = if (direction == Direction.DOWN || direction == Direction.RIGHT) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return info.performAction(plain)
    }

    override fun imeEnter() = info.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
}
