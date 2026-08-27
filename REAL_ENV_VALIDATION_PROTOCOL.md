# SecurityExpert --- Real Environment Validation Protocol

## Purpose

Separate implementation correctness from real-device behavior.

The coding agent does not declare a network-facing feature DONE merely
because tests pass.

## Validation states

``` text
IMPLEMENTED
AUTOMATED_VALIDATED
REAL_ENV_VALIDATED
DONE
```

A feature can remain PARTIAL when only part of fleet coverage is proven.

## Human validation loop

Agent provides exactly one preferred validation command.

Human runs it in the controlled environment and returns preferably: -
SAFE SUMMARY, - sanitized relevant log lines, - screenshot if
visual/semantic evidence is required, - intentionally shareable support
artifact if needed.

Do not request credentials or full raw configuration.

## Evidence minimization

If one device must be investigated: - extract only that entity's safe
telemetry, - do not upload the entire telemetry corpus.

If UI proves a state, record the semantic observation rather than
requiring the whole screenshot forever.

## PASS / PARTIAL / FAIL

PASS: - acceptance criterion demonstrated in real environment.

PARTIAL: - mechanism works, but fleet coverage or secondary criteria
remain incomplete.

FAIL: - primary acceptance criterion not demonstrated or regression
introduced.

## Current CP example

B.1.2 is real-environment validated for interactive direct-Clish
collection.

The overall CP Configuration feature is still PARTIAL because 101/122
entities are current and 21 remain unavailable.

Do not collapse mechanism PASS and fleet-coverage PARTIAL into one
status.

## Safe summary preference

Prefer:

``` text
Successful
Unavailable
Capability gaps
Identity accepted
Platform coverage
Model/Serial/HA coverage
Shell profiles
Failure families/reasons
Workers
Duration
Security posture flags
```

over full command transcripts.

## Operational safety

If a run correlates with device availability impact: - stop performance
optimization, - preserve evidence, - audit interaction surface, - do not
claim causality without evidence, - do not increase concurrency, -
distinguish command read-only semantics from connection/session load.

## Destructive validation

Never ask the human to run destructive commands, `--apply`, device write
operations or migration without: 1. code-level verification, 2. dry-run
where possible, 3. explicit human approval, 4. rollback plan.
