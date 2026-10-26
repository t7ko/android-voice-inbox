package t7helpers.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStopTest {
    private val autoStop = AutoStop(silenceMs = 2000)

    @Test fun stopsAtTheEndOfTheFirstUtteranceWithText() {
        assertFalse(autoStop.shouldStop(utteranceEnded = false, text = "Купить", audioMs = 1500))
        assertTrue(autoStop.shouldStop(utteranceEnded = true, text = "Купить молоко", audioMs = 4000))
    }

    @Test fun anUtteranceWithoutWordsDoesNotStopIt() {
        // A cough or a door: Vosk may end an utterance with no text.
        assertFalse(autoStop.shouldStop(utteranceEnded = true, text = "", audioMs = 3000))
    }

    @Test fun stopsAfterLongSilenceWithNothingSaid() {
        assertFalse(autoStop.shouldStop(utteranceEnded = false, text = " ", audioMs = 9900))
        assertTrue(autoStop.shouldStop(utteranceEnded = false, text = "", audioMs = 10_000))
    }

    @Test fun speechLateInTheRecordingKeepsIt() {
        // Text started arriving before the no-speech limit: wait for the pause.
        assertFalse(autoStop.shouldStop(utteranceEnded = false, text = "Позвонить", audioMs = 12_000))
    }

    @Test fun autoSaveTakesOnlyClearSpeechThatEndedNormally() {
        assertEquals(null, AutoSave.hold(StopReason.SILENCE, 0.96))
        assertEquals(null, AutoSave.hold(StopReason.USER, 0.9))
        // Measured: English through the Russian model, a word made up from noise.
        assertTrue(AutoSave.hold(StopReason.SILENCE, 0.80)!!.contains("unsure"))
        assertTrue(AutoSave.hold(StopReason.USER, null)!!.contains("unsure"))
        assertTrue(AutoSave.hold(StopReason.NO_SPEECH, 0.99)!!.contains("no clear speech"))
        assertTrue(AutoSave.hold(StopReason.END, 0.99)!!.contains("no clear speech"))
        assertTrue(AutoSave.hold(StopReason.LIMIT, 1.0)!!.contains("time limit"))
    }

    @Test fun wordConfidencesFromAVoskResult() {
        val json = """{"result":[{"conf":1.0,"word":"buy"},{"conf":0.5,"word":"milk"}],"text":"buy milk"}"""
        assertEquals(listOf(1.0, 0.5), Transcript.confidences(json))
        assertEquals(emptyList<Double>(), Transcript.confidences("""{"text":""}"""))
    }

    @Test fun recordsWithoutLanguageAreRussian() {
        // Records from 0.2.0 have no language field.
        val json = """{"id":"a","createdAt":1,"status":"READY","text":"x"}"""
        assertEquals("ru", CaptureRecord.fromJson(json, hasAudio = false).language)
        val english = CaptureRecord("b", 1, CaptureStatus.READY, language = "en")
        assertEquals(english, CaptureRecord.fromJson(english.toJson(), hasAudio = false))
    }
}
