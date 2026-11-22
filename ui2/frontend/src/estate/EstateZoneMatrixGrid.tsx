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
