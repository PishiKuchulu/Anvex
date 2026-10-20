package com.anvex.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.6: a video request must be detected so the media contract can force a real
 * MP4 deliverable instead of the historic index.html fallback. Web-animation
 * edits and how-to questions must NOT be treated as video requests.
 */
class MediaDetectionTest {
    @Test fun `english video request is detected`() =
        assertTrue(UserIntentClassifier.requestsMedia("make me a video of the logo animation"))

    @Test fun `motion graphics showreel is detected`() =
        assertTrue(UserIntentClassifier.requestsMedia("create a 15-second motion graphics showreel"))

    @Test fun `persian video request is detected`() =
        assertTrue(UserIntentClassifier.requestsMedia("یه ویدیو موشن گرافیک بساز"))

    @Test fun `mp4 request is detected`() =
        assertTrue(UserIntentClassifier.requestsMedia("export it as an mp4 file"))

    @Test fun `how-to question is not a media request`() =
        assertFalse(UserIntentClassifier.requestsMedia("how do I make a video in HTML?"))

    @Test fun `web animation edit is not a media request`() =
        assertFalse(UserIntentClassifier.requestsMedia("add a fade animation to the hero section"))

    @Test fun `persian web animation edit is not a media request`() =
        assertFalse(UserIntentClassifier.requestsMedia("به صفحه یه انیمیشن اضافه کن"))

    @Test fun `mp4 artifact action parses render parameters`() {
        var parsed: BoltAction.ArtifactAction? = null
        val callbacks = object : ParserCallbacks {
            override fun onActionClose(data: ActionCallbackData) {
                (data.action as? BoltAction.ArtifactAction)?.let { parsed = it }
            }
        }
        val parser = BoltStreamParser(callbacks)
        parser.parse(
            "m1",
            "<boltArtifact id=\"reel\" title=\"Reel\">" +
                "<boltAction type=\"artifact\" format=\"mp4\" source=\"render.html\" duration=\"15\" fps=\"30\" " +
                "width=\"1280\" height=\"720\" outputName=\"showreel.mp4\"></boltAction></boltArtifact>",
        )
        parser.flush("m1")
        val action = parsed ?: error("no artifact action parsed")
        assertEquals("mp4", action.format)
        assertEquals("render.html", action.source)
        assertEquals(15, action.durationSec)
        assertEquals(30, action.fps)
        assertEquals(1280, action.width)
        assertEquals(720, action.height)
        assertEquals("showreel.mp4", action.outputName)
    }
}
