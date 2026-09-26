# FortiManager physical link state: diagnose hardware info nic per port (gate + read)

status: in_progress · target:

2026-09-26: V87 gated diagnose hardware info nic <port> and dry-ran the migration on HOST-A. One aiview FortiManager inventory job completed; all 12 NIC probes were rejected by the appliance CLI. The worker probe was removed in the follow-up change. Physical link is UNSUPPORTED/UNKNOWN; configured state remains separately observed. Next: identify a FortiManager 7.4.11 documented read, gate it, measure output shape, then parse and verify under aiview.

2026-09-26 follow-up: V88 diagnose system print interface produced a 13-line interface-information shape without an explicit link field. V89 diagnose fmnetwork interface detail was documented and gated; one aiview inventory job completed, but the real 7.4.11 output likewise had no Status field (status token ABSENT). No physical-link value was inferred. The diagnostic probe was removed after measurement. Link evidence remains UNKNOWN for this FortiManager VM; the existing displayed up/down is configured enable/disable. Next requires a vendor-proven link source or a PO decision to label the UI as configured state.
