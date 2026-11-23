"""Deploy-freeze Phase 1 (PO 2026-11-23): a routine image replacement never changes module ownership; only an explicit,
audited transfer does. Also the shorter drain wait for yield-capable modules. Offline; no cluster or device access."""
import json
from pathlib import Path
import re
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/delivery'))
import module_deploy as deploy

IMAGE = 'registry.example.invalid/image@sha256:' + 'a' * 64


def _harness(monkeypatch, target, fallback, fail_at=None, drain_fails=False, prior_drain=False, quarantined=False):
    """replace(target) with every external effect stubbed; returns (events, queries)."""
    events, queries = [], []
    def run(*args, **kwargs):
        events.append(args)
        if 'pods' in args:
            return '{"items":[]}'
        if any('jsonpath={.spec.replicas}' == a for a in args):
            return '1'
        if 'jsonpath={.spec.template.spec.containers[0].image}' in args:
            return IMAGE
        return ''
    def query(sql):
        queries.append(sql)
        if 'select fallback_enabled' in sql:
            return 't' if fallback else 'f'
        if 'select effective_owner' in sql:
            return 'general' if fallback else target
        if sql.startswith('select drain_requested'):
            return 't' if prior_drain else 'f'
        if sql.startswith('select (exists'):
            return 't' if quarantined else 'f'
        return 't'
    monkeypatch.setattr(deploy, 'run', run)
    monkeypatch.setattr(deploy, 'query', query)
    monkeypatch.setattr(deploy, 'kubectl_diff', lambda manifest: False)
    monkeypatch.setattr(deploy, 'ensure_policy', lambda image: IMAGE)
    monkeypatch.setattr(deploy, 'ensure_backup', lambda image: IMAGE)
    monkeypatch.setattr(deploy, 'request_drain', lambda *a: events.append(('request_drain',)) or 5)
    def wait_drain(*a):
        events.append(('wait_drain',))
        if drain_fails and events.count(('wait_drain',)) == 1:
            raise deploy.Blocked('synthetic drain timeout', continuable=True)
    monkeypatch.setattr(deploy, 'wait_drain', wait_drain)
    monkeypatch.setattr(deploy, 'wait_heartbeat', lambda *a: events.append(('heartbeat',)))
    for name in ('cancel_drain', 'cancel_general_drain', 'reset_to_general', 'handover_policy', 'handover_backup',
                 'resume_dedicated', 'clear_drain'):
        monkeypatch.setattr(deploy, name, lambda *a, _n=name: events.append((_n,)))
    def e2e(*a):
        events.append(('e2e',))
        if fail_at == 'e2e':
            raise deploy.Blocked('synthetic e2e failure')
    monkeypatch.setattr(deploy, 'module_e2e', e2e)
    return events, queries


@pytest.mark.parametrize('target', ['policy', 'backup'])
def test_routine_replacement_lifts_the_drain_without_handover_or_generation_change(monkeypatch, target):
    events, queries = _harness(monkeypatch, target, fallback=False)
    deploy.replace(target, IMAGE, 'synthetic-snapshot', 20)  # no authorization reference needed
    assert ('resume_dedicated',) in events
    assert events.index(('heartbeat',)) < events.index(('resume_dedicated',)) < events.index(('e2e',))
    assert not {('handover_policy',), ('handover_backup',), ('reset_to_general',)} & set(events)
    assert not any('generation=generation+1' in sql for sql in queries)
    assert not any('fallback_enabled=' in sql or "effective_owner='" in sql.split(' where ')[0] for sql in queries
                   if sql.startswith('begin;'))


@pytest.mark.parametrize('target', ['policy', 'backup'])
def test_routine_failure_fences_the_module_and_never_returns_it_to_general(monkeypatch, target, capsys):
    events, _ = _harness(monkeypatch, target, fallback=False, fail_at='e2e')
    with pytest.raises(deploy.Blocked, match='e2e failure'):
        deploy.replace(target, IMAGE, 'synthetic-snapshot', 20)
    assert ('reset_to_general',) not in events and ('cancel_general_drain',) not in events
    assert not any('--replicas=0' in e for e in events)  # the pod keeps running: no uncertain device work is killed
    assert events.count(('request_drain',)) == 2        # the release drain, then the fence
    assert '--rollback' in capsys.readouterr().out


