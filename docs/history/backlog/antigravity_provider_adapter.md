# Antigravity as a third engineer provider: probe, contract, adapter

status: planned · target: 

Product Owner intent 2026-09-14: the worker roster becomes codex (default), antigravity (once an adapter exists), claude (last resort). Blocked on measurement, not on effort: there is no Antigravity CLI on the machine and the published google-antigravity SDK is not installed. docs/design/ANTIGRAVITY_PROVIDER_MEASUREMENT_2026_09_14.md records what was observed of the wheel without installing it, and names the four things that must be measured before an adapter binds anything: call signatures, token and cost reporting, budget enforcement, and the resume mechanism (session_id does not exist in the distribution). Sequence: Product Owner authorizes an install in a throwaway environment, a local probe records the event shapes in a successor to that record, a short contract fixes the adapter's obligations, then one movement implements it.
