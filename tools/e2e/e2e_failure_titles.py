"""Names the failing Playwright tests of an e2e Job log without letting any page text leave.

A failed e2e Job's log can hold page text, and the preview database is a copy of live data, so the log itself is
never reported. Test titles and spec file:line are written by us and carry no data. This module reads a log and keeps
exactly two kinds of line:

  * a failed-test header of the Playwright list reporter ("✘  10 [chromium] › e2e/screens.spec.ts:113:3 › title"),
    and only when its spec path, line and every title segment match a title literal in the spec sources of the
    shipped tree (a template literal's placeholders may only stand for a short run of plain characters);
  * the summary counts ("2 failed", "10 passed (1.2m)", "1 flaky"), digits only.

Everything else (error messages, locator dumps, URLs, addresses, a title that is not in the spec files) is dropped.
The output lines are the contract read by tools/e2e/hosta_preview_e2e.sh and tools/delivery/standalone_orchestrate.py.
"""
from pathlib import Path
import re

SPEC_DIR = Path("ui2/frontend/e2e")
MAX_TITLES = 20
TITLE_LINE = "PREVIEW E2E FAILED TEST: "
COUNTS_LINE = "PREVIEW E2E COUNTS: "
# One output line: spec path, line, one space, the validated title.
TITLE_LINE_PATTERN = r"PREVIEW E2E FAILED TEST: e2e/[A-Za-z0-9_.-]+\.spec\.ts:\d+ [^\r\n]{1,300}"
COUNTS_LINE_PATTERN = r"PREVIEW E2E COUNTS: failed=(\d+|\?) passed=(\d+|\?) flaky=(\d+|\?)"

_ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
_CALL = re.compile(r"\btest(?P<describe>\.describe(?:\.(?:serial|parallel|configure))?)?"
                   r"(?:\.(?:only|fixme|skip|fail|slow))?\s*\(\s*(?P<q>[\"'`])(?P<body>(?:\\.|(?!(?P=q)).)*)(?P=q)", re.S)
_PLACEHOLDER = re.compile(r"\$\{[^}]*\}")
# A placeholder in a title template is one of our constants (a screen id, a vendor key): a short run of plain
# characters. A value with more shape than that is not accepted as part of a title.
_PLACEHOLDER_VALUE = r"[A-Za-z0-9 _./:+-]{0,80}"
_HEADER = re.compile(
    r"^\s*(?P<mark>[✘✗×xX✓✔√])\s+\d+\s+(?:\[[^\]]{1,40}\]\s+›\s+)?"
    r"(?P<spec>(?:e2e/)?[A-Za-z0-9_.-]+\.spec\.ts):(?P<line>\d+):\d+\s+›\s+(?P<title>.+?)\s*$")
_SUFFIX = re.compile(r"\s+\((?:retry #\d+|\d+(?:\.\d+)?(?:ms|s|m|h))\)$")
_COUNT = re.compile(r"^\s{0,8}(?P<n>\d{1,6}) (?P<kind>failed|passed|flaky)(?: \(\d+(?:\.\d+)?(?:ms|s|m|h)\))?\s*$")
_ADDRESS = re.compile(r"\d{1,3}(?:\.\d{1,3}){3}|[0-9a-fA-F:]*::[0-9a-fA-F:]*|\b[0-9a-f]{8}-[0-9a-f]{4}-")
_FAILED_MARKS = "✘✗×xX"


def _pattern(body, quote):
    if quote == "`":
        parts = _PLACEHOLDER.split(body)
        return _PLACEHOLDER_VALUE.join(re.escape(p) for p in parts)
    return re.escape(re.sub(r"\\(.)", r"\1", body))


def spec_titles(tree):
    """{ 'e2e/name.spec.ts': {'tests': {line: [pattern, ...]}, 'describes': [pattern, ...]} } from the shipped tree."""
    result = {}
    directory = Path(tree) / SPEC_DIR
    if not directory.is_dir():
        return result
    for path in sorted(directory.glob("*.spec.ts")):
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        entry = {"tests": {}, "describes": []}
        for match in _CALL.finditer(text):
            pattern = _pattern(match.group("body"), match.group("q"))
            if match.group("describe"):
                entry["describes"].append(pattern)
            else:
                line = text.count("\n", 0, match.start()) + 1
                entry["tests"].setdefault(line, []).append(pattern)
        result["e2e/" + path.name] = entry
    return result


def _valid(titles, spec, line, title):
    entry = titles.get(spec)
    if entry is None or not title or len(title) > 300 or _ADDRESS.search(title):
        return False
    tests = entry["tests"].get(line)
    if not tests:
        return False
    prefix = "(?:(?:" + "|".join(entry["describes"]) + ") › ){0,3}" if entry["describes"] else ""
    return re.fullmatch(prefix + "(?:" + "|".join(tests) + ")", title) is not None


def extract(log, tree):
    """(failed, counts): failed is [(spec, line, title)], at most MAX_TITLES; counts is {'failed'|'passed'|'flaky': int}."""
    titles = spec_titles(tree)
    failed, passed, counts = {}, set(), {}
    for raw in _ANSI.sub("", log or "").splitlines():
        raw = raw.rstrip("\r")
        count = _COUNT.match(raw)
        if count:
            counts[count.group("kind")] = int(count.group("n"))
            continue
        header = _HEADER.match(raw)
        if not header:
            continue
        title = header.group("title")
        while True:
            stripped = _SUFFIX.sub("", title)
            if stripped == title:
                break
            title = stripped
        spec = header.group("spec")
        spec = spec if spec.startswith("e2e/") else "e2e/" + spec
        line = int(header.group("line"))
        if not _valid(titles, spec, line, title):
            continue
        key = (spec, line, title)
        if header.group("mark") in _FAILED_MARKS:
            failed.setdefault(key, None)
        else:
            passed.add(key)
    # A test that failed on its first attempt and passed on a retry is flaky, not failed.
    return [key for key in failed if key not in passed][:MAX_TITLES], counts


def report_lines(log, tree):
    """The lines to print: one per failed test (title only), then the counts. Empty when the log names nothing."""
    failed, counts = extract(log, tree)
    lines = [f"{TITLE_LINE}{spec}:{line} {title}" for spec, line, title in failed]
    if lines or counts:
        value = lambda name: str(counts[name]) if name in counts else "?"
        lines.append(f"{COUNTS_LINE}failed={value('failed')} passed={value('passed')} flaky={value('flaky')}")
    return lines


def first_failed(lines):
    """The first failed-test line's 'spec:line title' (for a failure reason), or None."""
    for line in lines:
        if re.fullmatch(TITLE_LINE_PATTERN, line):
            return line[len(TITLE_LINE):]
    return None
