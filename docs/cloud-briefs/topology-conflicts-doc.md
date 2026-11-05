Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/topology-conflicts-doc-1105 origin/cloud-base/1105. Read AGENTS.md first (binding: masking law, English, UNKNOWN over guessing). No access to the live system. Small task: finish within about 15 minutes.

TASK (document only): path analysis topology (ui2/service/.../pathanalysis/topology) reports about 250 conflicts on the live estate.
From the code, write docs/design/TOPOLOGY_CONFLICTS_GUIDE_2026_11_05.md:
- every conflict type the builder emits;
- the exact condition for each;
- what it means operationally;
- which are expected (for example overlapping private ranges in separate VRFs) and which indicate data or parsing problems;
- how the 2026-11-04 VSX cluster_virtual change (commit ed068bbb) should reduce one class;
- what an operator or override can do about each.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/topology-conflicts-doc-1105, no PR.