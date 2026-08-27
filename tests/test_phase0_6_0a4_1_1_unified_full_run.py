from pathlib import Path


def test_full_run_source_invokes_pan_config_and_preserves_separate_support_bundles():
    source = Path("main.py").read_text(encoding="utf-8")
    assert 'args.only == "all" and not args.skip_config' in source
    assert "orchestration_run_id=run_ctx.run_id" in source
    assert "limit=_pan_config_limit_for_mode()" in source
    assert "Inventory support:" in source
    assert "Config support:" in source
    assert "--skip-config" in source


def test_run_context_declares_pan_config_as_a_first_class_stage():
    from utils.run_context import CORE_STAGES
    assert "pan_config" in CORE_STAGES
    assert CORE_STAGES.index("pan_config") > CORE_STAGES.index("panorama")
    assert CORE_STAGES.index("pan_config") < CORE_STAGES.index("snapshot")


def test_pan_config_collector_can_reuse_parent_orchestration_run_id():
    import inspect
    from configuration.panorama_config_collector import run_panorama_config_evidence
    signature = inspect.signature(run_panorama_config_evidence)
    assert "orchestration_run_id" in signature.parameters
