#!/usr/bin/env python3
"""Count Java test results of a pretest run and re-run ONLY allowlisted flaky tests (jt.sh helper).

Usage: jt_java_results.py <ui2-dir> [gradle-exit-code]

Reads <module>/build/test-results/test/*.xml under <ui2-dir>. A failed test class is re-run once on its own ONLY when
the class is listed ("java <Class>") in the flaky allowlist (NEXUS_FLAKY_ALLOWLIST, default flaky_allowlist.txt beside
this file) and is not security-relevant; every other failure stays a failure on the first red. A test that passes on its
re-run is reported FLAKY and noted in NEXUS_FLAKY_LOG. More than 5 failures are never re-run.
Fails closed: no results at all with a non-zero Gradle exit is a failure, not "0 failures".
Gradle runs without a daemon (2026-11-24 review: parallel worktrees left 3-hour daemons that starved the Mac);
NEXUS_GRADLE overrides the wrapper path for tests.
"""
import glob
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
SECURITY_RE = re.compile(
    r"(security|authz|authori[sz]|authenticat|credential|redact|sanitiz|privacy|secret|crypto|tls|leak|masking|"
    r"maskedprojection|admission|lease|permission|csrf|session|password|token)", re.IGNORECASE)


def allowlist_path() -> str:
    return os.environ.get("NEXUS_FLAKY_ALLOWLIST") or os.path.join(HERE, "flaky_allowlist.txt")


def load_allowlist(path: str) -> set:
    entries = set()
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                parts = line.split("#", 1)[0].split()
                if len(parts) >= 2 and parts[0] == "java":
                    entries.add(parts[1])
    except OSError:
        pass
    return entries


def is_security(name: str) -> bool:
    return bool(SECURITY_RE.search(name))


def rerunnable(cls: str, allow: set) -> bool:
    """A class may be re-run only when listed (full or simple name) and not security-relevant."""
    return (cls in allow or cls.rsplit(".", 1)[-1] in allow) and not is_security(cls)


def scan(paths):
    total, fails = 0, []
    for path in paths:
        root = ET.parse(path).getroot()
        total += int(root.get("tests"))
        for case in root.iter("testcase"):
            for node in list(case.findall("failure")) + list(case.findall("error")):
                fails.append((path.split("/")[0], case.get("classname"), case.get("name"), (node.get("message") or "")[:200]))
    return total, fails


def main(argv) -> int:
    root = argv[1] if len(argv) > 1 else "."
    gradle_rc = int(argv[2]) if len(argv) > 2 and argv[2].lstrip("-").isdigit() else 0
    os.chdir(root)
    allow = load_allowlist(allowlist_path())
    gradle = os.environ.get("NEXUS_GRADLE", "./gradlew")
    total, fails = scan(glob.glob("*/build/test-results/test/*.xml"))
    flaky, refused = [], []
    if 0 < len(fails) <= 5:
        for mod, cls in sorted({(m, c) for m, c, _, _ in fails}):
            if not rerunnable(cls, allow):
                refused.append(cls)
                continue
            xml = f"{mod}/build/test-results/test/TEST-{cls}.xml"
            try:
                os.remove(xml)
            except OSError:
                pass
            subprocess.run([gradle, "--no-daemon", f":{mod}:test", "--tests", cls, "-q"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if os.path.exists(xml):
                _, again = scan([xml])
                failed_again = {(c, n) for _, c, n, _ in again}
                orig = [f for f in fails if f[0] == mod and f[1] == cls]
                fails = [f for f in fails if f not in orig] + again
                flaky += [f for f in orig if (f[1], f[2]) not in failed_again]
    if flaky:
        try:
            log_path = os.environ.get("NEXUS_FLAKY_LOG") or os.path.expanduser("~/Codo/ops-scripts/flaky-tests.log")
            with open(log_path, "a", encoding="utf-8") as log:
                for m, c, n, msg in flaky:
                    log.write(f"{time.strftime('%F %T')} {os.path.basename(os.path.dirname(os.getcwd()))} {m} {c}.{n} | {msg[:160]}\n")
        except OSError:
            pass
    if total == 0 and not fails and gradle_rc != 0:
        fails = [("-", "gradle", f"exit {gradle_rc}", "no test results were written")]   # fail closed, counted as a failure
    print(f"java tests {total} failures {len(fails)}")
    for m, c, n, msg in fails[:30]:
        print(f"FAIL {c.split('.')[-1]}.{n} | {msg}")
    for m, c, n, msg in flaky:
        print(f"FLAKY {c.split('.')[-1]}.{n} passed on re-run")
    if refused:
        print("NOTE not re-run (not on the flaky allowlist or security-relevant): " + " ".join(sorted(set(r.split('.')[-1] for r in refused))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
