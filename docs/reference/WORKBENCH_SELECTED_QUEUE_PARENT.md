# Selected queue — active parent procedure

Authority: `docs/design/WORKBENCH_SELECTED_WORK_QUEUE_CONTRACT.md` (FROZEN).
This is the procedure for the existing PO session, not a background service.

1. Use the canonical relay/state roots for every call. Read `selected_queue.py
   --help`; all subcommands take `--state-dir` and `--relay-dir` before the verb.
2. The user selects/reorders backlog items in Workbench. Read `status` to see
   exact source and entry IDs. Prepare an existing approved SESSION_START for
   each intended item under the normal PO rules. Do not infer a scope from its
   title. Preflight the actual authority, runtime/provider and baseline.
3. `attach --parent-id <active-task-id>`
   returns a generation. This is an explicit active-parent attestation; it does
   not discover or wake a conversation. An optional `--parent-pid` can corroborate
   a real owning execution; never substitute the application's long-lived PID.
   Stale parent contact appears UNKNOWN after two minutes and never releases
   reservations. Subsequent parent commands refresh the observed contact. Transfer requires the
   explicit `--transfer` flag and no unresolved execution reservations.
4. `bind --entry-id ... --movement-id ... --provider ... --model ... --effort ...
   --budget ... --resource <repo-relative-conflict-scope>` binds one prepared
   movement. Repeat `--resource` for all affected scopes. Use `*` when independence
   is unknown; include shared migration/schema resources. `--dependency <source-id>`
   means accepted delivery is required. Bind never changes the backlog or starts work.
5. `approve --parent-id ... --generation ... --limit 2` records the bounded batch
   authorization. The authenticated user can now Start/Resume in Workbench.
   Alternatively use `mutate --request <typed-JSON>` with a fresh status revision,
   unique operation ID and operation START. Do not enable a batch without its
   existing task/budget/dispatch authorization.
6. Call `next --parent-id ... --generation ...`. A null claim means full capacity
   or no compatible READY work; read the visible reason, do not fabricate a job.
   For a claim, give the existing native supervisor the normal `orchestrator run`
   invocation with matching approved provider/model/effort/budget and these flags:
   `--queue-claim <claim> --queue-parent <parent-id> --queue-generation <generation>`.
   It must retain the run's execution session until completion. Preserve all
   existing worktree/profile/timeouts/Git authorization options. Do not nohup the
   supervising task and then end it. Fill only the available reservations.
7. Answer side questions in commentary and continue native waiting. On completion,
   inspect the actual relay/verification/integration evidence. After the wrapper
   has exited, call `complete --movement-id ... --claim ... --parent-id ...
   --generation ...`. This records terminal execution; it does not accept delivery.
   Missing/uncertain evidence retains the reservation. Do not bypass or delete it.
8. Perform authorized delivery actions through the existing integration path.
   `accept --entry-id ... --parent-id ... --generation ...` is the PO attestation
   that the dependency/conflict scope may be released, after its actual gates.
   It neither merges nor authorizes merge. Preserve blocked integration visibly.
9. Refill with `next` after every handled completion. Pause stops new dispatch,
   not running work. Later additions need binding and batch approval; Resume never
   expands authority automatically.
10. Before ending ownership, `detach --parent-id ... --generation ...`. If blocked,
    report the actual blocker. Do not claim the persistent queue will wake an idle
    conversation. Application shutdown/restart and closed-turn recovery are not
    proven by this first release.

Validation must cover deterministic 20-item synthetic admission and a separately
budgeted two-job-plus-successor live pilot. The synthetic test proves admission;
it does not prove that a PO followed this procedure during a real active turn.
