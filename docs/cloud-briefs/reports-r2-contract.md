Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/reports-r2-contract-1105 origin/cloud-base/1105. Read AGENTS.md first (binding: masking law with synthetic FW-TANGO-04 / 192.0.2.x data only, English artifacts, UNKNOWN over guessing). No access to the live system or devices.

TASK (document only): write docs/design/REPORTS_R2_CONTRACT.md (status DRAFT) building on docs/design/REPORTS_R1_CONTRACT.md (FROZEN) and REPORTS_DESIGN_2026_11_05_ARGON.md (including its appended engineer decisions).
R2 scope: scheduled reports and e-mail delivery, plus five more reports (RPT-06 policy hygiene, RPT-07 policy change, RPT-08 configuration change/drift, RPT-09 lifecycle/EoL, RPT-10 inventory).
Specify:
- the report_schedule table, with the exact SQL checked against the REAL notification_routes schema (read migrations V100/V113/V160/V189; the design doc's SQL is wrong);
- cron evaluation in Europe/Istanbul, and how schedules coexist with development mode (UI2_SCHEDULING_ENABLED=false): follow the independent-switch pattern of SystemLogCollectionScheduler;
- SmtpRelaySender multipart attachments (no new mail library), a 10 MiB attachment cap with a link fallback, and e-mail always MASKED;
- RBAC (report_schedule_manage), audit, retention;
- for each new report: sections, data sources (actual classes and tables), the honesty rules for UNKNOWN shares, and tests;
- phasing and risks.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/reports-r2-contract-1105, no PR.