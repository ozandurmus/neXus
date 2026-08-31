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
from utils.inventory_exclusions import InventoryExclusionPolicyError, load_inventory_exclusions
from utils.inventory_exclusions_ui import build_inventory_exclusions_payload
from utils.logger import info
from utils.project_plan import build_project_plan_payload


BASE_DIR = Path(__file__).resolve().parent.parent

UNIFIED_JSON = BASE_DIR / "output" / "unified.json"
OUTPUT_HTML = BASE_DIR / "output" / "index.html"

TEMPLATE_FILE = BASE_DIR / "templates" / "index.html"
STYLE_FILE = BASE_DIR / "static" / "style.css"
SCRIPT_FILE = BASE_DIR / "static" / "app.js"

# html_render_performance (0.6.x polish): opt-in stage-timing switch. Reading
# this env var (rather than threading a profile= kwarg through every
# main.py call site) means a normal checkpoint is unaffected with zero code
# change, and a local diagnostic run enables it with one env var.
PROFILE_ENV_VAR = "SECURITYEXPERT_HTML_RENDER_PROFILE"


def read_text_file(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(f"Required file not found: {path}")

    return path.read_text(encoding="utf-8")


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
    # 0.7.1b: the compliance control-assignment policy lives in RuntimeRoot
    # (data/state/control_assignments.json). Fall back to the repo-local data
    # dir when a runtime root was not threaded through (diagnostic paths).
    compliance_data_root = Path(data_root) if data_root is not None else (repository_root / "data")
    template_file = repository_root / "templates" / "index.html"
    style_file = repository_root / "static" / "style.css"
    script_file = repository_root / "static" / "app.js"

    unified_json = Path(unified_json)
    output_html = Path(output_html)

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

    with _stage_timer(timings, "read_template_files"):
        template = read_text_file(template_file)
        css = read_text_file(style_file)
        javascript = read_text_file(script_file)

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
