package com.phonebot.app.bridge

import com.phonebot.termux.ApprovalRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApprovalSwipePolicyTest {

    private val policy = ApprovalSwipePolicy(thresholdFraction = 0.35f)

    @Test
    fun `a short drag resolves nothing`() {
        assertEquals(ApprovalSwipe.None, policy.decide(0f, ApprovalRisk.Confirm))
        assertEquals(ApprovalSwipe.None, policy.decide(0.2f, ApprovalRisk.Confirm))
        assertEquals(ApprovalSwipe.None, policy.decide(-0.2f, ApprovalRisk.Confirm))
    }

    @Test
    fun `swiping right past the threshold approves`() {
        assertEquals(ApprovalSwipe.Approve, policy.decide(0.35f, ApprovalRisk.Confirm))
        assertEquals(ApprovalSwipe.Approve, policy.decide(1f, ApprovalRisk.Confirm))
    }

    @Test
    fun `swiping left past the threshold denies`() {
        assertEquals(ApprovalSwipe.Deny, policy.decide(-0.35f, ApprovalRisk.Confirm))
        assertEquals(ApprovalSwipe.Deny, policy.decide(-1f, ApprovalRisk.Confirm))
    }

    @Test
    fun `critical approvals can never be resolved by a gesture`() {
        assertEquals(ApprovalSwipe.None, policy.decide(1f, ApprovalRisk.Critical))
        assertEquals(ApprovalSwipe.None, policy.decide(-1f, ApprovalRisk.Critical))
    }

    @Test
    fun `info rows are not decisions`() {
        assertEquals(ApprovalSwipe.None, policy.decide(1f, ApprovalRisk.Info))
        assertEquals(ApprovalSwipe.None, policy.decide(-1f, ApprovalRisk.Info))
    }

    @Test
    fun `an invalid threshold is rejected rather than silently never firing`() {
        assertThrows(IllegalArgumentException::class.java) { ApprovalSwipePolicy(0f) }
        assertThrows(IllegalArgumentException::class.java) { ApprovalSwipePolicy(1.5f) }
    }
}
