"""codebase_modularization (frontend) — AC-3 static dependency-order check.

``static/app.js`` (one flat 4,905-line script, 173 implicit top-level globals)
was split into responsibility-owned files (nine as of OP.0c) that ``utils.html_export``
concatenates, in a fixed order, back into the same single inline ``<script>``
(D-MOD1: no bundler, no ES modules, no build step). Nothing at runtime changed
— the browser still executes one flat top-level script.

The one guarantee the flat file could never give and the split now must: a
module file references no top-level identifier that a *later*-loading module
owns. A wrong composition order otherwise surfaces only as a runtime
``ReferenceError`` partway through report initialization — exactly the
silent-partial-failure mode ``frontend_rendering_boundary`` flagged for CSP
violations. This test is the D-MOD2 tooling that replaces an explicit
``window.SecurityExpert`` namespace: cheap, and it does the review work a human
re-read of a large mechanical diff otherwise would.

Deliberately regex/AST-lite (no JS-parser dependency), matching this repo's
existing source-string UI checks.
"""
import re
from pathlib import Path

import pytest

from utils.html_export import (
    SCRIPT_MODULE_FILENAMES,
    compose_report_script,
)

pytestmark = pytest.mark.render

ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / "static"

# Consts declared in templates/index.html's own <script>, before the composed
# body — every module may read them regardless of order.
PAGE_LEVEL_CONSTS = {
    "rawData", "configUiData", "complianceUiData", "cryptoUiData",
    "projectPlanData", "discoveryUiData", "exclusionsUiData",
    "failoverReadinessData",
}

# The cross-module navigation dispatcher (D-MOD5 audit: "switchModule/savedModule
# ... the cross-module navigation dispatcher every module's render entrypoint is
# reached through"). Its owner, app_bootstrap.js, deliberately loads LAST because
# it also calls into every module's render entrypoint. A feature module's calls
# to switchModule are all inside deferred event-listener callbacks that fire long
# after every file has loaded, so a forward reference to these two names is safe
# and expected — this is the D-MOD2 "genuinely public surface" the ordering check
# is otherwise the substitute for a window.SecurityExpert namespace.
NAVIGATION_PUBLIC_SURFACE = {"switchModule", "savedModule"}

_DEF_RE = re.compile(
    r"^(?:function\s+([A-Za-z_$][\w$]*)\s*\(|"
    r"(?:let|const|var)\s+([A-Za-z_$][\w$]*)\s*[=;])",
    re.MULTILINE,
)
_TOKEN_RE = re.compile(r"[A-Za-z_$][\w$]*")
_LINE_COMMENT_RE = re.compile(r"//[^\n]*")
_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)


def _strip_comments(source):
    """Drop // and /* */ comments before the reference scan — a comment that
    names another module's function is documentation, not a load-time
    dependency. (AST-lite: does not touch strings; no current module has an
    identifier-shaped string literal that trips the ordering check.)"""
    return _LINE_COMMENT_RE.sub("", _BLOCK_COMMENT_RE.sub("", source))


def _module_source(name):
    return (STATIC / name).read_text(encoding="utf-8")


def _defined_identifiers(source):
    out = set()
    for m in _DEF_RE.finditer(source):
        out.add(m.group(1) or m.group(2))
    return out


def test_app_js_is_split_and_removed():
    assert not (STATIC / "app.js").exists(), "static/app.js must not exist after the split (AC-1)"
    for name in SCRIPT_MODULE_FILENAMES:
        assert (STATIC / name).is_file(), f"missing split module file: {name}"


def test_composed_script_line_multiset_matches_concatenation():
    # compose_report_script is exactly what run_html_export inlines; guard the
    # join semantics (one newline between files, order = SCRIPT_MODULE_FILENAMES).
    expected = "\n".join(_module_source(n) for n in SCRIPT_MODULE_FILENAMES)
    assert compose_report_script() == expected


def test_no_module_references_an_identifier_a_later_module_owns():
    order = list(SCRIPT_MODULE_FILENAMES)
    sources = {n: _module_source(n) for n in order}
    defined = {n: _defined_identifiers(sources[n]) for n in order}

    # owner[identifier] = index of the (first) module that declares it.
    owner = {}
    for idx, name in enumerate(order):
        for ident in defined[name]:
            owner.setdefault(ident, idx)

    violations = []
    for idx, name in enumerate(order):
        own = defined[name]
        referenced = (
            set(_TOKEN_RE.findall(_strip_comments(sources[name])))
            - own - PAGE_LEVEL_CONSTS - NAVIGATION_PUBLIC_SURFACE
        )
        for ident in referenced:
            if ident in owner and owner[ident] > idx:
                violations.append(
                    f"{name} (loads #{idx}) references '{ident}', "
                    f"owned by {order[owner[ident]]} (loads #{owner[ident]})"
                )

    assert not violations, "composition-order dependency violations:\n" + "\n".join(sorted(violations))


def test_every_top_level_function_survived_the_split():
    # AC-1 completeness, from the other direction: the full pre-split function
    # inventory (recorded in the frozen contract's audit) is 173 top-level
    # `function` declarations; the eight files together must still hold that
    # many, none dropped, none duplicated. CON.1 C1-2/C1-3 added five more —
    # app_core.js's reportMode() accessor, app_bootstrap.js's
    # initializeReport() entry point, and three rebuildX() functions
    # (inventory_ui.js, configuration_ui.js, compliance_ui.js) that recompute
    # payload-derived state initializeReport() now calls explicitly instead
    # of each module computing it once at load time against the still-empty
    # default payload — so the floor was 178. OP.0c added
    # failover_readiness_ui.js's three functions (failoverVerdictTone,
    # failoverCheckStatusTone, renderFailoverModule) — 181.
    all_defs = []
    per_file = {}
    for name in SCRIPT_MODULE_FILENAMES:
        src = _module_source(name)
        fns = re.findall(r"^function\s+([A-Za-z_$][\w$]*)\s*\(", src, re.MULTILINE)
        per_file[name] = fns
        all_defs.extend(fns)

    assert len(all_defs) == 181, f"expected 181 top-level functions, found {len(all_defs)}"
    dupes = sorted({f for f in all_defs if all_defs.count(f) > 1})
    assert not dupes, f"functions defined in more than one module file: {dupes}"
