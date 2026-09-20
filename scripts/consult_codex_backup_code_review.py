#!/usr/bin/env python3
"""Consultation Script for neXus Backup & Recovery Engine Code Review (Codex / Astra Reviewer).
Directly executes the installed host CLI tool `/Users/OzanDur/.local/bin/codex exec`.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
REVIEW_OUTPUT = BASE_DIR / "docs" / "design" / "CODEX_BACKUP_CODE_REVIEW.md"


def read_file_safely(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else f"File not found: {path}"


def main() -> int:
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

    prompt = f"""You are Astra, the Lead Software Architect for the neXus project.
In this single-turn review consultation, you are performing an architectural, resilience, and code review of the neXus Backup & Recovery Engine.
Do NOT execute tool calls or follow cold-start reading protocols. Provide your expert review directly in your response text.

### Background & Product Owner Directives:
1. Product Plane Separation: "Configuration evidence is not a recovery backup." The recovery plane (Plane 3) is write-once, encrypted, egress-denied, and separate from the evidence plane.
2. Capacity & Retention Directives:
   - Dedicated backup storage budget: 400 GiB.
   - Retention period: 14 days for standard daily backups (PO directive: "1 month is too long").
   - Snapshot cadence & depth: Depth 4 retained snapshots (PO directive: "Snapshot quantity will be high").
   - Deletions write append-only cryptographic tombstones.
3. Supported Vendors: Check Point Gaia snapshots (`add snapshot`, disk space validation, pre-flight safety) and Palo Alto Networks configuration & device-state XML exports.
4. Security & API: Opaque UUID artefact IDs over HTTP (never raw filesystem paths), audited downloads with fail-closed semantics.

Key Code Artifacts for Review:

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

Please perform a comprehensive software architecture and code review covering:
1. Architectural Decoupling & Plane Separation: Assess whether the recovery plane is strictly isolated from evidence collection and discovery. Verify that raw backup blobs containing sensitive credentials never cross into browser rendering, reporting, or unencrypted storage.
2. PO Capacity & Retention Policy: Review the 400 GiB storage budget, 14-day standard backup retention, and depth 4 snapshot pruning. Assess if storage headroom exhaustion and pruning edge-cases are properly handled.
3. Multi-Vendor Native Execution:
   - Check Point: Review `CheckPointSnapshotExecutor` for volume space pre-check, timeout handling, non-blocking execution, and command gate compliance.
   - Palo Alto: Review `PaloAltoBackupExecutor` for named config and device-state XML handling.
4. Diff Engine & Deviation Tracking: Review `SemanticDeviationEngine` for deterministic secret masking during diff calculation, line normalization, and semantic categorization.
5. API Security & File Traversal: Review `BackupController` for opaque UUID identification, path traversal prevention, and download audit logging.
6. Operational Robustness & Daemon Lifecycle: Review `Ui2BackupServer` for clean HTTP lifecycle, bounded JSON payloads, and error handling.

Provide your concrete verdict: [APPROVED] or [APPROVED WITH RECOMMENDATIONS] or [REJECTED], followed by your specific findings and actionable recommendations.
"""

    print("[*] Launching Codex code review consultation via `codex exec`...")
    cmd = [
        "/Users/OzanDur/.local/bin/codex",
        "exec",
        "--sandbox",
        "read-only",
        "-c",
        "model_reasoning_effort=high",
        "-o",
        str(REVIEW_OUTPUT),
        "-",
    ]

    try:
        proc = subprocess.run(
            cmd,
            input=prompt,
            text=True,
            capture_output=True,
            check=True,
        )
        print(f"[+] Codex code review written successfully to: {REVIEW_OUTPUT}")
        if proc.stdout:
            print("--- STDOUT SNIPPET ---")
            print(proc.stdout[:1000])
        return 0
    except subprocess.CalledProcessError as e:
        print(f"[!] Codex review failed with code {e.returncode}")
        if e.stderr:
            print(f"Error output:\n{e.stderr}")
        return e.returncode
    except FileNotFoundError:
        print("[!] Error: `/Users/OzanDur/.local/bin/codex` CLI not found on host.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
