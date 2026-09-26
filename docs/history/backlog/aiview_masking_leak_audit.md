# Masking leak audit: compare aiview responses against the raw tables (hostnames, addresses, serials) and report any raw value that survives masking; consider a masked read-model/schema with a separate DB role for aiview

status: planned · target: PO 2026-09-22 proposal (separate masked table read by aiview)

2026-09-22 F20 found under aiview: cluster pseudonyms collide -- the space is one dictionary word x a digit 1-9 (hash[0] % dictionary, hash[1] % 9), so ~40 clusters produce collisions; the aiview device list shows masked cluster refs with 4, 5 and 6 members and 35-36 clusters instead of 39. Two real clusters merge in every aiview screen (Overview count, Operations, Configuration tree). Fix: collision-free pseudonyms (wider suffix, or a persisted pseudonym dictionary that guarantees uniqueness) + the leak/collision audit comparing masked vs raw distinct counts.

2026-09-23 F20 fixed: V53 pseudonym_registry makes cluster and standalone-device pseudonyms unique (computed candidate first, next free name otherwise, claim persisted by keyed hash, never the raw name). Non-colliding names are unchanged. The leak/collision audit itself is still open.

2026-09-26 aiview accessibility inspection: Devices list management row exposed an unmasked management address in its accessible button description while visible text was masked. Classification: sensitive operational identity. Reproduce under aiview and fix the accessibility-label projection; no raw value retained here.
