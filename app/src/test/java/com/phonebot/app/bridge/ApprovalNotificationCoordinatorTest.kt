package com.phonebot.app.bridge

import com.phonebot.termux.ApprovalRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalNotificationCoordinatorTest {

    private class RecordingNotifier : ApprovalNotifier {
        val posted = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override fun post(request: ApprovalRequest) {
            posted += request.corrId
        }

        override fun cancel(corrId: String) {
            cancelled += corrId
        }
    }

    private fun approval(corrId: String, bot: String = "atlas") = ApprovalRequest(
        corrId = corrId,
        bot = bot,
        command = "rm -rf build/",
        detail = "destructive",
    )

    @Test
    fun `posts exactly one notification per pending approval`() {
        val notifier = RecordingNotifier()
        val coordinator = ApprovalNotificationCoordinator(notifier)

        coordinator.onPending(listOf(approval("corr-1"), approval("corr-2")))
        // The store re-publishes the full pending list on every bridge frame.
        coordinator.onPending(listOf(approval("corr-1"), approval("corr-2")))

        assertEquals(listOf("corr-1", "corr-2"), notifier.posted)
    }

    @Test
    fun `a resolved approval is never posted even if a stale frame arrives`() {
        val notifier = RecordingNotifier()
        val coordinator = ApprovalNotificationCoordinator(notifier)

        coordinator.onResolved("corr-1")
        coordinator.onPending(listOf(approval("corr-1")))

        assertTrue(notifier.posted.isEmpty())
        assertEquals(listOf("corr-1"), notifier.cancelled)
    }

    @Test
    fun `resolving a pending approval cancels its notification and does not repost`() {
        val notifier = RecordingNotifier()
        val coordinator = ApprovalNotificationCoordinator(notifier)

        coordinator.onPending(listOf(approval("corr-1")))
        coordinator.onResolved("corr-1")
        coordinator.onPending(listOf(approval("corr-1")))

        assertEquals(listOf("corr-1"), notifier.posted)
        assertEquals(listOf("corr-1"), notifier.cancelled)
    }

    @Test
    fun `new approvals still post after another one resolved`() {
        val notifier = RecordingNotifier()
        val coordinator = ApprovalNotificationCoordinator(notifier)

        coordinator.onPending(listOf(approval("corr-1")))
        coordinator.onResolved("corr-1")
        coordinator.onPending(listOf(approval("corr-2")))

        assertEquals(listOf("corr-1", "corr-2"), notifier.posted)
        assertEquals(listOf("corr-1"), notifier.cancelled)
    }

    @Test
    fun `planning twice without resolution does not duplicate`() {
        val planner = ApprovalNotificationPlanner()
        val pending = listOf(approval("corr-1"))

        assertEquals(1, planner.plan(pending).size)
        assertTrue(planner.plan(pending).isEmpty())
    }
}
