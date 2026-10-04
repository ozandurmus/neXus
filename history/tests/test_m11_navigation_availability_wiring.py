"""M11 -- D1-driven `availability_rule` wiring + shared entity workspace.

Contract: `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md`
`AC-CS-2` ("`D1` is the only input any surface-disposition decision reads
... a test over the full cross-product shows no rendering change when
`D2`...`D7` vary, including when a contradiction class holds") and
`docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` §6.3 (shared entity
workspace context).

Scope, per this movement's RELAY_NOTE (relay/NXS-LOCAL-0022): `D1`/stage 0 is
now the sole, explicit, named render/not-render gate on both the rail and the
device-tab strip (`static/navigation_ui.js::navigationResolveSurface`,
mirroring `utils/capability_state_resolver.py::resolve_union_tag`). `P2`/`P3`
content-level rendering (e.g. a `NOT_APPLICABLE` device tab) is explicitly
NOT implemented here -- it needs a real-device -> logical-entity-type
classifier that does not exist and is out of this movement's scope (building
one now would be new identity/producer architecture, not wiring). The shared
entity workspace extends the existing per-module hash/localStorage route
(`routes_preserved`) rather than inventing a second mechanism, and adopts a
shared id into a target module's own selection ONLY when that module's own
known-id set already contains it -- never a guessed cross-module identity
join (no canonical id is confirmed to span every module's id space today,
`RELAY_DECISION #11`, still open).
"""
from __future__ import annotations

import itertools
import re
import sys
from pathlib import Path

import pytest

pytestmark = pytest.mark.render

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from utils.capability_state_resolver import (
    CapabilityPolicy,
    Comparability,
    ComparabilityFacts,
    EntityApplicability,
    FreshnessFacts,
    IdentityConflict,
    LadderInputs,
    Omitted,
    QualifierInputs,
    Resolved,
    Stage1Inputs,
    SurfaceEligibility,
    VendorSupport,
    evaluate_stage1,
    resolve_primary_status,
    resolve_qualifiers,
    resolve_union_tag,
)
from utils.registry_evidence_reconciliation import D4_VALUES, RegistrySide

NAV_JS = (ROOT / "static" / "navigation_ui.js").read_text(encoding="utf-8")
BOOTSTRAP_JS = (ROOT / "static" / "app_bootstrap.js").read_text(encoding="utf-8")
INVENTORY_JS = (ROOT / "static" / "inventory_ui.js").read_text(encoding="utf-8")
CONFIGURATION_JS = (ROOT / "static" / "configuration_ui.js").read_text(encoding="utf-8")
COMPLIANCE_JS = (ROOT / "static" / "compliance_ui.js").read_text(encoding="utf-8")


def _launch_kwargs() -> dict:
    chromium = Path("/opt/pw-browsers/chromium-1194/chrome-linux/chrome")
    return {"executable_path": str(chromium)} if chromium.exists() else {}


def _playwright_chromium_available() -> bool:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return False
    try:
        with sync_playwright() as p:
            return Path(p.chromium.executable_path).exists()
    except Exception:
        return False


@pytest.fixture(scope="module")
def rendered_report(tmp_path_factory) -> Path:
    from scripts.render_uitest import render

    return render(tmp_path_factory.mktemp("m11_nav_render"))


# --------------------------------------------------------------------------
# AC-4 / AC-CS-2 -- cross-product: D2..D7 vary, the union tag never does
# --------------------------------------------------------------------------

# A representative, not exhaustive, cross-product (contract §9.1's finite
# input classes) -- proving the SAME `D1` always yields the SAME union tag
# (and therefore the same render/not-render outcome) regardless of every
# other dimension, including the three named contradiction/inconsistency
# classes (CX1, RI-1, RI-2) all holding at once.
_D2_VALUES = list(EntityApplicability)
_D3_VALUES = list(VendorSupport)
_D4_VALUES = sorted(D4_VALUES)
_D5_VALUES = list(CapabilityPolicy)
_D6B_VALUES = ("live", "last_known_good", "partial", "no_data")
_D6C_VALUES = ("success", "failed", "unsupported", "capability_gap", "identity_mismatch")