@pytest.mark.parametrize('target', ['policy', 'backup'])
def test_transfer_from_the_fallback_still_hands_over_and_recovers_to_general(monkeypatch, target):
    events, _ = _harness(monkeypatch, target, fallback=True)
    deploy.replace(target, IMAGE, 'synthetic-snapshot', 20, authorization='synthetic-approval')
    assert ('handover_' + target,) in events and ('resume_dedicated',) not in events
    with pytest.raises(deploy.Blocked, match='authorization'):
        _harness(monkeypatch, target, fallback=True)
        deploy.replace(target, IMAGE, 'synthetic-snapshot', 20)


@pytest.mark.parametrize('target,fallback,expected', [
    ('worker', False, 'cancel_drain'), ('policy', False, 'cancel_drain'),
    ('backup', False, 'cancel_drain'), ('backup', True, 'cancel_general_drain')])
def test_drain_timeout_gives_claims_back_without_replacing_anything(monkeypatch, target, fallback, expected):
    events, _ = _harness(monkeypatch, target, fallback=fallback, drain_fails=True)
    with pytest.raises(deploy.Blocked, match='drain timeout'):
        deploy.replace(target, IMAGE, 'synthetic-snapshot', 20, authorization='synthetic-approval')
    assert (expected,) in events
    assert not any(('set' in e or 'apply' in e or 'patch' in e or 'scale' in e) for e in events if isinstance(e[0], str)
                   and e[0] == 'kubectl')


def test_routine_sql_keeps_generation_owner_and_fallback(monkeypatch):
    calls = []
    monkeypatch.setattr(deploy, 'query', lambda sql: calls.append(sql) or 'policy')
    deploy.resume_dedicated('policy', 7, 'synthetic-snapshot')
    deploy.cancel_drain('worker', 9, 'synthetic-snapshot')
    resume, cancel = calls
    for sql in (resume, cancel):
        assert 'pg_advisory_xact_lock(294611)' in sql and 'drain_requested=false' in sql
        assert 'generation=generation' not in sql and 'fallback_enabled=' not in sql
        assert 'owner_instance=null' not in sql and 'effective_owner=' not in sql.split(' where ')[0]
    assert "module='policy' and effective_owner='policy' and not fallback_enabled" in resume
    assert 'drain_generation=7' in resume and "owner_heartbeat_at>now()-interval '60 seconds'" in resume
    assert "module='general' and drain_generation=9" in cancel
    monkeypatch.setattr(deploy, 'query', lambda sql: '')
    with pytest.raises(deploy.Blocked, match='fence or heartbeat'):
        deploy.resume_dedicated('policy', 7, 'synthetic-snapshot')


def _clock():
    now = [0]
    return now, (lambda: now[0]), (lambda seconds: now.__setitem__(0, now[0] + max(seconds, 1)))


@pytest.mark.parametrize('target', ['worker', 'policy'])
def test_yield_capable_drain_stops_after_the_short_limit(monkeypatch, target):
    now, clock, sleep = _clock()
    monkeypatch.setattr(deploy, 'barrier', lambda *a: False)
    monkeypatch.setattr(deploy, 'only_yieldable_work_holds', lambda *a: True)
    with pytest.raises(deploy.Blocked, match='yield-capable drain') as raised:
        deploy.wait_drain(target, 1, 14400, sleep=sleep, clock=clock)
    assert raised.value.continuable
    assert deploy.YIELD_WAIT_LIMIT_S <= now[0] < deploy.YIELD_WAIT_LIMIT_S + 2 * deploy.DRAIN_POLL_S


