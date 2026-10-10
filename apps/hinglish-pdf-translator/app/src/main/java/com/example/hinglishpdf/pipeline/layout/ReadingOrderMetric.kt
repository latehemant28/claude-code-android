package com.example.hinglishpdf.pipeline.layout

import com.example.hinglishpdf.pipeline.segment.Box

/**
 * Reading-order evaluation after Clausner, Pletschacher & Antonacopoulos,
 * "The Significance of Reading Order in Document Recognition and its
 * Evaluation", ICDAR 2013, pp. 688-692.
 *
 * A reading order is a tree of ordered and unordered groups of regions.
 * Every pair of detected regions gets one relation from the detected tree;
 * the ground truth relation for that pair is composed from the relations of
 * the ground-truth regions each one overlaps, weighted by relative overlap
 * area, so detected and true segmentations need not match one to one.
 * Each disagreement costs the penalty in the paper's matrix (Fig. 4, at most
 * 40); the error e is their weighted sum, and the success rate is
 * s = 1 / (e / e50 + 1) with e50 = pmax * nGT / 2 (equations 1 and 2).
 */
object ReadingOrderMetric {

    sealed interface Node
    data class Region(val id: String) : Node
    data class Group(val ordered: Boolean, val children: List<Node>) : Node

    enum class Relation(val symbol: String) {
        DIRECT_SUCCESSOR("→"),
        DIRECT_PREDECESSOR("←"),
        UNORDERED("--"),
        NOT_DIRECT("-x-"),
        NOT_DEFINED("n.d."),
        SOMEWHERE_BEFORE("→→"),
        SOMEWHERE_AFTER("←←"),
    }

    /** Fig. 4: rows are the detected relation, columns the ground truth, both in [Relation] order. */
    val PENALTIES: Array<IntArray> = arrayOf(
        intArrayOf(0, 40, 10, 20, 0, 0, 10),
        intArrayOf(40, 0, 10, 20, 0, 10, 0),
        intArrayOf(20, 20, 0, 10, 0, 10, 10),
        intArrayOf(20, 20, 10, 0, 0, 10, 10),
        intArrayOf(20, 20, 10, 0, 0, 10, 10),
        intArrayOf(0, 20, 5, 5, 0, 0, 10),
        intArrayOf(20, 0, 5, 5, 0, 10, 0),
    )
    const val MAX_PENALTY = 40

    /** A page's regions with their boxes, and its reading order tree. */
    class ReadingOrder(val boxes: Map<String, Box>, val tree: Group) {
        constructor(sequence: List<Pair<String, Box>>) : this(sequence.toMap(), Group(true, sequence.map { Region(it.first) }))
    }

    class Evaluation(val error: Double, val success: Double, val penalties: List<String>)

    fun evaluate(groundTruth: ReadingOrder, detected: ReadingOrder): Evaluation {
        // Region correspondence: each detected region against the ground-truth regions it overlaps.
        val overlaps = detected.boxes.mapValues { (_, box) ->
            groundTruth.boxes.mapNotNull { (id, g) -> overlap(box, g).takeIf { it > 0.0 }?.let { id to it } }
        }
        val ids = detected.boxes.keys.toList()
        var error = 0.0
        val notes = mutableListOf<String>()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = ids[i]
            val b = ids[j]
            val found = relation(detected.tree, a, b)
            for ((ga, wa) in overlaps.getValue(a)) for ((gb, wb) in overlaps.getValue(b)) {
                if (ga == gb) continue
                val truth = relation(groundTruth.tree, ga, gb)
                val penalty = PENALTIES[found.ordinal][truth.ordinal]
                if (penalty > 0) {
                    error += penalty * wa * wb
                    notes += "$a ${found.symbol} $b, truth $ga ${truth.symbol} $gb: $penalty"
                }
            }
        }
        val regions = groundTruth.boxes.size.coerceAtLeast(1)
        val e50 = MAX_PENALTY * regions / 2.0
        return Evaluation(error, 1.0 / (error / e50 + 1.0), notes)
    }

    /** The relation of [a] to [b] in [tree]: "→" if b directly follows a, and so on. */
    fun relation(tree: Group, a: String, b: String): Relation {
        val pathA = path(tree, a) ?: return Relation.NOT_DEFINED
        val pathB = path(tree, b) ?: return Relation.NOT_DEFINED
        var depth = 0
        while (depth < pathA.size && depth < pathB.size && pathA[depth].second == pathB[depth].second) depth++
        if (depth == pathA.size || depth == pathB.size) return Relation.NOT_DEFINED // the same region
        val group = pathA[depth].first
        if (!group.ordered) return Relation.UNORDERED
        val ia = pathA[depth].second
        val ib = pathB[depth].second
        val forward = ib == ia + 1
        val backward = ia == ib + 1
        if (!forward && !backward) return Relation.NOT_DIRECT
        // Below the common group, the earlier region must end its branch and the later one start its own.
        val (first, second) = if (forward) pathA.drop(depth + 1) to pathB.drop(depth + 1) else pathB.drop(depth + 1) to pathA.drop(depth + 1)
        var unordered = false
        for ((g, index) in first) if (!g.ordered) unordered = true else if (index != g.children.lastIndex) return Relation.NOT_DIRECT
        for ((g, index) in second) if (!g.ordered) unordered = true else if (index != 0) return Relation.NOT_DIRECT
        return when {
            forward && unordered -> Relation.SOMEWHERE_BEFORE
            forward -> Relation.DIRECT_SUCCESSOR
            unordered -> Relation.SOMEWHERE_AFTER
            else -> Relation.DIRECT_PREDECESSOR
        }
    }

    /** The groups from the root down to [id], each with the index of the child taken. */
    private fun path(group: Group, id: String): List<Pair<Group, Int>>? {
        group.children.forEachIndexed { i, child ->
            when (child) {
                is Region -> if (child.id == id) return listOf(group to i)
                is Group -> path(child, id)?.let { return listOf(group to i) + it }
            }
        }
        return null
    }

    /** Share of [detected] covered by [truth]. */
    private fun overlap(detected: Box, truth: Box): Double {
        val w = minOf(detected.x1, truth.x1) - maxOf(detected.x0, truth.x0)
        val h = minOf(detected.y1, truth.y1) - maxOf(detected.y0, truth.y0)
        if (w <= 0f || h <= 0f) return 0.0
        val area = detected.width.toDouble() * detected.height
        return if (area <= 0.0) 0.0 else w.toDouble() * h / area
    }

    /** A page layout's detected reading order: its content blocks in order (furniture is not read). */
    fun of(page: PageLayout): ReadingOrder = ReadingOrder(page.blocks.sortedBy { it.readingIndex }.map { it.id to it.box })
}
