#!/usr/bin/env python3
"""Offline integration for the exact signed APK Python asset, no real sudo."""
import base64
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

BRIDGE = Path(__file__).resolve().parents[1] / "app/src/main/assets/sudo_bridge.py"

class BridgeTests(unittest.TestCase):
    def execute(self, script, password=b"fixture-password\n", deny=False):
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "sudo"
            fake.write_text(
                "#!/usr/bin/env python3\n"
                "import os,sys\n"
                "password=sys.stdin.buffer.readline()\n"
                "if os.getenv('FAKE_DENY')=='1' or password!=b'fixture-password\\n':\n"
                "    sys.stderr.write('sudo: a password is required\\n');sys.exit(1)\n"
                "args=sys.argv\n"
                "at=args.index('--')\n"
                "os.execv('/usr/bin/python3',args[at+1:])\n"
            )
            fake.chmod(0o755)
            payload = struct.pack("!II", len(password), len(script)) + password + script
            encoded = base64.b64encode(BRIDGE.read_bytes()).decode()
            # Mirrors the single fixed SSH exec command built by Kotlin.
            command = ("python3 -c 'import base64;exec(compile(base64.b64decode(\""
                       + encoded + "\"),\"<lotus-sudo-bridge>\",\"exec\"))'")
            return subprocess.run(["/bin/sh", "-c", command], input=payload,
                                  capture_output=True, timeout=12,
                                  env={**os.environ, "PATH": tmp + ":" + os.environ["PATH"],
                                       "FAKE_DENY": "1" if deny else "0"})

    def test_valid_password_allows_python_and_redacts_marker(self):
        result = self.execute(b"print('ROOT_SCRIPT_EXECUTED')\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"ROOT_SCRIPT_EXECUTED", result.stdout)
        self.assertNotIn(b"LOTUS_ROOT_READY", result.stdout)

    def test_denied_password_never_executes_script(self):
        result = self.execute(b"print('NEVER_EXECUTE')\n", deny=True)
        self.assertEqual(73, result.returncode, result.stderr)
        self.assertNotIn(b"NEVER_EXECUTE", result.stdout)
        self.assertIn(b"sudo rejected", result.stderr)

    def test_output_is_bounded(self):
        result = self.execute(b"import sys;sys.stdout.write('X'*100000)\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(65536, len(result.stdout))

    def test_invalid_packet_fails_before_sudo(self):
        self.assertEqual(71, self.execute(b"", password=b"x\n").returncode)

if __name__ == "__main__":
    unittest.main()
