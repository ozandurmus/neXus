"""Offline ownership recovery regressions. No cluster or device access."""
import json
from pathlib import Path
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/delivery'))
sys.path.insert(0, str(ROOT / 'tools/e2e'))
import module_deploy as deploy
import release_snapshot as snapshot
import hosta_preview_e2e as preview

IMAGE = 'registry.example.invalid/image@sha256:' + 'a' * 64


def test_corrective_migration_preserves_applied_seed_and_resets_only_absent_owners():
    sql = (ROOT / 'ui2/service/src/main/resources/db/migration/V129__module_ownership_safety.sql').read_text()
    assert "effective_owner SET DEFAULT 'general'" in sql
    assert 'fallback_enabled SET DEFAULT TRUE' in sql
    assert "effective_owner='general',fallback_enabled=TRUE,generation=generation+1" in sql
    assert 'owner.module=m.effective_owner' in sql
    assert "owner.owner_heartbeat_at>now()-interval '60 seconds'" in sql
    assert 'owner.owner_instance IS NOT NULL' in sql
    assert "(effective_owner<>'general' OR NOT fallback_enabled)" in sql
    assert 'DELETE FROM' not in sql and 'CREATE TABLE' not in sql


def test_policy_creation_applies_required_objects_with_candidate_image(monkeypatch):
    calls = []
    monkeypatch.setattr(deploy, 'run', lambda *a, **kw: calls.append((a, kw)) or '')
    monkeypatch.setattr(deploy, 'prepare_policy_public_trust', lambda: calls.append(('trust', {})))
    assert deploy.ensure_policy(IMAGE) is None
    import yaml
    objects = list(yaml.safe_load_all(calls[-1][1]['input']))
    assert {item['kind'] for item in objects} == {'ServiceAccount', 'Deployment', 'NetworkPolicy'}
    obj = next(item for item in objects if item['kind'] == 'Deployment')
    assert obj['spec']['replicas'] == 0
    assert obj['spec']['template']['spec']['containers'][0]['image'] == IMAGE
    assert calls[1][0] == 'trust'


def test_policy_creation_does_not_reapply_an_existing_workload(monkeypatch):
    obj = {'spec': {'template': {'spec': {'containers': [{'image': IMAGE}]}}}}
    calls = []
    monkeypatch.setattr(deploy, 'run', lambda *a, **kw: calls.append(a) or json.dumps(obj))
    assert deploy.ensure_policy(IMAGE) == IMAGE
    assert len(calls) == 1


def test_handover_rechecks_liveness_and_generation_in_transaction(monkeypatch):
    calls = []
    monkeypatch.setattr(deploy, 'query', lambda sql: calls.append(sql) or '2')
    deploy.handover_policy(7, 'synthetic-snapshot', 'synthetic-approval')
    sql = calls[0]
    assert 'pg_advisory_xact_lock' in sql
    assert "owner_heartbeat_at>now()-interval '60 seconds'" in sql
    assert 'owner_instance is not null' in sql
    assert 'drain_generation=7' in sql and 'drain_ack_generation=drain_generation' in sql
    assert 'owner_instance=null' not in sql
    monkeypatch.setattr(deploy, 'query', lambda sql: '')
    with pytest.raises(deploy.Blocked, match='fence or heartbeat'):
        deploy.handover_policy(7, 'synthetic-snapshot', 'synthetic-approval')


def test_missing_heartbeat_cannot_handover(monkeypatch):
    now = [0]
    monkeypatch.setattr(deploy, 'query', lambda sql: 'f')
    with pytest.raises(deploy.Blocked, match='heartbeat'):
        deploy.wait_heartbeat('policy', 4, clock=lambda: now[0], sleep=lambda delay: now.__setitem__(0, now[0] + delay))


