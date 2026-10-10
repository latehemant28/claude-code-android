package com.example.hinglishpdf.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PipelineConfigTest {

    @Test
    fun `the shipped config file holds exactly the built-in defaults`() {
        val json = File("src/main/assets/${PipelineConfig.ASSET}").readText()
        assertEquals(PipelineConfig(), PipelineConfig.fromJson(json))
    }

    @Test
    fun `values missing from a config file keep their defaults`() {
        val config = PipelineConfig.fromJson("""{ "paragraphGapRatio": 1.5, "headerFooterPatterns": ["^x$"] }""")
        assertEquals(1.5f, config.paragraphGapRatio)
        assertEquals(listOf("^x$"), config.headerFooterPatterns)
        assertEquals(PipelineConfig().marginTop, config.marginTop)
        assertEquals(PipelineConfig().captionPatterns, config.captionPatterns)
        assertTrue(config.headerFooter.single().matches("x"))
    }

    @Test
    fun `terminal punctuation is judged on visible text`() {
        val config = PipelineConfig()
        assertTrue(config.endsTerminally("He left.{/1}[[SUP_2]] "))
        assertTrue(config.endsTerminally("वह चला गया।"))
        assertFalse(config.endsTerminally("and then"))
        assertFalse(PipelineConfig(terminalPunctuation = "!").endsTerminally("He left."))
    }
}
