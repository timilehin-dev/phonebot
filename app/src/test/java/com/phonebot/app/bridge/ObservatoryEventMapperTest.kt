package com.phonebot.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservatoryEventMapperTest {

    private fun frame(eventJson: String) = """{"type":"bus_event","event":$eventJson}"""

    @Test
    fun `maps a task event using its payload message`() {
        val event = ObservatoryEventMapper.fromBridgeLine(
            frame(
                """{"type":"task","from_bot":"chief","to_bot":"scout","corr_id":"chain","payload":{"message":"collect prices"},"timestamp":"2026-11-12T00:00:00+00:00"}"""
            )
        )

        requireNotNull(event)
        assertEquals(ObservatoryEventKind.Task, event.kind)
        assertEquals("chief", event.fromBot)
        assertEquals("scout", event.toBot)
        assertEquals("collect prices", event.summary)
        assertTrue(event.id.contains("chain"))
    }

    @Test
    fun `maps result and cron_result events`() {
        val result = ObservatoryEventMapper.fromBridgeLine(
            frame(
                """{"type":"result","from_bot":"scout","to_bot":"chief","corr_id":"chain","payload":{"summary":"prices collected"},"timestamp":"2026-11-12T00:00:01+00:00"}"""
            )
        )
        assertEquals(ObservatoryEventKind.Result, result?.kind)
        assertEquals("prices collected", result?.summary)

        val cron = ObservatoryEventMapper.fromBridgeLine(
            frame(
                """{"type":"cron_result","from_bot":"cron","to_bot":"scout","corr_id":"digest","payload":{"job_id":"digest","ok":true,"result":"digest done"},"timestamp":"2026-11-12T00:00:02+00:00"}"""
            )
        )
        assertEquals(ObservatoryEventKind.CronResult, cron?.kind)
        assertEquals("digest done", cron?.summary)
    }

    @Test
    fun `maps needs-approval and falls back to the tool name`() {
        val event = ObservatoryEventMapper.fromBridgeLine(
            frame(
                """{"type":"needs-approval","from_bot":"atlas","to_bot":"*","corr_id":"corr-42","payload":{"tool":"shell.run"},"timestamp":"2026-11-12T00:00:03+00:00"}"""
            )
        )
        assertEquals(ObservatoryEventKind.Approval, event?.kind)
        assertEquals("shell.run", event?.summary)
        assertEquals("corr-42", event?.id?.let { "corr-42" })
    }

    @Test
    fun `ignores non-bus frames malformed json and unknown types`() {
        assertNull(ObservatoryEventMapper.fromBridgeLine("""{"type":"ack","corr_id":"x"}"""))
        assertNull(ObservatoryEventMapper.fromBridgeLine("not json"))
        assertNull(
            ObservatoryEventMapper.fromBridgeLine(
                """{"type":"bus_event","event":{"type":"unknown","from_bot":"a","to_bot":"b"}}"""
            )
        )
        assertNull(ObservatoryEventMapper.fromBridgeLine("""{"type":"bus_event"}"""))
    }

    @Test
    fun `feed deduplicates identical frames`() {
        val raw = frame(
            """{"type":"task","from_bot":"chief","to_bot":"scout","corr_id":"chain","payload":{"message":"collect prices"},"timestamp":"2026-11-12T00:00:00+00:00"}"""
        )
        val feed = ObservatoryFeed()

        assertTrue(feed.ingest(raw))
        assertFalse(feed.ingest(raw))
        assertEquals(1, feed.snapshot().size)
    }

    @Test
    fun `feed keeps newest first and caps its size`() {
        val feed = ObservatoryFeed(limit = 2)
        for (i in 1..3) {
            feed.ingest(
                frame(
                    """{"type":"result","from_bot":"scout","to_bot":"chief","corr_id":"c$i","payload":{"summary":"step $i"},"timestamp":"2026-11-12T00:00:0$i+00:00"}"""
                )
            )
        }

        val snapshot = feed.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("step 3", snapshot[0].summary)
        assertEquals("step 2", snapshot[1].summary)
    }
}