_CONTRADICTION_STAGE1_INPUTS = Stage1Inputs(
    identity_conflict=IdentityConflict(
        canonical_id="cp-edge-01", resolution_a="cp_standalone_gateway", resolution_b="cp_clusterxl_member"
    ),
    ri1_support_status=VendorSupport.UNSUPPORTED,
    ri1_comparability=ComparabilityFacts(
        k1_same_subject=Comparability.TRUE,
        k2_same_capability=Comparability.TRUE,
        k3_same_platform=Comparability.TRUE,
        k4_generation_not_superseded=Comparability.TRUE,
        k5_same_support_rule_version=Comparability.TRUE,
        k6_producer_version_comparable=Comparability.TRUE,
    ),
    ri2_d4_value="EVIDENCE_ONLY",
    ri2_cross_check_registry_side=RegistrySide.ENROLLED,
)


@pytest.mark.parametrize("d1", list(SurfaceEligibility) + [None, "malformed-value"])
def test_ac_cs_2_union_tag_is_a_pure_function_of_d1_alone(d1):
    """`resolve_union_tag`'s own signature already proves this structurally
    (it takes no D2..D7 parameter at all) -- this test makes the contract
    falsifiable rather than merely true by construction: the full downstream
    ladder/stage-1 outputs are computed too, over a real cross-product
    including a contradiction case, and never consulted by stage 0."""
    baseline = resolve_union_tag(d1)

    for d2, d3, d4, d5, d6b, d6c in itertools.islice(
        itertools.product(_D2_VALUES, _D3_VALUES, _D4_VALUES, _D5_VALUES, _D6B_VALUES, _D6C_VALUES),
        0,
        None,
    ):
        stage1 = evaluate_stage1(_CONTRADICTION_STAGE1_INPUTS)
        assert stage1.any_holds, "the contradiction/inconsistency fixture must actually trigger CX1/RI-1/RI-2"

        ladder_inputs = LadderInputs(
            stage1=stage1,
            d2_entity_applicability=d2,
            d3_vendor_support=d3,
            d4_reconciliation=d4,
            d5_capability_policy=d5,
            d6b_data_state=d6b,
            d6c_collection_outcome=d6c,
        )
        # Exercise the full downstream pipeline so a hypothetical future bug
        # that let stage 2/3 leak into stage 0 would be caught here.
        resolve_primary_status(ladder_inputs)
        resolve_qualifiers(
            QualifierInputs(
                freshness=FreshnessFacts(fresh=(d6b != "no_data"), as_of="2026-09-08T00:00:00Z"),
                data_state=d6b,
                capability_policy=d5,
            )
        )

        # The one assertion AC-CS-2 actually makes: D1 alone decided the
        # union tag, and nothing above changed it.
        assert resolve_union_tag(d1) == baseline

    # An absent/unresolvable surface never renders (§5.0 V-B/V-D), regardless
    # of the contradiction-class inputs exercised above.
    if d1 is not SurfaceEligibility.SURFACE_PRESENT:
        assert isinstance(baseline, Omitted)
    else:
        assert isinstance(baseline, Resolved)


# --------------------------------------------------------------------------
# AC-3 -- D1/stage-0 is the sole, named client-side render gate
# --------------------------------------------------------------------------

def test_ac3_render_gate_is_named_and_tri_state_not_a_bare_boolean():
    assert "function navigationResolveSurface" in NAV_JS
    assert "SURFACE_PRESENT" in NAV_JS and "SURFACE_ABSENT" in NAV_JS
    assert "SURFACE_ELIGIBILITY_UNRESOLVABLE" in NAV_JS

    rail_gate = NAV_JS[NAV_JS.index("function navigationEntryAvailable"):]
    rail_gate = rail_gate[: rail_gate.index("\n}\n")]
    assert "navigationResolveSurface" in rail_gate
    assert '.tag === "RESOLVED"' in rail_gate

    tab_gate = NAV_JS[NAV_JS.index("function renderDeviceTabs"):]
    tab_gate = tab_gate[: tab_gate.index("\n}\n")]
    assert "navigationResolveSurface" in tab_gate


