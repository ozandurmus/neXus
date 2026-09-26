# FortiManager physical link state: diagnose hardware info nic per port (gate + read)

status: in_progress · target:

2026-09-26: V87 gated diagnose hardware info nic <port> and dry-ran the migration on HOST-A. One aiview FortiManager inventory job completed; all 12 NIC probes were rejected by the appliance CLI. The worker probe was removed in the follow-up change. Physical link is UNSUPPORTED/UNKNOWN; configured state remains separately observed. Next: identify a FortiManager 7.4.11 documented read, gate it, measure output shape, then parse and verify under aiview.
