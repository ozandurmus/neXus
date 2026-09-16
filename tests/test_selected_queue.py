"""Selected queue: synthetic admission, no provider calls or real state."""
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import selected_queue as sq
import orchestrator as orch


@pytest.fixture
def queue(tmp_path, monkeypatch):
    repo = tmp_path / 'repo'
    (repo / 'project').mkdir(parents=True)
    (repo / 'project/backlog.json').write_text(json.dumps({'items': [
        {'id': 'item-' + str(i), 'title': 'Work ' + str(i), 'category': 'Engineering', 'priority': 'P1', 'status': 'planned'} for i in range(20)]}))
    relay = repo / 'relay'
    relay.mkdir()
    q = sq.Queue(tmp_path / 'state', relay)
    monkeypatch.setattr(orch, 'LEGACY_STATE_DIR', tmp_path / 'legacy')
    monkeypatch.setattr(q, 'evidence', lambda entry: ({}, None) if entry.get('binding') else (None, 'PO preparation required'))
    q.attach('parent', os.getpid())
    q.mutate({'operation': 'ADD', 'expected_revision': 1, 'operation_id': 'add',
              'source_ids': ['item-' + str(i) for i in range(20)]})
    with q.locked() as doc:
        for i, entry in enumerate(doc['entries']):
            entry['binding'] = {'movement_id': f'NXS-LOCAL-{i}', 'task_hash': 'fixture', 'base_sha': 'fixture',
                                'provider': 'codex', 'model': 'fixture', 'effort': 'medium', 'budget': 1,
                                'resources': [f'synthetic/{i}'], 'dependencies': []}
        q.save(doc)
    q.approve('parent', 1)
    q.mutate({'operation': 'START', 'expected_revision': 3, 'operation_id': 'start'})
    return q


def finish(q, claim, monkeypatch):
    movement = claim['movement_id']
    with q.locked() as doc:
        doc['reservations'][movement]['wrapper_pid'] = None
        q.save(doc)
    orch._save_state(q.state_dir, movement, {'movement_id': movement, 'phase': orch.PHASE_DONE,
                     'pid': None, 'admission_claim': claim['claim'], 'verify': {'passed': True}})
    monkeypatch.setattr(orch, '_resolve_relay_file', lambda *args: Path('fixture'))
    monkeypatch.setattr(orch, '_load_relay', lambda *args: {'entries': [{'marker': 'SESSION_CLOSE', 'seq': 2, 'outcome': 'DONE'}]})
    return q.complete(movement, claim['claim'], 'parent', 1)


def test_twenty_jobs_refill_two_slots_without_duplicate_claims(queue, monkeypatch):
    q = queue
    completed = []
    for _ in range(10):
        with ThreadPoolExecutor(max_workers=3) as executor:
            claims = list(executor.map(lambda _: q.claim('parent', 1), range(3)))
        admitted = [c for c in claims if c['claim']]
        assert len(admitted) == 2
        assert len(q.read()['reservations']) == 2
        for claim in admitted:
            assert claim['movement_id'] not in completed
            finish(q, claim, monkeypatch)
            assert q.complete(claim['movement_id'], claim['claim'], 'parent', 1) == {'completed': True}
            completed.append(claim['movement_id'])
    assert len(completed) == 20
    assert q.claim('parent', 1)['claim'] is None


def test_blocked_dependency_keeps_order_and_skips_to_independent(queue):
    with queue.locked() as doc:
        order = [e['source_id'] for e in doc['entries']]
        doc['entries'][0]['binding']['dependencies'] = ['not-accepted']
        queue.save(doc)
    assert queue.claim('parent', 1)['movement_id'] == 'NXS-LOCAL-1'
    assert [e['source_id'] for e in queue.read()['entries']] == order