@pytest.mark.parametrize('failure', ['set', 'rollout', 'heartbeat', 'e2e', 'handover'])
def test_failed_policy_rollout_stops_pod_then_returns_ownership(monkeypatch, failure):
    events = []
    def run(*args, **kwargs):
        events.append(args)
        if failure in {'set', 'rollout'} and failure in args:
            raise deploy.Blocked('synthetic rollout failure')
        if 'pods' in args:
            return '{"items":[]}'
        return ''
    monkeypatch.setattr(deploy, 'run', run)
    monkeypatch.setattr(deploy, 'query', lambda sql: 't')
    monkeypatch.setattr(deploy, 'ensure_policy', lambda image: IMAGE)
    monkeypatch.setattr(deploy, 'request_drain', lambda *a: events.append(('drain',)) or 1)
    monkeypatch.setattr(deploy, 'wait_drain', lambda *a: None)
    def checkpoint(name):
        events.append((name,))
        if name == failure:
            raise deploy.Blocked('synthetic rollout failure')
    monkeypatch.setattr(deploy, 'wait_heartbeat', lambda *a: checkpoint('heartbeat'))
    monkeypatch.setattr(deploy, 'handover_policy', lambda *a: checkpoint('handover'))
    monkeypatch.setattr(deploy, 'module_e2e', lambda *a: checkpoint('e2e'))
    monkeypatch.setattr(deploy, 'reset_to_general', lambda *a: events.append(('general',)))
    with pytest.raises(deploy.Blocked, match='synthetic'):
        deploy.replace('policy', IMAGE, 'synthetic-snapshot', authorization='synthetic-approval')
    stopped = next(i for i, event in enumerate(events) if '--replicas=0' in event)
    deleted = next(i for i, event in enumerate(events) if '--for=delete' in event)
    assert stopped < deleted < events.index(('general',))


def test_recovery_preserves_quarantine_and_unrelated_work(monkeypatch):
    calls = []
    monkeypatch.setattr(deploy, 'query', lambda sql: calls.append(sql) or 'policy')
    deploy.reset_to_general('synthetic-snapshot', 'policy', 7)
    assert "ui2_job_module(capability_id)='policy'" in calls[0]
    assert "owner_role='policy'" in calls[0]
    assert 'QUARANTINED' in calls[0] and 'DELETE' not in calls[0]
    monkeypatch.setattr(deploy, 'query', lambda sql: '')
    with pytest.raises(deploy.Blocked, match='uncertain'):
        deploy.reset_to_general('synthetic-snapshot', 'policy', 7)


def test_kubectl_failure_is_diagnosable_and_masked(monkeypatch):
    error = 'Error from server (Forbidden): User "synthetic-principal" cannot create resource "deployments" at https://example.invalid/192.0.2.1 token=synthetic-secret'
    monkeypatch.setattr(deploy.subprocess, 'run', lambda *a, **kw: subprocess.CompletedProcess(a, 1, '', error))
    with pytest.raises(deploy.Blocked) as blocked:
        deploy.run('kubectl', 'apply', '-f', '-')
    assert 'Forbidden' in str(blocked.value) and 'cannot create resource' in str(blocked.value)
    assert all(value not in str(blocked.value) for value in ['synthetic-principal', 'example.invalid', '192.0.2.1', 'synthetic-secret'])


def test_split_rollback_dry_run_and_apply_use_exact_snapshot_images(monkeypatch, capsys):
    manifest = {'runtime': {'compatible_runtime': True}, 'images': {'ui2': [
        {'kind': 'Deployment', 'workload': 'ui2-worker', 'container': 'worker', 'image': IMAGE}]}}
    calls = []
    monkeypatch.setattr(deploy, 'replace', lambda *a, **kw: calls.append((a, kw)))
    snapshot.split_rollback(manifest, 'synthetic-snapshot', False)
    assert not calls
    assert IMAGE in capsys.readouterr().out
    snapshot.split_rollback(manifest, 'synthetic-snapshot', True)
    assert calls[0][0] == ('worker', IMAGE, 'synthetic-snapshot')


def test_full_rollback_stops_all_owners_before_general_reset(monkeypatch):
    calls = []
    def sql(text):
        calls.append(('sql', text))
        return 't'
    obj = {'metadata': {'name': 'ui2-policy'}, 'spec': {'selector': {'matchLabels': {'component': 'policy'}},
           'template': {'spec': {'containers': [{'args': ['worker', 'policy']}]}}}}
    monkeypatch.setattr(snapshot, 'sql', sql)
    monkeypatch.setattr(snapshot, 'get', lambda *a: {'items': [obj]})
    monkeypatch.setattr(snapshot, 'run', lambda *a, **kw: calls.append(a))
    snapshot.stop_split_runtime('synthetic-snapshot')
    assert 'drain_requested=true' in calls[0][1]
    stopped = next(i for i, event in enumerate(calls) if '--replicas=0' in event)
    deleted = next(i for i, event in enumerate(calls) if '--for=delete' in event)
    restored = next(i for i, event in enumerate(calls) if event[0] == 'sql' and "effective_owner='general'" in event[1])
    assert stopped < deleted < restored
    assert all('DELETE FROM' not in event[-1] for event in calls)


