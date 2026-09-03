# SecurityExpert --- Privacy & Data Handling Contract

This policy applies to ChatGPT, Claude, Copilot, other AI agents,
Git/Bitbucket, support artifacts and human handovers.

## Classification

### CLASS 0 --- Repository-safe

May be stored in approved internal source control and supplied to coding
agents:

-   source code,
-   tests using synthetic data,
-   generic fixtures,
-   architecture/design docs,
-   roadmap/backlog/feature/build metadata,
-   CLAUDE.md / AGENTS.md,
-   synthetic examples.

Do not use real production hostnames, management IPs, serials,
credentials or secrets in tests when synthetic values are sufficient.

Prefer RFC documentation ranges and synthetic names.

### CLASS 1 --- AI-shareable sanitized evidence

May be shared when needed:

-   SAFE COLLECTION SUMMARY,
-   aggregate counters,
-   sanitized failure family/reason,
-   intentionally sanitized screenshots,
-   synthetic command examples,
-   support bundles explicitly designed as shareable.

This is the preferred debugging evidence class.

### CLASS 2 --- LOCAL-ONLY sensitive operational evidence

Do **not** upload/share by default:

-   `output/*` telemetry,
-   real management IPs,
-   real hostnames/device names,
-   serial numbers,
-   topology details,
-   management object names,
-   SSH fingerprints,
-   raw command transcripts,
-   real routes/interfaces,
-   CAS metadata containing identities,
-   migration manifests,
-   local operator artifacts.

A local coding agent may inspect a **narrow requested record** if
necessary, but must not dump the entire file into conversation/context.

Correct pattern:

``` text
locally read telemetry
→ filter one requested entity/field
→ report only safe derived status
```

Incorrect:

``` text
cat entire telemetry file into AI conversation
```

### CLASS 3 --- SECRET / NEVER SHARE

Never place in AI conversation, Git, HTML, support bundles or normal
logs:

-   passwords,
-   API keys,
-   private keys,
-   PSKs,
-   SNMP communities,
-   RADIUS/TACACS shared secrets,
-   auth keys,
-   credential stores,
-   certificate private material,
-   unredacted secret-bearing configuration.

## Runtime directory policy

Default AI behavior:

``` text
DO NOT SCAN
data/
output/
logs/
CAS runtime objects
support artifacts
credential stores
```

Only inspect a narrow runtime artifact when explicitly required by a
concrete validation/debug task.

## Distributed evidence store (DEV.3.3, opt-in)

When `SECURITYEXPERT_EVIDENCE_BACKEND=postgres` is set, the CAS metadata
index (`config_snapshot` table: device names, management IPs, entity ids),
run manifests, last-known-good state and scheduler state move from
per-container local files into a PostgreSQL instance — see
`docs/history/phase/DEV3_3_DISTRIBUTED_EVIDENCE_STORE_MIGRATION.md` (decision
E1). That Postgres instance is therefore a **CLASS 2** identity-bearing
store, architecturally equivalent to local disk, not a CLASS 1/shareable
artifact:

-   must be a **dedicated instance for this product**, never a shared
    multi-tenant database;
-   the connection DSN must use TLS in production, the same as any other
    credential-bearing configuration value (never in Git, never in AI
    conversation);
-   the database role used by the application should be restricted to the
    four DEV.3.3 tables it needs;
-   volume/disk encryption at rest is expected wherever the deployment
    already encrypts other local-disk evidence.
-   Content-addressed payload blobs never move to Postgres — they remain a
    local-disk-only artifact under `data/artifacts/config/sha256/`,
    unchanged by this backend.

Default (`filesystem`, unset) behavior and its existing CLASS 2 rules above
are unchanged.

## Raw configuration

### Check Point

`show configuration` can contain secret-bearing lines.

Required path:

``` text
raw config
→ RAM only
→ canonical fingerprint
→ secret detection/withholding
→ safe projection
→ CAS/history/UI
```

Raw secret-bearing configuration must not be persisted or surfaced
casually.

### Palo Alto

Raw XML/effective configuration can also contain sensitive values.
Browser/support output must remain secret-aware.

## Screenshot policy

Screenshots can expose: - hostnames, - IP addresses, - serials, -
topology, - configuration values.

Prefer cropped/sanitized screenshots when exact identity is unnecessary.

Do not infer that because an AI is enterprise-approved every production
identifier is automatically shareable; follow organizational policy.

## Source-code hygiene

Do not hard-code production values such as:

``` text
real device IP
real hostname
real username
real internal DNS domain
real serial
```

Use synthetic examples such as:

``` text
192.0.2.10
CP-SPARK-TEST-01
example.invalid
```

The full documentation-range set approved for tests/fixtures is the three
RFC 5737 blocks — `192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24` — paired
with synthetic device names such as `CP-SPARK-TEST-01`, `PAN-FW-TEST-01` and
`example.invalid`. `CP-SPARK-TEST-01` is the canonical Check Point synthetic
name; do not introduce a second synthetic name for the same role.

unless runtime configuration is explicitly intended to supply real
values.

## Git/Bitbucket boundary

Before first commit: 1. create strict `.gitignore`, 2. inspect
untracked/staged files, 3. scan for credentials/secrets/environment
artifacts, 4. only then create baseline commit.

`.gitignore` added after a secret is committed does not remove it from
Git history.

## AI output

AI must not echo sensitive input merely to demonstrate understanding.

When reporting local telemetry, prefer:

``` text
success=true
shell_profile=interactive_direct_clish
identity_accepted=true
```

instead of repeating device-specific sensitive details.
