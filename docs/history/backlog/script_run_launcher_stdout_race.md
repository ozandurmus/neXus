# Script-run launcher stdout capture race: investigation not confirmed

status: planned · target: docs/reviews/SCRIPT_RUN_LAUNCHER_RACE_2026_11_22.md

Failed once in a loaded ship pretest; not reproduced in 200 unloaded and 400 loaded launcher runs (2026-11-22). Launcher unchanged.

2026-11-26 (5e8cef7a): root cause CONFIRMED on macOS only: BSD sed -u writes a line's text and newline in two write(2) calls, so the stdout and stderr taggers interleave (18/1200 exit-0 and 9/1200 exit-7 runs failed on the build Mac under load; 0/600 with one write per line). Linux pod image (GNU sed 4.8) stays UNKNOWN by measurement, no Linux runtime was available. The launcher is unchanged; the behavioural check now runs only with GNU sed and is skipped on BSD sed. Item stays planned until a Linux measurement exists.
