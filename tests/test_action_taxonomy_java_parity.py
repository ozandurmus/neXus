"""action_taxonomy_java_parity — Python/Java action-class vocabulary gate.

`utils/action_taxonomy.py` is AGENTS.md's declared single source of truth
for the product's action classes. UI 2.0's Java side independently declares
the same vocabulary as an enum
(`ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/
ActionClass.java`), documented (`UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §1.3)
as "the action classes ... ported to Java verbatim". Nothing enforced that
claim before this test: a class added, reordered, or re-flagged on either
side would drift silently.

This test parses the Java source as text (regex only -- no JVM, Gradle, or
build) and compares it against `utils.action_taxonomy.ACTION_CLASSES`.

What "agree" means here, and every property pinned:

  - **Member set, both directions.** The set of enum constant names on the
    Java side must equal the set of `ACTION_CLASS_*` module attribute names
    on the Python side, exactly. A class added on only one side fails.
  - **Declaration order.** Both sides declare the classes in the same
    ascending-severity order (Java's enum order; Python's `ACTION_CLASSES`
    tuple order / `.level` ordering). A reorder on either side fails.
  - **`id` string.** The persisted id (Java `ActionClass.id()` /
    Python `ActionClass.id`) must match exactly for each corresponding
    member. This is the value that round-trips through job records, so a
    mismatch here is a silent persistence-format split.
  - **`console_submittable` flag.** Java's `consoleSubmittable` must equal
    Python's `console_submittable` for each corresponding member.

Properties that exist on only one side (not pinned here, by design --
noted rather than silently ignored):

  - Python's `permitted` (may this class execute anywhere in the product
    today, vs. `console_submittable`'s narrower "may the console submit it
    synchronously") has no Java counterpart. The Java docstring explains
    `consoleSubmittable()` is "never a job-admission or claim-time gate by
    itself" -- the job-engine module(s) that would carry an equivalent to
    `permitted` are out of scope for this file and were not located as part
    of this test.
  - Python's `label`, `refusal_code` and `why` (human-readable / refusal
    metadata) have no Java counterpart in this enum.
  - Java's numeric ordinal is implicit (declaration order only); Python's
    `level` is an explicit field (including the non-integer `1.5` for
    CLASS_1B). Ordering is compared; the literal numeric `level` value is
    not, since Java never states it.

Fail-closed by construction: if the Java enum cannot be located, or the
regex parse yields zero members on either side, the test FAILS with a
named message -- it never passes vacuously.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

pytestmark = pytest.mark.runtime_platform

ROOT = Path(__file__).resolve().parents[1]

JAVA_ACTION_CLASS_PATH = (
    ROOT
    / "ui2"
    / "platform-core"
    / "src"
    / "main"
    / "java"
    / "com"
    / "securityexpert"
    / "nexus"
    / "ui2"
    / "platform"
    / "ActionClass.java"
)

# Matches lines like:  CLASS_0_READ("read", true),
_MEMBER_RE = re.compile(
    r'(?P<name>CLASS_\w+)\s*\(\s*"(?P<id>[^"]+)"\s*,\s*(?P<submittable>true|false)\s*\)',
)


def _parse_java_action_class(path: Path) -> list[dict]:
    """Parse the Java ``ActionClass`` enum's members in declaration order.

    Returns a list of ``{"name": str, "id": str, "console_submittable": bool}``
    dicts. Raises ``AssertionError`` (not a silent empty list) if the file is
    missing, the ``enum ActionClass`` block cannot be located, or it parses
    to zero members -- this function must never let the caller mistake "found
    nothing" for "found an empty enum".
    """
    assert path.is_file(), (
        f"Java ActionClass source not found at {path} -- cannot verify "
        "Python/Java action-class parity. This is a hard failure, not a "
        "vacuous pass: if the file moved, update JAVA_ACTION_CLASS_PATH."
    )
    text = path.read_text(encoding="utf-8")

    enum_match = re.search(r"enum\s+ActionClass\s*\{(?P<body>.*?)\n\s*(?:private|public)\b", text, re.DOTALL)
    assert enum_match, (
        "Could not locate 'enum ActionClass { ... }' member block in "
        f"{path}. Refusing to report a pass with zero parsed members."
    )
    body = enum_match.group("body")

    members = []
    seen_names = set()
    for m in _MEMBER_RE.finditer(body):
        name = m.group("name")
        if name in seen_names:
            continue
        seen_names.add(name)
        members.append(
            {
                "name": name,
                "id": m.group("id"),
                "console_submittable": m.group("submittable") == "true",
            }
        )

    assert members, (
        f"Parsed zero ActionClass members from {path}'s enum body. This "
        "would make the parity test pass vacuously -- treating it as a "
        "hard failure instead."
    )
    return members


def test_java_action_class_parses_to_nonzero_members():
    members = _parse_java_action_class(JAVA_ACTION_CLASS_PATH)
    assert len(members) >= 1
    # Sanity: every member name looks like a CLASS_* constant.
    for m in members:
        assert m["name"].startswith("CLASS_")


def test_python_and_java_action_classes_agree():
    from utils import action_taxonomy as tax

    java_members = _parse_java_action_class(JAVA_ACTION_CLASS_PATH)
    java_names = [m["name"] for m in java_members]

    python_members = [
        {
            "name": attr,
            "id": ac.id,
            "console_submittable": ac.console_submittable,
        }
        for attr, ac in (
            (name, getattr(tax, name))
            for name in dir(tax)
            if name.startswith("CLASS_") and name.isupper()
        )
    ]
    # Preserve ACTION_CLASSES' own declared order rather than dir()'s
    # alphabetical order, so "declaration order" is compared meaningfully.
    order_index = {ac.id: i for i, ac in enumerate(tax.ACTION_CLASSES)}
    python_members.sort(key=lambda m: order_index[m["id"]])
    python_names = [m["name"] for m in python_members]

    assert python_members, (
        "Parsed zero CLASS_* members from utils.action_taxonomy -- would "
        "make this test pass vacuously; treating it as a hard failure."
    )

    # 1. Member-name set must match exactly, in both directions.
    java_name_set = set(java_names)
    python_name_set = set(python_names)
    only_in_java = java_name_set - python_name_set
    only_in_python = python_name_set - java_name_set
    assert not only_in_java and not only_in_python, (
        "Action-class member sets diverge between Java and Python.\n"
        f"  Only in Java ActionClass.java: {sorted(only_in_java) or '(none)'}\n"
        f"  Only in Python action_taxonomy.py: {sorted(only_in_python) or '(none)'}\n"
        f"  Java order: {java_names}\n"
        f"  Python order: {python_names}"
    )

    # 2. Declaration order must match.
    assert java_names == python_names, (
        "Action-class declaration order diverges between Java and Python.\n"
        f"  Java order: {java_names}\n"
        f"  Python order: {python_names}"
    )

    # 3. id and console_submittable must match per corresponding member.
    java_by_name = {m["name"]: m for m in java_members}
    python_by_name = {m["name"]: m for m in python_members}
    mismatches = []
    for name in python_names:
        j = java_by_name[name]
        p = python_by_name[name]
        if j["id"] != p["id"]:
            mismatches.append(
                f"{name}: id mismatch -- Java={j['id']!r} Python={p['id']!r}"
            )
        if j["console_submittable"] != p["console_submittable"]:
            mismatches.append(
                f"{name}: console_submittable mismatch -- "
                f"Java={j['console_submittable']!r} "
                f"Python={p['console_submittable']!r}"
            )
    assert not mismatches, "Action-class field mismatch(es):\n" + "\n".join(mismatches)
