import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import type { ManagementTree } from "../auth/adminApi";
import type { SourceRead } from "../screens/overview/attentionRules";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { ESTATE_MONO, ev } from "./estateTokens";
import { standardManagementTreeHref } from "./estateRouting";
import { PRODUCT_NAME } from "../brand/product";
import { buildManagementTreeBlockModel, MANAGEMENT_TREE_BOUNDARY } from "./estateDossierManagementTreeModel";

/**
 * Read-only management tree for one unit: each management domain with its node counts, not-imported count and HA
 * line. Management-plane intent, not device runtime truth. Carries no acknowledge or import control; the link opens
 * the standard Inventory view for this unit.
 */
export function EstateDossierManagementTreeBlock({
  deviceId,
  tree,
  now,
}: {
  readonly deviceId: string;
  readonly tree: SourceRead<ManagementTree> | "loading" | null | undefined;
  readonly now?: Date;
}) {
  const m = buildManagementTreeBlockModel(tree);
  const stateKey: "ok" | "unk" = m.state === "OK" ? "ok" : "unk";

  return (
    <EstateCard sx={{ mt: "16px" }}>
      <Box data-testid="dossier-management-tree-block" data-tree-state={m.state} sx={{ pb: "10px" }}>
        <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", px: "18px", pt: "14px", pb: "8px", flexWrap: "wrap" }}>
          <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
            Management tree
          </Typography>
          <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>{MANAGEMENT_TREE_BOUNDARY}</Typography>
          <Box sx={{ ml: "auto" }}>
            <EvidenceStamp at={m.evidenceAt} source="management tree discovery" now={now} missingLabel="UNKNOWN" />
          </Box>
        </Box>

        <Box sx={{ px: "18px", pb: "8px", display: "flex", flexDirection: "column", gap: "6px" }}>
          <Box>
            <StateBadge state={stateKey} word={m.badgeWord} dense />
          </Box>
          <Typography sx={{ fontSize: "14.5px", fontWeight: 650, lineHeight: 1.3 }}>{m.headline}</Typography>
          <Typography sx={{ fontSize: "12.5px", color: ev.ink2, lineHeight: 1.4 }}>{m.detail}</Typography>
          {m.notImportedTotal !== null ? (
            <Typography data-testid="dossier-management-tree-not-imported" sx={{ fontSize: "12.5px", color: ev.ink2 }}>
              {`${m.notImportedTotal} ${m.notImportedTotal === 1 ? "node" : "nodes"} not imported into ${PRODUCT_NAME} and not acknowledged (all domains)`}
            </Typography>
          ) : null}
        </Box>

        {m.domains.map((d) => (
          <Box key={d.key} data-testid="dossier-management-tree-domain" sx={{ px: "18px", pt: "10px", borderTop: `1px solid ${ev.line}` }}>
            <Typography sx={{ fontSize: "13.5px", fontWeight: 650 }}>{d.name}</Typography>
            <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
              {`Nodes: ${d.counts.clusters} ${d.counts.clusters === 1 ? "cluster" : "clusters"}, ${d.counts.gateways} ${d.counts.gateways === 1 ? "gateway" : "gateways"} (incl. members), ${d.counts.vs} virtual ${d.counts.vs === 1 ? "system" : "systems"}`}
            </Typography>
            <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
              {`Not imported: ${d.notImported} ${d.notImported === 1 ? "node" : "nodes"} (not acknowledged)`}
            </Typography>
            <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>{`HA: ${d.haLine}`}</Typography>
            <Box component="ul" sx={{ listStyle: "none", m: "6px 0 0", p: 0 }}>
              {d.rows.map((r) => (
                <Box
                  component="li"
                  key={r.key}
                  data-testid="dossier-management-tree-row"
                  sx={{ fontSize: "12.5px", py: "2px", pl: `${r.depth * 14}px`, fontFamily: ESTATE_MONO }}
                >
                  {`${r.label} · ${r.type}${r.notImported ? " · not imported" : ""}`}
                </Box>
              ))}
            </Box>
            <MoreFooter shown={d.rows.length} total={d.nodeTotal} noun="nodes" testId={`dossier-management-tree-more-${d.key}`} />
          </Box>
        ))}

        <Box sx={{ px: "18px", pt: "10px" }}>
          <Box
            component="a"
            href={standardManagementTreeHref(deviceId)}
            sx={{ fontSize: "12.5px", color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
          >
            Open management tree (standard view)
          </Box>
        </Box>
      </Box>
    </EstateCard>
  );
}