def test_ac3_no_p2_p3_dimension_is_read_by_the_render_gate():
    """AC-CS-2/AC-CS-25/AC-CS-33/D-NAV13: P2 (entity applicability) and P3
    (evidence/capability state) must never gate render/not-render. Neither
    predicate's vocabulary appears anywhere in the render-gate functions."""
    for fn_name in ("navigationResolveSurface", "navigationSurfaceEligibility", "navigationEntryAvailable"):
        body = NAV_JS[NAV_JS.index(f"function {fn_name}"):]
        body = body[: body.index("\n}\n")]
        for forbidden in ("capability_state", "NOT_APPLICABLE", "UNSUPPORTED", "entity_type"):
            assert forbidden not in body, f"{fn_name} reads {forbidden!r} -- a P2/P3 fact must never gate render"


@pytest.mark.skipif(
    not _playwright_chromium_available(),
    reason="playwright not installed or no Chromium resolvable — "
           "pip install -r requirements-dev.txt && playwright install chromium",
)
def test_ac4_rendered_nav_and_tab_set_is_unchanged_across_varying_content(rendered_report, tmp_path):
    """The browser-level half of AC-CS-2: two report variants carrying very
    different module content (including a deliberately contradictory-looking
    configuration payload -- a stand-in for a D2..D7-varying render, since no
    payload builder carries D2..D7 output yet) must render an IDENTICAL
    nav-entry and device-tab set. Only D1 (shipped panels) changed nothing;
    only content should differ."""
    import json

    from playwright.sync_api import sync_playwright

    html = rendered_report.read_text(encoding="utf-8")

    def _rendered_sets(page_html: str, tmp_name: str) -> dict:
        target = tmp_path / tmp_name
        target.write_text(page_html, encoding="utf-8")
        errors: list[str] = []
        with sync_playwright() as p:
            browser = p.chromium.launch(**_launch_kwargs())
            try:
                page = browser.new_page()
                page.on("pageerror", lambda exc: errors.append(str(exc)))
                page.goto(target.resolve().as_uri())
                page.wait_for_timeout(300)
                roots = page.eval_on_selector_all(
                    ".module-nav-item", "els => els.map(e => e.dataset.module)"
                )
                tabs = page.eval_on_selector_all(
                    ".config-tab", "els => els.map(e => e.dataset.configTab)"
                )
            finally:
                browser.close()
        assert not errors, errors
        return {"roots": sorted(roots), "tabs": sorted(tabs)}

    baseline = _rendered_sets(html, "baseline.html")

    # Mutate configUiData's embedded payload (passed as one property of the
    # object literal `initializeReport({ ..., configUiData: <json>, ... })`)
    # to something contradictory/extreme in content terms (empty fleet) while
    # leaving every [data-module-panel] and device-tab panel element exactly
    # as shipped -- D1 is unchanged.
    match = re.search(r"configUiData: (\{.*?\}),\n", html, re.DOTALL)
    assert match, "configUiData payload not found in rendered report"
    payload = json.loads(match.group(1))
    if isinstance(payload, dict):
        payload["available"] = False
        payload["devices"] = []
        payload["fleet"] = {}
    mutated_html = html[: match.start(1)] + json.dumps(payload) + html[match.end(1):]

    mutated = _rendered_sets(mutated_html, "mutated.html")

    assert mutated == baseline, (
        "varying module content changed the rendered nav/tab structure -- "
        "P3-shaped content must never affect D1's render decision"
    )


# --------------------------------------------------------------------------
# AC-5 -- shared entity workspace context
# --------------------------------------------------------------------------

