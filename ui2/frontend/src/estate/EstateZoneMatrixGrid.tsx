import { useMemo } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import type { ZoneVersionDetail } from "../screens/zonematrix/zoneMatrixApi";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { STANDARD_ZONE_MATRIX_HREF, zoneMatrixGridModel } from "./estateZoneMatrixModel";
import { ESTATE_MONO, ev } from "./estateTokens";

/**
 * Read-only zone-by-zone statement grid under the Ask zones answer (concept row 11). Every edit lives in the
 * standard zone-matrix view; the only control here is the link to it.
 */
export function EstateZoneMatrixGrid({ version, loading, failed }: {
  readonly version: ZoneVersionDetail | null;
  readonly loading: boolean;
  readonly failed: boolean;
}) {
  const model = useMemo(() => (version ? zoneMatrixGridModel(version) : null), [version]);

  return (
    <EstateCard sx={{ p: "18px", mt: "16px" }}>
      <Box data-testid="zone-matrix-grid" aria-label="Zone matrix grid">
        <Typography component="h2" sx={{ fontSize: "16px", fontWeight: 650 }}>Zone matrix · active version</Typography>
        {loading ? (
          <Typography role="status" sx={{ mt: "8px", fontSize: "13.5px", color: ev.ink2 }}>Loading the active zone matrix…</Typography>
        ) : failed ? (
          <Box role="status" data-testid="zone-matrix-grid-failed" sx={{ mt: "8px" }}>
            <StateBadge state="unk" word="Not readable" />
            <Typography sx={{ fontSize: "13.5px", color: ev.ink2, mt: "6px" }}>
              The active zone matrix could not be read. The answer above is unaffected.
            </Typography>
          </Box>
        ) : !model ? (
          <Typography role="status" sx={{ mt: "8px", fontSize: "13.5px", color: ev.ink2 }}>No active zone version is published.</Typography>
        ) : (
          <>
            <Box sx={{ display: "flex", alignItems: "center", gap: "12px", flexWrap: "wrap", mt: "8px" }}>
              <Typography data-testid="zone-matrix-version" sx={{ fontSize: "14px", fontWeight: 650 }}>{model.versionLabel}</Typography>
              <EvidenceStamp at={model.evidenceAt} source="stored version" />
            </Box>
            <Typography sx={{ fontSize: "12.5px", color: ev.ink3, mt: "4px" }}>{model.defaultLine}</Typography>
            <Box sx={{ display: "flex", alignItems: "center", gap: "12px", flexWrap: "wrap", mt: "12px" }}>
              <Typography data-testid="zone-inventory-count" sx={{ fontSize: "13.5px", fontWeight: 650 }}>
                {`${model.totalZones} ${model.totalZones === 1 ? "zone" : "zones"} in this version`}
              </Typography>
              <EvidenceStamp at={model.evidenceAt} source="stored version" />
            </Box>
            {model.zoneSummaries.length > 0 ? (
              <Box sx={{ overflowX: "auto", mt: "8px" }}>
                <Box component="table" data-testid="zone-inventory-table" aria-label="Zones in this version" sx={{ borderCollapse: "collapse", fontSize: "13px", minWidth: "100%" }}>
                  <Box component="thead">
                    <Box component="tr">
                      <Box component="th" scope="col" sx={{ textAlign: "left", p: "6px 8px", color: ev.ink3, fontWeight: 650 }}>Zone</Box>
                      <Box component="th" scope="col" sx={{ textAlign: "left", p: "6px 8px", color: ev.ink3, fontWeight: 650 }}>Role</Box>
                      <Box component="th" scope="col" sx={{ textAlign: "right", p: "6px 8px", color: ev.ink3, fontWeight: 650 }}>Blocks</Box>
                      <Box component="th" scope="col" sx={{ textAlign: "right", p: "6px 8px", color: ev.ink3, fontWeight: 650 }}>IPv4 addresses</Box>
                      <Box component="th" scope="col" sx={{ textAlign: "right", p: "6px 8px", color: ev.ink3, fontWeight: 650 }}>IPv6 addresses</Box>
                    </Box>
                  </Box>
                  <Box component="tbody">
                    {model.zoneSummaries.map((z) => (
                      <Box component="tr" key={z.zoneId} data-testid="zone-inventory-row">
                        <Box component="th" scope="row" sx={{ textAlign: "left", p: "6px 8px", fontFamily: ESTATE_MONO, fontWeight: 650, borderTop: `1px solid ${ev.line}` }}>{z.name}</Box>
                        <Box component="td" sx={{ p: "6px 8px", borderTop: `1px solid ${ev.line}` }}>{z.roleWord}</Box>
                        <Box component="td" sx={{ textAlign: "right", p: "6px 8px", fontFamily: ESTATE_MONO, borderTop: `1px solid ${ev.line}` }}>{z.blockCount}</Box>
                        <Box component="td" sx={{ textAlign: "right", p: "6px 8px", fontFamily: ESTATE_MONO, borderTop: `1px solid ${ev.line}` }}>{z.ipv4Addresses}</Box>
                        <Box component="td" sx={{ textAlign: "right", p: "6px 8px", fontFamily: ESTATE_MONO, borderTop: `1px solid ${ev.line}` }}>{z.ipv6Addresses}</Box>
                      </Box>
                    ))}
                  </Box>
                </Box>
              </Box>
            ) : null}
            <Box sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", mt: "6px" }}>
              <MoreFooter shown={model.shownZones} total={model.totalZones} noun="zones" testId="zone-inventory-more" />
            </Box>
            <Typography data-testid="zone-unassigned-space" sx={{ fontSize: "12.5px", color: ev.ink2, mt: "8px" }}>
              {model.unassignedLine}
            </Typography>
            {model.redundantBlocksCount !== null && model.redundantBlocksCount > 0 ? (
              <Box data-testid="zone-overlap-warning" sx={{ display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap", mt: "8px" }}>
                <StateBadge state="watch" dense />
                <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
                  {`Overlap warning: ${model.redundantBlocksCount} block(s) repeat or sit inside another block of the same zone`}
                </Typography>
              </Box>
            ) : model.redundantBlocksCount === null ? (
              <Box data-testid="zone-overlap-unknown" sx={{ display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap", mt: "8px" }}>
                <StateBadge state="unk" word="UNKNOWN" dense />
                <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                  Overlap status UNKNOWN
                </Typography>
              </Box>
            ) : null}
            {model.rows.length === 0 ? (
              <Typography role="status" sx={{ mt: "10px", fontSize: "13.5px", color: ev.ink2 }}>This version has no zones.</Typography>
            ) : (
              <Box sx={{ overflowX: "auto", mt: "12px" }}>
                <Box component="table" aria-label="Statement by source and destination zone" sx={{ borderCollapse: "collapse", fontSize: "13px", minWidth: "100%" }}>
                  <Box component="thead">
                    <Box component="tr">
                      <Box component="th" scope="col" sx={{ textAlign: "left", p: "6px 8px", color: ev.ink3, fontWeight: 650 }}>Source ↓ / Destination →</Box>
                      {model.columns.map((c) => (
                        <Box component="th" scope="col" key={c.zoneId} sx={{ textAlign: "left", p: "6px 8px", color: ev.ink3, fontWeight: 650 }}>{c.name}</Box>
                      ))}
                    </Box>
                  </Box>
                  <Box component="tbody">
                    {model.rows.map((row) => (
                      <Box component="tr" key={row.zoneId} data-testid="zone-matrix-row">
                        <Box component="th" scope="row" sx={{ textAlign: "left", p: "6px 8px", fontFamily: ESTATE_MONO, fontWeight: 650, borderTop: `1px solid ${ev.line}` }}>{row.name}</Box>
                        {row.cells.map((cell) => (
                          <Box component="td" key={cell.key} data-zone-cell-state={cell.state} sx={{ p: "6px 8px", borderTop: `1px solid ${ev.line}`, verticalAlign: "top" }}>
                            <StateBadge state={cell.state} word={cell.word} dense />
                            {cell.known ? (
                              <Typography sx={{ fontSize: "11.5px", color: ev.ink3, mt: "3px" }}>
                                {`Severity ${cell.severity} · ${cell.reference}`}
                              </Typography>
                            ) : null}
                          </Box>
                        ))}
                      </Box>
                    ))}
                  </Box>
                </Box>
              </Box>
            )}
            <Box sx={{ display: "flex", alignItems: "center", flexWrap: "wrap", mt: "8px" }}>
              <MoreFooter shown={model.shownRows} total={model.totalRows} noun="source zones" testId="zone-matrix-more" />
            </Box>
          </>
        )}
        <Typography sx={{ mt: "12px", fontSize: "12.5px", color: ev.ink3 }}>
          Read-only. <Box component="a" href={STANDARD_ZONE_MATRIX_HREF} data-testid="zone-matrix-standard-link" sx={{ color: ev.accent }}>Open in standard view →</Box>
        </Typography>
      </Box>
    </EstateCard>
  );
}
