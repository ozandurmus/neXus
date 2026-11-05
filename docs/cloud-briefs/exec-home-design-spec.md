Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/exec-home-design-spec-1105 origin/cloud-base/1105. Read AGENTS.md first (binding: masking law with synthetic FW-TANGO-04 / 192.0.2.x data only, English artifacts, UNKNOWN over guessing). No access to the live system or devices.

TASK (design document, written as a senior product/UI designer): produce docs/design/EXEC_HOME_AND_TRACE_DESIGN_SPEC_2026_11_05.md, a precise visual and interaction spec for:
(1) the executive home (UI review item C1: a verdict word, a freshness sentence, 4 pillars, at most 5 attention lines, below-the-fold framework bars and the 24 h pulse);
(2) the Trace screen (C3: path and lookup modes, the path diagram, advanced fields).
Base it on docs/design/UI_REVIEW_2026_11_05_ARGON.md, UI_REVIEW_2026_11_05_FABLE.md and UI_REVIEW_2026_11_05_SYNTHESIS.md, and on the existing design tokens and components in ui2/frontend (find the theme and tokens files and the M3 widgets).
Specify:
- layout grids and spacing tokens;
- typography;
- colour semantics for states (OK / warn / critical / UNKNOWN / stale);
- empty, stale and error states;
- responsive behaviour at 1280, 1440 and 1920 px;
- accessibility (contrast, keyboard, aria);
- an exact copy for every label in plain language.
Include ASCII wireframes. No code.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/exec-home-design-spec-1105, no PR.