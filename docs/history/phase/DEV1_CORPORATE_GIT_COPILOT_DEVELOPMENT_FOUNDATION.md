# DEV.1 — Corporate Git + Copilot Development Foundation

## Objective

Make the sanitized SecurityExpert repository itself the durable development
memory and establish a repeatable Copilot-native build/session lifecycle before
full-scale product development resumes.

## Architecture decision

- Repository state, project metadata and Git history are authoritative.
- Chat history is transient working context.
- One coherent build normally uses one chat from scope through handover.
- New build/major objective normally starts a fresh chat.
- Every meaningful build starts with SESSION START and closes with SESSION CLOSE.
- Movement type and reasoning level are explicit and task-driven.
- High reasoning is reserved for ambiguity/risk; normal strong models perform
  deterministic implementation.
- Runtime operational data remains physically outside the repository.

## Repository instruction surface

- `AGENTS.md` — cross-agent engineering laws and lifecycle.
- `.github/copilot-instructions.md` — Copilot-specific operating behavior.
- `.github/instructions/*.instructions.md` — path/domain contracts.
- `.github/prompts/*.prompt.md` — repeatable build-start, architecture,
  implementation, root-cause, handover and build-close workflows.
- `PROJECT_VISION.md` — product/architecture north star.
- `CURRENT_STATE.md` — small authoritative checkpoint.
- `project/*` — living roadmap/backlog/features/build history.
- `AI_DEVELOPMENT_PROTOCOL.md` — model/session/build operating protocol.

## Definition of Done

- Copilot can reconstruct current scope from repository state without chat replay.
- Standard SESSION START/CLOSE contracts are encoded in repository instructions.
- Movement/reasoning routing is encoded.
- Build acceptance requires durable project-state update.
- Privacy/runtime/network safety contracts remain intact.
- Local privacy gate passes.
- Automated regression remains green except known xfails.
- Corporate Git baseline is created only after staged-file review and human
  confirmation of the target internal repository/branch workflow.

## Out of scope

- Server/container implementation.
- History/CAS relocation.
- New device commands or polling changes.
- PAN TLS/CP SSH trust closure.
- CI/CD pipeline implementation.
- Firewall write/change automation.
