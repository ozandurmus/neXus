import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import { diagnosticHistory } from "../auth/adminApi";
import { isRestricted } from "../shell/States";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { CASE_ACTION_HREFS } from "./estateCasesModel";
import {
  buildDiagnosticRunsModel,
  DIAGNOSTIC_HISTORY_EVIDENCE_SOURCE,
  DIAGNOSTIC_RUNS_EMPTY_TEXT,
  type DiagnosticRunsRead,
} from "./estateDiagnosticRunsModel";
import { ESTATE_MONO, ev } from "./estateTokens";

const linkSx = {
  display: "inline-flex",
  alignItems: "center",
  border: `1px solid ${ev.line}`,
  bgcolor: ev.surface,
  color: ev.accent,
  borderRadius: "999px",
  px: "12px",
  py: "4px",
  fontSize: "12.5px",
  textDecoration: "none",
  fontFamily: "inherit",
} as const;

function useDiagnosticRunsRead(): DiagnosticRunsRead | null {
  const [read, setRead] = useState<DiagnosticRunsRead | null>(null);
  useEffect(() => {
    let active = true;
    diagnosticHistory("", 0).then(
      (res) => {
        if (!active) return;
        const readAt = new Date().toISOString();
        setRead(
          Array.isArray(res?.runs)
            ? { status: "ok", runs: res.runs, readAt }
            : { status: "failed", readAt },
        );
      },
      (err: unknown) => {
        if (!active) return;
        const readAt = new Date().toISOString();
        setRead(isRestricted(err) ? { status: "restricted", readAt } : { status: "failed", readAt });
      },
    );
    return () => {
      active = false;
    };
  }, []);
  return read;
}

/**
 * Read-only "Diagnostic runs" section on the Estate Cases screen (standard-view parity with DiagnosticPanel's
 * execution history). Never executes commands or fetches raw command output.
 */
export function EstateDiagnosticRuns() {
  const read = useDiagnosticRunsRead();
  const model = read ? buildDiagnosticRunsModel(read) : null;

  return (
    <Box data-testid="diagnostic-runs-section" sx={{ display: "flex", flexDirection: "column", gap: 1.5, mt: 1 }}>
      <Box component="h2" sx={{ m: 0, fontSize: 17, fontWeight: 650 }}>Diagnostic runs</Box>
      <Box sx={{ color: ev.ink2, fontSize: 13, maxWidth: 780 }}>
        Read-only execution history across all devices from the standard Diagnostics screen. Running commands and
        inspecting command output stay in the standard view.
      </Box>

      {model === null ? <Box sx={{ color: ev.ink3 }}>Reading diagnostic runs…</Box> : null}

      {model?.status === "restricted" ? (
        <EstateCard sx={{ p: 2 }}>
          <StateBadge state={model.badge.state} word={model.badge.word} />
          <Box
            data-testid="diagnostic-runs-restricted"
            sx={{ mt: 1, display: "flex", gap: 1, flexWrap: "wrap", alignItems: "center", fontSize: 13, color: ev.ink2 }}
          >
            <span>{model.text}</span>
            <EvidenceStamp at={model.evidenceAt} source={DIAGNOSTIC_HISTORY_EVIDENCE_SOURCE} />
          </Box>
        </EstateCard>
      ) : null}

      {model?.status === "failed" ? (
        <EstateCard sx={{ p: 2 }}>
          <StateBadge state={model.badge.state} word={model.badge.word} />
          <Box
            data-testid="diagnostic-runs-failed"
            sx={{ mt: 1, display: "flex", gap: 1, flexWrap: "wrap", alignItems: "center", fontSize: 13, color: ev.ink2 }}
          >
            <span>{model.text}</span>
            <EvidenceStamp at={model.evidenceAt} source={DIAGNOSTIC_HISTORY_EVIDENCE_SOURCE} />
          </Box>
        </EstateCard>
      ) : null}

      {model?.status === "ok" ? (
        <>
          <Box
            data-testid="diagnostic-runs-counts"
            sx={{ fontSize: 13, color: ev.ink2, display: "flex", gap: 1, flexWrap: "wrap", alignItems: "center" }}
          >
            <span>{model.countLine}</span>
            <EvidenceStamp at={model.evidenceAt} source={DIAGNOSTIC_HISTORY_EVIDENCE_SOURCE} />
          </Box>
          {model.total === 0 ? (
            <Box data-testid="diagnostic-runs-empty" sx={{ color: ev.ink3 }}>
              {DIAGNOSTIC_RUNS_EMPTY_TEXT}
            </Box>
          ) : (
            <>
              {model.shown.map((row) => (
                <EstateCard key={row.key} sx={{ p: 2 }}>
                  <Box
                    data-testid="diagnostic-run-row"
                    data-job={row.jobId}
                    sx={{ display: "flex", gap: 2, flexWrap: "wrap", alignItems: "center" }}
                  >
                    <Box sx={{ fontFamily: ESTATE_MONO, fontWeight: 650 }}>{row.target}</Box>
                    <Box sx={{ fontSize: 13, color: ev.ink }}>{row.description}</Box>
                    <Box sx={{ fontSize: 12.5, color: ev.ink2 }}>{row.actor}</Box>
                    <StateBadge state={row.badge.state} word={row.badge.word} dense />
                    <Box sx={{ fontFamily: ESTATE_MONO, fontSize: 12.5, color: ev.ink2 }}>{row.duration}</Box>
                    <EvidenceStamp at={row.submittedAt} source="diagnostic run" />
                  </Box>
                </EstateCard>
              ))}
              {model.total > model.shown.length ? (
                <Box sx={{ fontSize: 13, color: ev.ink3 }}>
                  <MoreFooter
                    shown={model.shown.length}
                    total={model.total}
                    noun="diagnostic runs"
                    testId="diagnostic-runs-more"
                  />
                </Box>
              ) : null}
            </>
          )}
        </>
      ) : null}

      <Box sx={{ display: "flex", gap: 1, flexWrap: "wrap" }}>
        <Box component="a" href={CASE_ACTION_HREFS.diagnostics} sx={linkSx}>
          Open diagnostics (standard view)
        </Box>
      </Box>
    </Box>
  );
}
