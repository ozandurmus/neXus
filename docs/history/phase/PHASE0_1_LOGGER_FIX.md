# Phase 0.1 - Logger startup safety fix

## Problem

A fresh checkout can fail on the first call to `info()` because `utils/logger.py` writes to
`logs/run_<id>.log` before the `logs/` directory exists. `main.py` does not explicitly call
`init_logger()` before its first log write.

## Change

`utils/logger.py::_write()` now creates the parent directory of `LOG_FILE` immediately before
opening the log file. The existing `init_logger()` behavior and the existing log filename format
are preserved.

No collector, parser, merge, HTML, UI, credential, network command, SSH, or Panorama API behavior
is changed by this patch.

## Regression test

The previous strict `xfail` for logging without explicit initialization is now a normal passing
test.

Expected test result after this patch:

    15 passed, 2 xfailed

The remaining expected failures document known VSX/Panorama correctness gaps and are not changed
in this patch.
