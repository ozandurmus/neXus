# deploy_persistent_secret_material — Persistent runtime volume contract: HMAC key stability + mounted CP/PAN trust material (DEV.2.2)

## Summary

User request: close the DEV.2.2 persistent-volume contract left open by DEV.3.1. data/.support_hmac.key persistence was already structurally correct via runtime_paths.data_root; new utils/persistent_secret_material.py + main.py --persistent-secret-material-check make it explicit and offline-checkable, reusing utils.cp_ssh_trust / utils.pan_tls_trust preflight code verbatim (value-free, no network). New docker-compose.prod.yml overlay mounts deploy/secrets/known_hosts and pan-ca-bundle.pem read-only and sets SECURITYEXPERT_CP_MDS_STRICT_HOST_KEY=1 / SECURITYEXPERT_PAN_CA_BUNDLE, moving CP/PAN trust from opt-in to mounted-and-required on the server while docker-compose.yml keeps compatibility mode as the base default. No collector/transport/trust-logic semantic changed.

## Evidence

- **automated**: py -m pytest -q: 640 passed, 3 skipped, 2 failed (645 collected; +6 net from tests/test_dev2_2_persistent_secret_material.py). Two pre-existing failures (test_run_html_export_embeds_discovery_payload_without_leftover_placeholder, test_checkpoint_render_appends_one_record) reproduce identically on the unmodified branch head with zero changes applied (634 passed, same 2 failed, 3 skipped), confirmed via git stash -u before/after -- unrelated test-order state bleed, not introduced or fixed by this build.
- **manual_cli**: python main.py --persistent-secret-material-check exercised directly: PASS/advisory output with nothing enabled, no key material/path/credential printed; --apply and --render-only combined with the new flag correctly rejected via parser.error (exit 2), matching the --repository-privacy-check guard pattern.
- **compose_config**: docker compose -f docker-compose.yml -f docker-compose.prod.yml config confirms the overlay merges correctly (hardening env vars + two read-only bind mounts added to worker only). Full docker build/up blocked by this sandbox's TLS-intercepting proxy, same constraint already documented for linux_container_image.
- **privacy_gate**: python main.py --repository-privacy-check: PASS, 0 findings, including the new synthetic (RFC 5737) deploy/secrets/known_hosts.example placeholder.