def test_a_non_yieldable_job_or_other_blocker_keeps_the_long_limit(monkeypatch):
    now, clock, sleep = _clock()
    monkeypatch.setattr(deploy, 'barrier', lambda *a: False)
    monkeypatch.setattr(deploy, 'only_yieldable_work_holds', lambda *a: False)
    with pytest.raises(deploy.Blocked, match='bounded drain expired'):
        deploy.wait_drain('worker', 1, 3600, sleep=sleep, clock=clock)
    assert now[0] >= 3600


def test_the_short_timer_starts_when_only_yieldable_work_holds_and_resets_on_any_other_blocker(monkeypatch):
    """Review D2: a long non-yieldable job (or quarantine/task lease) for 1000 s, then yield-capable work only: the
    short limit counts from that moment, not from the start of the wait, and restarts after any interruption."""
    now, clock, sleep = _clock()
    monkeypatch.setattr(deploy, 'barrier', lambda *a: False)
    def holds(*a):
        return 1000 <= now[0] < 1300 or now[0] >= 1400
    monkeypatch.setattr(deploy, 'only_yieldable_work_holds', holds)
    with pytest.raises(deploy.Blocked, match='yield-capable drain'):
        deploy.wait_drain('worker', 1, 14400, sleep=sleep, clock=clock)
    assert 1400 + deploy.YIELD_WAIT_LIMIT_S <= now[0] < 1400 + deploy.YIELD_WAIT_LIMIT_S + 2 * deploy.DRAIN_POLL_S


@pytest.mark.parametrize('target', ['backup', 'packet-analysis', 'script-runner'])
def test_modules_without_yield_capability_never_get_the_short_limit(target):
    assert deploy.yield_wait_limit(target, 14400) is None


def test_short_limit_never_lengthens_a_shorter_long_limit_and_zero_disables(monkeypatch):
    assert deploy.yield_wait_limit('worker', 300) is None
    monkeypatch.setattr(deploy, 'YIELD_WAIT_LIMIT_S', 0)
    assert deploy.yield_wait_limit('worker', 14400) is None


def test_only_yieldable_condition_needs_jobs_ack_live_owner_and_no_lease_or_quarantine(monkeypatch):
    calls = []
    monkeypatch.setattr(deploy, 'query', lambda sql: calls.append(sql) or 't')
    assert deploy.only_yieldable_work_holds('worker', 3)
    sql = calls[0]
    assert sql.startswith('select (drain_requested and drain_generation=3 and drain_ack_generation=drain_generation')
    assert "owner_heartbeat_at>now()-interval '60 seconds'" in sql
    assert "and exists(select 1 from jobs j where j.state in ('CLAIMED','EXECUTING')" in sql          # busy_jobs > 0
    assert "and not exists(select 1 from jobs j" in sql and "j.capability_id <> all(array['cp_inventory_collect'" in sql
    assert "not exists(select 1 from runtime_task_lease where owner_role='general' and state='LEASED')" in sql
    assert "state='QUARANTINED'" in sql and 'from endpoint_admission' in sql
    assert "from module_runtime_control where module='general'" in sql
    for writer in ('cp_mds_export', 'cp_gateway_backup', 'cp_cluster_failover', 'script_run', 'diagnostic_read'):
        assert "'" + writer + "'" not in sql
    monkeypatch.setattr(deploy, 'query', lambda sql: (_ for _ in ()).throw(deploy.Blocked('db unavailable')))
    assert not deploy.only_yieldable_work_holds('policy', 3)  # unreadable: long limit


def test_yield_allowlists_are_read_only_collection_types():
    allowed = re.compile(r'^(cp|pan|https|asa|fgt|proxysg|rdw|fmg|bcmc)_(policy_collect|inventory_collect|'
                         r'configuration_collect|discovery_enumerate)$')
    for target, types in deploy.YIELDABLE_JOB_TYPES.items():
        assert target in ('worker', 'policy')
        assert all(allowed.match(t) for t in types), types


