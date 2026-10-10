package com.example.hinglishpdf.ui.status

import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.service.LivePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stage map through a book's life: every state transition the status view can show. */
class ProjectStatusTest {

    private fun states(view: ProjectStatusView) = view.stages.map { it.state }
    private val D = StageState.DONE
    private val C = StageState.CURRENT
    private val A = StageState.ATTENTION
    private val W = StageState.WAITING
    private val U = StageState.UNAVAILABLE

    @Test
    fun `a book goes uploaded, parsed, translated, rebuilt, delivered`() {
        // Just added: waiting for the service.
        val queued = ProjectStatus.of(ProjectFacts(BookStatus.QUEUED, pageCount = 0, translatedPages = 0))
        assertEquals(listOf(D, W, W, U, W, W), states(queued))
        assertEquals("Waiting to start", queued.headline)

        // Reading the book.
        val reading = ProjectStatus.of(ProjectFacts(BookStatus.READING, 0, 0, livePhase = LivePhase.ANALYSING))
        assertEquals(listOf(D, C, W, U, W, W), states(reading))
        assertEquals(Stage.PARSED, reading.current!!.stage)

        // Translating page by page.
        val translating = ProjectStatus.of(ProjectFacts(BookStatus.TRANSLATING, 300, 45, livePhase = LivePhase.TRANSLATING))
        assertEquals(listOf(D, D, C, U, W, W), states(translating))
        assertEquals("45 of 300 pages", translating.current!!.detail)
        assertEquals("Translating · 45 of 300 pages", translating.headline)
        assertEquals("300 pages found", translating.stages[1].detail)

        // Waiting out a rate limit is still the translation step, not an error.
        val waiting = ProjectStatus.of(ProjectFacts(BookStatus.TRANSLATING, 300, 45, livePhase = LivePhase.TRANSLATING, waiting = true))
        assertEquals(listOf(D, D, C, U, W, W), states(waiting))
        assertTrue(waiting.current!!.detail.startsWith("Waiting to continue"))

        // Building the file.
        val saving = ProjectStatus.of(ProjectFacts(BookStatus.TRANSLATING, 300, 300, livePhase = LivePhase.SAVING))
        assertEquals(listOf(D, D, D, U, C, W), states(saving))

        // Delivered.
        val done = ProjectStatus.of(ProjectFacts(BookStatus.COMPLETED, 300, 300, outputName = "Book (Hindi).epub"))
        assertEquals(listOf(D, D, D, U, D, D), states(done))
        assertTrue(done.finished)
        assertEquals("Done · saved to Downloads", done.headline)
        assertEquals("Saved to Downloads: Book (Hindi).epub", done.stages.last().detail)
    }

    @Test
    fun `pausing keeps the map where it was`() {
        val paused = ProjectStatus.of(ProjectFacts(BookStatus.PAUSED, 300, 120))
        assertEquals(listOf(D, D, W, U, W, W), states(paused))
        assertEquals("Paused at 120 of 300 pages", paused.stages[2].detail)
        assertEquals("Paused · tap Resume to continue", paused.headline)
        assertFalse(paused.needsAttention)
    }

    @Test
    fun `a failure highlights the stage it happened in`() {
        val duringTranslation = ProjectStatus.of(ProjectFacts(BookStatus.FAILED, 300, 120))
        assertEquals(listOf(D, D, A, U, W, W), states(duringTranslation))
        assertEquals("Stopped at 120 of 300 pages", duringTranslation.current!!.detail)
        assertTrue(duringTranslation.needsAttention)

        val beforeReading = ProjectStatus.of(ProjectFacts(BookStatus.FAILED, 0, 0))
        assertEquals(listOf(D, A, W, U, W, W), states(beforeReading))

        val whileSaving = ProjectStatus.of(ProjectFacts(BookStatus.FAILED, 300, 300))
        assertEquals(listOf(D, D, D, U, A, W), states(whileSaving))

        val scanned = ProjectStatus.of(ProjectFacts(BookStatus.FAILED, 0, 0, needsOcr = true))
        assertEquals(listOf(D, A, W, U, W, W), states(scanned))
        assertEquals("Scanned pages: no text to read", scanned.current!!.detail)
    }

    @Test
    fun `resuming a failed book moves the highlight back to work in progress`() {
        val failed = ProjectStatus.of(ProjectFacts(BookStatus.FAILED, 300, 120))
        val resumed = ProjectStatus.of(ProjectFacts(BookStatus.TRANSLATING, 300, 120, livePhase = LivePhase.TRANSLATING))
        assertEquals(StageState.ATTENTION, failed.stages[2].state)
        assertEquals(StageState.CURRENT, resumed.stages[2].state)
        assertFalse(resumed.needsAttention)
    }

    @Test
    fun `at most one stage is highlighted, and quality checks are honest about not existing yet`() {
        val all = listOf(
            ProjectFacts(BookStatus.QUEUED, 0, 0),
            ProjectFacts(BookStatus.READING, 0, 0, livePhase = LivePhase.READING),
            ProjectFacts(BookStatus.TRANSLATING, 10, 3, livePhase = LivePhase.TRANSLATING),
            ProjectFacts(BookStatus.FAILED, 10, 3),
            ProjectFacts(BookStatus.FAILED, 0, 0, needsOcr = true),
            ProjectFacts(BookStatus.TRANSLATING, 10, 10, livePhase = LivePhase.SAVING),
            ProjectFacts(BookStatus.COMPLETED, 10, 10, outputName = "x.epub"),
        )
        for (facts in all) {
            val view = ProjectStatus.of(facts)
            assertTrue(facts.toString(), view.stages.count { it.state == C || it.state == A } <= 1)
            assertEquals(StageState.UNAVAILABLE, view.stages.single { it.stage == Stage.CHECKED }.state)
        }
    }

    @Test
    fun `epub books count sections, not pages`() {
        val view = ProjectStatus.of(ProjectFacts(BookStatus.TRANSLATING, 40, 4, unitName = "section", livePhase = LivePhase.TRANSLATING))
        assertEquals("4 of 40 sections", view.current!!.detail)
    }
}
