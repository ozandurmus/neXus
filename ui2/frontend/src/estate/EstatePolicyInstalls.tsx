import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import { getPolicyInstallStatus, type PolicyInstallStatusResponse } from "../auth/adminApi";
import { StatusPill } from "../shell/StatusPill";
import { formatStamp } from "../shell/time";
import { EstateCard, MoreFooter } from "./EstatePrimitives";
import { buildPolicyInstallsModel, policyInstallsHref } from "./estatePolicyInstallsModel";
import { ESTATE_MONO, ev } from "./estateTokens";

type ReadState =
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly res: PolicyInstallStatusResponse }
  | { readonly status: "failed" };

/**
 * Read-only "Policy installs" section at the foot of the Estate Changes screen. Shows the standard install panel's
 * state per unit; every action (install, retry, approve) stays in the standard view behind the row link.
 */
export function EstatePolicyInstalls() {
  const [read, setRead] = useState<ReadState>({ status: "loading" });

  useEffect(() => {
    let cancelled = false;
    getPolicyInstallStatus({})
      .then((res) => {
        // A response without a rows array is not a readable status: it is UNKNOWN, never an empty OK list.
        if (!cancelled) setRead(Array.isArray(res?.rows) ? { status: "ready", res } : { status: "failed" });
      })
      .catch(() => {
        if (!cancelled) setRead({ status: "failed" });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const model = useMemo(
    () => (read.status === "ready" ? buildPolicyInstallsModel(read.res.rows, read.res.total) : null),
    [read],
  );
  const now = new Date();
  const href = policyInstallsHref();

  return (
    <EstateCard sx={{ mt: "16px" }}>
      <Box sx={{ px: "18px", pt: "14px", pb: "10px", display: "flex", flexWrap: "wrap", gap: "12px", alignItems: "center" }}>
        <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
          Policy installs
        </Typography>
        <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
          Read-only. Install, retry and approve stay in the standard view.
        </Typography>
      </Box>

      {read.status === "loading" ? (
        <Box sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink3 }}>Loading policy install status…</Box>
      ) : null}

      {read.status === "failed" ? (
        <Box sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink3 }}>
          Policy install status could not be read: UNKNOWN.
        </Box>
      ) : null}

      {model && model.totalKnown && model.totalCount === 0 ? (
        <Box sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink3 }}>No policy install rows.</Box>
      ) : null}

      {model && !model.totalKnown && model.lines.length === 0 ? (
        <Box sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink3 }}>
          No policy install rows returned; total not reported.
        </Box>
      ) : null}

      {model && model.lines.length > 0 && !model.totalKnown ? (
        <Box sx={{ px: "18px", pt: "10px", fontSize: "12px", color: ev.ink3 }}>
          {`Showing ${model.lines.length} policy install${model.lines.length === 1 ? "" : "s"}; total not reported.`}
        </Box>
      ) : null}

      {model && model.lines.length > 0 ? (
        <Box component="ul" sx={{ listStyle: "none", m: 0, p: 0 }}>
          {model.lines.map((line) => (
            <Box
              component="li"
              key={line.key}
              sx={{
                display: "flex",
                flexWrap: "wrap",
                alignItems: "center",
                gap: "10px",
                px: "18px",
                py: "8px",
                borderTop: `1px solid ${ev.line}`,
                fontSize: "13px",
              }}
            >
              <Box component="span" sx={{ fontFamily: ESTATE_MONO, minWidth: 0, flex: "1 1 200px", overflowWrap: "anywhere" }}>
                {line.hostname}
                {line.target ? <Box component="span" sx={{ color: ev.ink3 }}>{`  ${line.target}`}</Box> : null}
              </Box>
              <StatusPill tone={line.tone} word={line.word} dense now={now} />
              {line.haWord && line.haTone ? <StatusPill tone={line.haTone} word={line.haWord} dense now={now} /> : null}
              <Box component="span" sx={{ color: ev.ink3, fontSize: "12px" }}>
                {line.installedAt
                  ? `installed ${formatStamp(line.installedAt)}${line.installedAge ? ` · ${line.installedAge}` : ""}`
                  : "install time UNKNOWN"}
              </Box>
              <Box component="a" href={href} sx={{ color: ev.accent, fontSize: "12px" }}>
                Open in standard view
              </Box>
            </Box>
          ))}
        </Box>
      ) : null}

      {model && model.totalKnown && model.totalCount !== null && model.moreCount > 0 ? (
        <Box sx={{ px: "18px", py: "10px", borderTop: `1px solid ${ev.line}`, fontSize: "12px", color: ev.ink3 }}>
          <Box component="a" href={href} sx={{ color: ev.ink3 }}>
            <MoreFooter shown={model.lines.length} total={model.totalCount} noun="policy installs" />
            <Box sx={{ display: "block" }}>in the standard view</Box>
          </Box>
        </Box>
      ) : null}

      {model && model.notEvaluatedCount > 0 ? (
        <Box sx={{ px: "18px", py: "10px", borderTop: `1px solid ${ev.line}`, fontSize: "12px", color: ev.ink3 }}>
          {`${model.notEvaluatedCount} row(s) PENDING or UNKNOWN: not evaluated, not counted as OK.`}
        </Box>
      ) : null}
    </EstateCard>
  );
}
