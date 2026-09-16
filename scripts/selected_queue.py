"""Selected-work admission. Shared by the dashboard and orchestrator CLI.

No model calls, shell strings, scheduler or inferred authorization. The parent
binds existing approved movements, attaches, then consumes next/completion.
"""
from __future__ import annotations

import argparse
import contextlib
import copy
import hashlib
import json
import math
import os
from pathlib import Path
import sys
import tempfile
import time
import uuid

import local_relay as lr
import project_queue as pq


class QueueError(ValueError):
    pass


RESERVED = {"CLAIMED", "DISPATCHING", "RUNNING", "DISPATCH_UNCERTAIN"}


def _id(value):
    if not isinstance(value, str) or not value or len(value) > 200 or any(ord(c) < 32 for c in value):
        raise QueueError("Invalid identifier")
    return value


def _hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def pool(repo: Path):
    active = pq.load_json(repo / "project/backlog.json")
    terminal_path = repo / "project/archive/backlog_terminal.json"
    terminal = pq.load_json(terminal_path) if terminal_path.exists() else {"items": []}
    pq.validate_backlog_split(active, terminal)
    return [{k: i.get(k, "") for k in ("id", "title", "category", "priority", "status")}
            for i in pq.all_backlog_items(active, terminal) if i.get("status") in pq.OPEN_STATUSES]


