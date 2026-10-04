# Phase 0.4.3 - Check Point Management State + Failed Output Isolation

## Goals

Phase 0.4.3 keeps the existing Check Point collection method and adds two distinctions required by real production inventories:

1. A gateway can legitimately exist in Check Point management while it is currently non-communicating/down.
2. A failed `cprid_util` response must never be parsed as interface or route inventory.

No VSX, Panorama, merge, HTML, CSS or JavaScript collection behavior is changed in this build.

## Check Point discovery

The existing `cpmiquerybin` discovery now also requests `connection_state`:

```text
__name__, ipaddr, connection_state
```

The regular gateway branch is no longer pre-filtered to only `connection_state='communicating'`. This allows F-Buddy to distinguish devices registered in management from devices from which live runtime inventory can currently be collected.

Behavior:

- `communicating`: live `cprid_util` collection is attempted.
- explicit non-communicating state: live commands are intentionally skipped and the device is recorded as `management_down`.
- missing/unknown state: collection is still attempted. Unknown state is never treated as down by assumption.

A management-down device is an operational availability observation, not a collector error.

## Failed command output isolation

The live commands remain unchanged:

```text
ip -details -4 addr show
ip -4 route show table all
```

Each `cprid_util` call now writes first to temporary output/error files. Only a successful (`rc=0`) non-empty command result is promoted to the normal parser RAW path.

Failed/timeout/empty output is moved to the collector-owned remote error area:

```text
/home/admin/cp_raw/errors/
```

and the normal `*_interfaces.txt` / `*_routes.txt` file is removed. Therefore command-error text cannot become a fake route or interface row.

The contents of remote `.err` files are not included in the shareable support bundle.

## Status model

The per-device collection status now includes:

```text
management_state
collection_outcome
```

`collection_outcome` is one of:

```text
success
partial
collection_failed
management_down
mdsenv_error
```

The run marker includes:

```text
discovered
attempted
successful
partial
failed
management_up
management_down
management_unknown
retried
recovered_after_retry
```

## Verification and support bundle

Verification no longer expects `discovered == parsed`. A management-down device is expected to have no fresh runtime object in the current CP parsed set.

The support bundle reports management-down devices separately from actual collector failures. Device identity remains HMAC-tokenized.

This distinction is intentionally designed as the input for Phase 0.5 Last-Known-Good behavior:

```text
management_down / collection_failed
        +
previous successful snapshot
        ->
last_known_good (stale, not removed)
```

## Regression

```text
37 passed
2 xfailed
0 failed
```

The two existing xfails are unchanged known data-semantics observations.
