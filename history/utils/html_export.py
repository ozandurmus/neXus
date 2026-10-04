import json
import os
import re
import time
from contextlib import contextmanager
from functools import lru_cache
from pathlib import Path

from utils.config_ui import build_configuration_ui_payload
from utils.compliance_history import append_run, load_history, summarise_overview
from utils.compliance_posture import build_compliance_posture
from utils.crypto_posture import build_crypto_posture
from utils.discovery_capability_ui import build_discovery_capability_payload
from utils.failover_readiness_ui import build_failover_readiness_payload
from utils.inventory_exclusions import InventoryExclusionPolicyError, load_inventory_exclusions
from utils.inventory_exclusions_ui import build_inventory_exclusions_payload
from utils.logger import info
from utils.project_plan import build_project_plan_payload


BASE_DIR = Path(__file__).resolve().parent.parent

UNIFIED_JSON = BASE_DIR / "output" / "unified.json"
OUTPUT_HTML = BASE_DIR / "output" / "index.html"

TEMPLATE_FILE = BASE_DIR / "templates" / "index.html"
STYLE_FILE = BASE_DIR / "static" / "style.css"

# codebase_modularization (frontend): static/app.js was split into eight
# responsibility-owned source files. They are concatenated here, in this fixed
# dependency order (D-MOD5), into the exact same single inline <script> the
# report has always shipped — no bundler, no ES modules, no build step (D-MOD1).
# The browser executes byte-for-byte the same flat top-level script; only the
# on-disk source layout changed. tests/test_frontend_module_composition.py
# statically enforces that no file references an identifier a later file owns.
SCRIPT_MODULE_FILENAMES = (
    "app_core.js",
    # NAV.1: the navigation model + its renderers load second — after
    # app_core.js's escaping/formatting helpers, before every feature module
    # and before app_bootstrap.js, which derives its route universe from the
    # model (docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md D-NAV7/D-NAV8).
    "navigation_ui.js",
    "inventory_ui.js",
    "configuration_ui.js",
    "compliance_ui.js",
    "discovery_ui.js",
    "failover_readiness_ui.js",
    "project_plan_ui.js",
    "overview_ui.js",
    "app_bootstrap.js",
)
SCRIPT_FILES = tuple(BASE_DIR / "static" / name for name in SCRIPT_MODULE_FILENAMES)

# CON.1 C1-2: the console (console/app.py) and the exporter must compose the
# identical ordered module list from one implementation, so a drift between
# what the report runs and what the console runs is impossible by
# construction. MODULE_ORDER / compose_modules are the names that contract
# specifies; SCRIPT_MODULE_FILENAMES / compose_report_script (below) are the
# pre-existing names kept for the tests already bound to them.
MODULE_ORDER = SCRIPT_MODULE_FILENAMES

# html_render_performance (0.6.x polish): opt-in stage-timing switch. Reading
# this env var (rather than threading a profile= kwarg through every
# main.py call site) means a normal checkpoint is unaffected with zero code
# change, and a local diagnostic run enables it with one env var.
PROFILE_ENV_VAR = "SECURITYEXPERT_HTML_RENDER_PROFILE"


def read_text_file(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(f"Required file not found: {path}")

    return path.read_text(encoding="utf-8")


def compose_modules(order: tuple[str, ...] = MODULE_ORDER, repository_root=None) -> str:
    """The concatenated module source for ``order`` (default ``MODULE_ORDER``),
    joined with a single newline — the exact same join ``run_html_export``
    performs at ``__SCRIPT_PLACEHOLDER__``. ``console/app.py`` (CON.1) calls
    this to serve ``/assets/app.js`` so the two surfaces cannot drift apart.
    """
    base = Path(repository_root) if repository_root is not None else BASE_DIR
    return "\n".join(read_text_file(base / "static" / name) for name in order)


def compose_report_script(repository_root=None) -> str:
    """The single inline ``<script>`` body — the eight module source files
    (``SCRIPT_MODULE_FILENAMES``) joined in composition order exactly as
    ``run_html_export`` inlines them at ``__SCRIPT_PLACEHOLDER__``.

    codebase_modularization (frontend): before the split this was one
    ``static/app.js`` read; test harnesses that inspect the report script as a
    single string call this instead.
    """
    return compose_modules(repository_root=repository_root)


def _script_json(value) -> str:
    # Avoid a literal </script> sequence if a device/object name unexpectedly
    # contains one. The resulting JSON remains valid JavaScript data.
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
    ).replace("</", "<\\/")