class Queue:
    def __init__(self, state_dir: Path, relay_dir: Path):
        self.state_dir = Path(state_dir).resolve()
        self.relay_dir = Path(relay_dir).resolve()
        self.repo = self.relay_dir.parent
        self.path = self.state_dir / "admission/selected_queue.json"

    def read(self):
        if not self.path.exists():
            return {"schema_version": 1, "revision": 0, "queue_id": str(uuid.uuid4()),
                    "control": "PAUSED", "limit": 2, "generation": 0, "parent": None,
                    "entries": [], "receipts": {}, "reservations": {}, "approved": []}
        try:
            doc = json.loads(self.path.read_text())
            if (doc["schema_version"] != 1 or not isinstance(doc["entries"], list)
                    or not isinstance(doc["reservations"], dict) or not isinstance(doc["receipts"], dict)
                    or doc["control"] not in {"PAUSED", "RUNNING"}
                    or type(doc["limit"]) is not int or not 1 <= doc["limit"] <= 3):
                raise ValueError()
            if type(doc["revision"]) is not int or type(doc["generation"]) is not int or not isinstance(doc["approved"], list):
                raise ValueError()
            ids, sources = set(), set()
            for entry in doc["entries"]:
                entry_id, source = _id(entry["entry_id"]), _id(entry["source_id"])
                if entry_id in ids or source in sources or entry["state"] not in RESERVED | {"PENDING", "TERMINAL"}:
                    raise ValueError()
                ids.add(entry_id)
                sources.add(source)
            for movement, reservation in doc["reservations"].items():
                _id(movement)
                _id(reservation["claim"])
                if reservation["state"] not in RESERVED:
                    raise ValueError()
            return doc
        except (OSError, ValueError, KeyError, TypeError) as exc:
            raise QueueError("Admission record invalid; no dispatch permitted") from exc

    def save(self, doc):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(dir=self.path.parent, prefix=".queue-")
        try:
            with os.fdopen(fd, "w") as f:
                json.dump(doc, f, sort_keys=True, indent=2)
                f.flush()
                os.fsync(f.fileno())
            os.replace(name, self.path)
            # Admission is deliberately fail-closed on unsupported durability.
            directory = os.open(self.path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if os.path.exists(name):
                os.unlink(name)

    @contextlib.contextmanager
    def locked(self):
        if lr._fcntl is None:
            raise QueueError("Durable admission requires supported POSIX locking")
        with lr._FileLock(self.path):
            yield self.read()

    def entry(self, doc, entry_id):
        for entry in doc["entries"]:
            if entry["entry_id"] == entry_id:
                return entry
        raise QueueError("Queue entry not found")

    def parent(self, doc, parent_id=None, generation=None):
        import orchestrator as orch
        parent = doc.get("parent")
        if not isinstance(parent, dict):
            raise QueueError("NOT_ATTACHED: attach an active parent before admission")
        if parent.get("pid") is not None and not orch.pid_alive(parent["pid"]):
            raise QueueError("NOT_ATTACHED: owning execution ended")
        if parent_id is not None:
            if parent["id"] != parent_id or doc["generation"] != generation:
                raise QueueError("Stale parent generation")
            parent["last_contact"] = time.time()
        elif time.time() - parent.get("last_contact", 0) > 120:
            raise QueueError("UNKNOWN: parent contact is stale")
        return parent

    def evidence(self, entry):
        """Re-read the approved packet, never trust a stored READY flag."""
        import orchestrator as orch
        binding = entry.get("binding")
        if not binding:
            return None, "PO preparation required"
        try:
            relay = orch._load_relay(orch._resolve_relay_file(self.relay_dir, binding["movement_id"]))
            first = relay["entries"][0]
            if orch.task_hash(first) != binding["task_hash"]:
                return None, "Approved task changed"
            if relay.get("status") == "CLOSED":
                return None, "Movement already closed; review its delivery evidence"
            report = first["report"]
            if not orch.resolve_authority_status_ok(report.get("baseline", {}).get("authority", ""), repo_root=self.repo):
                return None, "Frozen authority unavailable"
            if orch._packet_validation_errors(first, binding["movement_id"]):
                return None, "Approved packet invalid"
            if orch._git_rev_parse(report["git"]["base"], self.repo) != binding["base_sha"]:
                return None, "Baseline changed; PO must revalidate binding"
            return first, None
        except (orch.OrchestratorError, KeyError, TypeError, OSError):
            return None, "Approved movement evidence unavailable"

    def occupancy(self, doc):
        import orchestrator as orch
        occupied = set(doc["reservations"])
        for record in orch._list_state_records(self.state_dir):
            movement = record["movement_id"]
            if movement in occupied:
                continue
            if orch.pid_alive(record.get("pid")) or orch.pid_alive(record.get("run_pid")):
                occupied.add(movement)
            elif record.get("phase") not in orch.TERMINAL_PHASES:
                raise QueueError("Unknown activity: " + movement)
        # Legacy live/nonterminal records cannot silently disappear from capacity.
        if orch.LEGACY_STATE_DIR.resolve() != self.state_dir:
            for record in orch._list_state_records(orch.LEGACY_STATE_DIR):
                if orch.pid_alive(record.get("pid")) or record.get("phase") not in orch.TERMINAL_PHASES:
                    raise QueueError("Unreconciled legacy activity")
        return occupied

    def blocker(self, doc, entry, source_ids):
        if entry["source_id"] not in source_ids:
            return "Source is no longer in the open work pool"
        _, reason = self.evidence(entry)
        if reason:
            return reason
        binding = entry["binding"]
        entries = {e["source_id"]: e for e in doc["entries"]}
        for source in binding["dependencies"]:
            dependency = entries.get(source)
            if not dependency or not dependency.get("delivery_accepted"):
                return "Dependency awaiting accepted delivery: " + source
        mine = binding["resources"]
        def overlaps(other):
            return any(a == "*" or b == "*" or a == b or a.startswith(b.rstrip('/') + '/')
                       or b.startswith(a.rstrip('/') + '/') for a in mine for b in other)
        for other in doc["entries"]:
            if other is entry:
                continue
            if other["state"] in RESERVED or (other["state"] == "TERMINAL" and not other.get("delivery_accepted")):
                if overlaps((other.get("binding") or {}).get("resources", ["*"])):
                    return "Scope awaiting release: " + other["source_id"]
        known = {e.get("binding", {}).get("movement_id") for e in doc["entries"] if e["state"] in RESERVED}
        if self.occupancy(doc) - known:
            return "Outside-batch activity requires scope reconciliation"
        return None

    def snapshot(self):
        doc = self.read()
        items = pool(self.repo)
        by_id = {i["id"]: i for i in items}
        try:
            parent = self.parent(doc)
            loop = "PAUSED" if doc["control"] == "PAUSED" else "WAITING"
        except QueueError as exc:
            parent, loop = None, "UNKNOWN" if str(exc).startswith("UNKNOWN") else "NOT_ATTACHED"
        try:
            occupied = len(self.occupancy(doc))
            capacity_error = None
        except QueueError as exc:
            occupied, capacity_error = None, str(exc)
            loop = "UNKNOWN"
        entries = []
        for entry in doc["entries"]:
            reason = None
            if entry["state"] == "PENDING":
                try:
                    reason = self.blocker(doc, entry, set(by_id))
                except QueueError as exc:
                    reason = str(exc)
            state = ("BLOCKED" if reason else "READY") if entry["state"] == "PENDING" else entry["state"]
            if state == "RUNNING" and parent:
                loop = "RUNNING"
            entries.append({"entry_id": entry["entry_id"], "source_id": entry["source_id"],
                            "title": by_id.get(entry["source_id"], {}).get("title", entry["source_id"]),
                            "state": state, "blocker": reason, "movement_id": entry.get("binding", {}).get("movement_id"),
                            "delivery_accepted": entry.get("delivery_accepted", False)})
        selected = {e["source_id"] for e in entries}
        return {"revision": doc["revision"], "control": doc["control"], "loop": loop,
                "limit": doc["limit"], "occupied": occupied, "capacity_error": capacity_error,
                "pool": [{**item, "selected": item["id"] in selected} for item in items],
                "entries": entries, "can_start": bool(parent and doc["approved"])}

    def mutate(self, request):
        if not isinstance(request, dict):
            raise QueueError("Expected object")
        op = request.get("operation")
        specific = {"ADD": {"source_ids"}, "MOVE": {"entry_id", "direction"},
                    "REMOVE": {"entry_id"}, "START": set(), "PAUSE": set(), "RESUME": set()}
        if not isinstance(op, str) or op not in specific or set(request) != {"operation", "expected_revision", "operation_id"} | specific[op]:
            raise QueueError("Unknown operation or payload fields")
        operation_id = _id(request["operation_id"])
        if type(request["expected_revision"]) is not int:
            raise QueueError("Expected integer revision")
        digest = _hash(request)
        source_ids = {i["id"] for i in pool(self.repo)}
        with self.locked() as doc:
            receipt = doc["receipts"].get(operation_id)
            if receipt:
                if receipt["digest"] != digest:
                    raise QueueError("Operation ID reused with different payload")
                return receipt["result"]
            if request["expected_revision"] != doc["revision"]:
                raise QueueError("Queue changed; refresh before editing")
            if op == "ADD":
                ids = request["source_ids"]
                if not isinstance(ids, list) or not 1 <= len(ids) <= 100 or any(not isinstance(i, str) for i in ids):
                    raise QueueError("Select 1–100 source IDs")
                existing = {e["source_id"] for e in doc["entries"]}
                if len(ids) != len(set(ids)) or set(ids) & existing or not set(ids) <= source_ids:
                    raise QueueError("Duplicate or unavailable source ID")
                doc["entries"].extend({"entry_id": str(uuid.uuid4()), "source_id": i, "state": "PENDING"} for i in ids)
            elif op in {"MOVE", "REMOVE"}:
                entry = self.entry(doc, request["entry_id"])
                if entry["state"] != "PENDING":
                    raise QueueError("Claimed work cannot be moved or removed")
                index = doc["entries"].index(entry)
                if op == "REMOVE":
                    doc["entries"].remove(entry)
                    doc["approved"] = [i for i in doc["approved"] if i != entry["entry_id"]]
                else:
                    if not isinstance(request["direction"], str) or request["direction"] not in {"up", "down"}:
                        raise QueueError("Invalid move direction")
                    target = index + (-1 if request["direction"] == "up" else 1)
                    if not 0 <= target < len(doc["entries"]) or doc["entries"][target]["state"] != "PENDING":
                        raise QueueError("Cannot move across running work or queue boundary")
                    doc["entries"][index], doc["entries"][target] = doc["entries"][target], entry
            elif op in {"START", "RESUME"}:
                self.parent(doc)
                if not doc["approved"]:
                    raise QueueError("PO must approve the selected batch first")
                doc["control"] = "RUNNING"
            else:
                doc["control"] = "PAUSED"
            doc["revision"] += 1
            result = {"revision": doc["revision"], "operation_id": operation_id}
            doc["receipts"][operation_id] = {"digest": digest, "result": result}
            self.save(doc)
            return result

    def attach(self, parent_id, pid=None, transfer=False):
        import orchestrator as orch
        _id(parent_id)
        if pid is not None and (type(pid) is not int or not orch.pid_alive(pid)):
            raise QueueError("If supplied, the owning execution PID must be alive")
        with self.locked() as doc:
            if doc["parent"] and not transfer:
                raise QueueError("Existing owner: explicit transfer and reconciliation required")
            self.occupancy(doc)
            if doc["reservations"]:
                raise QueueError("Resolve existing reservations before ownership transfer")
            doc["generation"] += 1
            doc["parent"] = {"id": parent_id, "pid": pid, "last_contact": time.time()}
            doc["control"] = "PAUSED"
            doc["revision"] += 1
            self.save(doc)
            return {"parent_id": parent_id, "generation": doc["generation"]}

    def bind(self, entry_id, movement_id, provider, model, effort, budget, resources, dependencies):
        import orchestrator as orch
        _id(movement_id)
        if provider not in orch.op.PROVIDERS or not model or not effort or type(budget) not in (int, float) or not math.isfinite(budget) or budget <= 0:
            raise QueueError("Explicit provider/model/effort and positive budget required")
        orch.op.validate_effort(provider, effort)
        if not resources or any(not isinstance(r, str) or not r or r.startswith('/') or '..' in r.split('/') for r in resources):
            raise QueueError("Explicit repository-relative conflict scopes required")
        relay = orch._load_relay(orch._resolve_relay_file(self.relay_dir, movement_id))
        first = relay["entries"][0]
        if orch._load_state(self.state_dir, movement_id):
            raise QueueError("Bind a fresh approved movement; never silently resume existing work")
        binding = {"movement_id": movement_id, "task_hash": orch.task_hash(first),
                   "base_sha": orch._git_rev_parse(first["report"]["git"]["base"], self.repo),
                   "provider": provider, "model": model, "effort": effort, "budget": budget,
                   "resources": resources, "dependencies": dependencies}
        with self.locked() as doc:
            entry = self.entry(doc, entry_id)
            if entry["state"] != "PENDING" or any(e.get("binding", {}).get("movement_id") == movement_id and e is not entry for e in doc["entries"]):
                raise QueueError("Claimed entry or duplicate movement")
            entry["binding"] = binding
            doc["approved"] = [i for i in doc["approved"] if i != entry_id]
            doc["revision"] += 1
            self.save(doc)

    def approve(self, parent_id, generation, limit=2):
        if type(limit) is not int or not 1 <= limit <= 3:
            raise QueueError("Concurrency must be 1–3")
        with self.locked() as doc:
            self.parent(doc, parent_id, generation)
            doc["approved"] = [e["entry_id"] for e in doc["entries"] if e.get("binding") and (e["state"] == "PENDING" or e["entry_id"] in doc["approved"])]
            doc["limit"] = limit
            doc["revision"] += 1
            self.save(doc)

    def reconcile(self, parent_id, generation):
        import orchestrator as orch
        with self.locked() as doc:
            self.parent(doc, parent_id, generation)
            released = []
            for movement, reservation in list(doc["reservations"].items()):
                if reservation.get("entry_id") is not None:
                    continue
                record = orch._load_state(self.state_dir, movement)
                if (record and record.get("admission_claim") == reservation["claim"]
                        and record.get("phase") in orch.TERMINAL_PHASES
                        and not orch.pid_alive(record.get("pid"))
                        and not orch.pid_alive(reservation.get("wrapper_pid"))):
                    del doc["reservations"][movement]
                    released.append(movement)
            if released:
                doc["revision"] += 1
                self.save(doc)
            return {"released": released}

    def claim(self, parent_id, generation):
        ids = {i["id"] for i in pool(self.repo)}
        with self.locked() as doc:
            self.parent(doc, parent_id, generation)
            if doc["control"] != "RUNNING":
                raise QueueError("Queue paused")
            if len(self.occupancy(doc)) >= doc["limit"]:
                return {"claim": None, "reason": "All execution slots occupied"}
            for entry in doc["entries"]:
                if entry["state"] != "PENDING" or entry["entry_id"] not in doc["approved"]:
                    continue
                reason = self.blocker(doc, entry, ids)
                if reason:
                    continue
                binding = entry["binding"]
                token = str(uuid.uuid4())
                entry["state"] = "CLAIMED"
                doc["reservations"][binding["movement_id"]] = {"claim": token, "generation": generation,
                    "entry_id": entry["entry_id"], "state": "CLAIMED", "wrapper_pid": None, "engineer_pid": None}
                doc["revision"] += 1
                self.save(doc)
                return {"claim": token, "generation": generation, **binding}
            return {"claim": None, "reason": "No compatible, approved READY work"}

    def complete(self, movement_id, claim, parent_id, generation, accept_delivery=False):
        import orchestrator as orch
        _id(claim)
        with self.locked() as doc:
            self.parent(doc, parent_id, generation)
            entry = next((e for e in doc["entries"] if e.get("binding", {}).get("movement_id") == movement_id), None)
            if not entry:
                raise QueueError("Movement not in queue")
            if entry.get("completion_claim") == claim:
                if accept_delivery:
                    raise QueueError("Use delivery acceptance after reviewing the completed movement")
                return {"completed": True}
            reservation = doc["reservations"].get(movement_id)
            if not reservation or reservation["claim"] != claim:
                raise QueueError("Unknown claim")
            record = orch._load_state(self.state_dir, movement_id)
            if (not record or record.get("admission_claim") != claim or record.get("phase") not in orch.TERMINAL_PHASES
                    or orch.pid_alive(record.get("pid")) or orch.pid_alive(reservation.get("wrapper_pid"))):
                raise QueueError("Terminal engineer/wrapper evidence incomplete; reservation retained")
            relay = orch._load_relay(orch._resolve_relay_file(self.relay_dir, movement_id))
            closes = [e for e in relay.get("entries", []) if e.get("marker") == "SESSION_CLOSE"]
            if not closes:
                raise QueueError("No terminal relay report; PO reconciliation required")
            entry.update(state="TERMINAL", completion_claim=claim, close_seq=closes[-1].get("seq"), delivery_accepted=False)
            del doc["reservations"][movement_id]
            doc["revision"] += 1
            self.save(doc)
            return {"completed": True, "outcome": closes[-1].get("outcome"), "failure_reason": record.get("failure_reason")}

    def accept(self, entry_id, parent_id, generation):
        """Parent-only attestation after existing delivery gates; never called by UI."""
        import orchestrator as orch
        with self.locked() as doc:
            self.parent(doc, parent_id, generation)
            entry = self.entry(doc, entry_id)
            record = orch._load_state(self.state_dir, entry.get("binding", {}).get("movement_id", ""))
            if entry["state"] != "TERMINAL" or not record or record.get("phase") != orch.PHASE_DONE or not (record.get("verify") or {}).get("passed"):
                raise QueueError("Delivery gates are not green")
            relay = orch._load_relay(orch._resolve_relay_file(self.relay_dir, entry["binding"]["movement_id"]))
            closes = [e for e in relay.get("entries", []) if e.get("marker") == "SESSION_CLOSE"]
            if not closes or str(closes[-1].get("outcome", "")).upper() != "DONE":
                raise QueueError("A PARTIAL/BLOCKED close cannot satisfy delivery dependencies")
            entry["delivery_accepted"] = True
            doc["revision"] += 1
            self.save(doc)


def domain_path(repo):
    import subprocess
    result = subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=repo, capture_output=True, text=True)
    if result.returncode:
        return None
    return (Path(repo) / result.stdout.strip()).resolve() / "nexus-admission-domain.json"


