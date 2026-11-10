package com.phonebot.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.util.Log

/**
 * The "device backend: assist" plane (docs/ADR-001 D6).
 *
 * Phase 1: event sink only — logs window changes. The service is
 * declared in the manifest but NOT auto-enabled; the user opts in via
 * Settings → Accessibility when they want the human-assist fallback.
 *
 * Phase 2: an action queue (enqueue [AccessibilityServiceServiceAction])
 * that the assist backend consumes, plus the gesture-injection path
 * (dispatchGesture) for tapping through UI elements on the user's behalf.
 */
class PhonebotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PhonebotA11y"

        /** A device-control action the assist backend should run. */
        sealed interface AccessibilityServiceServiceAction {
            data class Tap(val x: Float, val y: Float) : AccessibilityServiceServiceAction
            data class Swipe(val from: Pair<Float, Float>, val to: Pair<Float, Float>) : AccessibilityServiceServiceAction
            data class Back : AccessibilityServiceServiceAction
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo(serviceInfo).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_DEFAULT_NOTIFICATION or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        Log.i(TAG, "assist backend online")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 1: pure sink; Phase 2 pushes into an action queue here.
    }

    override fun onInterrupt() {
        Log.w(TAG, "accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "assist backend offline")
        super.onDestroy()
    }
}