def test_preflight_skips_a_fallback_module_in_a_routine_release(monkeypatch, capsys):
    monkeypatch.setattr(deploy, 'query', lambda sql: 't' if "module='backup'" in sql else 'f')
    assert deploy.ownership_preflight(['service', 'worker', 'policy', 'backup'], False, 'synthetic-approval') == [
        'service', 'worker', 'policy']
    assert 'backup: still on the general fallback' in capsys.readouterr().out


def test_preflight_transfer_is_explicit_authorized_and_alone(monkeypatch):
    monkeypatch.setattr(deploy, 'query', lambda sql: 't' if "module='backup'" in sql else 'f')
    assert deploy.ownership_preflight(['backup'], True, 'synthetic-approval') == ['backup']
    with pytest.raises(deploy.Blocked, match='authorization'):
        deploy.ownership_preflight(['backup'], True, None)
    with pytest.raises(deploy.Blocked, match='only policy/backup'):
        deploy.ownership_preflight(['worker', 'backup'], True, 'synthetic-approval')
    with pytest.raises(deploy.Blocked, match='only policy/backup'):
        deploy.ownership_preflight(['policy'], True, 'synthetic-approval')  # policy already owns itself


def test_default_ship_targets_carry_backup_so_a_cut_over_backup_pod_gets_each_release():
    for path, pattern in (("tools/delivery/hosta_deploy.sh", r'^TARGETS="([^"]+)"$'),
                          ("deploy/ui2-image-build/run_build.sh", r'NEXUS_DEPLOY_TARGETS:-([^}]+)\}')):
        targets = re.search(pattern, (ROOT / path).read_text(), re.M)[1].split()
        assert targets.index('policy') < targets.index('backup') < targets.index('packet-analysis'), path


@pytest.mark.parametrize('target', ['worker', 'policy'])
@pytest.mark.parametrize('case', ['rollback', 'prior_drain', 'quarantined'])
def test_drain_timeout_keeps_the_drain_on_rollback_earlier_fence_or_quarantine(monkeypatch, target, case):
    """Review D1/D7: the automatic lift is only for a routine forward release that set the drain itself."""
    events, _ = _harness(monkeypatch, target, fallback=False, drain_fails=True,
                         prior_drain=case == 'prior_drain', quarantined=case == 'quarantined')
    with pytest.raises(deploy.Blocked, match='drain retained') as raised:
        deploy.replace(target, IMAGE, 'synthetic-snapshot', 20, rollback=case == 'rollback')
    assert raised.value.continuable
    assert ('cancel_drain',) not in events and ('cancel_general_drain',) not in events


def test_the_drain_lift_is_reported(monkeypatch):
    _harness(monkeypatch, 'worker', fallback=False, drain_fails=True)
    with pytest.raises(deploy.Blocked, match='drain lifted'):
        deploy.replace('worker', IMAGE, 'synthetic-snapshot', 20)


def test_quarantine_and_prior_drain_reads_fail_closed(monkeypatch):
    monkeypatch.setattr(deploy, 'query', lambda sql: (_ for _ in ()).throw(deploy.Blocked('db unavailable')))
    assert deploy.prior_drain_requested('policy') and deploy.quarantine_holds('policy', 4)
    calls = []
    monkeypatch.setattr(deploy, 'query', lambda sql: calls.append(sql) or 'f')
    assert not deploy.quarantine_holds('backup', 4)
    assert "(owner_role='backup' or purpose_class='BACKUP') and ((state='QUARANTINED'" in calls[0]
    assert "state='LEASED'" not in calls[0].split('runtime_task_lease')[0]


def test_a_fallback_module_is_never_transferred_without_the_flag(monkeypatch):
    _harness(monkeypatch, 'backup', fallback=True)
    with pytest.raises(deploy.Blocked, match='--ownership-transfer'):
        deploy.replace('backup', IMAGE, 'synthetic-snapshot', 20, authorization='synthetic-approval',
                       allow_transfer=False)
