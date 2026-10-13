# Lotus Executor Owner SSH prototype (v0.4) — NOT INSTALLED

Status: source-only **experimental** Android branch. Existing v0.2 on the owner's
Galaxy S23 and the Lotus services remain unchanged.

## What it implements

1. An Android App Link `/chat-executor/run?op=owner-ssh&id=...#v1.<gzip-base64url>.<sha256>`
   dispatches a bounded **Python-only** UTF-8 script (20 KiB max) to a non-exported review screen.
   HTTPS URL fragments aren't sent in HTTP requests (but are still visible to anyone with
   the full link, including users/apps handling it). **Do not include passwords or secrets.**
2. The owner sees **all code**, the task ID, digest, and destination before any execution.
   It does not run automatically, even if the chat URL was clicked.
3. The app generates a dedicated 3072-bit RSA SSH identity on-device once. The private
   key PEM is AES-256-GCM encrypted by Android Keystore and stored in private app preferences.
   No host credentials are embedded in APKs, chat links, commits, GitHub secrets or Lotus Bridge.
4. Owner authorizes the **public key only** in their `sergey` SSH account on their VPS
   using a separately trusted owner action. The app must additionally have a **verified
   known_hosts record** copied from the owner's already trusted desktop SSH client.
   `StrictHostKeyChecking=yes` prohibits silent first-use acceptance.
5. A second explicit owner confirmation starts `ssh sergey@135.106.163.200 python3 -`
   with the supplied script through stdin. Timeout 200 seconds, bounded output 64 KiB
   per stdout/stderr, no automatic retry. Replayed (id,digest) submissions are refused.
6. Output is shown locally and can be copied. No server/ChatGPT automatic result return.

## Important limitations

- **Not yet built/installed/tested on real device**, no SSH key paired, no Lotus write performed.
- This is explicitly **owner-operated SSH**, not a ChatGPT backend Bridge or a bypass of
  unapproved Lotus Control scopes/actions. Protected backend grants are unchanged.
- A link isn't a digital signature. The SHA-256 digest checks bytes, not source authenticity.
  The owner MUST review code. Only use on trusted device with screen lock.
- App backup disabled; uninstalling or reinstalling with a different certificate loses
  the SSH identity. The prior v0.2 debug signing certificate differs from new CI debug
  builds. Before owner onboarding, establish the existing stable APK signing workflow
  and verify off-device encrypted key backup.
- The owner must independently trust the SSH host key from their original desktop
  connection, not from an unverified `ssh-keyscan`.
- Outgoing SSH TCP/22 from the Android network to Lotus and Lotus SSH key authorization
  remain untested. Both may fail without a firewall change; don't weaken firewall blindly.
- Task execution requires explicit owner approval and existing operating-system privileges.
  No unprivileged app can magically grant new server rights.
- Sending a script in an Android App Link means URLs may be retained in user chat/local
  history even though fragments aren't sent to the HTTP server.
- If security system explicitly denies the underlying **operation**, use the documented
  authorized owner path; do not present this as a way to evade a denial.

## Local link creation

```sh
python3 lotus-executor/tools/encode_owner_link.py --id leva-pdf-20261013-r1 --file command.py
```

The resulting URL is for the **updated** app only; existing v0.2 recognizes only
`op=ping` and silently ignores `op=owner-ssh`.

## First owner setup after a **stable-signed** APK exists

1. Owner installs approved stable APK (one uninstall/reinstall from old debug signer).
2. Owner generates app SSH key, copies **only public** OpenSSH line from the screen.
3. Owner securely adds that public key to `~/.ssh/authorized_keys` on Lotus using
   previously working desktop SSH; avoids broadening any `sudo` grants.
4. Owner copies a trusted host entry from the desktop's `~/.ssh/known_hosts`
   into the app and checks that it refers to their actual Lotus SSH host key.
5. First trial must be a **read-only** test, before modifying site sources or
   publishing a PDF patch. Inspect resulting output.
6. Only after successful SSH acceptance should an owner-approved modifying
   command be proposed for a later separate URL.

## Build

Branch: `lotus-executor-owner-ssh-prototype`.
GitHub Actions `.github/workflows/lotus-executor-apk.yml` builds debug for
compilation diagnostics. **Debug artifact must not be distributed as a final
upgradable app**. Release signing is still an owner action.