def register_domain(queue):
    path = domain_path(queue.repo)
    if path is None:
        raise QueueError("Canonical repository unavailable")
    if lr._fcntl is None:
        raise QueueError("Admission locking unavailable")
    with lr._FileLock(path):
        if path.exists():
            if json.loads(path.read_text()).get("state_dir") != str(queue.state_dir):
                raise QueueError("Another canonical admission state root is registered")
        else:
            # Same durable writer, separate domain pointer; never repository metadata.
            writer = copy.copy(queue)
            writer.path = path
            writer.save({"state_dir": str(queue.state_dir), "relay_dir": str(queue.relay_dir)})


def guarded_start(args, start):
    """One gate for start/run/resume after this repository enables admission."""
    import orchestrator as orch
    path = domain_path(Path(args.relay_dir).resolve().parent)
    if path is None or not path.exists():
        if getattr(args, "queue_claim", None):
            raise QueueError("Admission domain not registered")
        return start(args)
    domain = json.loads(path.read_text())
    if str(Path(args.state_dir).resolve()) != domain["state_dir"] or str(Path(args.relay_dir).resolve()) != domain["relay_dir"]:
        raise QueueError("Alternate admission state/relay root refused")
    queue = Queue(Path(args.state_dir), Path(args.relay_dir))
    with queue.locked() as doc:
        token = getattr(args, "queue_claim", None)
        reservation = doc["reservations"].get(args.movement)
        entry = None
        if token:
            if not reservation or reservation["claim"] != token or reservation["state"] != "CLAIMED":
                raise QueueError("Claim missing or already dispatched; reconcile instead of retrying")
            parent_id = _id(getattr(args, "queue_parent", None))
            queue.parent(doc, parent_id, getattr(args, "queue_generation", None))
            if doc["control"] != "RUNNING":
                raise QueueError("Queue paused; unspawned claim retained")
            if len(queue.occupancy(doc)) > doc["limit"]:
                raise QueueError("Capacity lowered; reconcile reservations before dispatch")
            entry = queue.entry(doc, reservation["entry_id"])
            if entry["entry_id"] not in doc["approved"]:
                raise QueueError("Entry lacks current batch authority")
            _, reason = queue.evidence(entry)
            if reason:
                raise QueueError(reason)
            binding = entry["binding"]
            args._queue_base_sha = binding["base_sha"]
            if any(getattr(args, arg, None) != binding[key] for arg, key in
                   (("provider", "provider"), ("model", "model"), ("effort", "effort"), ("max_budget_usd", "budget"))):
                raise QueueError("Dispatch differs from approved binding")
            if orch._load_state(queue.state_dir, args.movement):
                raise QueueError("Existing movement requires explicit reconciliation")
        else:
            if any(e.get("binding", {}).get("movement_id") == args.movement for e in doc["entries"]):
                raise QueueError("Selected movement requires its queue claim")
            if any(e["state"] in RESERVED or (e["state"] == "TERMINAL" and not e.get("delivery_accepted")) for e in doc["entries"]):
                raise QueueError("Outside-batch scope compatibility is unproven")
            if reservation:
                raise QueueError("Movement already reserved")
            if len(queue.occupancy(doc)) >= min(args.max_workers, doc["limit"]):
                raise QueueError("Admission capacity exhausted")
            token = str(uuid.uuid4())
            reservation = {"claim": token, "state": "CLAIMED", "generation": doc["generation"],
                           "entry_id": None, "engineer_pid": None, "wrapper_pid": None}
            doc["reservations"][args.movement] = reservation
        reservation.update(state="DISPATCHING", wrapper_pid=os.getpid() if args.command == "run" else None)
        if entry:
            entry["state"] = "DISPATCHING"
        doc["revision"] += 1
        queue.save(doc)  # Lost acknowledgement retains capacity; no automatic retry.
        try:
            result = start(args)
        except BaseException:
            reservation["state"] = "DISPATCH_UNCERTAIN"
            if entry:
                entry["state"] = "DISPATCH_UNCERTAIN"
            queue.save(doc)
            raise
        rc, payload, proc = result
        if rc != orch.EXIT_OK:
            # This function returned before a spawn; exceptions after spawn take
            # the uncertainty path above and never release capacity.
            del doc["reservations"][args.movement]
            if entry:
                entry["state"] = "PENDING"
                doc["approved"] = [i for i in doc["approved"] if i != entry["entry_id"]]
        else:
            reservation.update(state="RUNNING", engineer_pid=proc.pid)
            if entry:
                entry["state"] = "RUNNING"
            record = orch._load_state(queue.state_dir, args.movement)
            orch._save_state(queue.state_dir, args.movement,
                             {**record, "admission_claim": token, "run_pid": reservation["wrapper_pid"]})
        doc["revision"] += 1
        queue.save(doc)
        return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", required=True)
    parser.add_argument("--relay-dir", required=True)
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("status")
    attach = sub.add_parser("attach")
    attach.add_argument("--parent-id", required=True)
    attach.add_argument("--parent-pid", type=int, help="Optional corroborating execution PID; never use the application PID as turn evidence")
    attach.add_argument("--transfer", action="store_true")
    bind = sub.add_parser("bind")
    for name in ("entry-id", "movement-id", "provider", "model", "effort"):
        bind.add_argument("--" + name, required=True)
    bind.add_argument("--budget", type=float, required=True)
    bind.add_argument("--resource", action="append", required=True)
    bind.add_argument("--dependency", action="append", default=[])
    for name in ("approve", "next", "complete", "accept", "detach", "reconcile"):
        command = sub.add_parser(name)
        command.add_argument("--parent-id", required=True)
        command.add_argument("--generation", type=int, required=True)
        if name == "approve":
            command.add_argument("--limit", type=int, default=2, choices=(1, 2, 3))
        if name == "complete":
            command.add_argument("--movement-id", required=True)
            command.add_argument("--claim", required=True)
        if name == "accept":
            command.add_argument("--entry-id", required=True)
    change = sub.add_parser("mutate")
    change.add_argument("--request", required=True, help="Typed mutation JSON, same as the dashboard")
    args = parser.parse_args(argv)
    queue = Queue(Path(args.state_dir), Path(args.relay_dir))
    try:
        if args.action == "status":
            result = queue.snapshot()
        elif args.action == "attach":
            register_domain(queue)
            result = queue.attach(args.parent_id, args.parent_pid, args.transfer)
        elif args.action == "bind":
            result = queue.bind(args.entry_id, args.movement_id, args.provider, args.model, args.effort,
                                args.budget, args.resource, args.dependency)
        elif args.action == "approve":
            result = queue.approve(args.parent_id, args.generation, args.limit)
        elif args.action == "reconcile":
            result = queue.reconcile(args.parent_id, args.generation)
        elif args.action == "next":
            result = queue.claim(args.parent_id, args.generation)
        elif args.action == "complete":
            result = queue.complete(args.movement_id, args.claim, args.parent_id, args.generation)
        elif args.action == "accept":
            result = queue.accept(args.entry_id, args.parent_id, args.generation)
        elif args.action == "detach":
            with queue.locked() as doc:
                queue.parent(doc, args.parent_id, args.generation)
                doc["parent"] = None
                doc["control"] = "PAUSED"
                doc["generation"] += 1
                doc["revision"] += 1
                queue.save(doc)
            result = {"detached": True}
        else:
            result = queue.mutate(json.loads(args.request))
        print(json.dumps(result if result is not None else {"ok": True}))
        return 0
    except (QueueError, lr.LocalRelayError, pq.QueueToolError, OSError, ValueError) as exc:
        print(json.dumps({"error": str(exc)}))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
