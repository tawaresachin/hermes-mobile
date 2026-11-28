package com.hermes.mobile.ui.screens.kanban

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KanbanModelsTest {

    @Test
    fun `mode parses enabled and dispatcher state`() {
        val mode = parseKanbanMode(JSONObject("""
            {"ok":true,"available":true,"enabled":true,"dispatch_in_gateway":true,
             "explicit":true,"restart_required":false,"boards":[{"slug":"default"}]}
        """.trimIndent()))
        assertTrue(mode.available)
        assertTrue(mode.enabled)
        assertTrue(mode.dispatchInGateway)
        assertEquals(listOf("default"), mode.boards)
        assertNull(mode.error)
    }

    @Test
    fun `mode surfaces unavailable agent without crashing`() {
        val mode = parseKanbanMode(JSONObject(
            """{"ok":false,"error":"kanban is not available on this agent"}"""))
        assertFalse(mode.available)
        assertFalse(mode.enabled)
        assertEquals("kanban is not available on this agent", mode.error)
    }

    @Test
    fun `board parses columns tasks and progress`() {
        val board = parseKanbanBoard(JSONObject("""
            {"ok":true,"board":"default","boards":["default","ops"],
             "assignees":["default","reviewer"],"latest_event_id":42,"now":100,
             "columns":[
               {"key":"ready","label":"Ready","icon":"▶","tasks":[
                 {"id":"t_1","title":"Ship it","status":"ready","assignee":"default",
                  "priority":2,"created_at":90,"latest_summary":"working",
                  "block_kind":null,"comment_count":1,
                  "progress":{"done":1,"total":3},"current_run_started_at":95,
                  "children":[{"id":"t_child","title":"Child stage","status":"running","assignee":"worker"}]}]},
               {"key":"done","label":"Done","icon":"✓","tasks":[]}
             ]}
        """.trimIndent()))
        assertNotNull(board)
        assertEquals("default", board!!.board)
        assertEquals(2, board.columns.size)
        assertEquals(1, board.totalTasks)
        val task = board.columns.first().tasks.first()
        assertEquals("Ship it", task.title)
        assertEquals("ready", task.status)
        assertEquals(1, task.progressDone)
        assertEquals(3, task.progressTotal)
        assertEquals(1, task.subtasks.size)
        assertEquals("running", task.subtasks.first().status)
        assertEquals(42L, board.latestEventId)
    }

    @Test
    fun `board rejects an error payload`() {
        assertNull(parseKanbanBoard(JSONObject("""{"ok":false,"error":"nope"}""")))
        assertNull(parseKanbanBoard(null))
    }

    @Test
    fun `task detail parses comments runs and result`() {
        val detail = parseKanbanTaskDetail(JSONObject("""
            {"ok":true,
             "task":{"id":"t_2","title":"Fix login","status":"blocked","assignee":"default",
                     "block_kind":"needs_input","body":"Repro steps","result":null},
             "comments":[{"id":1,"author":"worker","body":"Which env?","created_at":10}],
             "events":[{"id":7,"kind":"blocked","created_at":11}],
             "runs":[{"id":3,"status":"blocked","outcome":"needs_input",
                      "summary":"needs env","error":"","started_at":5,"ended_at":11}]}
        """.trimIndent()))
        assertNotNull(detail)
        assertEquals("Fix login", detail!!.task.title)
        assertEquals("Repro steps", detail.body)
        assertEquals(1, detail.comments.size)
        assertEquals("Which env?", detail.comments.first().body)
        assertEquals(1, detail.runs.size)
        assertEquals("needs_input", detail.runs.first().outcome)
        assertEquals(7L, detail.events.first().id)
    }

    @Test
    fun `status labels are human readable`() {
        assertEquals("Ready", kanbanStatusLabel("ready"))
        assertEquals("Blocked", kanbanStatusLabel("blocked"))
        assertEquals("Done", kanbanStatusLabel("done"))
    }
}
