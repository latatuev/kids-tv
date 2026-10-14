"""Ephemeral memory-only sudo bridge; no secrets in argv or on disk."""
import os
import selectors
import signal
import struct
import subprocess
import sys
import time

MAX_SCRIPT = 20 * 1024
MAX_CREDENTIAL = 1100
MAX_OUTPUT = 64 * 1024
MAX_PACKET = 8 + MAX_CREDENTIAL + MAX_SCRIPT

def fail(reason, exit_code=71):
    sys.stderr.write("LOTUS_SUDO_BRIDGE: " + reason + "\n")
    sys.stderr.flush()
    raise SystemExit(exit_code)

def main():
    packet = bytearray(sys.stdin.buffer.read(MAX_PACKET + 1))
    if len(packet) < 8 or len(packet) > MAX_PACKET:
        fail("invalid request size")
    credential_size, script_size = struct.unpack("!II", packet[:8])
    if not (2 <= credential_size <= MAX_CREDENTIAL and 1 <= script_size <= MAX_SCRIPT
            and len(packet) == 8 + credential_size + script_size):
        fail("invalid request lengths")
    password = bytearray(packet[8:8 + credential_size])
    script = bytearray(packet[8 + credential_size:])
    packet[:] = b"\x00" * len(packet)
    del packet
    if password[-1] != 10 or b"\x00" in password:
        password[:] = b"\x00" * len(password)
        fail("invalid sudo credential framing")
    marker = b"\x01LOTUS_ROOT_READY_" + os.urandom(14).hex().encode() + b"\x02"
    root_loader = (
        "import sys\n"
        "sys.stdout.buffer.write(" + repr(marker) + ")\n"
        "sys.stdout.buffer.flush()\n"
        "source=sys.stdin.buffer.read()\n"
        "exec(compile(source,\"<lotus-owner-script>\",\"exec\"))\n"
    )
    process = None
    stdout = bytearray()
    stderr = bytearray()
    ready = False
    try:
        process = subprocess.Popen(
            ["sudo", "-k", "-S", "-p", "", "--", "/usr/bin/python3", "-u", "-c", root_loader],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            bufsize=0, start_new_session=True, close_fds=True,
        )
        process.stdin.write(password)
        process.stdin.flush()
        password[:] = b"\x00" * len(password)
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ, stdout)
            selector.register(process.stderr, selectors.EVENT_READ, stderr)
            deadline = time.monotonic() + 20
            while selector.get_map():
                now = time.monotonic()
                if now >= deadline:
                    fail("root readiness timeout" if not ready else "script timed out", 72)
                for key, _ in selector.select(min(0.3, deadline - now)):
                    fd = key.fileobj
                    try:
                        chunk = os.read(fd.fileno(), 8192)
                    except OSError:
                        chunk = b""
                    if not chunk:
                        selector.unregister(fd)
                    elif len(key.data) < MAX_OUTPUT:
                        key.data.extend(chunk[:MAX_OUTPUT - len(key.data)])
                if not ready and (marker in stdout or marker in stderr):
                    stdout[:] = stdout.replace(marker, b"", 1)
                    stderr[:] = stderr.replace(marker, b"", 1)
                    ready = True
                    try:
                        process.stdin.write(script)
                        process.stdin.flush()
                        process.stdin.close()
                    finally:
                        script[:] = b"\x00" * len(script)
                    deadline = time.monotonic() + 190
        code = process.wait(timeout=2)
        if not ready:
            fail("sudo rejected or exited before root confirmation (exit %d)" % code, 73)
        sys.stdout.buffer.write(stdout)
        sys.stderr.buffer.write(stderr)
        return code
    finally:
        password[:] = b"\x00" * len(password)
        script[:] = b"\x00" * len(script)
        if process is not None and process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except OSError:
                try:
                    process.kill()
                except OSError:
                    pass
            process.wait(timeout=3)

if __name__ == "__main__":
    raise SystemExit(main())
