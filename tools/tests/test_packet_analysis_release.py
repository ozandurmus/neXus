"""Release tooling for the self-owned packet_analysis module (V154); offline, no host or cluster access.

The 2026-10-05 ownership incident: a release path that moves claims can stall the job pipeline silently. The
packet_analysis module owns itself with no fallback, so every path here must keep it self-owned and must never
hand packet_analysis_run jobs to the general worker, which has no engine.
"""
import json
from pathlib import Path
import re
import sys

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/delivery'))
sys.path.insert(0, str(ROOT / 'tools/e2e'))
import module_deploy as deploy
import release_snapshot as snapshot
import hosta_preview_e2e as preview

IMAGE = 'registry.example.invalid/image@sha256:' + 'a' * 64
OLD = 'registry.example.invalid/image@sha256:' + 'b' * 64


def test_the_module_is_a_closed_release_target_with_its_own_role():
    assert deploy.validate_targets('packet-analysis') == ['packet-analysis']
    assert deploy.TARGETS['packet-analysis'] == 'packet-analysis'
    assert deploy.ROLES['packet-analysis'] == 'packet_analysis'
    assert deploy.NEVER_GENERAL == ('packet_analysis',)
    manifest = next(d for d in yaml.safe_load_all((ROOT / 'deploy/ui2/58-packet-analysis-deployment.yaml').read_text())
                    if d['kind'] == 'Deployment')
    container, = manifest['spec']['template']['spec']['containers']
    assert manifest['metadata']['name'] == 'ui2-packet-analysis'
    assert container['name'] == deploy.TARGETS['packet-analysis'], 'kubectl set image addresses the container by name'
    assert manifest['spec']['template']['metadata']['labels']['app.kubernetes.io/component'] == 'packet-analysis'


def test_the_drain_barrier_covers_only_packet_analysis_jobs(monkeypatch):
    queries = []
    monkeypatch.setattr(deploy, 'query', lambda sql: queries.append(sql) or 't')
    assert deploy.barrier('packet-analysis', 4)
    barrier = queries[-1]
    assert "ui2_job_module(j.capability_id)='packet_analysis'" in barrier
    assert "effective_owner='general'" not in barrier
    assert "where module='packet_analysis'" in barrier
    monkeypatch.setattr(deploy, 'query', lambda sql: queries.append(sql) or '7')
    assert deploy.request_drain('packet-analysis', 'synthetic-snapshot') == 7
    assert "where module='packet_analysis'" in queries[-1]


def test_no_path_hands_packet_analysis_to_the_general_worker(monkeypatch):
    queries = []
    monkeypatch.setattr(deploy, 'query', lambda sql: queries.append(sql) or 'packet_analysis')
    with pytest.raises(deploy.Blocked, match='never handed to the general worker'):
        deploy.reset_to_general('synthetic-snapshot', 'packet_analysis', 1)
    assert not queries


def test_first_release_applies_the_claim_and_manifest_and_keeps_ownership(monkeypatch):
    events = []
    def run(*args, input=None, timeout=660):
        events.append((args, input))
        if args[:5] == ('kubectl', '-n', 'ui2', 'get', 'deployment') and 'ui2-packet-analysis' in args:
            return ''
        if 'pods' in args:
            return json.dumps({'items': []})
        return ''
    monkeypatch.setattr(deploy, 'run', run)
    queries = []
    monkeypatch.setattr(deploy, 'query', lambda sql: queries.append(sql) or ('7' if 'returning drain_generation' in sql else 't'))
    monkeypatch.setattr(deploy, 'module_e2e', lambda commit=None: events.append((('e2e',), None)))
    deploy.replace('packet-analysis', IMAGE, 'synthetic-snapshot', 20)
    applied = [payload for args, payload in events if 'apply' in args]
    assert applied[0] == (ROOT / 'deploy/ui2/36-packet-analysis-pvc.yaml').read_text()
    assert 'kind: PriorityClass' in applied[1] and IMAGE in applied[1]
    assert applied[1].count('image: ') == 1
    assert any(args[-1] == 'packet-analysis=' + IMAGE for args, _ in events if 'set' in args)
    assert any('rollout' in args and 'deployment/ui2-packet-analysis' in args for args, _ in events)
    assert not any("effective_owner='general'" in sql for sql in queries)
    assert any("owner_instance is not null" in sql and "where module='packet_analysis'" in sql for sql in queries)


