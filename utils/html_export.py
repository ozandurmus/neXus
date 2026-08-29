import json
from pathlib import Path

from utils.config_ui import build_configuration_ui_payload
from utils.compliance_posture import build_compliance_posture
from utils.crypto_posture import build_crypto_posture
from utils.discovery_capability_ui import build_discovery_capability_payload
from utils.logger import info
from utils.project_plan import build_project_plan_payload


BASE_DIR = Path(__file__).resolve().parent.parent

UNIFIED_JSON = BASE_DIR / "output" / "unified.json"
OUTPUT_HTML = BASE_DIR / "output" / "index.html"

TEMPLATE_FILE = BASE_DIR / "templates" / "index.html"
STYLE_FILE = BASE_DIR / "static" / "style.css"
SCRIPT_FILE = BASE_DIR / "static" / "app.js"


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
):

    info(">>> GENERATING HTML")

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

    with unified_json.open("r", encoding="utf-8") as file:
        data = json.load(file)

    configuration_ui = build_configuration_ui_payload(
        config_result,
        checkpoint_config_result=checkpoint_config_result,
        workflow_context=workflow_context,
    )
    project_plan = build_project_plan_payload()
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
    compliance_ui = build_compliance_posture(
        configuration_ui, project_plan,
        data_root=compliance_data_root,
        crypto_facts_by_subject=crypto_facts_by_subject,
    )
    # 0.6.1C Phase 3: additive discovery/capability/coordinator observability.
    # Callers that have not yet wired Phase 4 collector integration may omit
    # all four arguments; the payload then renders an explicit empty state.
    discovery_ui = build_discovery_capability_payload(
        lifecycle_store=lifecycle_store,
        capability_store=capability_store,
        coordinator=coordinator,
        scheduler_policy=scheduler_policy,
    )

    template = read_text_file(template_file)
    css = read_text_file(style_file)
    javascript = read_text_file(script_file)

    html = template.replace(
        "/* __STYLE_PLACEHOLDER__ */",
        css,
    )

    html = html.replace(
        "/* __SCRIPT_PLACEHOLDER__ */",
        javascript,
    )

    html = html.replace(
        "__DATA_JSON_PLACEHOLDER__",
        _script_json(data),
    )

    html = html.replace(
        "__CONFIG_JSON_PLACEHOLDER__",
        _script_json(configuration_ui),
    )

    html = html.replace(
        "__PROJECT_PLAN_JSON_PLACEHOLDER__",
        _script_json(project_plan),
    )

    html = html.replace(
        "__COMPLIANCE_JSON_PLACEHOLDER__",
        _script_json(compliance_ui),
    )

    html = html.replace(
        "__CRYPTO_JSON_PLACEHOLDER__",
        _script_json(crypto_ui),
    )

    html = html.replace(
        "__DISCOVERY_JSON_PLACEHOLDER__",
        _script_json(discovery_ui),
    )

    output_html.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    output_html.write_text(
        html,
        encoding="utf-8",
    )

    info(f">>> HTML READY -> {output_html}")
