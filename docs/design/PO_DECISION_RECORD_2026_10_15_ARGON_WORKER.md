# PO decision record 2026-10-15 — Argon joins as a development worker and external reviewer; Codex retired

## Status

**RATIFIED — PRODUCT OWNER, 2026-10-15**, in chat: "Google Argon'da yetkinliğini gösterdi. Bazı konularda onu
çağırabiliriz ... Şu an için Codex kısımını atıyoruz ... Anlaşmamızda bu kısımı kalıcı olarak ekleyelim, bazı
geliştirmeleri ve işleri argona da yaptırabiliriz çünkü seninle yaptığımız geliştirmede paralel iş yaparken limitimiz
yetmiyor."

## Decision

1. **Argon** (Google Gemini 4 Argon through the Antigravity CLI `agy`, installed on the operator's Mac) is a standing
   neXus worker and external reviewer. **Codex** (`codex exec`, the Astra/Sol/Luna models) is no longer used; the
   existing `scripts/consult_codex_*.py` stay as history and are not run.
2. **Development lanes.** The orchestrating agent may give a development lane to Argon instead of an in-session
   subagent, to keep parallel work inside the usage budget. Invocation is only through
   `scripts/argon_task.py --worktree ~/Codo/nexus-<lane> --brief <brief>` (default model `gemini-4-argon-high`). The
   orchestrator stays the owner: it writes the brief, reviews the diff, runs the tests, ships through the normal PR
   gates and validates live. Argon output is never shipped unreviewed.
3. **Boundaries for Argon** (enforced by the script and by the CLI's own permission rules, template in
   `tools/argon/settings.template.json`, installed at `~/.gemini/antigravity-cli/settings.json`):
   - writes only inside `~/Codo/nexus-*` worktrees on a feature branch, never the main checkout;
   - commits on that branch; never pushes, opens PRs, deploys, or uses `gh`, `kubectl`, `docker`, `sudo`;
   - no network tools (`ssh`, `scp`, `sftp`, `rsync`, `curl`, `wget`, `nc`): it never reaches HOST-A or any device;
   - AGENTS.md applies to it in full (masking, raw-evidence, migrations, English artefacts, test honesty).
4. **External review.** Argon is an authorized external reviewer under AGENTS.md's consultation law, through
   `scripts/consult_argon.py --brief <file> --output docs/design/<NAME>.md` (read-only `--mode plan`, full output
   written unaltered). Claude/Fable consultations keep their existing `scripts/consult_claude_*.py` / `consult_fable_*`.
5. **Choice per task** stays with the orchestrator: judgement-heavy, cross-cutting or security-boundary work stays with
   Claude; well-specified implementation lanes with a parity or test oracle are good Argon lanes.
