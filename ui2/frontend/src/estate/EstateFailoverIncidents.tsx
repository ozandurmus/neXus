import { useCallback, useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import { listFailoverIncidents, type FailoverIncident } from "../auth/adminApi";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import type { FailoverBoardUnit } from "./estateSlice2Model";
import { buildFailoverIncidentsModel, type FailoverIncidentsModel } from "./estateFailoverIncidentsModel";
import { standardHaHref } from "./estateRouting";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";

type ReadState =
  | { readonly phase: "idle" }
  | { readonly phase: "loading" }
  | { readonly phase: "error" }
  | { readonly phase: "ready"; readonly incidents: readonly FailoverIncident[]; readonly readAt: string };

/** Unit label from the Estate board when the incident maps onto a board unit; never a raw host name. */
function labelFor(incident: FailoverIncident, units: readonly FailoverBoardUnit[]): string {
  const unit = units.find((u) => u.unitId === incident.unitId) ?? units.find((u) => u.clusterRef === incident.clusterId);
  return unit?.label ?? incident.maskedName ?? "Unlabelled unit";
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/**
 * Read-only "Incidents" section of the Failover lens: open failover incidents and unresolved
 * sends from the standard endpoint. No release or write action is offered here; the standard
 * HA view owns that. A failed read is shown as UNKNOWN, never as "no incidents".
 */
export function EstateFailoverIncidents({ units }: { readonly units: readonly FailoverBoardUnit[] }) {
  const [state, setState] = useState<ReadState>({ phase: "idle" });

  const load = useCallback(async () => {
    setState({ phase: "loading" });
    try {
      const value = await listFailoverIncidents();
      if (!Array.isArray(value?.incidents)) {
        setState({ phase: "error" });
        return;
      }
      setState({ phase: "ready", incidents: value.incidents, readAt: new Date().toISOString() });
    } catch {
      setState({ phase: "error" });
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const model: FailoverIncidentsModel | null = useMemo(
    () => (state.phase === "ready" ? buildFailoverIncidentsModel(state.incidents, (i) => labelFor(i, units)) : null),
    [state, units],
  );

  return (
    <EstateCard sx={{ mt: "20px" }}>
      <Box
        component="div"
        aria-label="Failover incidents"
        sx={{ p: "14px 18px", display: "flex", flexDirection: "column", gap: "10px" }}
      >
        <Box sx={{ display: "flex", alignItems: "baseline", gap: "10px", flexWrap: "wrap" }}>
          <Box component="h2" sx={{ m: 0, fontSize: "16px", fontWeight: 650, fontFamily: ESTATE_SANS }}>
            Incidents
          </Box>
          <Box
            component="a"
            href={standardHaHref()}
            sx={{ ml: "auto", color: ev.accent, textDecoration: "none", fontSize: "12.5px", "&:hover": { textDecoration: "underline" } }}
          >
            Standard HA →
          </Box>
        </Box>

        {state.phase === "idle" && <Box sx={{ color: ev.ink3, fontSize: "13px" }}>Failover incidents not read yet.</Box>}

        {state.phase === "loading" && (
          <Box role="status" sx={{ color: ev.ink2, fontSize: "13px" }}>
            Reading failover incidents…
          </Box>
        )}

        {state.phase === "error" && (
          <Box role="alert" sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", fontSize: "13px" }}>
            <StateBadge state="unk" word="UNKNOWN" dense />
            <span style={{ color: ev.ink2 }}>
              Failover incidents could not be read. This is UNKNOWN, not zero incidents.
            </span>
            <Box
              component="button"
              type="button"
              onClick={() => void load()}
              sx={{
                height: "27px",
                px: "10px",
                borderRadius: "8px",
                border: `1px solid ${ev.line}`,
                bgcolor: ev.surface,
                color: ev.ink,
                fontSize: "12px",
                cursor: "pointer",
                fontFamily: ESTATE_SANS,
              }}
            >
              Retry read
            </Box>
          </Box>
        )}

        {state.phase === "ready" && model && model.total === 0 && (
          <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", fontSize: "13px" }}>
            <span style={{ color: ev.ink2 }}>0 failover incidents in the last read</span>
            <EvidenceStamp at={state.readAt} source="read" />
          </Box>
        )}

        {state.phase === "ready" && model && model.total > 0 && (
          <>
            <Box sx={{ fontSize: "13px", color: ev.ink2 }}>
              {`${plural(model.total, "incident", "incidents")} listed: ${model.openCount} open, ${model.unresolvedCount} unresolved ${model.unresolvedCount === 1 ? "send" : "sends"}, ${model.unknownCount} with UNKNOWN status`}
            </Box>
            <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0, display: "flex", flexDirection: "column", gap: "6px" }}>
              {model.rows.map((row) => (
                <Box
                  component="li"
                  key={row.key}
                  data-testid="failover-incident-row"
                  sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", fontSize: "13px" }}
                >
                  <StateBadge state={row.status.state} word={row.status.word} dense />
                  <span style={{ fontFamily: ESTATE_MONO, fontWeight: 600 }}>{row.label}</span>
                  {row.blocksEstate && <span style={{ color: ev.ink3 }}>blocks every failover in the estate</span>}
                  <EvidenceStamp at={row.openedAt} source="opened" />
                </Box>
              ))}
            </Box>
            <MoreFooter shown={model.rows.length} total={model.total} noun="incidents" testId="failover-incidents-more" />
          </>
        )}
      </Box>
    </EstateCard>
  );
}
