package com.phonebot.app.bridge

import com.phonebot.termux.ApprovalRisk

/** What a horizontal drag on a pending approval should resolve to. */
enum class ApprovalSwipe { None, Approve, Deny }

/**
 * Pure swipe-to-resolve decision for the Approvals inbox.
 *
 * Phase 2's inbox is "notify → swipe → resolve", but resolving a `critical`
 * tool call must never be a gesture: critical rows keep their explicit
 * Approve/Deny buttons only, and `info` rows are not decisions at all. Only
 * `confirm`-tier rows can be swiped, which is what makes the inbox risk-tiered
 * rather than merely risk-coloured.
 *
 * [dragFraction] is the horizontal drag distance divided by the row width, so
 * the threshold is screen-size independent. Kept free of Compose/Android types
 * so the rule is unit-testable without a device.
 */
class ApprovalSwipePolicy(private val thresholdFraction: Float = 0.35f) {

    init {
        require(thresholdFraction > 0f && thresholdFraction <= 1f) {
            "thresholdFraction must be in (0, 1], was $thresholdFraction"
        }
    }

    fun decide(dragFraction: Float, risk: ApprovalRisk): ApprovalSwipe = when {
        risk != ApprovalRisk.Confirm -> ApprovalSwipe.None
        dragFraction >= thresholdFraction -> ApprovalSwipe.Approve
        dragFraction <= -thresholdFraction -> ApprovalSwipe.Deny
        else -> ApprovalSwipe.None
    }
}
