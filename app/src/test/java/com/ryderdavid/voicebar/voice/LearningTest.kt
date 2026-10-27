package com.ryderdavid.voicebar.voice

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LearningTest {
    @Test fun aMisheardNameIsACorrection() {
        val c = Corrections.find("Tell Shauna I'll be there at six", "Tell Siona I'll be there at six")
        assertEquals(listOf(Corrections.Change("Shauna", "Siona", "Tell", "I'll be there")), c)
    }

    @Test fun aMisheardPhraseBecomesOneTerm() {
        val c = Corrections.find("the you da report is due Friday", "the YODA report is due Friday")
        assertEquals(1, c.size)
        assertEquals("you da", c[0].heard)
        assertEquals("YODA", c[0].fixed)
    }

    @Test fun itFindsTheTypedTextInsideALongerField() {
        val c = Corrections.find("meet at camel back at noon", "Hey! Earlier stuff here. meet at Camelback at noon")
        assertEquals("camel back", c.single().heard)
        assertEquals("Camelback", c.single().fixed)
    }

    @Test fun ordinaryEditsAreNotCorrections() {
        assertTrue(Corrections.find("see you on Tuesday", "see you on Wednesday").isEmpty(), "a different word, chosen on purpose")
        assertTrue(Corrections.find("it costs 40 dollars", "it costs 45 dollars").isEmpty(), "numbers")
        assertTrue(Corrections.find("hello there", "Hello there").isEmpty(), "case only")
        assertTrue(Corrections.find("I think we should go to the store later", "Let's grab groceries tonight instead").isEmpty(), "a rewrite")
        assertTrue(Corrections.find("running late", "running late, sorry!").isEmpty(), "added words")
    }

    @Test fun theDictionaryFeedsKeytermsThePromptAndSureReplacements() {
        val d = Dictionary(File.createTempFile("dict", ".json").apply { delete() })
        d.add("Siona", "Shauna")
        d.add("YODA", "you da", vocabulary = true, learned = false)
        d.add("Supercalifragilisticexpialidocious")
        assertEquals(listOf("Siona", "YODA"), d.keyterms().sorted(), "the over-long one doesn't fit a keyterm")
        assertTrue(d.promptSection().contains("\"Shauna\" → \"Siona\""))
        // added by hand: replaced every time; learned once: left to the cleanup
        assertEquals("the YODA report for Shauna", d.applySure("the you da report for Shauna"))
        d.add("Siona", "Shauna")
        assertEquals("for Siona", d.applySure("for Shauna"))
    }

    @Test fun aReCapitalizedWordIsHowTheUserWritesIt() {
        assertEquals(listOf("LOL" to "lol"), Corrections.casings("That's great LOL see you there", "That's great lol see you there"))
        assertEquals(listOf("iphone" to "iPhone"), Corrections.casings("my iphone died", "my iPhone died"))
        assertTrue(Corrections.casings("The plan works", "the plan works").isEmpty(), "a first letter alone is where it fell in a sentence")
        assertTrue(Corrections.casings("I said hi", "i said hi").isEmpty(), "one letter")
        assertTrue(Corrections.casings("Meet at 6PM", "Meet at 6pm").isEmpty(), "letters only")
        assertTrue(Corrections.casings("great LOL", "something else entirely now").isEmpty(), "a rewrite")
        assertTrue(Corrections.find("great LOL", "great lol").isEmpty(), "and it's not a mishearing for Claude to judge")
    }

    @Test fun aWordReCapitalizedTwiceIsAlwaysWrittenThatWay() {
        val d = Dictionary(null)
        d.add("lol", "LOL", vocabulary = false)
        assertEquals("Ha LOL", d.applySure("Ha LOL"), "once could be a one-off")
        d.add("lol", "LOL", vocabulary = false)
        assertEquals("Ha lol, lol. lol LOLA", d.applySure("Ha LOL, Lol. lol LOLA"), "whole words, whatever the capitals")
        assertFalse(d.promptSection().contains("\"LOL\""), "capitals aren't a mishearing for the cleanup")
        assertTrue(Dictionary.keepsCase(d.find("lol")!!))
    }

    @Test fun aWordAddedInItsOwnCapitalsIsAlwaysWrittenThatWay() {
        val d = Dictionary(null)
        d.add("lol", learned = false)
        d.add("iPhone", learned = false)
        d.add("Will", learned = false)
        d.add("ok", learned = true)
        assertEquals("lol my iPhone will do. OK", d.applySure("LOL my IPHONE will do. OK"))
        assertFalse(Dictionary.keepsCase(d.find("Will")!!), "a capitalized name would take every \"will\"")
        assertFalse(Dictionary.keepsCase(d.find("ok")!!), "learned vocabulary, not a spelling the user chose")
    }

    @Test fun undoPutsItBack() {
        val d = Dictionary(null)
        val before = d.find("Ezra")
        d.add("Ezra", "Ezra")
        d.restore("Ezra", before)
        assertNull(d.find("Ezra"))
    }

    @Test fun theDictionarySurvivesARestart() {
        val f = File.createTempFile("dict", ".json").apply { delete() }
        Dictionary(f).add("Camelback", "camel back")
        assertEquals(listOf("camel back"), Dictionary(f).find("Camelback")?.heard)
    }

    @Test fun theJudgesReplyIsRead() {
        val reply = """{"content":[{"type":"text","text":"{\"verdict\": \"both\", \"term\": \"Siona\", \"heard\": \"Shauna\"}"}],"usage":{"input_tokens":120,"output_tokens":20}}"""
        assertEquals(LearnerRequest.Result(LearnerRequest.Verdict.BOTH, "Siona", "Shauna"), LearnerRequest.parse(reply))
        val none = """{"content":[{"type":"text","text":"{\"verdict\":\"none\",\"term\":\"\",\"heard\":\"\"}"}]}"""
        assertEquals(LearnerRequest.Verdict.NONE, LearnerRequest.parse(none)?.verdict)
    }

    @Test fun keytermsGoInTheRealtimeUrl() {
        val url = ElevenLabsProtocol.url("wss://api.elevenlabs.io", listOf("Siona", "YODA", "x".repeat(25)))
        assertEquals(listOf("Siona", "YODA"), url.queryParameterValues("keyterms"))
    }

    @Test fun theWatchFinishesWhenTheMessageIsSent() {
        var got: String? = null
        CorrectionWatch.onCheck = { _, field -> got = field; true }
        CorrectionWatch.start("tell Shauna hi", "com.app", "f")
        CorrectionWatch.seen("com.app", "tell Siona hi")
        assertNull(got, "still being edited")
        CorrectionWatch.seen("com.app", "") // sent: the field cleared
        assertEquals("tell Siona hi", got)
        assertNull(CorrectionWatch.pending)
        CorrectionWatch.onCheck = null
    }

    @Test fun overlapCountsTypedWordsStillThere() {
        assertEquals(1.0, CorrectionWatch.overlap("a b c", "x a b c"))
        assertFalse(CorrectionWatch.overlap("tell her hi", "") > 0)
    }
}
