#!/usr/bin/env python3
"""Consultation Script for neXus Backup & Recovery Engine Security Code Review (Claude / Fable Reviewer).
Directly executes the installed host CLI tool `/Users/OzanDur/.local/bin/claude -p`.
Feeds Codex (Astra)'s architectural review into Claude (Fable) for a sequential second-opinion review.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
ASTRA_REVIEW_FILE = BASE_DIR / "docs" / "design" / "CODEX_BACKUP_CODE_REVIEW.md"
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CLAUDE_BACKUP_CODE_REVIEW.md"


def read_file_safely(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else f"File not found: {path}"


def main() -> int:
    astra_review = read_file_safely(ASTRA_REVIEW_FILE)
    retention_service = read_file_safely(
        BASE_DIR / "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/backup/retention/RetentionPruningService.java"
    )
    capabilities = read_file_safely(
        BASE_DIR / "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/backup/BackupCapabilities.java"
    )
    capability_executor = read_file_safely(
        BASE_DIR / "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/backup/BackupCapabilityExecutor.java"
    )
    cp_snapshot = read_file_safely(
        BASE_DIR / "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/backup/cp/CheckPointSnapshotExecutor.java"
    )
    pan_backup = read_file_safely(
        BASE_DIR / "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/backup/pan/PaloAltoBackupExecutor.java"
    )
    diff_engine = read_file_safely(
        BASE_DIR / "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/backup/diff/SemanticDeviationEngine.java"
    )
    controller = read_file_safely(
        BASE_DIR / "ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/BackupController.java"
    )
    backup_server = read_file_safely(
        BASE_DIR / "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/backup/server/Ui2BackupServer.java"
    )

    prompt = f"""You are Fable, the Enterprise Security Architect for the neXus project.
In this single-turn review consultation, you are performing a security, risk, secret containment, and second-opinion code review of the neXus Backup & Recovery Engine.
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert review directly in your response text.

### Background & Product Owner Requirements:
1. Product Plane Separation: "Configuration evidence is not a recovery backup." The recovery plane (Plane 3) is write-once, encrypted, egress-denied, and separate from the evidence plane.
2. Capacity & Retention:
   - Dedicated backup vault budget: 400 GiB.
   - Standard daily backup retention: 14 days (PO directive: "1 month is too long").
   - Snapshot cadence & retention depth: Depth 4 retained snapshots (PO directive: "Snapshot quantity will be high").
   - Cryptographic append-only tombstones on deletion.
3. Supported Vendors: Check Point Gaia snapshots (`add snapshot`, disk space validation, pre-flight safety) and Palo Alto Networks configuration & device-state XML exports.
4. Security: Opaque UUID artefact IDs over HTTP, audited downloads with fail-closed semantics.

---
### Astra's Architectural Code Review (from Codex):
The Lead Software Architect (Astra / Codex) has already reviewed this codebase and issued the following review report:

```markdown
{astra_review}
```
---

Key Code Artifacts Under Review:

### 1. RetentionPruningService.java
```java
{retention_service}
```

### 2. BackupCapabilities.java
```java
{capabilities}
```

### 3. BackupCapabilityExecutor.java
```java
{capability_executor}
```

### 4. CheckPointSnapshotExecutor.java
```java
{cp_snapshot}
```

### 5. PaloAltoBackupExecutor.java
```java
{pan_backup}
```

### 6. SemanticDeviationEngine.java (Diff Engine)
```java
{diff_engine}
```

### 7. BackupController.java (REST API)
```java
{controller}
```

### 8. Ui2BackupServer.java (Daemon Server)
```java
{backup_server}
```

Please perform a comprehensive security and second-opinion code review covering:
1. Evaluation of Astra's Findings: Assess Astra's critical findings and minimum approval gates. Where do you strongly agree? Where do you disagree or consider Astra's recommendations over-engineered or missing key security threats?
2. Secret Containment & Plane Separation: Validate that sensitive credentials, pre-shared keys, private certificates, and raw configuration bytes cannot leak via `SemanticDeviationEngine`, `Ui2BackupServer`, error responses, or logs.
3. Path Traversal & Identifier Safety: Audit `RetentionPruningService`'s file deletion, `BackupController`'s artefact retrieval, and Check Point / PAN archive naming for path traversal vulnerabilities.
4. Audit Logging & Fail-Closed Guarantees: Audit the download and export flows to ensure fail-closed execution on audit log failure.
5. Denial of Service & Vault Exhaustion: Assess the 400 GiB storage quota, streaming byte limits, snapshot cadence, and concurrency controls.
6. Check Point & PAN Native Security: Assess remote command injection, SSH session lifetime, credential handling, and remote archive cleanup.

Provide your concrete verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by your specific findings, assessment of Astra's review, and concrete recommendations for the Product Owner and development team.
"""

    print("[*] Launching Fable (Claude) code review consultation via `claude -p`...")
    cmd = [
        "/Users/OzanDur/.local/bin/claude",
        "-p",
        prompt,
        "--tools",
        "",
        "--dangerously-skip-permissions",
    ]

    try:
        proc = subprocess.run(
            cmd,
            text=True,
            capture_output=True,
            check=True,
        )
        output = proc.stdout.strip()
        REVIEW_OUTPUT.write_text(output, encoding="utf-8")
        print(f"[+] Claude code review written successfully to: {REVIEW_OUTPUT}")
        print("--- STDOUT SNIPPET ---")
        print(output[:1000])
        return 0
    except subprocess.CalledProcessError as e:
        print(f"[!] Claude review failed with code {e.returncode}")
        if e.stderr:
            print(f"Error output:\n{e.stderr}")
        return e.returncode
    except FileNotFoundError:
        print("[!] Error: `/Users/OzanDur/.local/bin/claude` CLI not found on host.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
