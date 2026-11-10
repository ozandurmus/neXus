# Brief: independent review of the log anomaly agent design (Argon, 2026-11-10)

You are an independent external reviewer for neXus, a firewall management and analysis product. The backend is Java (Spring Boot service, plain-Java workers), with PostgreSQL and k3s on one host. Read-only review: do not edit files, run commands against hosts, or contact any external service.

## Document under review
- `docs/design/LOG_ANOMALY_AGENT_DESIGN_2026_11_10.md` (DRAFT). Read the whole document.
- Constitution: `AGENTS.md`. Note in particular the aiview masking law, the UNKNOWN / fail-closed law, the vendor semantics law and the raw-evidence law.
- Context it builds on:
  - `docs/design/DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md`
  - `docs/design/PO_DECISION_RECORD_2026_10_15_INTERFACE_MAC_ZONE_AND_SYSTEM_LOGS.md`
  - `docs/design/PO_DECISION_RECORD_2026_11_05_ANALYSIS_CONTAINER_DIRECTION.md`
  - `docs/design/PO_DECISION_RECORD_2026_11_10_ENTERPRISE_STACK_ALIGNMENT.md`. This last record is the company's mandated enterprise stack; it has no LLM slot.

## The Product Owner's goal
An agent inside the product that can use Claude and Antigravity/Gemini services. Its first purpose is system-log analysis and anomaly analysis. The PO is a security expert, not a software engineer, and wants to decide how to build it and what scope to start with.

## What to review
1. **Scope and value.** Is this the right first scope for "system logs and anomaly analysis"? What should be cut, deferred or brought forward? Is the design over-engineered for a first pilot? Where exactly, and what is the simplest version that still meets the security bar?
2. **Masking and egress security.** Check the disclosure levels, the closed-vocabulary egress profile, request-scoped aliases, the egress guard (G1 to G6) and the fail-closed rule. Look for ways an identity, secret or sensitive fact can still leave. Look for checks that add complexity without reducing risk.
3. **Detection.** Are the deterministic detectors (template extraction, robust rate scores, silence, flap, crash signatures, correlation) sound for firewall system logs? Name the false-positive and false-negative traps.
4. **Provider abstraction and the Gemini side.**
   - Is calling the Gemini API directly, not the Antigravity SDK, the right integration point?
   - Check the Gemini facts the document states: model names, prices, implicit caching thresholds, structured output and function calling limits, data retention on the Developer API versus Vertex AI. Mark each as correct, wrong or unverifiable from your knowledge.
   - Are there Gemini or Antigravity capabilities the design should use and does not?
5. **Agent shape.** Is "one structured call per finding first, a bounded read-tool loop later" right? Is a separate gateway container justified, or is it premature?
6. **Placement and operations.** Look at the worker job for indexing, ui2-service for masking and audit, and the new `ui2-llm-gateway`. Consider the egress path through a forward proxy, and the risks of a single host.
7. **Enterprise fit.** The company stack has no LLM slot, and outbound connectivity from the host is UNKNOWN. What should the PO ask the enterprise architecture and security teams before phase 2?
8. **The PO decisions in §12.** For each one, give your recommendation and the reason.

## Output format
- Start with a verdict in at most 8 lines: build it as designed, build a reduced version, or do not build.
- Then numbered findings. Give each a severity (BLOCKER, HIGH, MEDIUM, LOW) and a `section` reference into the document.
- Then a proposed minimal first scope: what ships in the first two phases.
- Then your answers to the §12 decisions.
- Mark anything you cannot verify as UNVERIFIED. Do not invent facts about vendor APIs.
- English. No raw hostnames, IPs or serials: the documents are already masked.
