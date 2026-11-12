package com.phonebot.app.bridge

import com.phonebot.termux.ApprovalRequest
import com.phonebot.termux.IpcReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterMapperTest {

    private fun state(json: String): IpcReply = IpcReply(
        type = "state",
        corrId = "",
        ok = true,
        error = null,
        raw = Json.parseToJsonElement(json).jsonObject,
    )

    @Test
    fun `maps name status and current task from get_state`() {
        val snapshot = RosterMapper.fromState(
            state(
                """{"type":"state","bots":[
                   {"bot":"atlas","paused":false,"spend_usd":1.5,"current_task":"scraping prices"}
                ]}"""
            )
        )

        assertTrue(snapshot.online)
        assertNull(snapshot.error)
        val atlas = snapshot.bots.single()
        assertEquals("atlas", atlas.id)
        assertEquals("Atlas", atlas.label)
        assertEquals(BotStatus.Running, atlas.status)
        assertEquals("scraping prices", atlas.currentTask)
        assertEquals(1.5, atlas.spendUsd, 0.0001)
    }

    @Test
    fun `paused wins over a current task and a pending approval marks waiting`() {
        val snapshot = RosterMapper.fromState(
            state(
                """{"type":"state","bots":[
                   {"bot":"sable","paused":true,"current_task":"deploying"},
                   {"bot":"atlas","paused":false,"current_task":"deploying"}
                ]}"""
            ),
            pendingApprovals = listOf(
                ApprovalRequest(corrId = "corr-9", bot = "atlas", command = "rm -rf build/", detail = "destructive")
            ),
        )

        val byId = snapshot.bots.associateBy { it.id }
        assertEquals(BotStatus.Paused, byId.getValue("sable").status)
        assertEquals(BotStatus.WaitingApproval, byId.getValue("atlas").status)
    }

    @Test
    fun `bot with no task and no flags is idle`() {
        val snapshot = RosterMapper.fromState(
            state("""{"type":"state","bots":[{"bot":"vex"}]}""")
        )
        assertEquals(BotStatus.Idle, snapshot.bots.single().status)
        assertEquals("", snapshot.bots.single().currentTask)
    }

    @Test
    fun `multi-word ids get a readable label and rows are sorted`() {
        val snapshot = RosterMapper.fromState(
            state("""{"type":"state","bots":[{"bot":"vex"},{"bot":"chief-of-staff"}]}""")
        )
        assertEquals(listOf("chief-of-staff", "vex"), snapshot.bots.map { it.id })
        assertEquals("Chief Of Staff", snapshot.bots.first().label)
    }

    @Test
    fun `empty roster is still online`() {
        val snapshot = RosterMapper.fromState(state("""{"type":"state","bots":[]}"""))
        assertTrue(snapshot.online)
        assertEquals(emptyList<BotRow>(), snapshot.bots)
        assertNull(snapshot.error)
    }

    @Test
    fun `missing reply is offline`() {
        val snapshot = RosterMapper.fromState(null)
        assertFalse(snapshot.online)
        assertEquals(emptyList<BotRow>(), snapshot.bots)
        assertEquals("botd unreachable", snapshot.error)
    }

    @Test
    fun `error reply surfaces the daemon message`() {
        val reply = IpcReply(
            type = "error",
            corrId = "",
            ok = false,
            error = "botd_home_missing",
            raw = Json.parseToJsonElement("""{"type":"error"}""").jsonObject,
        )
        val snapshot = RosterMapper.fromState(reply)
        assertFalse(snapshot.online)
        assertEquals("botd_home_missing", snapshot.error)
    }

    @Test
    fun `malformed entries are skipped without failing the roster`() {
        val snapshot = RosterMapper.fromState(
            state("""{"type":"state","bots":["nope",{"paused":false},{"bot":"atlas"}]}""")
        )
        assertTrue(snapshot.online)
        assertEquals(listOf("atlas"), snapshot.bots.map { it.id })
    }
}
