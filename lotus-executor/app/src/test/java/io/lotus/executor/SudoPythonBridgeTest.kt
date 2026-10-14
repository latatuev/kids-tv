package io.lotus.executor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SudoPythonBridgeTest {
    @Test fun framesPasswordAndScriptWithSeparateLengths() {
        val password = "fixture-password\n".toByteArray()
        val script = "import os\nprint(os.geteuid())\n".toByteArray()
        val payload = SudoPythonBridge.packet(password, script)
        val frame = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        assertEquals(password.size, frame.int)
        assertEquals(script.size, frame.int)
        val pw = ByteArray(password.size)
        val code = ByteArray(script.size)
        frame.get(pw)
        frame.get(code)
        assertArrayEquals(password, pw)
        assertArrayEquals(script, code)
    }

    @Test fun invalidPasswordOrScriptIsRejectedBeforeSsh() {
        assertThrows(IllegalArgumentException::class.java) {
            SudoPythonBridge.packet("no-newline".toByteArray(), "print(1)".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SudoPythonBridge.packet("test\n".toByteArray(), ByteArray(0))
        }
    }
}