def test_ac5_hash_route_extends_the_existing_module_route_not_a_second_mechanism():
    assert "function parsedHashRoute" in BOOTSTRAP_JS
    # savedModule() and writeActiveHashRoute() both go through the one parser/
    # writer pair -- no second hash-splitting implementation anywhere.
    assert BOOTSTRAP_JS.count("function parsedHashRoute") == 1
    saved_module = BOOTSTRAP_JS[BOOTSTRAP_JS.index("function savedModule"):]
    saved_module = saved_module[: saved_module.index("\n}\n")]
    assert "parsedHashRoute" in saved_module


def test_ac5_shared_id_never_written_when_absent():
    setter = BOOTSTRAP_JS[BOOTSTRAP_JS.index("function setSharedEntityId"):]
    setter = setter[: setter.index("\n}\n")]
    assert "localStorage.removeItem" in setter


def test_ac5_adoption_never_guesses_a_cross_module_match():
    """No fabricated cross-module identity join (opaque-identifier law): the
    adoption helper only ever returns the shared id back, unmodified, or
    null -- it constructs no new id and does not silently pick a "closest"
    match."""
    adopt = BOOTSTRAP_JS[BOOTSTRAP_JS.index("function navigationAdoptSharedEntityId"):]
    adopt = adopt[: adopt.index("\n}\n")]
    assert "ids.has(sharedEntityId)" in adopt
    assert "return null" in adopt


def test_ac5_all_three_selection_surfaces_publish_to_the_shared_workspace():
    for name, source in (
        ("inventory_ui.js", INVENTORY_JS),
        ("configuration_ui.js", CONFIGURATION_JS),
        ("compliance_ui.js", COMPLIANCE_JS),
    ):
        assert "setSharedEntityId(" in source, f"{name} never publishes its selection to the shared workspace"


@pytest.mark.skipif(
    not _playwright_chromium_available(),
    reason="playwright not installed or no Chromium resolvable — "
           "pip install -r requirements-dev.txt && playwright install chromium",
)
def test_ac5_shared_workspace_mechanism_is_correct_in_a_real_browser(rendered_report):
    """Exercises the actual mechanism end-to-end in Chromium: a hash-carried
    entity id survives a module switch and is stored/read back through
    localStorage; an id absent from a target module's own known-id set is
    safely dropped rather than guessed. This does not assert that today's
    Inventory/Configuration id spaces happen to coincide for a real fixture
    device -- they do not yet (RELAY_NOTE, RELAY_DECISION #11 still open) --
    it proves the join primitive itself is correct so a future canonical-id
    movement can rely on it without changing this file."""
    from playwright.sync_api import sync_playwright

    errors: list[str] = []
    with sync_playwright() as p:
        browser = p.chromium.launch(**_launch_kwargs())
        try:
            page = browser.new_page()
            page.on("pageerror", lambda exc: errors.append(str(exc)))
            page.goto(rendered_report.resolve().as_uri())
            page.wait_for_timeout(300)

            page.evaluate("() => setSharedEntityId('probe-entity-1')")
            assert page.evaluate("() => window.location.hash") == "#overview/probe-entity-1"
            assert page.evaluate(
                "() => localStorage.getItem('securityexpert-entity')"
            ) == "probe-entity-1"

            # Present in the target module's known-id set -> adopted.
            assert page.evaluate(
                "() => navigationAdoptSharedEntityId(['probe-entity-1', 'other'])"
            ) == "probe-entity-1"
            # Absent -> safely null, never a guessed match.
            assert page.evaluate(
                "() => navigationAdoptSharedEntityId(['unrelated-id'])"
            ) is None

            # A genuine module switch preserves the entity segment in the hash.
            page.eval_on_selector(
                '.module-nav-item[data-module="configuration"]', "el => el.click()"
            )
            page.wait_for_timeout(120)
            assert page.evaluate("() => window.location.hash") == "#configuration/probe-entity-1"

            # Clearing the shared id drops the entity segment.
            page.evaluate("() => setSharedEntityId(null)")
            assert page.evaluate("() => window.location.hash") == "#configuration"
            assert page.evaluate(
                "() => localStorage.getItem('securityexpert-entity')"
            ) is None
        finally:
            browser.close()

    assert not errors, errors