def test_preview_uses_real_claim_sql_and_rolls_back_each_capability(monkeypatch):
    import yaml
    workers = []
    for path in ('52-worker-deployment.yaml', '57-policy-deployment.yaml'):
        obj = next(d for d in yaml.safe_load_all((ROOT / 'deploy/ui2' / path).read_text()) if d['kind'] == 'Deployment')
        workers.append({'deployment': obj})
    queries = []
    monkeypatch.setattr(preview, 'k', lambda *a, **kw: queries.append(kw['input']) or ('CLAIM_PASS' if 'DO $$' in kw['input'] else 't'))
    preview.assert_module_claims(ROOT, workers)
    assertions = [sql for sql in queries if 'PREVIEW_CLAIM_DENIED' in sql]
    assert len(assertions) > 20
    assert all('UPDATE jobs' in sql and 'ROLLBACK;' in sql and 'owner_control AS MATERIALIZED' in sql for sql in assertions)
    assert any("cp_policy_collect" in sql for sql in assertions)
    assert 'owner_heartbeat_at' in queries[0]
    assert "effective_owner='policy'" in queries[1]
    assert all('{0}' not in sql and '{1}' not in sql and '{2}' not in sql for sql in assertions)
    with pytest.raises(RuntimeError, match='required claim owner'):
        preview.assert_module_claims(ROOT, workers[:1])


def test_split_fallback_snapshot_stops_added_policy_without_temporary_handover(monkeypatch):
    manifest = {'runtime': {'compatible_runtime': True, 'modules': [{'module': 'policy', 'effective_owner': 'general'}]},
                'images': {'ui2': [{'kind': 'Deployment', 'workload': 'ui2-worker', 'container': 'worker', 'image': IMAGE}]}}
    events = []
    monkeypatch.setattr(deploy, 'replace', lambda *a, **kw: events.append(('replace', a[0])))
    monkeypatch.setattr(deploy, 'return_policy_to_general', lambda *a: events.append(('fallback',)))
    monkeypatch.setattr(deploy, 'module_e2e', lambda: events.append(('e2e',)))
    snapshot.split_rollback(manifest, 'synthetic-snapshot', True)
    assert events == [('replace', 'worker'), ('fallback',), ('e2e',)]


def test_full_rollback_preserves_uncertain_work_without_stopping_pods(monkeypatch):
    calls = []
    monkeypatch.setattr(snapshot, 'sql', lambda sql: calls.append(sql) or 'f')
    times = iter([0, 601])
    monkeypatch.setattr(snapshot.time, 'monotonic', lambda: next(times))
    monkeypatch.setattr(snapshot, 'run', lambda *a, **kw: pytest.fail('must not stop owners with uncertain work'))
    with pytest.raises(snapshot.SafeReleaseError, match='uncertain work retained'):
        snapshot.stop_split_runtime('synthetic-snapshot')
    assert "effective_owner='general'" not in ''.join(calls)


def test_general_replacement_installs_fallback_with_image_and_preserves_jvm_options(monkeypatch):
    calls = []
    worker = {'name': 'worker', 'env': [{'name': 'JAVA_TOOL_OPTIONS',
              'value': '-Dui2.db.pool.maximum-pool-size=6 -Dui2.worker.claim-policy-fallback=false'}]}
    def run(*args, **kwargs):
        calls.append(args)
        return json.dumps({'spec': {'template': {'spec': {'containers': [worker]}}}}) if 'get' in args else ''
    monkeypatch.setattr(deploy, 'run', run)
    deploy.replace_image('worker', IMAGE)
    obj = json.loads(calls[-1][-1])['spec']['template']['spec']['containers'][0]
    assert obj['image'] == IMAGE
    assert obj['env'][0]['value'] == '-Dui2.db.pool.maximum-pool-size=6 -Dui2.worker.claim-policy-fallback=true'
    assert 'patch' in calls[-1]
