# NXS-LOCAL-0365 — Platform identity facts: serial, version, hotfix / jumbo take, content versions, uptime

status: automated_validated · completed: 2026-09-22 · movement: IMPLEMENTATION

V46 device_platform_facts filled by the inventory job -- Palo Alto from the already-gated show system info (parse-scope extension), Check Point from three gates the Product Owner signed off by running the commands by hand (cpinfo -y all, uptime, show asset system; V47, V48). Serial masked SN-xxxx for the aiview persona; the cluster members table marks version or hotfix differences DIFF. Real-environment validation waits for the next Bulk Collect.

## Authority and evidence

- `docs/design/PLATFORM_IDENTITY_FACTS_CONTRACT.md`
- `docs/design/CP_PLATFORM_IDENTITY_MEASUREMENTS.md`

## Real-environment note

Deployed to HOST-A the same day (run_build.sh, rollout verified). Screens inspected under the aiview persona; device-facing reads graded automated_validated until the next Bulk Collect confirms them on gateways and VSX members.
