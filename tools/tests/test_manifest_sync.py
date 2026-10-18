"""A release applies the committed manifest of the policy, service, configuration and compliance deployments.

The 2026-10-18 incident: a release added the fingerprint-key env, secret mount and 8Gi memory to the policy manifest,
but the tool only swapped the image of an existing deployment, so the new pod ran the old manifest and crashed. The
manifest is now rendered with the candidate image, diffed before the drain and applied after it. Offline; no cluster.
"""
import json
from pathlib import Path
import sys

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/delivery'))
sys.path.insert(0, str(ROOT / 'tools/e2e'))
import module_deploy as deploy

IMAGE = 'registry.example.invalid/image@sha256:' + 'a' * 64
OLD = 'registry.example.invalid/image@sha256:' + 'b' * 64


def deployment(text):
    return next(d for d in yaml.safe_load_all(text) if d['kind'] == 'Deployment')


def cluster(monkeypatch, replicas='1', differs=True):
    """An already-released deployment: records kubectl calls, drain queries and diff checks in one ordered log."""
    events, diffs = [], []
    def run(*args, input=None, timeout=660):
        events.append((args, input))
        if 'get' in args and 'jsonpath={.spec.replicas}' in args:
            return replicas
        if args[:5] == ('kubectl', '-n', 'ui2', 'get', 'deployment') and 'json' in args:
            return json.dumps({'spec': {'template': {'spec': {'containers': [{'image': OLD}]}}}})
        if 'jsonpath={.spec.template.spec.containers[0].image}' in args:
            return OLD
        if 'pods' in args:
            return json.dumps({'items': []})
        return ''
    def query(sql):
        events.append((('query', sql), None))
        return '7' if 'returning drain_generation' in sql else 't'
    monkeypatch.setattr(deploy, 'run', run)
    monkeypatch.setattr(deploy, 'query', query)
    monkeypatch.setattr(deploy, 'kubectl_diff', lambda manifest: events.append((('diff',), manifest)) or diffs.append(manifest) or differs)
    monkeypatch.setattr(deploy, 'module_e2e', lambda commit=None: events.append((('e2e',), None)))
    monkeypatch.setattr(deploy, 'wait_heartbeat', lambda target: None)
    monkeypatch.setattr(deploy, 'handover_policy', lambda *a: events.append((('handover',), None)))
    return events, diffs


def index(events, predicate):
    return next(i for i, (args, _) in enumerate(events) if predicate(args))


def applies(events):
    return [(i, payload) for i, (args, payload) in enumerate(events) if args[:4] == ('kubectl', '-n', 'ui2', 'apply')]


def test_the_policy_manifest_is_rendered_with_the_candidate_image_and_the_fingerprint_key():
    manifest = deploy.render_manifest('policy', IMAGE)
    assert manifest.count('image: ') == 1 and IMAGE in manifest and 'nexus-ui2-service@' not in manifest
    workload = deployment(manifest)
    container, = workload['spec']['template']['spec']['containers']
    assert container['image'] == IMAGE
    assert {'name': 'UI2_HOSTNAME_FINGERPRINT_KEY_FILE', 'value': '/run/secrets/ui2-hostname-fingerprint/key'} in container['env']
    assert any(m['mountPath'] == '/run/secrets/ui2-hostname-fingerprint' for m in container['volumeMounts'])
    assert any(v['name'] == 'hostname-fingerprint-key' for v in workload['spec']['template']['spec']['volumes'])
    assert container['resources']['limits']['memory'] == '8Gi'
    assert workload['spec']['replicas'] == 0, 'without a live count the first-release value stays'
    assert {d['kind'] for d in yaml.safe_load_all(manifest)} == {'ServiceAccount', 'Deployment', 'NetworkPolicy'}


def test_the_rendered_replica_count_is_the_live_one():
    for live in (0, 1, 3):
        assert deployment(deploy.render_manifest('policy', IMAGE, live))['spec']['replicas'] == live


def test_policy_is_diffed_before_the_drain_and_applied_after_it_keeping_the_live_replicas(monkeypatch, capsys):
    events, diffs = cluster(monkeypatch, replicas='1')
    deploy.replace('policy', IMAGE, 'synthetic-snapshot', 20, authorization='synthetic-approval')
    (apply_index, manifest), = applies(events)
    assert manifest == deploy.render_manifest('policy', IMAGE, 1)
    assert diffs == [manifest], 'the candidate manifest is checked against the cluster'
    drain = index(events, lambda a: a[0] == 'query' and 'drain_requested=true' in a[1].replace(' ', ''))
    barrier = index(events, lambda a: a[0] == 'query' and 'drain_ack_generation=drain_generation' in a[1])
    diff = index(events, lambda a: a == ('diff',))
    scale = index(events, lambda a: '--replicas=1' in a)
    rollout = index(events, lambda a: 'rollout' in a)
    assert diff < drain < barrier < apply_index < scale < rollout
    assert not any('set' in args and 'image' in args for args, _ in events), 'the bare image swap is gone'
    assert 'policy manifest: differs from the cluster; applied after the drain' in capsys.readouterr().out


def test_an_in_sync_policy_manifest_is_reported_and_still_applied(monkeypatch, capsys):
    events, diffs = cluster(monkeypatch, differs=False)
    deploy.replace('policy', IMAGE, 'synthetic-snapshot', 20, authorization='synthetic-approval')
    assert 'policy manifest: already in sync' in capsys.readouterr().out
    assert len(applies(events)) == 1, 'the image still moves; the apply installs it'


