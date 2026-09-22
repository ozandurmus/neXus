# UI baseline before the Fable review changes — 2026-09-23

**Why.** The Product Owner approved implementing every change in
`docs/design/UI_VISUAL_REVIEW_2026_09_23_FABLE.md` (P0, P1, P2) and asked for the current state to be set
aside first so it can be restored.

## What was set aside

| Item | Value |
|---|---|
| Git commit (main) | `353c408` |
| Git tag | `ui-baseline-2026-09-23` (pushed) |
| Git branch | `baseline/ui-2026-09-23` (pushed) |
| Schema (Flyway) | 54 |
| ui2-service image | `nexus-ui2-service@sha256:7100e745050bb31b9728e02a3d6e92a3fea8fe76e7b639da248eb72f5972ffec` |
| ui2-worker image | same digest as ui2-service |
| ui2-compliance image | same digest as ui2-service |
| ui2-configuration image | `nexus-ui2-service@sha256:5e9cf53cbb5edfecfe3f28021d3d30c9363f638018d899d892eac669fe7c5102` |

The review changes are presentation only: no migration, no new endpoint semantics that the baseline image
would not understand, no device command. The baseline image therefore runs against the database as it is
after the changes.

## Restore (HOST-A, `kubectl -n ui2` only)

```sh
R=registry.kube-system.svc.cluster.local/nexus-ui2-service
kubectl -n ui2 set image deploy/ui2-service  service=$R@sha256:7100e745050bb31b9728e02a3d6e92a3fea8fe76e7b639da248eb72f5972ffec
kubectl -n ui2 set image deploy/ui2-worker   worker=$R@sha256:7100e745050bb31b9728e02a3d6e92a3fea8fe76e7b639da248eb72f5972ffec
kubectl -n ui2 rollout status deploy/ui2-service --timeout=240s
```

Container names are read with `kubectl -n ui2 get deploy <name> -o jsonpath='{.spec.template.spec.containers[*].name}'`
before running the lines above. Source restore: `git checkout ui-baseline-2026-09-23`.

**Risk.** The in-cluster registry keeps an image by digest until a garbage collection removes untagged
manifests; none is scheduled today. If one is ever introduced, rebuild from the tag instead.
