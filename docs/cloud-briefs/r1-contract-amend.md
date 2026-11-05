Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/r1-contract-amend-1105 origin/cloud-base/1105. Read AGENTS.md first (binding). No access to the live system. Keep it small: finish within about 15 minutes.

TASK (document only): append a dated amendment to docs/design/REPORTS_R1_CONTRACT.md that corrects three wrong references found by the R2 contract review.
(1) The ActionClass CLASS_0/CLASS_1 values are for network-device actions. Find in ActionRegistry how the existing non-device read actions and raw-view actions (for example the packet analysis raw view, audit_log_read) are classified, and use that same classification for report_read, report_generate and report_raw_view.
(2) 'Report projection masker' is the Packet Analysis masker. Name the correct general masking path for service responses (find it: PrivacyMaskingResponseBodyAdvice / TopologyNamePseudonymizer / SubnetPreservingIpMasker) for the structured report document.
(3) There is no general retention janitor. Name the actual retention mechanism for artefacts and say how report artefacts get expired.
Cite file paths.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/r1-contract-amend-1105, no PR.