def test_pause_idempotency_and_stale_edits(queue):
    doc = queue.read()
    req = {'operation': 'PAUSE', 'expected_revision': doc['revision'], 'operation_id': 'pause'}
    first = queue.mutate(req)
    assert queue.mutate(req) == first
    with pytest.raises(sq.QueueError, match='reused'):
        queue.mutate({**req, 'operation': 'START'})
    with pytest.raises(sq.QueueError, match='paused'):
        queue.claim('parent', 1)
    with pytest.raises(sq.QueueError, match='changed'):
        queue.mutate({**req, 'operation_id': 'different'})


def test_uncertain_reservation_survives_reopen_and_no_auto_reclaim(queue):
    claim = queue.claim('parent', 1)
    with queue.locked() as doc:
        doc['reservations'][claim['movement_id']]['state'] = 'DISPATCH_UNCERTAIN'
        queue.save(doc)
    other = sq.Queue(queue.state_dir, queue.relay_dir)
    assert claim['movement_id'] in other.read()['reservations']
    with pytest.raises(sq.QueueError, match='retained'):
        queue.complete(claim['movement_id'], claim['claim'], 'parent', 1)
    assert queue.claim('parent', 1)['claim']
    assert queue.claim('parent', 1)['claim'] is None


def test_scope_conflicts_and_pending_delivery_block_overlap(queue, monkeypatch):
    with queue.locked() as doc:
        doc['entries'][1]['binding']['resources'] = ['synthetic/0/file']
        queue.save(doc)
    first = queue.claim('parent', 1)
    finish(queue, first, monkeypatch)
    assert queue.claim('parent', 1)['movement_id'] == 'NXS-LOCAL-2'
    entry = queue.read()['entries'][0]
    queue.accept(entry['entry_id'], 'parent', 1)
    assert queue.claim('parent', 1)['movement_id'] == 'NXS-LOCAL-1'


def test_outside_workers_and_stale_parent_fail_closed(queue):
    orch._save_state(queue.state_dir, 'NXS-LOCAL-external', {'movement_id': 'NXS-LOCAL-external', 'pid': os.getpid(), 'phase': 'running'})
    assert queue.claim('parent', 1)['claim'] is None
    with pytest.raises(sq.QueueError, match='Stale parent'):
        queue.claim('old-parent', 1)


def test_unknown_occupancy_and_invalid_payload(queue):
    orch._save_state(queue.state_dir, 'NXS-LOCAL-unknown', {'movement_id': 'NXS-LOCAL-unknown', 'pid': None, 'phase': 'running'})
    with pytest.raises(sq.QueueError, match='Unknown activity'):
        queue.claim('parent', 1)
    with pytest.raises(sq.QueueError, match='Unknown operation'):
        queue.mutate({'operation': 'ADD', 'expected_revision': 4, 'operation_id': 'bad', 'source_ids': ['item-0'], 'argv': ['bad']})


def test_claimed_entry_cannot_be_removed(queue):
    queue.claim('parent', 1)
    doc = queue.read()
    with pytest.raises(sq.QueueError, match='Claimed'):
        queue.mutate({'operation': 'REMOVE', 'expected_revision': doc['revision'], 'operation_id': 'remove', 'entry_id': doc['entries'][0]['entry_id']})


def test_unsupported_lock_never_writes(queue, monkeypatch):
    before = queue.path.read_bytes()
    monkeypatch.setattr(sq.lr, '_fcntl', None)
    with pytest.raises(sq.QueueError, match='locking'):
        queue.claim('parent', 1)
    assert queue.path.read_bytes() == before


def test_dispatch_gate_retains_uncertain_spawn(queue, monkeypatch, tmp_path):
    claim = queue.claim('parent', 1)
    domain = tmp_path / 'domain.json'
    domain.write_text(json.dumps({'state_dir': str(queue.state_dir), 'relay_dir': str(queue.relay_dir)}))
    monkeypatch.setattr(sq, 'domain_path', lambda _: domain)
    monkeypatch.setattr(sq.Queue, 'evidence', lambda *args: ({}, None))
    args = SimpleNamespace(relay_dir=str(queue.relay_dir), state_dir=str(queue.state_dir), movement=claim['movement_id'],
                           queue_claim=claim['claim'], queue_parent='parent', queue_generation=1,
                           provider='codex', model='fixture', effort='medium', max_budget_usd=1, command='run')
    def crash(_):
        raise RuntimeError('synthetic crash after spawn boundary')
    with pytest.raises(RuntimeError):
        sq.guarded_start(args, crash)
    assert queue.read()['reservations'][claim['movement_id']]['state'] == 'DISPATCH_UNCERTAIN'
    with pytest.raises(sq.QueueError, match='already dispatched'):
        sq.guarded_start(args, crash)


