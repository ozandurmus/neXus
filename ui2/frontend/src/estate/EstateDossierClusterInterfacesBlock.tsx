import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getClusterInventory } from "../auth/adminApi";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import {
  buildDossierClusterModel,
  DOSSIER_CLUSTER_FAILED_TEXT,
  DOSSIER_CLUSTER_MALFORMED_TEXT,
  DOSSIER_CLUSTER_NOT_CLUSTER_TEXT,
  type DossierClusterRead,
  type DossierClusterModel,
} from "./estateDossierClusterModel";
import { dossierHref } from "./estateRouting";
import { ESTATE_MONO, ev } from "./estateTokens";

/** Standard Inventory view for this unit (`standard=1` opens it outside the Estate shell). */
export function dossierClusterStandardHref(deviceId: string): string {
  return `${dossierHref(deviceId)}&standard=1`;
}

/**
 * Dossier block: the cluster's own interfaces and routes per context (virtual system or physical), read-only,
 * as the standard view's cluster panels list them. A non-clustered unit makes no call. A failed or malformed
 * read says UNKNOWN; it is never shown as an empty list or as OK.
 */
export function EstateDossierClusterInterfacesBlock({
  deviceId,
  clusterRef,
  now,
}: {
  readonly deviceId: string;
  readonly clusterRef: string | null;
  readonly now: Date;
}) {
  if (clusterRef === null) {
    return <ClusterView key={`none:${deviceId}`} deviceId={deviceId} read={null} clusterUnit={false} now={now} />;
  }
  return <ClusterLoader key={`${deviceId}:${clusterRef}`} deviceId={deviceId} clusterRef={clusterRef} now={now} />;
}

function ClusterLoader({
  deviceId,
  clusterRef,
  now,
}: {
  readonly deviceId: string;
  readonly clusterRef: string;
  readonly now: Date;
}) {
  const [read, setRead] = useState<DossierClusterRead | null>(null);

  useEffect(() => {
    let active = true;
    // The cluster reference is passed as-is, never parsed or normalised.
    getClusterInventory(clusterRef)
      .then(
        (value): DossierClusterRead => ({ ok: true, value }),
        (): DossierClusterRead => ({ ok: false }),
      )
      .then((settled) => {
        if (active) setRead(settled);
      });
    return () => {
      active = false;
    };
  }, [clusterRef]);

  return <ClusterView deviceId={deviceId} read={read} clusterUnit now={now} />;
}

function ClusterView({
  deviceId,
  read,
  clusterUnit,
  now,
}: {
  readonly deviceId: string;
  readonly read: DossierClusterRead | null;
  readonly clusterUnit: boolean;
  readonly now: Date;
}) {
  const model: DossierClusterModel = buildDossierClusterModel(read, clusterUnit);
  const link = (
    <Box
      component="a"
      href={dossierClusterStandardHref(deviceId)}
      sx={{ fontSize: "13px", color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
    >
      Open cluster interfaces and routes (standard view)
    </Box>
  );
  const header = (
    <Box sx={{ px: "18px", pt: "14px", pb: "8px" }}>
      <Typography sx={{ fontSize: "14px", fontWeight: 600, color: ev.ink }}>Cluster interfaces and routes</Typography>
      <Typography sx={{ fontSize: "12px", color: ev.ink3 }}>
        The cluster's own interfaces and routes for each context, as the standard view lists them. Read-only.
      </Typography>
    </Box>
  );

  let body;
  if (model.status === "not_cluster") {
    body = (
      <Typography data-testid="dossier-cluster-not-cluster" sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink2 }}>
        {DOSSIER_CLUSTER_NOT_CLUSTER_TEXT}
      </Typography>
    );
  } else if (model.status === "loading") {
    body = <Typography sx={{ px: "18px", pb: "14px", fontSize: "12.5px", color: ev.ink3 }}>Reading cluster interfaces and routes…</Typography>;
  } else if (model.status === "failed" || model.status === "malformed") {
    const text = model.status === "failed" ? DOSSIER_CLUSTER_FAILED_TEXT : DOSSIER_CLUSTER_MALFORMED_TEXT;
    body = (
      <Box sx={{ px: "18px", pb: "14px" }} data-testid="dossier-cluster-unknown">
        <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
          <StateBadge state="unk" word="UNKNOWN" dense />
          <Typography sx={{ fontSize: "13px", color: ev.ink2 }}>{text}</Typography>
        </Box>
        <Box sx={{ mt: "4px" }}>
          <EvidenceStamp at={null} source="cluster read" now={now} missingLabel="evidence time UNKNOWN" />
        </Box>
      </Box>
    );
  } else if (model.contexts.length === 0) {
    body = (
      <Typography data-testid="dossier-cluster-empty" sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink2 }}>
        The cluster read lists no contexts.
        <Box sx={{ mt: "4px" }}>
          <EvidenceStamp at={model.collectedAt} source="cluster inventory" now={now} missingLabel="evidence time UNKNOWN" />
        </Box>
      </Typography>
    );
  } else {
    body = (
      <Box sx={{ px: "18px", pb: "8px" }} data-testid="dossier-cluster">
        <Typography sx={{ fontSize: "12px", color: ev.ink3, pb: "4px" }}>
          {`${model.contexts.length} ${model.contexts.length === 1 ? "context" : "contexts"} (physical and virtual systems)`}
        </Typography>
        {model.contexts.map((ctx) => (
          <Box key={ctx.label} data-cluster-context={ctx.label} sx={{ py: "8px", borderTop: `1px solid ${ev.line}` }}>
            <Box sx={{ display: "flex", alignItems: "baseline", gap: "10px", flexWrap: "wrap" }}>
              <Typography sx={{ fontSize: "13px", fontWeight: 600, fontFamily: ESTATE_MONO }}>{ctx.label}</Typography>
              <Typography sx={{ fontSize: "12px", color: ev.ink2 }}>{`${ctx.interfaceCount} interfaces`}</Typography>
              <Typography sx={{ fontSize: "12px", color: ev.ink2 }}>{`${ctx.routeCount} routes`}</Typography>
              <Box sx={{ ml: "auto" }}>
                <EvidenceStamp at={model.collectedAt} source="cluster inventory" now={now} missingLabel="evidence time UNKNOWN" />
              </Box>
            </Box>
            {ctx.interfaceNames.length > 0 ? (
              <Typography sx={{ fontSize: "12px", fontFamily: ESTATE_MONO, color: ev.ink2, mt: "2px" }}>
                {ctx.interfaceNames.join(", ")}
                {ctx.interfacesMore > 0 ? (
                  <MoreFooter shown={ctx.interfaceNames.length} total={ctx.interfaceNames.length + ctx.interfacesMore} noun="interfaces" testId="dossier-cluster-interfaces-more" />
                ) : null}
              </Typography>
            ) : null}
            {ctx.routeDestinations.length > 0 ? (
              <Typography sx={{ fontSize: "12px", fontFamily: ESTATE_MONO, color: ev.ink2, mt: "2px" }}>
                {ctx.routeDestinations.join(", ")}
                {ctx.routesMore > 0 ? (
                  <MoreFooter shown={ctx.routeDestinations.length} total={ctx.routeDestinations.length + ctx.routesMore} noun="routes" testId="dossier-cluster-routes-more" />
                ) : null}
              </Typography>
            ) : null}
          </Box>
        ))}
      </Box>
    );
  }

  return (
    <EstateCard sx={{ mt: "16px" }}>
      {header}
      {body}
      <Box sx={{ px: "18px", pb: "14px" }}>{link}</Box>
    </EstateCard>
  );
}
