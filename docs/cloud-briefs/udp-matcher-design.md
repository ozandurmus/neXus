Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/udp-matcher-design-1105 origin/cloud-base/1105. Read AGENTS.md first (binding: masking law with synthetic FW-TANGO-04 / 192.0.2.x data only, English artifacts, UNKNOWN over guessing). No access to the live system or devices.

TASK (document only, no device commands): neXus path validation (A6; docs/design/PATH_ANALYSIS_A6_COMMAND_PROPOSAL.md, RATIFIED rows 1-5) uses Palo Alto test security-policy-match and nat-policy-match literals that hard-code <protocol>6</protocol>, so UDP flows are refused as PROTOCOL_NOT_APPROVED.
Write docs/design/PATH_ANALYSIS_A6_UDP_PROPOSAL.md (status DRAFT, for PO approval):
- the exact proposed literals for protocol 17;
- what changes in the worker and validator code (cite files);
- the command-gate fields (vendor, class, shell, timeout, retry, frequency, secret-output risk, safe telemetry), modelled on the existing V188 gate rows;
- the official Palo Alto documentation for the protocol field in these test commands (cite URLs; mark UNKNOWN where the docs are silent);
- whether ICMP needs a separate shape;
- the tests to add.
Make no code change that would issue new commands.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/udp-matcher-design-1105, no PR.