def test_direct_selected_dispatch_cannot_bypass_claim(queue, monkeypatch, tmp_path):
    domain = tmp_path / 'domain.json'
    domain.write_text(json.dumps({'state_dir': str(queue.state_dir), 'relay_dir': str(queue.relay_dir)}))
    monkeypatch.setattr(sq, 'domain_path', lambda _: domain)
    args = SimpleNamespace(relay_dir=str(queue.relay_dir), state_dir=str(queue.state_dir), movement='NXS-LOCAL-0',
                           queue_claim=None, max_workers=3, command='run')
    with pytest.raises(sq.QueueError, match='requires its queue claim'):
        sq.guarded_start(args, lambda _: pytest.fail('must not spawn'))
    args.state_dir = str(tmp_path / 'alternate')
    with pytest.raises(sq.QueueError, match='Alternate'):
        sq.guarded_start(args, lambda _: pytest.fail('must not spawn'))


def test_write_failure_cannot_admit_work(queue, monkeypatch):
    def fail(_):
        raise OSError('synthetic disk failure')
    monkeypatch.setattr(queue, 'save', fail)
    with pytest.raises(OSError):
        queue.claim('parent', 1)
    assert queue.read()['reservations'] == {}


def test_http_mutations_require_auth_origin_and_bounded_body(queue):
    import http.client
    import threading
    from http.server import ThreadingHTTPServer
    import orchestrator_dashboard as dash
    server = ThreadingHTTPServer(('127.0.0.1', 0), dash.make_handler_class(token='synthetic',
        relay_dir=queue.relay_dir, state_dir=queue.state_dir, repo_root=queue.repo, retry_limit=2, port=0))
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    def send(headers, body):
        connection = http.client.HTTPConnection('127.0.0.1', server.server_port)
        connection.request('POST', '/api/selected-queue/mutations', body, headers)
        response = connection.getresponse()
        result = response.status
        response.read()
        connection.close()
        return result
    try:
        assert send({}, '{}') == 401
        assert send({'Authorization':'Bearer synthetic','Origin':'https://example.invalid'}, '{}') == 403
        assert send({'Authorization':'Bearer synthetic'}, 'x' * 17000) == 409
        assert send({'Authorization':'Bearer synthetic'}, '{') == 409
        doc = queue.read()
        assert send({'Authorization':'Bearer synthetic'}, json.dumps({'operation':'PAUSE','expected_revision':doc['revision'],'operation_id':'http-pause'})) == 200
    finally:
        server.shutdown()
        server.server_close()
        worker.join()


def test_parent_contact_staleness_is_not_worker_failure(queue):
    claim = queue.claim('parent', 1)
    with queue.locked() as doc:
        doc['parent']['last_contact'] = 0
        doc['entries'][0]['state'] = 'RUNNING'
        doc['reservations'][claim['movement_id']]['state'] = 'RUNNING'
        queue.save(doc)
    status = queue.snapshot()
    assert status['loop'] == 'UNKNOWN'
    assert status['entries'][0]['state'] == 'RUNNING'
    assert status['can_start'] is False


def test_malformed_queue_and_null_claim_are_rejected(queue):
    with pytest.raises(sq.QueueError, match='identifier'):
        queue.complete('NXS-LOCAL-0', None, 'parent', 1)
    queue.path.write_text('{"schema_version":1}')
    with pytest.raises(sq.QueueError, match='invalid'):
        queue.read()
