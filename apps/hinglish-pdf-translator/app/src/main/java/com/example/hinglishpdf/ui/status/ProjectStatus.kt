package com.example.hinglishpdf.ui.status

import com.example.hinglishpdf.data.db.BookStatus
import com.example.hinglishpdf.service.LivePhase

/** The stages a book goes through, in order: the user's map of the work. */
enum class Stage(val title: String) {
    UPLOADED("Uploaded"),
    PARSED("Parsed"),
    TRANSLATED("Translated"),
    CHECKED("Checked"),
    REBUILT("Rebuilt"),
    DELIVERED("Delivered"),
}

enum class StageState {
    DONE,
    /** Being worked on right now. */
    CURRENT,
    /** Stopped here; the user has to do something (see the guidance). */
    ATTENTION,
    /** Not started yet (or paused before it). */
    WAITING,
    /** Not part of the app yet: shown so the map is honest about it. */
    UNAVAILABLE,
}

data class StageView(val stage: Stage, val state: StageState, val detail: String)

/** Everything the status view needs to know about one book. */
data class ProjectFacts(
    val status: BookStatus,
    val pageCount: Int,
    val translatedPages: Int,
    val unitName: String = "page",
    /** The service is working on this book now, and in which phase. */
    val livePhase: LivePhase? = null,
    /** Waiting out a rate limit or a dropped connection. */
    val waiting: Boolean = false,
    val outputName: String? = null,
    val needsOcr: Boolean = false,
)

/** The stage map, plus a one-line headline of where the book is. */
data class ProjectStatusView(val stages: List<StageView>, val headline: String) {
    /** The highlighted stage: the one in progress or needing attention. */
    val current: StageView? get() = stages.firstOrNull { it.state == StageState.CURRENT || it.state == StageState.ATTENTION }
    val needsAttention: Boolean get() = stages.any { it.state == StageState.ATTENTION }
    val finished: Boolean get() = stages.last().state == StageState.DONE
}

/**
 * Derives the stage map from what the app already stores about a book, so it
 * is always true to the work actually done. At most one stage is highlighted.
 */
object ProjectStatus {

    fun of(f: ProjectFacts): ProjectStatusView {
        val units = "${f.unitName}s"
        val parsed = f.pageCount > 0
        val translated = parsed && f.translatedPages >= f.pageCount
        val failed = f.status == BookStatus.FAILED
        val live = f.livePhase

        val parsedState = when {
            f.needsOcr -> StageState.ATTENTION
            parsed -> StageState.DONE
            live == LivePhase.STARTING || live == LivePhase.ANALYSING || live == LivePhase.READING || f.status == BookStatus.READING ->
                if (failed) StageState.ATTENTION else StageState.CURRENT
            failed -> StageState.ATTENTION
            else -> StageState.WAITING
        }
        val translatedState = when {
            parsedState != StageState.DONE -> StageState.WAITING
            translated -> StageState.DONE
            live == LivePhase.TRANSLATING -> StageState.CURRENT
            failed -> StageState.ATTENTION
            else -> StageState.WAITING
        }
        val rebuiltState = when {
            f.outputName != null -> StageState.DONE
            translatedState != StageState.DONE -> StageState.WAITING
            live == LivePhase.SAVING -> StageState.CURRENT
            failed -> StageState.ATTENTION
            else -> StageState.WAITING
        }
        val deliveredState = if (f.outputName != null) StageState.DONE else StageState.WAITING

        val stages = listOf(
            StageView(Stage.UPLOADED, StageState.DONE, "Copied into the app"),
            StageView(
                Stage.PARSED, parsedState,
                when (parsedState) {
                    StageState.DONE -> "${f.pageCount} $units found"
                    StageState.CURRENT -> "Reading the book's structure"
                    StageState.ATTENTION -> if (f.needsOcr) "Scanned pages: no text to read" else "Could not be read"
                    else -> "Waiting to start"
                },
            ),
            StageView(
                Stage.TRANSLATED, translatedState,
                when (translatedState) {
                    StageState.DONE -> "All ${f.pageCount} $units"
                    StageState.CURRENT -> if (f.waiting) "Waiting to continue · ${f.translatedPages} of ${f.pageCount}" else "${f.translatedPages} of ${f.pageCount} $units"
                    StageState.ATTENTION -> "Stopped at ${f.translatedPages} of ${f.pageCount} $units"
                    else -> if (parsed && f.status == BookStatus.PAUSED) "Paused at ${f.translatedPages} of ${f.pageCount} $units" else "Not started"
                },
            ),
            StageView(Stage.CHECKED, StageState.UNAVAILABLE, "Quality checks come in a later update"),
            StageView(
                Stage.REBUILT, rebuiltState,
                when (rebuiltState) {
                    StageState.DONE -> "File built"
                    StageState.CURRENT -> "Building the file"
                    StageState.ATTENTION -> "The file could not be built"
                    else -> "After translation"
                },
            ),
            StageView(Stage.DELIVERED, deliveredState, f.outputName?.let { "Saved to Downloads: $it" } ?: "Saved to Downloads when done"),
        )
        return ProjectStatusView(stages, headline(f, stages))
    }

    private fun headline(f: ProjectFacts, stages: List<StageView>): String {
        val attention = stages.firstOrNull { it.state == StageState.ATTENTION }
        val current = stages.firstOrNull { it.state == StageState.CURRENT }
        return when {
            attention != null -> "Needs your attention: ${attention.stage.title.lowercase()} step"
            stages.last().state == StageState.DONE -> "Done · saved to Downloads"
            current != null -> when (current.stage) {
                Stage.PARSED -> "Reading the book"
                Stage.TRANSLATED -> "Translating · ${f.translatedPages} of ${f.pageCount} ${f.unitName}s"
                Stage.REBUILT -> "Building your file"
                else -> current.stage.title
            }
            f.status == BookStatus.PAUSED -> "Paused · tap Resume to continue"
            else -> "Waiting to start"
        }
    }
}
