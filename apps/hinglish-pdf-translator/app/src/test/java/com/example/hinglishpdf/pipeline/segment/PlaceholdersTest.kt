package com.example.hinglishpdf.pipeline.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholdersTest {

    @Test
    fun `tokens, sequence, strip and renumber`() {
        val text = "A {1}bold [[IMG_2]] word{/1}."
        assertEquals(listOf("{1}", "[[IMG_2]]", "{/1}"), Placeholders.sequence(text))
        assertEquals("A bold  word.", Placeholders.strip(text))
        assertEquals("x {1}y{/1} [[BR_2]]", Placeholders.renumber("x {7}y{/7} [[BR_9]]"))
        val tokens = Placeholders.tokenize(text)
        assertEquals(7, tokens.size) // "A ", {1}, "bold ", [[IMG_2]], " word", {/1}, "."
        assertTrue(tokens[1] is PlaceholderToken.Open && tokens[2] is PlaceholderToken.Text && tokens[3] is PlaceholderToken.Standalone)
    }

    @Test
    fun `a faithful translation has no problems`() {
        assertEquals(emptyList<PlaceholderProblem>(), Placeholders.check("A {1}cat{/1} [[IMG_2]].", "एक {1}बिल्ली{/1} [[IMG_2]]।"))
    }

    @Test
    fun `missing, extra, reordered and unbalanced placeholders are reported`() {
        val source = "{1}One{/1} and [[IMG_2]] and {3}three{/3}"
        assertTrue(Placeholders.check(source, "{1}One{/1} and {3}three{/3}").contains(PlaceholderProblem.Missing("[[IMG_2]]")))
        assertTrue(Placeholders.check(source, "{1}One{/1} [[IMG_2]] [[IMG_2]] {3}three{/3}").contains(PlaceholderProblem.Extra("[[IMG_2]]")))
        assertTrue(Placeholders.check(source, "{3}three{/3} [[IMG_2]] {1}One{/1}").single() is PlaceholderProblem.Reordered)
        assertTrue(Placeholders.check("{1}a{2}b{/2}{/1}", "{1}a{2}b{/1}{/2}").any { it is PlaceholderProblem.Unbalanced })
        assertNull(Placeholders.balanceProblem("{1}a{2}b{/2}{/1}"))
    }

    @Test
    fun `text that looks like a placeholder is protected`() {
        val builder = PlaceholderTextBuilder()
        builder.text("Set {1} to [[A_2]] now")
        val (text, tags) = builder.result()
        assertEquals("Set [[TXT_1]] to [[TXT_2]] now", text)
        assertEquals(listOf("{1}", "[[A_2]]"), tags.map { it.markup })
    }

    @Test
    fun `segment hashes match for the same sentence with different placeholder numbers`() {
        assertEquals(Segmenter.hash("A {4}cat{/4}."), Segmenter.hash(" A {1}cat{/1}."))
        assertTrue(Segmenter.hash("A cat.") != Segmenter.hash("A dog."))
        assertEquals(3, Segmenter.wordCount("{1}Don't{/1} stop [[IMG_2]] now!"))
        assertEquals(3, Segmenter.wordCount("वह घर गया।"))
    }
}
