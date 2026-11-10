# PO decision record - 2026-11-10: log anomaly agent, first decisions

**Status: RATIFIED - PO DECISION (chat, 2026-11-10), direction only.** Inputs:
- [LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md](LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md) (DRAFT)
- [ARGON_LOG_ANOMALY_AGENT_REVIEW_2026_11_10.md](ARGON_LOG_ANOMALY_AGENT_REVIEW_2026_11_10.md), Argon's unaltered verdict: "build a reduced version"

This record approves no code, device command, outbound connection or credential. Phase 2 egress on real logs still needs the security review, a frozen contract and enterprise approval.

## 1. PO answers

| # | Question (design §12 numbering where it applies) | PO answer |
| --- | --- | --- |
| A1 | First step | AI trial first, before the deterministic detector |
| A2 | Disclosure level (§12.1) | Not chosen yet. "Burada zaten sanitizer modülümüz var. Logları önce bir göz analiz edip orada neler riskli bakıp ona göre veri çıkışı düşünmek gerek." (We already have the sanitizer. First analyse the logs for what is risky, then decide on egress.) |
| A3 | First provider (§12.3) | Claude (Anthropic) |
| A4 | Real names in explanations (§12.7) | Yes, for authorised roles (`operation_admin`, `security_admin`), audited per view |
| A5 | Model tier and ceiling (§12.4) | Measure in the trial first: Haiku 5.5, Sonnet 5.5 and Opus 5.5 side by side |
| A6 | Separate container (§12.6) | Yes, `ui2-llm-gateway`, on the trust-boundary ground |
| A7 | Audit retention (§12.8) | Request and response content 30 days; the call ledger 180 days, hash-chained, `security_admin` only |
| A8 | Who triggers a call (§12.5) | On demand only, by `operation_admin` and `security_admin`; no nightly automatic digest |
| — | Exclusions (§12.9), legal check (§12.10), traffic logs (§12.2) | Not asked. The design's recommendations stand until decided: exclusions confirmed, legal check before any Level 2, system logs only |

## 2. How each answer is applied

1. **The AI trial (A1, A5) uses synthetic logs only.**
   - The logs are generated from the measured line shapes. They contain no customer value and no output of the sanitizer's reverse mapping.
   - The trial runs from a development machine with a PO-provided Claude API key. Nothing runs on HOST-A, so no enterprise egress decision is needed.
   - It measures, per model: explanation quality on a rubric, refusal rate on security content, tokens and cost.
   - The PO-assistant objection is recorded. It is answered by measuring the refusal rate early, which the design rated low.
2. **Log risk analysis comes before the level decision (A2).**
   - The existing masking (`LogMasking` `logmask-v4` and the Data Sanitizer text rules) runs over stored system-log chunks on HOST-A.
   - It reports counts and token classes only: what kinds of sensitive tokens occur per source, what the masking catches, and what remains outside the masked classes and the vocabulary.
   - No raw value leaves HOST-A or enters a chat, a document or a commit. The level decision is taken on that report.
3. **Real names (A4).** The PO chose this against the recommendation of both reviewers (Argon finding 4). The design's §3.6 guard is mandatory:
   - only an alias that neXus itself sent is mapped back;
   - an alias-shaped or name-shaped token the model produced is marked "unverified reference" and is never mapped to a real device;
   - aiview always sees pseudonyms.
4. **Argon findings adopted without a PO question.** They are corrections that keep the design within the existing law:
   - no automatic Level 2 to Level 1 fallback on a guard hit (finding 3; fail-closed law);
   - `template_id` computed before the closed-vocabulary filter (finding 6);
   - typed placeholders for ASN, VLAN and VSID numbers, and a bounded number shape (finding 1);
   - for Check Point elg files, timestamps propagated within a chunk; a scale floor when MAD is 0; flagged windows not auto-excluded (finding 7);
   - HA-role and upgrade awareness in the baselines (finding 8);
   - partitioned counts, with 30-day hourly detail and daily rollups after that (finding 11);
   - TLS passthrough at the corporate proxy for the provider host (finding 13);
   - one real adapter (Claude) plus the mock; no batch API and no phase 3 tool loop in the first build (finding 12);
   - a short static prefix unless measured cache reuse justifies padding it (finding 10).
5. **Request-scoped aliases (Argon finding 5).** These stay a security-review question. A4 needs a per-call alias map in any case.