@contextmanager
def _stage_timer(timings: list[tuple[str, float]] | None, name: str):
    """Record ``(name, elapsed_seconds)`` into ``timings`` when profiling is
    enabled (``timings`` is a list); a true no-op (no ``perf_counter()`` call
    at all) when ``timings`` is ``None`` -- html_render_performance AC-1 /
    privacy invariant 3: provably inert when disabled.
    """
    if timings is None:
        yield
        return
    start = time.perf_counter()
    try:
        yield
    finally:
        timings.append((name, time.perf_counter() - start))


def _log_profile_report(timings: list[tuple[str, float]]) -> None:
    total = sum(duration for _, duration in timings)
    info(">>> HTML RENDER PROFILE (opt-in; stage name / seconds / % of total)")
    for name, duration in timings:
        pct = (duration / total * 100.0) if total else 0.0
        info(f"    {name:<40} {duration:8.4f}s  {pct:5.1f}%")
    info(f"    {'TOTAL':<40} {total:8.4f}s")


@lru_cache(maxsize=32)
def _sentinel_pattern(keys: tuple[str, ...]) -> re.Pattern[str]:
    # Longest keys first so a sentinel can never be shadowed by a prefix of a
    # longer one (see _fill_template's docstring). Every real call site uses
    # the same fixed sentinel set, so this compiled pattern is built once and
    # reused across every render rather than re-sorted/re-escaped/re-compiled
    # per call; the small lru_cache also keeps ad hoc key sets (e.g. tests)
    # cheap without unbounded growth.
    ordered = sorted(keys, key=len, reverse=True)
    return re.compile("|".join(re.escape(key) for key in ordered))


def _fill_template(template: str, replacements: dict[str, str]) -> str:
    """Substitute every sentinel in a single left-to-right pass so inserted
    content is never re-scanned.

    A chain of ``str.replace()`` calls is unsafe here: an earlier payload can
    legitimately contain the literal text of a later sentinel -- the embedded
    project plan carries a backlog note that mentions
    ``__CRYPTO_JSON_PLACEHOLDER__`` -- and the later ``replace()`` would then
    splice JSON into the middle of an already-emitted JS string literal, whose
    stray quotes break the whole inline <script>.
    """
    pattern = _sentinel_pattern(tuple(replacements))
    return pattern.sub(lambda match: replacements[match.group(0)], template)


