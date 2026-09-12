# Pre-existing: DEV.0.5B prose-collision catalogue is stale, 6 tracked files trip test_repository_text_has_no_known_dlp_assignment_collision / legacy_redaction_collision

status: planned · target: 

PRE-EXISTING (not from this branch). Proof: both tests fail identically on the origin/main tree, and neither tests/test_dev_0_5b_auth_consumer_canonical_config.py nor utils/repository_privacy.py differs from origin/main (git diff --stat origin/main -- <both> is empty). All six colliding files are present on origin/main.

Failures:
tests/test_dev_0_5b_auth_consumer_canonical_config.py:110: in test_repository_text_has_no_known_dlp_assignment_collision
    assert _uncatalogued_prose_matches(pattern.search) == []
E   AssertionError: 6 uncatalogued files

tests/test_dev_0_5b_auth_consumer_canonical_config.py:115: in test_repository_text_has_no_known_legacy_redaction_collision
    assert _uncatalogued_prose_matches(lambda line: marker in line) == []
E   AssertionError: 4 uncatalogued files

Uncatalogued files -- DLP assignment-form collision (file + location only; matched values withheld per AGENTS.md sensitive identity reporting law; all matches are prose/test-fixture discussion of the redaction pattern itself, classification: DLP_PATTERN_COLLISION, not real credential material):
  docs/history/builds/gov_po_1_local_relay_watch_command.md
  relay/NXS-LOCAL-0030-credential-profiles-reference-model.json
  relay/NXS-LOCAL-0033-deploy1-database-migrations-and-roles-re.json
  tests/test_dev0_4_repository_privacy_gate.py
  tests/test_gov_po_3_ci_privacy_gate_baseline.py
  utils/repository_privacy.py

Uncatalogued files -- legacy redaction-marker collision:
  docs/history/builds/gov_po_1_local_relay_watch_command.md
  relay/NXS-LOCAL-0030-credential-profiles-reference-model.json
  tests/test_dev0_4_repository_privacy_gate.py
  utils/repository_privacy.py

Cause: the DEV.0.5B prose-collision catalogue was not extended as these files were added, so the guard reports them as new uncatalogued collisions. Needs a decision per file: catalogue the intentional occurrence, or reword the prose. Do NOT weaken the detector (AGENTS.md: do not weaken security detection to satisfy DLP).
