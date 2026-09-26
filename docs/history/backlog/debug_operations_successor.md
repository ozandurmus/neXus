# General Debug screen with optional command catalog, PO-approved agent execution, and role-aware output

status: in_progress · target: docs/design/DEBUG_OPERATIONS_SUCCESSOR_DECISION_2026_09_26.md

PO approved the product direction on 2026-09-26. Existing pilot remains deployed at schema 90. Architecture preparation identified runtime gate registration, protected agent attribution, and complete output privacy as implementation boundaries. Existing failover services and encrypted artefact delivery should be evaluated for reuse without copying operation-specific approval rules.

PO narrowed scope: Phase 1 is only a diagnostic device/command/output screen for FortiManager and other firewalls, using existing neXus jobs and transports. Optional saved commands are Phase 2. Automation, scripts, configuration writes and failover are excluded. No implementation or device command has run for this successor.

Phase 1 local implementation: all-device listing, role-aware device names, masked read inspection and always-visible result panel. 203 frontend tests, targeted diagnostic Java tests and 29 render/architecture checks pass (one harness skip). Full command execution, agent approval and administrator response delivery remain unfinished pending output-lifetime decision and implementation contract freeze. No deployment or device execution.
