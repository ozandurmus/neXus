/**
 * Pure derivation for the Estate dossier's cluster-level interfaces and routes block (no React).
 *
 * The read is `getClusterInventory(clusterMemberRef)`, the same GET the standard view makes. Only the
 * shape the block needs is checked here; anything else is UNKNOWN, never a count of 0 and never OK.
 */

import { isRecord } from "../shell/malformedResponse";
import { parseTimeMs } from "./estateModel";

/** Items listed per context and per kind (interfaces, routes); anything beyond is counted in "+N more". */
export const DOSSIER_CLUSTER_ROW_CAP = 5;
export const DOSSIER_CLUSTER_NOT_CLUSTER_TEXT = "Not a cluster unit";
export const DOSSIER_CLUSTER_FAILED_TEXT = "Cluster interfaces and routes could not be read";
export const DOSSIER_CLUSTER_MALFORMED_TEXT = "Cluster interfaces and routes read returned an unexpected shape";
export const DOSSIER_CLUSTER_UNKNOWN_NAME = "UNKNOWN";

/** One read of `getClusterInventory`: the raw payload (validated here) or a failure. */
export type DossierClusterRead = { readonly ok: true; readonly value: unknown } | { readonly ok: false };

export interface DossierClusterContextRow {
  /** Context label: the virtual system name when present, otherwise the context key (e.g. "physical"). */
  readonly label: string;
  /** Number of cluster interfaces in this context (the full list, not the capped one). */
  readonly interfaceCount: number;
  /** Number of cluster routes in this context (the full list, not the capped one). */
  readonly routeCount: number;
  /** At most DOSSIER_CLUSTER_ROW_CAP interface names. */
  readonly interfaceNames: readonly string[];
  /** Interfaces beyond the cap; shown as "+N more interfaces". */
  readonly interfacesMore: number;
  /** At most DOSSIER_CLUSTER_ROW_CAP route destinations. */
  readonly routeDestinations: readonly string[];
  /** Routes beyond the cap; shown as "+N more routes". */
  readonly routesMore: number;
}

export type DossierClusterModel =
  | { readonly status: "not_cluster" }
  | { readonly status: "loading" }
  | { readonly status: "failed" }
  | { readonly status: "malformed" }
  | {
      readonly status: "ok";
      readonly contexts: readonly DossierClusterContextRow[];
      /** The read's own collection timestamp when the payload carries a parseable one; otherwise null (age UNKNOWN). */
      readonly collectedAt: string | null;
    };

type Obj = Readonly<Record<string, unknown>>;

function nameOf(v: unknown, key: string): string {
  if (!isRecord(v)) return DOSSIER_CLUSTER_UNKNOWN_NAME;
  const raw = v[key];
  return typeof raw === "string" && raw.trim() !== "" ? raw : DOSSIER_CLUSTER_UNKNOWN_NAME;
}

function collectedAtOf(payload: Obj): string | null {
  const raw = payload.collected_at;
  if (typeof raw !== "string" || raw.trim() === "") return null;
  return parseTimeMs(raw) === null ? null : raw;
}

/**
 * Derive the block model. `clusterUnit` false means the unit has no cluster reference: no read is made
 * and the model says "not_cluster". `read` null while a clusterUnit's read is in flight means "loading".
 */
export function buildDossierClusterModel(
  read: DossierClusterRead | null,
  clusterUnit: boolean,
): DossierClusterModel {
  if (!clusterUnit) return { status: "not_cluster" };
  if (read === null) return { status: "loading" };
  if (!read.ok) return { status: "failed" };

  const payload = read.value;
  if (!isRecord(payload) || !Array.isArray(payload.contexts)) return { status: "malformed" };

  const contexts: DossierClusterContextRow[] = [];
  for (const ctx of payload.contexts as readonly unknown[]) {
    if (!isRecord(ctx)) return { status: "malformed" };
    if (!Array.isArray(ctx.interfaces) || !Array.isArray(ctx.routes)) return { status: "malformed" };
    const interfaces = ctx.interfaces as readonly unknown[];
    const routes = ctx.routes as readonly unknown[];
    const label =
      typeof ctx.vs_name === "string" && ctx.vs_name.trim() !== ""
        ? ctx.vs_name
        : typeof ctx.context === "string" && ctx.context.trim() !== ""
          ? ctx.context
          : null;
    if (label === null) return { status: "malformed" };

    const interfaceNames = interfaces.slice(0, DOSSIER_CLUSTER_ROW_CAP).map((i) => nameOf(i, "name"));
    const routeDestinations = routes.slice(0, DOSSIER_CLUSTER_ROW_CAP).map((r) => nameOf(r, "destination"));
    contexts.push({
      label,
      interfaceCount: interfaces.length,
      routeCount: routes.length,
      interfaceNames,
      interfacesMore: Math.max(0, interfaces.length - interfaceNames.length),
      routeDestinations,
      routesMore: Math.max(0, routes.length - routeDestinations.length),
    });
  }

  return { status: "ok", contexts, collectedAt: collectedAtOf(payload) };
}
