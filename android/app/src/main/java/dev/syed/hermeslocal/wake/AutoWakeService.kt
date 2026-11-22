package dev.syed.hermeslocal.wake

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The minimal accessibility service behind "Auto-wake with official Hermes" (res/xml/auto_wake_service.xml):
 * the system sends it window-state changes of the official Hermes app only (packageNames filter), and it may
 * not read window content (canRetrieveWindowContent=false). Of each event it looks at the type and the package
 * name, nothing else. Event-driven: no polling, no wake lock, no work while Hermes is not opened.
 */
class AutoWakeService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AutoWake.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (WakePolicy.isHermesForeground(e.eventType, e.packageName)) AutoWake.onHermesForeground(this)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        AutoWake.onServiceDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AutoWake.onServiceDisconnected(this)
        super.onDestroy()
    }

    /** Gives the accessibility access back (the toggle in Hermes Local was turned off). */
    fun disableSelfCompat() {
        runCatching { disableSelf() }
    }
}