def build_report_payloads(
    unified_json=UNIFIED_JSON,
    *,
    config_result=None,
    checkpoint_config_result=None,
    workflow_context=None,
    repository_root=None,
    lifecycle_store=None,
    capability_store=None,
    coordinator=None,
    scheduler_policy=None,
    data_root=None,
    timings: list[tuple[str, float]] | None = None,
    failover_readiness_report=None,
) -> dict:
    """Build the eight payload dicts the report embeds and the console (CON.1)
    serves at ``/api/payloads`` — ``rawData``, ``configUiData``,
    ``complianceUiData``, ``cryptoUiData``, ``projectPlanData``,
    ``discoveryUiData``, ``exclusionsUiData``, ``failoverReadinessData``. Pure
    computation, no write side
    effect: ``run_html_export`` is the only caller that appends a trend-ledger
    record, and only for a full checkpoint (``record_checkpoint=True``).

    C1-4: this is the *single* implementation both surfaces call, with the
    same inputs, so a drift between what the report renders and what the
    console serves is impossible by construction rather than by discipline.
    """
    repository_root = Path(repository_root) if repository_root is not None else BASE_DIR
    # 0.7.1b: the compliance control-assignment policy lives in RuntimeRoot
    # (data/state/control_assignments.json). Fall back to the repo-local data
    # dir when a runtime root was not threaded through (diagnostic paths).
    compliance_data_root = Path(data_root) if data_root is not None else (repository_root / "data")
    unified_json = Path(unified_json)

    if not unified_json.exists():
        raise FileNotFoundError(
            f"Unified inventory file not found: {unified_json}"
        )

    with _stage_timer(timings, "read_unified_json"):
        with unified_json.open("r", encoding="utf-8") as file:
            data = json.load(file)

    with _stage_timer(timings, "build_configuration_ui_payload"):
        configuration_ui = build_configuration_ui_payload(
            config_result,
            checkpoint_config_result=checkpoint_config_result,
            workflow_context=workflow_context,
        )
    with _stage_timer(timings, "build_project_plan_payload"):
        project_plan = build_project_plan_payload()
    with _stage_timer(timings, "build_crypto_posture"):
        crypto_ui = build_crypto_posture(
            config_result,
            checkpoint_config_result=checkpoint_config_result,
            repository_root=repository_root,
            configuration_ui=configuration_ui,
        )
    # CE.1 fast-follow — expose the already-normalised, privacy-reviewed 0.7.0
    # crypto facts to the user-check engine, keyed by the shared subject id.
    crypto_facts_by_subject = {
        str(subject.get("subject_id")): dict(subject.get("facts") or {})
        for subject in (crypto_ui.get("subjects") or [])
        if subject.get("subject_id")
    }
    with _stage_timer(timings, "load_compliance_history"):
        # 0.7.5 — the trend ledger (read on every render; written only on a full
        # checkpoint, below). Fail-safe: a missing/corrupt ledger -> [].
        compliance_history = load_history(compliance_data_root)
    with _stage_timer(timings, "build_compliance_posture"):
        compliance_ui = build_compliance_posture(
            configuration_ui, project_plan,
            data_root=compliance_data_root,
            crypto_facts_by_subject=crypto_facts_by_subject,
            # CE.1 fast-follow — the merged inventory already loaded above, so a user
            # check can assert over unified.interfaces / unified.routes. Also covers
            # --render-only (it rebuilds from the same unified.json).
            unified_inventory=data if isinstance(data, list) else None,
            history=compliance_history,
        )
    # 0.6.1C Phase 3: additive discovery/capability/coordinator observability.
    # Callers that have not yet wired Phase 4 collector integration may omit
    # all four arguments; the payload then renders an explicit empty state.
    with _stage_timer(timings, "build_discovery_capability_payload"):
        discovery_ui = build_discovery_capability_payload(
            lifecycle_store=lifecycle_store,
            capability_store=capability_store,
            coordinator=coordinator,
            scheduler_policy=scheduler_policy,
        )
    # inventory_exclusions_ui (0.6.1C Inventory UX, phase 1): read-only
    # projection of the same local policy cp_runner.py already gates
    # collection with. A malformed local policy file fails CLOSED for actual
    # collection (see utils/inventory_exclusions.py) -- that guarantee is
    # untouched -- but must never crash report *rendering*, so this path
    # degrades to the payload's own explicit empty state instead.
    with _stage_timer(timings, "build_inventory_exclusions_payload"):
        try:
            exclusion_policy = load_inventory_exclusions(compliance_data_root)
        except InventoryExclusionPolicyError:
            exclusion_policy = None
        exclusions_ui = build_inventory_exclusions_payload(exclusion_policy)

    # OP.0c: pure projection over utils.failover.compute_ha_readiness, fed the
    # same already-loaded unified/config-telemetry data as every other
    # builder above -- no extra file read, no duplicated verdict logic.
    # OP.0b S7.5 console closure: an explicit HA preflight invocation has
    # already evaluated readiness once (fresh snapshot included) and passes
    # that canonical record here; the projection then re-evaluates nothing.
    # Every other caller leaves it None and gets the stored-telemetry basis
    # exactly as before -- report generation never contacts a device.
    with _stage_timer(timings, "build_failover_readiness_payload"):
        failover_readiness_ui = build_failover_readiness_payload(
            data if isinstance(data, list) else None,
            checkpoint_config_result=checkpoint_config_result,
            config_result=config_result,
            readiness_report=failover_readiness_report,
        )

    return {
        "rawData": data,
        "configUiData": configuration_ui,
        "complianceUiData": compliance_ui,
        "cryptoUiData": crypto_ui,
        "projectPlanData": project_plan,
        "discoveryUiData": discovery_ui,
        "exclusionsUiData": exclusions_ui,
        "failoverReadinessData": failover_readiness_ui,
    }


