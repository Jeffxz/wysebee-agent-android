package com.wysebee.agent.android.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.wysebee.agent.android.AgentApp
import com.wysebee.agent.android.core.App
import com.wysebee.agent.android.core.Device
import com.wysebee.agent.android.core.DeviceException
import com.wysebee.agent.android.core.Key
import com.wysebee.agent.android.core.Shot
import com.wysebee.agent.android.core.UiNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The agent's eyes and hands on the phone. It exists only while the person turned it on in
 * Settings › Accessibility; the agent's ops read it through [current] and fail plainly without it.
 * It reads nothing by itself: every look and touch is one of [com.wysebee.agent.android.core.Ops].
 */
class AgentAccessibilityService : AccessibilityService(), Device {
    @Volatile private var lastEvent = 0L

    override fun onServiceConnected() {
        current = this
        (application as AgentApp).serviceChanged()
    }

    override fun onDestroy() {
        if (current === this) current = null
        (application as AgentApp).serviceChanged()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEvent = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() {}

    // ---- Device ----

    /** The window in front: the topmost active or focused one ([windows] lists them top first),
     *  but never the keyboard or an overlay. A system window counts — the notification shade
     *  over an app is what the person sees then, and is System UI's, not that app's. */
    private fun activeWindow(): AccessibilityWindowInfo? = windows.firstOrNull {
        (it.isActive || it.isFocused) && it.type !in NOT_IN_FRONT && it.root != null
    }

    override fun locked(): Boolean = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun activeApp(): String? =
        (activeWindow()?.root ?: rootInActiveWindow)?.packageName?.toString()

    override fun root(): UiNode? = (activeWindow()?.root ?: rootInActiveWindow)?.let(::AndroidNode)

    override fun focusedInput(): UiNode? = (activeWindow()?.root ?: rootInActiveWindow)
        ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::AndroidNode)

    private val metrics get() = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
    override fun screenWidth() = metrics.width()
    override fun screenHeight() = metrics.height()

    override fun keyboardShown() = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    override fun appLabel(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    override fun launch(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { startActivity(intent) }.isSuccess
    }

    override fun global(key: Key): Boolean = performGlobalAction(
        when (key) {
            Key.BACK -> GLOBAL_ACTION_BACK
            Key.HOME -> GLOBAL_ACTION_HOME
            Key.RECENTS -> GLOBAL_ACTION_RECENTS
            Key.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            Key.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
        },
    )

    override suspend fun tap(x: Int, y: Int, durationMs: Long) =
        gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, durationMs)

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) =
        gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, durationMs)

    private suspend fun gesture(path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!sent && cont.isActive) cont.resume(false)
    }

    override suspend fun screenshot(maxSide: Int): Shot = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this), object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val shot = runCatching {
                    result.hardwareBuffer.use { buffer ->
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?: throw DeviceException("The screenshot could not be read.")
                        val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                        val scale = minOf(1f, maxSide.toFloat() / max(soft.width, soft.height))
                        val fit = if (scale < 1f) Bitmap.createScaledBitmap(soft, (soft.width * scale).roundToInt(), (soft.height * scale).roundToInt(), true) else soft
                        val bytes = ByteArrayOutputStream().also { fit.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
                        Shot(bytes, fit.width, fit.height).also {
                            if (fit !== soft) fit.recycle()
                            soft.recycle()
                        }
                    }
                }
                if (!cont.isActive) return
                shot.fold({ cont.resume(it) }, { cont.resumeWith(Result.failure(it as? DeviceException ?: DeviceException("The screenshot could not be read."))) })
            }

            override fun onFailure(errorCode: Int) {
                if (!cont.isActive) return
                val why = when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Screenshots were asked for too quickly: try again in a moment."
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This screen does not allow screenshots (the app protects it)."
                    else -> "The phone could not take a screenshot (error $errorCode)."
                }
                cont.resumeWith(Result.failure(DeviceException(why)))
            }
        })
    }

    override suspend fun settle(maxMs: Long) {
        val start = SystemClock.uptimeMillis()
        delay(QUIET)
        while (SystemClock.uptimeMillis() - start < maxMs && SystemClock.uptimeMillis() - lastEvent < QUIET) delay(50)
    }

    companion object {
        private val NOT_IN_FRONT = setOf(
            AccessibilityWindowInfo.TYPE_INPUT_METHOD,
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
        )

        /** The screen counts as settled after this long without a change. */
        private const val QUIET = 300L

        @Volatile var current: AgentAccessibilityService? = null
            private set

        fun launchable(pm: PackageManager): List<App> =
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
    }
}
