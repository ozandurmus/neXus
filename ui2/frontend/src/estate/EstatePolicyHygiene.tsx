import { useEffect, useMemo, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getPolicyHygiene, type PolicyHygienePage } from "../auth/adminApi";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import {
  POLICY_HYGIENE_DEFAULT_DAYS,
  POLICY_HYGIENE_STANDARD_HREF,
  buildPolicyHygieneView,
  isValidPolicyHygienePage,
} from "./estatePolicyHygieneModel";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";

type HygieneRead =
  | { readonly state: "loading" }
  | { readonly state: "ok"; readonly value: PolicyHygienePage }
  | { readonly state: "unknown" };

/**
 * Read-only per-policy "Hygiene" tab of the Estate Policy browser (standard-view parity with PolicyHygieneTab).
 * Shows shadow-analysis coverage, hit-counter coverage, the 7 per-class rule counts, capped not-provable reason
 * breakdown, and capped finding rows. Read-only: no CSV export or rule comparison; links out to standard view.
 */
export function EstatePolicyHygiene({
  policy,
  collectedAt = null,
  now = new Date(),
}: {
  readonly policy: string;
  readonly collectedAt?: string | null;
  readonly now?: Date;
}) {
  const [read, setRead] = useState<HygieneRead>({ state: "loading" });

  useEffect(() => {
    if (!policy) {
      setRead({ state: "loading" });
      return;
    }
    let active = true;
    setRead({ state: "loading" });
    getPolicyHygiene(policy, 0, "", "", POLICY_HYGIENE_DEFAULT_DAYS).then(
      (res) => {
        if (!active) return;
        setRead(isValidPolicyHygienePage(res) ? { state: "ok", value: res } : { state: "unknown" });
      },
      () => {
        if (active) setRead({ state: "unknown" });
      },
    );
    return () => {
      active = false;
    };
  }, [policy]);

  const view = useMemo(
    () => (read.state === "ok" ? buildPolicyHygieneView(read.value) : buildPolicyHygieneView(null)),
    [read],
  );

  return (
    <EstateCard sx={{ mt: "14px" }}>
      <Box
        role="tabpanel"
        aria-label="Hygiene"
        sx={{ p: "12px 18px", fontFamily: ESTATE_SANS, fontSize: "13px", color: ev.ink }}
      >
        <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", mb: "8px" }}>
          <Typography sx={{ fontWeight: 700, fontSize: "15px", color: ev.ink }}>Policy hygiene</Typography>
          <EvidenceStamp
            at={read.state === "ok" && view.state === "ok" ? collectedAt : null}
            source="policy snapshot"
            now={now}
          />
          <a
            href={POLICY_HYGIENE_STANDARD_HREF}
            style={{ marginLeft: "auto", fontSize: "12.5px", color: ev.accent }}
          >
            Open policy hygiene (standard view)
          </a>
        </Box>

        {!policy ? (
          <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>Select a unit to view its policy hygiene.</Typography>
        ) : read.state === "loading" ? (
          <Typography role="status" sx={{ fontSize: "12.5px", color: ev.ink2 }}>
            Loading policy hygiene…
          </Typography>
        ) : view.state === "unknown" ? (
          <Box sx={{ display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" }}>
            <StateBadge state="unk" word="UNKNOWN" />
            <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>{view.message}</Typography>
          </Box>
        ) : (
          <Box sx={{ display: "flex", flexDirection: "column", gap: "12px" }}>
            <Box
              role="region"
              aria-label="Analysis coverage"
              sx={{
                p: "10px 12px",
                border: `1px solid ${ev.line}`,
                borderRadius: "8px",
                bgcolor: ev.surface2,
                display: "flex",
                flexDirection: "column",
                gap: "6px",
              }}
            >
              <Box sx={{ display: "flex", alignItems: "center", gap: "8px", flexWrap: "wrap" }}>
                <Typography sx={{ fontSize: "12.5px", fontWeight: 600, color: ev.ink }} title={view.coverage.basis}>
                  {view.coverage.sentence}
                </Typography>
                {view.coverageUnknown && <StateBadge state="unk" word="UNKNOWN" dense />}
                {view.budgetReached && <StateBadge state="watch" word="Analysis limit reached" dense />}
              </Box>
              <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }} title={view.coverage.basis}>
                {view.coverage.hitsSentence}
              </Typography>
            </Box>

            <Box>
              <Typography sx={{ fontWeight: 650, fontSize: "13px", color: ev.ink, mb: "4px" }}>
                Per-class rule counts
              </Typography>
              <Box
                component="ul"
                aria-label="Per-class rule counts"
                sx={{
                  listStyle: "none",
                  p: 0,
                  m: 0,
                  display: "flex",
                  flexWrap: "wrap",
                  gap: "8px",
                  fontFamily: ESTATE_MONO,
                  fontSize: "12px",
                }}
              >
                {view.classCounts.map((c) => (
                  <Box
                    component="li"
                    key={c.key}
                    data-hygiene-class={c.key}
                    sx={{
                      px: "8px",
                      py: "4px",
                      border: `1px solid ${ev.line}`,
                      borderRadius: "6px",
                      display: "inline-flex",
                      alignItems: "center",
                      gap: "6px",
                    }}
                  >
                    <span>{c.label}:</span>
                    {c.rules !== null ? (
                      <span>{c.countText}</span>
                    ) : (
                      <StateBadge state="unk" word="UNKNOWN" dense />
                    )}
                  </Box>
                ))}
              </Box>
            </Box>

            {view.shownReasons.length > 0 && (
              <Box>
                <Typography sx={{ fontWeight: 650, fontSize: "13px", color: ev.ink, mb: "4px" }}>
                  Why not provable
                </Typography>
                <Box
                  component="ul"
                  aria-label="Not provable reasons"
                  sx={{ listStyle: "none", p: 0, m: 0, fontSize: "12px" }}
                >
                  {view.shownReasons.map((row) => (
                    <Box
                      component="li"
                      key={row.group.key}
                      data-reason-group={row.group.key}
                      title={row.reasons.join("\n")}
                      sx={{
                        py: "4px",
                        borderBottom: `1px solid ${ev.line}`,
                        display: "flex",
                        gap: "8px",
                        alignItems: "baseline",
                        flexWrap: "wrap",
                      }}
                    >
                      <Box component="span" sx={{ fontFamily: ESTATE_MONO, fontWeight: 700 }}>
                        {`${row.rules} rules`}
                      </Box>
                      <Box component="span" sx={{ fontWeight: 600 }}>
                        {row.group.label}
                      </Box>
                      <Box component="span" sx={{ color: ev.ink2 }}>
                        {row.group.explain}
                      </Box>
                    </Box>
                  ))}
                  {view.totalReasons > view.shownReasons.length && (
                    <Box component="li" sx={{ py: "4px" }}>
                      <MoreFooter
                        shown={view.shownReasons.length}
                        total={view.totalReasons}
                        noun="reason groups"
                        testId="policy-hygiene-reasons-more"
                      />
                    </Box>
                  )}
                </Box>
              </Box>
            )}

            <Box>
              <Typography sx={{ fontWeight: 650, fontSize: "13px", color: ev.ink, mb: "4px" }}>
                {view.totalRows === null
                  ? "Finding rows: matching rule count UNKNOWN"
                  : `Finding rows: ${view.totalRows} matching rules`}
              </Typography>
              {view.shownRows.length === 0 ? (
                view.totalRows === null ? (
                  <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                    Matching rule count UNKNOWN; no finding rows were returned.
                  </Typography>
                ) : view.totalRows === 0 ? (
                  <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                    No hygiene findings match this policy.
                  </Typography>
                ) : (
                  <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
                    No finding rows on this page ({view.totalRows} matching rules exist).
                  </Typography>
                )
              ) : (
                <Box
                  component="ul"
                  aria-label="Policy hygiene findings"
                  sx={{ listStyle: "none", p: 0, m: 0, fontFamily: ESTATE_MONO, fontSize: "12px" }}
                >
                  {view.shownRows.map((row) => (
                    <Box
                      component="li"
                      key={row.ruleId}
                      sx={{
                        py: "4px",
                        borderBottom: `1px solid ${ev.line}`,
                        display: "flex",
                        gap: "8px",
                        alignItems: "center",
                        flexWrap: "wrap",
                      }}
                    >
                      <Box component="span" sx={{ fontWeight: 600 }}>
                        {`${row.number} · ${row.name}`}
                      </Box>
                      <Box component="span" sx={{ color: ev.ink2 }}>
                        {row.findingsSummary}
                      </Box>
                      <Box component="span" sx={{ display: "inline-flex", alignItems: "center", gap: "6px" }}>
                        {row.shadowStatus === "UNKNOWN" && <StateBadge state="unk" word="UNKNOWN" dense />}
                        <span>{row.shadowText}</span>
                      </Box>
                    </Box>
                  ))}
                  {view.totalRows !== null && view.totalRows > view.shownRows.length && (
                    <Box component="li" sx={{ py: "4px" }}>
                      <MoreFooter
                        shown={view.shownRows.length}
                        total={view.totalRows}
                        noun="matching rules"
                        testId="policy-hygiene-rows-more"
                      />
                    </Box>
                  )}
                </Box>
              )}
              {view.totalRows === null && view.shownRows.length > 0 && (
                <Typography sx={{ fontSize: "12.5px", color: ev.ink3, mt: "4px" }}>
                  Matching rule total UNKNOWN (not reported); more matching rules may exist beyond these rows.
                </Typography>
              )}
            </Box>
          </Box>
        )}
      </Box>
    </EstateCard>
  );
}
