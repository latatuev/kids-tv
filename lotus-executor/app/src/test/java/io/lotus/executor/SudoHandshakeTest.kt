package io.lotus.executor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SudoHandshakeTest {
    private val prompt = "LOTUS_SUDO_PROMPT_9f8c7a6b"
    private val marker = "LOTUS_SUDO_READY_9f8c7a6b"

    @Test fun promptFromStderrBeforePassword() {
        assertTrue(SudoHandshake.confirmed("", prompt, prompt))
        assertFalse(SudoHandshake.confirmed("", prompt, marker))
    }

    @Test fun promptFromMergedStdoutBeforePassword() {
        assertTrue(SudoHandshake.confirmed(prompt, "", prompt))
        assertFalse(SudoHandshake.confirmed(prompt, "", marker))
    }

    @Test fun successfulRootMarkerFromEitherStream() {
        assertTrue(SudoHandshake.confirmed("output" + marker, "", marker))
        assertTrue(SudoHandshake.confirmed("", marker, marker))
    }

    @Test fun missingRootMarkerMustNeverAuthorizePython() {
        assertFalse(SudoHandshake.confirmed(prompt, "", marker))
        assertFalse(SudoHandshake.confirmed("LOTUS_SUDO_READY_", "", marker))
    }

    @Test fun secondPasswordPromptSignalsRejection() {
        assertEquals(2, SudoHandshake.occurrences(prompt + prompt, "", prompt))
        assertEquals(2, SudoHandshake.occurrences(prompt, prompt, prompt))
        assertEquals(1, SudoHandshake.occurrences("", prompt, prompt))
    }

    @Test fun allHandshakeTokensStrippedFromBothStreams() {
        assertEquals("UID: 0", SudoHandshake.redact(prompt + marker + "UID: 0", marker, prompt))
        assertEquals("", SudoHandshake.redact(marker, marker, prompt))
    }

    @Test fun passwordFailureIsRecognizedWithoutRetry() {
        assertTrue(SudoHandshake.passwordRejected("", "Sorry, try again."))
        assertFalse(SudoHandshake.passwordRejected("UID: 0", ""))
    }
}
