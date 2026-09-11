# UI2 B1-1 — CRC-backed validation amendment

## Status

**DRAFT — PRODUCT OWNER APPROVAL PENDING.** This document proposes a
validation-path amendment to the frozen B1-1 contract. It changes no product
runtime authority and does not make Docker or Podman a prerequisite.

## Decision proposed

CRC/OpenShift Local is the canonical local and staging-like validation
environment. The CRC overlay provisions a disposable PostgreSQL test service;
the host-side integration test connects through `oc port-forward` using
`UI2_TEST_POSTGRES_JDBC_URL`, `UI2_TEST_POSTGRES_ADMIN_USER`,
`UI2_TEST_POSTGRES_ADMIN_PASSWORD`, and `UI2_TEST_POSTGRES_DATABASE`.

The existing Testcontainers path remains an optional developer fallback when a
container runtime is available. Its absence is a blocked validation gate, never
a skipped or passing result. Production uses a separately managed PostgreSQL
service and does not use the CRC `emptyDir` database.

## Acceptance

- `oc apply -k ui2/deploy/openshift/overlays/crc` creates the UI2 roles and the
  disposable PostgreSQL validation service.
- A port-forwarded CRC database runs the same Flyway and application-role
  integration test without Docker or Podman.
- Image build and deployment remain OpenShift builder/image-stream operations.
- Docker/Podman is not referenced by the UI2 production runtime or required for
  the canonical CRC validation path.
- The database test remains separate from CRC process-liveness evidence.
