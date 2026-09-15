# Workbench Companion C5 lifecycle artifacts

Status: **DRAFT operational runbook — installation is not authorized.**
Implementation authority: [FROZEN companion contract](../design/WORKBENCH_COMPANION_CONTRACT.md), §§4–6 and 9.

## Delivered boundary

`python3 -m scripts.workbench_companion_launchagent` emits either a macOS
LaunchAgent XML plist (`plist`) or a JSON lifecycle/removal plan (`plan`) to
stdout. It performs no installation, launchctl calls, source reads, notification
delivery or deletion. All paths are explicit local inputs; no arbitrary argv,
environment mapping, credentials, raw-log paths or notification text is accepted.
Generated plans contain local paths and stay private; do not publish them.

The plist uses a fixed label, GUI login-session scope, foreground supervision,
30-second restart throttle, umask 077, disabled core dumps and discarded stdout
and stderr. Polling and duplicate-instance exclusion remain C2 `Observer.run`
and its advisory lock, rather than a second scheduler or PID-based kill path.
No browser, MCP client, plugin cache or terminal supervises this job.

Apple's [launchd guide](https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPSystemStartup/Chapters/CreatingLaunchdJobs.html)
defines per-user agents; the workstation's `launchd.plist(5)` and `launchctl(1)`
manuals define the generated keys and command syntax. Exact macOS version
compatibility and effective restart timing remain **UNVERIFIED**.

## Generate for review

Supply `--home`, `--release`, `--configuration` and `--snapshot-directory` as
absolute, canonical paths under the authorized user's home. Release,
configuration and snapshot roots must be separate, outside the source checkout
and LaunchAgents directory. The release input must identify a reviewed immutable
version, not a mutable `current` link or an ephemeral worker checkout.

The generated executable path is `RELEASE/bin/nexus-workbench-observer`, with
exact argv `--configuration CONFIGURATION`. **That packaged launcher is a future
installation prerequisite, not an executable provided or certified by this
generator.** The inspected C2 implementation currently exposes an `Observer`
Python API, not a runnable service/configuration CLI. Packaging must connect a
reviewed C1 scan callback to that API, pin dependencies and configuration schema,
and handle SIGTERM by stopping the loop and releasing its lock. Do not substitute
a shell wrapper, MCP server, dashboard collector or raw-log reader.

## Future explicitly authorized installation

1. Verify the host authorization and record exact OS/release versions. Validate
   ownership, regular-file type, restrictive permissions and absence of symlink
   replacements on opened descriptors for release/configuration and approved
   source roots. Lexical generator checks alone do not prove these properties.
   Reject source/relay/sync/share state locations. Configuration must pin the
   same snapshot directory supplied to the generator. Use 0700 directories and
   0600 configuration, plist and snapshot files; executable is user-only 0700.
2. Review and retain an exact companion-owned file inventory. Place only the
   generated plist at the plan's fixed `plist_path`, without overwriting an
   unrelated file or unrecognized existing installation. Do not copy any raw
   runtime data into the release or plugin.
3. Execute the plan's `start` argv in order: enable only the companion service,
   then bootstrap its exact plist in `gui/UID`. Do not target the entire domain,
   use sudo, force-kickstart, or bypass the throttle. If already loaded, inspect
   `status` rather than loading another instance. launchctl errors are fixed
   local failure conditions, never permission to broaden scope.
4. Independently validate with synthetic sources and browser/client closed:
   baseline emits no historical alerts, fixture changes are observed, duplicate
   start is rejected, restart is throttled and preserves state, SIGTERM leaves
   a complete snapshot, sleep/logout gaps reconcile once, and unsupported/newer
   state stops writes. These host tests have **not** been performed here.

## Stop, uninstall and rollback

Execute `stop`/`uninstall.commands` sequentially: disable the exact companion
service first to prevent next-login reload, then boot it out. Confirm supervision
and the observer have stopped before removing any file. If bootout fails, retain
executables/configuration until absence is independently established. Never kill
from a stale PID file or remove an entire launchctl domain.

Remove only verified, inventory-recorded owned files. The plan lists the plist,
fixed executable and configuration; extra reviewed release files require their
own exact inventory. Never recursively remove a release directory. Preserve
`snapshot_directory`, snapshot and lock by default, plus every upstream record,
relay, usage cache and ledger. Plugin/MCP removal is independent and must preserve
unrelated entries. No purge command is supplied. A future explicit purge needs
separate authorization for companion-owned derived history only.

To roll back, keep supervision disabled and restore a compatible reviewed
release; never rewrite a newer snapshot schema with an older binary.

## Notifications and support

Notification delivery remains **UNSUPPORTED**, including when OS permission is
denied. This slice has no enable flag, notifier, click target or delivery attempt;
C2 notification bookkeeping remains unchanged and separate from workflow
authority. Allowed safe transitions remain queryable through MCP. Native delivery
and its atomic at-most-once/coalescing integration require separate approved
implementation and workstation validation. Linux, Windows, WSL, remote/shared
accounts and unvalidated clients remain deferred by the frozen contract.

## Synthetic validation

Run `python3 -m pytest -q -p no:cacheprovider tests/test_workbench_companion.py`,
the repository privacy gate and `git diff --check origin/main`. Synthetic tests
verify plist round-trip, fixed argv and supervision settings, invalid paths,
non-execution, exact stop ordering and snapshot preservation, and reuse of C2
duplicate/restart-gap semantics. They do not certify an installed macOS service.

NXS-LOCAL-0256 validation, 2026-09-16: companion plus contract-authority and
cross-reference tests passed (93 tests); repository privacy gate passed with
zero findings. Lifecycle artifacts are AUTOMATED_VALIDATED. Actual installation,
packaged-launcher integration and native notifications remain unvalidated/deferred.