def test_a_stopped_policy_deployment_is_not_started_by_the_apply(monkeypatch):
    # Fallback state: the deployment exists at 0 replicas; only the tool scales it, after the apply.
    events, diffs = cluster(monkeypatch, replicas='0')
    deploy.replace('policy', IMAGE, 'synthetic-snapshot', 20, authorization='synthetic-approval')
    (apply_index, manifest), = applies(events)
    assert deployment(manifest)['spec']['replicas'] == 0
    assert apply_index < index(events, lambda a: '--replicas=1' in a)


def test_a_running_replica_count_is_never_reset_by_the_apply(monkeypatch):
    events, diffs = cluster(monkeypatch, replicas='2')
    deploy.replace('policy', IMAGE, 'synthetic-snapshot', 20, authorization='synthetic-approval')
    assert deployment(applies(events)[0][1])['spec']['replicas'] == 2


def test_the_policy_manifest_is_validated_before_any_drain(monkeypatch):
    events, diffs = cluster(monkeypatch)
    monkeypatch.setattr(deploy, 'kubectl_diff', lambda manifest: (_ for _ in ()).throw(deploy.Blocked('diff failed')))
    with pytest.raises(deploy.Blocked, match='diff failed'):
        deploy.replace('policy', IMAGE, 'synthetic-snapshot', 20, authorization='synthetic-approval')
    assert not applies(events) and not any(args[0] == 'query' and 'drain_requested' in args[1] for args, _ in events)


def test_a_rollback_restores_only_the_recorded_image(monkeypatch):
    events, diffs = cluster(monkeypatch)
    deploy.replace('policy', OLD, 'synthetic-snapshot', 20, authorization='synthetic-approval', rollback=True)
    assert any(args[-1] == 'policy=' + OLD for args, _ in events if 'set' in args)
    assert not applies(events) and not diffs, 'the forward manifest is not applied or diffed on rollback'


def test_a_first_release_still_creates_the_disabled_deployment(monkeypatch):
    calls = []
    monkeypatch.setattr(deploy, 'run', lambda *a, **kw: calls.append((a, kw)) or '')
    monkeypatch.setattr(deploy, 'prepare_policy_public_trust', lambda: None)
    assert deploy.ensure_policy(IMAGE) is None
    manifest = calls[-1][1]['input']
    assert manifest == deploy.render_manifest('policy', IMAGE)
    assert deployment(manifest)['spec']['replicas'] == 0


@pytest.mark.parametrize('target', ['service', 'configuration', 'compliance'])
def test_other_deployments_apply_their_manifest_after_the_diff_keeping_live_replicas(monkeypatch, target):
    events, diffs = cluster(monkeypatch, replicas='1')
    deploy.replace(target, IMAGE, 'synthetic-snapshot', 20)
    (apply_index, manifest), = applies(events)
    assert manifest == deploy.render_manifest(target, IMAGE, 1) and diffs == [manifest]
    assert deployment(manifest)['spec']['template']['spec']['containers'][0]['image'] == IMAGE
    assert index(events, lambda a: a == ('diff',)) < apply_index < index(events, lambda a: 'rollout' in a)
    assert not any('set' in args and 'image' in args for args, _ in events)


@pytest.mark.parametrize('target', ['service', 'configuration', 'compliance'])
def test_other_deployments_roll_back_by_image_only(monkeypatch, target):
    events, diffs = cluster(monkeypatch)
    deploy.replace(target, OLD, 'synthetic-snapshot', 20, rollback=True)
    assert any(args[-1] == target + '=' + OLD for args, _ in events if 'set' in args)
    assert not applies(events) and not diffs


def test_the_worker_keeps_its_runtime_fallback_patch_and_is_not_manifest_applied():
    assert 'worker' not in deploy.SYNCED_MANIFESTS and 'packet-analysis' not in deploy.SYNCED_MANIFESTS
    assert set(deploy.SYNCED_MANIFESTS) == {'service', 'configuration', 'compliance', 'policy'}


@pytest.mark.parametrize('target', sorted(deploy.SYNCED_MANIFESTS))
def test_every_synced_manifest_has_one_image_and_one_replica_line(target):
    for replicas in (None, 1):
        manifest = deploy.render_manifest(target, IMAGE, replicas)
        assert manifest.count(IMAGE) == 1
    assert deployment(manifest)['metadata']['name'] == 'ui2-' + target


def test_a_template_with_other_than_one_image_or_replica_line_is_refused(monkeypatch, tmp_path):
    (tmp_path / 'deploy/ui2').mkdir(parents=True)
    monkeypatch.setattr(deploy, 'REPO', tmp_path)
    path = tmp_path / deploy.SYNCED_MANIFESTS['policy']
    for text in ('kind: Deployment\n', '  image: a\n  image: b\n'):
        path.write_text(text)
        with pytest.raises(deploy.Blocked, match='exactly one workload image'):
            deploy.render_manifest('policy', IMAGE)
    for text in ('  image: a\n', '  image: a\n  replicas: 0\n  replicas: 1\n'):
        path.write_text(text)
        with pytest.raises(deploy.Blocked, match='exactly one replica count'):
            deploy.render_manifest('policy', IMAGE, 1)


def test_an_unreadable_live_replica_count_stops_before_any_drain(monkeypatch):
    events, diffs = cluster(monkeypatch, replicas='')
    with pytest.raises(deploy.Blocked, match='live replica count'):
        deploy.replace('policy', IMAGE, 'synthetic-snapshot', 20, authorization='synthetic-approval')
    assert not applies(events) and not any(args[0] == 'query' and 'drain_requested' in args[1] for args, _ in events)
