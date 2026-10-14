package io.lotus.executor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SudoHandshakeTest {
    private val marker = "LOTUS_SUDO_READY_9f8c7a6b5e4d"

    @Test fun readinessWhenPtyMergedStderrIntoStdout() {
        assertTrue(SudoHandshake.confirmed("sudo output" + marker, "", marker))
    }

    @Test fun readinessWhenStderrSeparate() {
        assertTrue(SudoHandshake.confirmed("", marker, marker))
    }

    @Test fun missingMarkerMustNeverAuthorizeSendingPython() {
        assertFalse(SudoHandshake.confirmed("sudo prompt", "Password:", marker))
        assertFalse(SudoHandshake.confirmed("LOTUS_SUDO_READY_", "", marker))
    }

    @Test fun markerStrippedFromEitherStream() {
        assertEquals("UID: 0", SudoHandshake.redact(marker + "UID: 0", marker))
        assertEquals("", SudoHandshake.redact(marker, marker))
    }

    @Test fun wrongPasswordIsRecognizedWithoutSecondAttempt() {
        assertTrue(SudoHandshake.passwordRejected("", "Sorry, try again."))
        assertFalse(SudoHandshake.passwordRejected("UID: 0", ""))
    }
}
