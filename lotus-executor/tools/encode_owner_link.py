#!/usr/bin/env python3
"""Make a compact user-reviewed Lotus Executor owner SSH deep link.

Usage: python3 encode_owner_link.py --id leva-pdf-20261013-r1 --file command.py
Never embed passwords, private keys, capability URLs or other secrets in a link.
The fragment is not transmitted in an HTTPS request, but anyone who sees the full
link can view the script. The app requires explicit owner confirmation.
"""
import argparse
import base64
import gzip
import hashlib
import re
import sys
from pathlib import Path

HOST = "https://lotus-135-106-163-200.sslip.io/chat-executor/run"

def link_for(task_id: str, body: bytes) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_-]{8,48}", task_id):
        raise ValueError("Invalid task ID")
    if not 1 <= len(body) <= 20 * 1024:
        raise ValueError("Script must be 1..20480 bytes")
    text = body.decode("utf-8", "strict")
    if not text.strip() or "\x00" in text:
        raise ValueError("Not valid nonempty Python UTF-8 code")
    compressed = gzip.compress(body, mtime=0)
    encoded = base64.urlsafe_b64encode(compressed).decode().rstrip("=")
    digest = hashlib.sha256(body).hexdigest()
    url = f"{HOST}?op=owner-ssh&id={task_id}#v1.{encoded}.{digest}"
    if len(url) > 14000:
        raise ValueError("URL too long for safe Android intent handoff")
    return url

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--id", required=True)
    parser.add_argument("--file", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(link_for(args.id, args.file.read_bytes()))
    except (OSError, UnicodeError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