def run_html_export(
    unified_json=UNIFIED_JSON,
    output_html=OUTPUT_HTML,
    *,
    config_result=None,
    checkpoint_config_result=None,
    workflow_context=None,
    repository_root=None,
    lifecycle_store=None,
    capability_store=None,
    coordinator=None,
    scheduler_policy=None,
    data_root=None,
    record_checkpoint=False,
    run_id=None,
    profile=None,
    failover_readiness_report=None,
):

    info(">>> GENERATING HTML")

    # html_render_performance (0.6.x polish): opt-in stage timing only, off by
    # default. profile=None (the default) falls back to PROFILE_ENV_VAR so a
    # normal main.py checkpoint is unaffected with zero call-site change;
    # profile=True/False always overrides the env var explicitly (used by
    # scripts/render_uitest.py --profile and tests). Disabled -> timings stays
    # None -> _stage_timer is a true no-op. Produces evidence only, never an
    # optimization.
    if profile is None:
        profile = os.environ.get(PROFILE_ENV_VAR, "") not in ("", "0", "false", "False")
    timings: list[tuple[str, float]] | None = [] if profile else None

    repository_root = Path(repository_root) if repository_root is not None else BASE_DIR
    compliance_data_root = Path(data_root) if data_root is not None else (repository_root / "data")
    template_file = repository_root / "templates" / "index.html"
    style_file = repository_root / "static" / "style.css"

    output_html = Path(output_html)

    payloads = build_report_payloads(
        unified_json,
        config_result=config_result,
        checkpoint_config_result=checkpoint_config_result,
        workflow_context=workflow_context,
        repository_root=repository_root,
        lifecycle_store=lifecycle_store,
        capability_store=capability_store,
        coordinator=coordinator,
        scheduler_policy=scheduler_policy,
        data_root=data_root,
        timings=timings,
        failover_readiness_report=failover_readiness_report,
    )
    data = payloads["rawData"]
    configuration_ui = payloads["configUiData"]
    compliance_ui = payloads["complianceUiData"]
    crypto_ui = payloads["cryptoUiData"]
    project_plan = payloads["projectPlanData"]
    discovery_ui = payloads["discoveryUiData"]
    exclusions_ui = payloads["exclusionsUiData"]
    failover_readiness_ui = payloads["failoverReadinessData"]

    with _stage_timer(timings, "read_template_files"):
        template = read_text_file(template_file)
        css = read_text_file(style_file)
        # D-MOD1 / C1-2: one composer, two consumers — this is the exact join
        # console/app.py performs for /assets/app.js.
        javascript = compose_modules(repository_root=repository_root)

    # html_render_optimization: split out from the "fill_template" stage,
    # which used to wrap both this JSON serialization and the regex
    # substitution below undistinguished -- the html_render_performance
    # profiling report could not say which one actually dominated the
    # ~40-46% it measured. Building the replacements dict here as its own
    # named stage answers that.
    with _stage_timer(timings, "build_json_replacements"):
        replacements = {
            "/* __STYLE_PLACEHOLDER__ */": css,
            "/* __SCRIPT_PLACEHOLDER__ */": javascript,
            "__DATA_JSON_PLACEHOLDER__": _script_json(data),
            "__CONFIG_JSON_PLACEHOLDER__": _script_json(configuration_ui),
            "__PROJECT_PLAN_JSON_PLACEHOLDER__": _script_json(project_plan),
            "__COMPLIANCE_JSON_PLACEHOLDER__": _script_json(compliance_ui),
            "__CRYPTO_JSON_PLACEHOLDER__": _script_json(crypto_ui),
            "__DISCOVERY_JSON_PLACEHOLDER__": _script_json(discovery_ui),
            "__EXCLUSIONS_JSON_PLACEHOLDER__": _script_json(exclusions_ui),
            "__FAILOVER_READINESS_JSON_PLACEHOLDER__": _script_json(failover_readiness_ui),
        }

    # One pass. Do NOT chain str.replace() here (see _fill_template): a payload
    # such as the project plan legitimately contains the literal text of another
    # sentinel and a second replace() would corrupt the emitted <script>.
    with _stage_timer(timings, "fill_template"):
        html = _fill_template(template, replacements)

    with _stage_timer(timings, "write_output_html"):
        output_html.parent.mkdir(
            parents=True,
            exist_ok=True,
        )

        output_html.write_text(
            html,
            encoding="utf-8",
        )

    # 0.7.5 — append one aggregate roll-up record to the trend ledger. Only a
    # full-integration checkpoint records; --render-only / --only / diagnostic
    # renders pass record_checkpoint=False. Best-effort inside append_run.
    if record_checkpoint and compliance_ui.get("available"):
        append_run(
            compliance_data_root,
            summarise_overview(
                compliance_ui.get("compliance_overview") or {},
                run_id=run_id,
                schema_version=compliance_ui.get("schema_version"),
            ),
        )

    info(f">>> HTML READY -> {output_html}")

    if timings is not None:
        _log_profile_report(timings)