def test_first_release_does_not_wait_for_its_own_new_pod_to_stop(monkeypatch):
    # 2026-10-16: the first release created the deployment with the candidate image, then drained the module and
    # waited 900 s for that same new pod to disappear: "old owner did not stop; drain retained".
    events = []
    def run(*args, input=None, timeout=660):
        events.append((args, input))
        if args[:5] == ('kubectl', '-n', 'ui2', 'get', 'deployment') and 'ui2-packet-analysis' in args:
            return ''
        if 'pods' in args:
            return json.dumps({'items': [{'metadata': {'uid': 'new-pod-from-first-apply'}}]})
        return ''
    monkeypatch.setattr(deploy, 'run', run)
    queries = []
    monkeypatch.setattr(deploy, 'query', lambda sql: queries.append(sql) or ('7' if 'returning drain_generation' in sql else 't'))
    monkeypatch.setattr(deploy, 'module_e2e', lambda commit=None: None)
    monkeypatch.setattr(deploy.time, 'sleep', lambda seconds: (_ for _ in ()).throw(AssertionError('waited for a pod')))
    deploy.replace('packet-analysis', IMAGE, 'synthetic-snapshot', 20)
    assert not any('drain_requested=true' in sql.replace(' ', '') for sql in queries), 'no old owner, no drain'
    assert any("owner_instance is not null" in sql and "where module='packet_analysis'" in sql for sql in queries)


def test_a_failed_release_never_resets_the_module_to_general(monkeypatch):
    events = []
    def run(*args, input=None, timeout=660):
        events.append(args)
        if 'get' in args and 'deployment' in args:
            return json.dumps({'spec': {'template': {'spec': {'containers': [{'image': OLD}]}}}})
        if 'pods' in args:
            return json.dumps({'items': []})
        return ''
    monkeypatch.setattr(deploy, 'run', run)
    queries = []
    monkeypatch.setattr(deploy, 'query', lambda sql: queries.append(sql) or ('7' if 'returning drain_generation' in sql else 't'))
    monkeypatch.setattr(deploy, 'module_e2e', lambda commit=None: (_ for _ in ()).throw(deploy.Blocked('masked e2e failed')))
    with pytest.raises(deploy.Blocked, match='masked e2e'):
        deploy.replace('packet-analysis', IMAGE, 'synthetic-snapshot', 20)
    assert not any("effective_owner='general'" in sql for sql in queries)
    assert not any('--replicas=0' in args for args in events), 'the module keeps its pod; its jobs never move'
    assert not any('apply' in args for args in events), 'an existing deployment is not re-applied'


def test_full_rollback_keeps_packet_analysis_self_owned(monkeypatch):
    calls = []
    monkeypatch.setattr(snapshot, 'sql', lambda text: calls.append(text) or 't')
    monkeypatch.setattr(snapshot, 'get', lambda *a: {'items': []})
    monkeypatch.setattr(snapshot, 'run', lambda *a, **kw: None)
    snapshot.stop_split_runtime('synthetic-snapshot')
    ownership = next(sql for sql in calls if 'full_rollback_ownership' in sql)
    assert "effective_owner=case when module in ('packet_analysis') then module else 'general' end" in ownership
    assert "fallback_enabled=case when module='general' or module in ('packet_analysis') then fallback_enabled else true end" in ownership


def test_full_rollback_keeps_and_restarts_an_added_packet_analysis_workload(tmp_path, monkeypatch, capfd):
    sys.path.insert(0, str(ROOT / 'tools/tests'))
    import test_release_snapshot as helpers
    monkeypatch.setattr(helpers, 'rs', snapshot)
    directory, _ = helpers.capture(tmp_path, monkeypatch)
    added = helpers.workload(name='ui2-packet-analysis')
    calls = helpers.mock_cluster(monkeypatch, version='154', items=[helpers.workload(), added])
    monkeypatch.setattr(snapshot, 'runtime_state', lambda: {'compatible_runtime': True, 'modules': [], 'tasks': []})
    monkeypatch.setattr(snapshot, 'stop_split_runtime', lambda name: calls.append((('stop_split', name), None)))
    helpers.diff_mock(monkeypatch)
    snapshot.rollback(tmp_path, str(directory), True, True)
    output = capfd.readouterr().out
    assert 'Keep self-owned workload: ui2/Deployment/ui2-packet-analysis' in output
    assert not any('delete' in args and 'ui2-packet-analysis' in args for args, _ in calls)
    stopped = next(i for i, (args, _) in enumerate(calls) if 'stop_split' in args)
    restarted = next(i for i, (args, _) in enumerate(calls) if 'deployment/ui2-packet-analysis' in args and '--replicas=1' in args)
    assert stopped < restarted


@pytest.mark.parametrize('present', [True, False])
def test_split_rollback_restores_the_recorded_digest_or_leaves_the_module_running(monkeypatch, capsys, present):
    images = [{'kind': 'Deployment', 'workload': 'ui2-worker', 'container': 'worker', 'image': IMAGE}]
    if present:
        images.append({'kind': 'Deployment', 'workload': 'ui2-packet-analysis', 'container': 'packet-analysis', 'image': OLD})
    manifest = {'runtime': {'compatible_runtime': True, 'modules': [
                    {'module': 'packet_analysis', 'effective_owner': 'packet_analysis'}]}, 'images': {'ui2': images}}
    events = []
    monkeypatch.setattr(deploy, 'replace', lambda *a, **kw: events.append((a[0], a[1])))
    monkeypatch.setattr(deploy, 'return_policy_to_general', lambda *a: events.append(('fallback',)))
    snapshot.split_rollback(manifest, 'synthetic-snapshot', True)
    output = capsys.readouterr().out
    assert ('fallback',) not in events
    if present:
        assert ('packet-analysis', OLD) in events
        assert 'self-owned; never general' in output
    else:
        assert all(event[0] != 'packet-analysis' for event in events)
        assert 'left running with its own ownership (never general)' in output


def test_a_snapshot_records_the_packet_analysis_digest_and_module_row(monkeypatch):
    items = [{'kind': 'Deployment', 'metadata': {'name': 'ui2-packet-analysis'},
              'spec': {'template': {'spec': {'containers': [{'name': 'packet-analysis', 'image': IMAGE}]}}}}]
    assert snapshot.pin_images(items, []) == [
        {'kind': 'Deployment', 'workload': 'ui2-packet-analysis', 'container': 'packet-analysis', 'image': IMAGE}]
    assert deploy.rollback_image({'runtime': {'compatible_runtime': True},
                                  'images': {'ui2': snapshot.pin_images(items, [])}}, 'packet-analysis') == IMAGE
    rows = json.dumps([{'module': 'packet_analysis', 'effective_owner': 'packet_analysis', 'fallback_enabled': False}])
    monkeypatch.setattr(snapshot, 'sql', lambda text: 't' if 'to_regclass' in text else rows if 'module_runtime_control' in text else '[]')
    assert snapshot.runtime_state()['modules'][0]['effective_owner'] == 'packet_analysis'


def yaml_load(repo, path):
    documents = [d for d in yaml.safe_load_all((repo / path).read_text()) if d]
    return documents[0] if len(documents) == 1 else {'kind': 'List', 'items': documents}


def test_preview_keeps_the_module_self_owned_and_exercises_its_claim(monkeypatch):
    reset = []
    monkeypatch.setattr(preview, 'k', lambda *a, **kw: reset.append(kw.get('input')) or '')
    preview.reset_preview_runtime()
    assert "effective_owner=CASE WHEN module='packet_analysis' THEN module ELSE 'general' END" in reset[0]
    assert 'fallback_enabled=(module<>\'packet_analysis\')' in reset[0]
    assert "WHEN capability = 'packet_analysis_run' THEN 'packet_analysis'" in preview.job_module_mapping(ROOT)
    statements = []
    monkeypatch.setattr(preview, 'load', lambda repo, path: yaml_load(repo, path))
    monkeypatch.setattr(preview, 'k', lambda *a, **kw: statements.append(kw.get('input') or '') or
                        ('CLAIM_PASS' if 'DO $$' in (kw.get('input') or '') else 't'))
    workers = preview.worker_templates(ROOT)
    preview.assert_module_claims(ROOT, workers)
    assert "module IN ('general','packet_analysis','policy')" in statements[0]
    claim = next(sql for sql in statements if "capability TEXT := 'packet_analysis_run'" in sql)
    assert "'packet_analysis_case'" in claim
    statements.clear()
    preview.assert_module_claims(ROOT, [w for w in workers if w['component'] != 'packet-analysis'])
    assert not any("capability TEXT := 'packet_analysis_run'" in sql for sql in statements)
    assert "module IN ('general','policy')" in statements[0]


def test_default_ship_targets_include_the_analysis_worker_last():
    for path, pattern in (("tools/delivery/hosta_deploy.sh", r'^TARGETS="([^"]+)"$'),
                          ("deploy/ui2-image-build/run_build.sh", r'NEXUS_DEPLOY_TARGETS:-([^}]+)\}')):
        targets = re.search(pattern, (ROOT / path).read_text(), re.M)[1].split()
        assert deploy.validate_targets(" ".join(targets)) == targets
        assert targets[-1] == 'packet-analysis', path
