import { useEffect, useState, type ReactNode } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Card from "@mui/material/Card";
import Collapse from "@mui/material/Collapse";
import Stack from "@mui/material/Stack";
import Typography from "@mui/material/Typography";

import type { PathBranch, PathHop, PathResult, PathReturnHop, PathRoute, PathSecurity, PathTuple, PathValidation, TopologySnapshotView } from "../../auth/adminApi";
import { M3Button } from "../../shell/M3Widgets";
import { VendorBadge } from "../../shell/States";
import { PillRow, StatusPill } from "../../shell/StatusPill";
import { Timestamp } from "../../shell/Timestamp";
import { MONO, m3 } from "../../theme/m3Theme";
import { LABEL_UPPER, RADIUS, TYPE } from "../../theme/tokens";
import {
  HOP_VERDICT_SIDE,
  NEIGHBOUR_CAP,
  NEIGHBOUR_SNAPSHOT_MISMATCH,
  NEIGHBOUR_TOPOLOGY_UNAVAILABLE,
  PathDiagram,
  buildPathDiagramModel,
  segmentPrefix,
} from "./PathDiagram";
import { HopDeviceBadge, HopDeviceCheck, PathValidationPanel } from "./PathValidationPanel";
import { hopValidation, verifiedBranchId, type ValidationHopView } from "./pathValidationWords";
import { VERDICT_SENTENCE, dependsOn, explain, ruleLabel, ruleWithheld, verdictTone, verdictWord } from "./pathWords";
import type { PathVerification } from "./usePathValidation";

export {
  HOP_VERDICT_SIDE,
  NEIGHBOUR_CAP,
  NEIGHBOUR_SNAPSHOT_MISMATCH,
  NEIGHBOUR_TOPOLOGY_UNAVAILABLE,
  PathDiagram,
  buildPathDiagramModel,
  segmentPrefix,
};

/** The verification shown beside the hops: the record and the branch it covers. */
interface DeviceView { readonly validation: PathValidation | null; readonly branchId: string | undefined }

/**
 * The answer to "can A reach B on this port, and if not, where and why" (PATH_ANALYSIS_DESIGN.md §5; Fable design §5):
 * the overall verdict first with the executive summary, then a toggle between the hop-by-hop list (default) and the
 * computed-path topology diagram, and every branch as a hop-by-hop list -- branches side by side when the route splits --
 * with the return path folded under each branch. Clicking a hop in the diagram scrolls to and highlights that hop's row
 * in the hop-by-hop list. With a `verification` (stage A6) the "Verify on devices" action follows the verdict and each
 * hop of the verified branch carries the device's MATCH / MISMATCH / NOT_EVALUABLE beside the neXus verdict.
 */
export function PathResultView({
  result,
  verification,
  now = new Date(),
  defaultView = "list",
  topology = null,
}: {
  readonly result: PathResult;
  readonly verification?: PathVerification;
  readonly now?: Date;
  readonly defaultView?: "list" | "diagram";
  readonly topology?: TopologySnapshotView | null;
}) {
  const [view, setView] = useState<"list" | "diagram">(defaultView);
  const [highlightedHop, setHighlightedHop] = useState<{ readonly branchId: string; readonly hopIndex: number } | null>(null);

  useEffect(() => {
    setHighlightedHop(null);
  }, [result]);

  const selectHop = (branchId: string, hopIndex: number) => {
    setHighlightedHop({ branchId, hopIndex });
    if (typeof document !== "undefined") {
      const row = document.querySelector<HTMLElement>(`[data-hop-row="${branchId}-${hopIndex}"]`);
      row?.scrollIntoView?.({ behavior: "smooth", block: "center" });
    }
  };

  const multi = result.branches.length > 1;
  const device: DeviceView = verification
    ? { validation: verification.state.validation, branchId: verifiedBranchId(verification.state.validation, verification.state.branchId, result) }
    : { validation: null, branchId: undefined };
  return (
    <Stack spacing={2} data-testid="path-result">
      <VerdictCard result={result} now={now} />
      {verification && <PathValidationPanel result={result} verification={verification} now={now} />}
      <Stack direction="row" spacing={1} alignItems="center" role="group" aria-label="Path result view" data-testid="path-view-toggle">
        <Button
          size="small"
          variant={view === "list" ? "contained" : "outlined"}
          aria-pressed={view === "list"}
          data-testid="path-view-list"
          onClick={() => setView("list")}
          sx={{
            height: 36,
            px: 2.5,
            borderRadius: `${RADIUS.pill}px`,
            textTransform: "none",
            fontWeight: 500,
          }}
        >
          Hop-by-hop
        </Button>
        <Button
          size="small"
          variant={view === "diagram" ? "contained" : "outlined"}
          aria-pressed={view === "diagram"}
          data-testid="path-view-diagram"
          onClick={() => setView("diagram")}
          sx={{
            height: 36,
            px: 2.5,
            borderRadius: `${RADIUS.pill}px`,
            textTransform: "none",
            fontWeight: 500,
          }}
        >
          Diagram
        </Button>
      </Stack>
      {view === "diagram" && (
        <PathDiagram
          result={result}
          verification={verification}
          topology={topology}
          highlightedHop={highlightedHop}
          onSelectHop={selectHop}
        />
      )}
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: multi ? "repeat(auto-fit, minmax(380px, 1fr))" : "minmax(0, 1fr)", alignItems: "start" }}>
        {result.branches.map((branch) => (
          <BranchColumn
            key={branch.branch_id}
            branch={branch}
            multi={multi}
            result={result}
            device={device}
            highlightedHopIndex={highlightedHop?.branchId === branch.branch_id ? highlightedHop.hopIndex : null}
          />
        ))}
      </Box>
      {result.branches.length === 0 && (
        <Typography variant="body1" sx={{ color: m3.onSurfaceVar }}>No branch was traced: the ingress could not be resolved (see the verdict reason above).</Typography>
      )}
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar, maxWidth: 880 }}>{result.limitations}</Typography>
    </Stack>
  );
}

function VerdictCard({ result, now }: { readonly result: PathResult; readonly now: Date }) {
  const summary = result.executive_summary;
  const asymmetric = result.asymmetric || summary.asymmetry_detected;
  const notices = [...new Set([...result.warnings, ...result.caveats])];
  const headlineReason = result.verdict_reason && result.verdict_reason !== "ALL_HOPS_ALLOWED" ? explain(result.verdict_reason) : null;
  return (
    <Card data-testid="path-verdict" data-verdict={result.verdict} role="status"
      sx={{ border: `1px solid ${m3.outlineVar}`, boxShadow: "none", borderRadius: `${RADIUS.card}px`, p: 2.5, bgcolor: m3.scLowest }}>
      <Stack spacing={1.5}>
        <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
          <Box component="span" data-testid="path-overall-verdict" sx={{ fontSize: 18 }}>
            <StatusPill tone={verdictTone(result.verdict)} word={verdictWord(result.verdict)} fact="path-verdict" />
          </Box>
          <Typography component="h3" sx={{ ...TYPE.section, color: m3.onSurface, m: 0 }}>{summary.headline}</Typography>
        </Stack>
        <Typography variant="body1" sx={{ color: m3.onSurface, maxWidth: 880 }}>
          {VERDICT_SENTENCE[result.verdict] ?? explain(result.verdict)}
          {headlineReason ? ` ${headlineReason}` : ""}
        </Typography>
        <PillRow ariaLabel="Verdict qualifiers">
          {asymmetric && <StatusPill tone="warn" word="Asymmetric return" title={explain("ASYMMETRIC_RETURN")} fact="path-asymmetric" />}
          {!result.plain && result.verdict !== "UNKNOWN" && <StatusPill tone="attn" word="With caveats" title="The verdict carries caveats: read the hops before acting on it." fact="path-not-plain" />}
          {summary.ecmp_branch_count > 1 && <StatusPill tone="neutral" word="Equal-cost branches" count={summary.ecmp_branch_count} fact="path-ecmp" />}
          {(result.ecmp_truncated || summary.ecmp_truncated) && <StatusPill tone="warn" word="Branches truncated" title={explain("ECMP_TRUNCATED")} fact="path-ecmp-truncated" />}
          <StatusPill tone={summary.freshness_status === "CURRENT" ? "ok" : summary.freshness_status === "STALE" ? "warn" : "neutral"}
            word={`Policy ${summary.freshness_status.toLowerCase().replace(/_/g, " ")}`} fact="path-freshness" />
          <StatusPill tone="neutral" word={`Ingress ${result.ingress.ingress_mode.toLowerCase()}`}
            title={result.ingress.candidates.map((c) => c.node_id).join(", ") || result.ingress.ingress_status} fact="path-ingress" />
        </PillRow>
        {(summary.blocking_hops.length > 0 || summary.unknown_hops.length > 0) && (
          <Stack spacing={0.5} data-testid="path-summary-hops">
            {summary.blocking_hops.map((ref) => <HopPointer key={`b-${ref.branch_id}-${ref.hop_index}`} label="Blocked at" result={result} branchId={ref.branch_id} hopIndex={ref.hop_index} />)}
            {summary.unknown_hops.map((ref) => <HopPointer key={`u-${ref.branch_id}-${ref.hop_index}`} label="UNKNOWN at" result={result} branchId={ref.branch_id} hopIndex={ref.hop_index} />)}
          </Stack>
        )}
        {notices.length > 0 && <CodeList title="Warnings and caveats" codes={notices} tone={asymmetric ? "warn" : "attn"} testId="path-notices" />}
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
          Query {result.query.src} → {result.query.dst} · {result.query.protocol}/{result.query.port}
          {result.query.ingress_node_id ? ` · from ${result.query.ingress_node_id}${result.query.ingress_interface_name ? ` ${result.query.ingress_interface_name}` : ""}` : ""}
          {result.query.edge_mode ? " · up to the edge" : ""}
          {" · snapshot "}<Box component="span" sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize }}>{result.snapshot_id}</Box>
          {" · computed "}<Timestamp at={result.computed_at} age now={now} />
        </Typography>
      </Stack>
    </Card>
  );
}

function HopPointer({ label, result, branchId, hopIndex }: { readonly label: string; readonly result: PathResult; readonly branchId: string; readonly hopIndex: number }) {
  const branch = result.branches.find((b) => b.branch_id === branchId);
  const hop = branch?.hops.find((h) => h.hop_index === hopIndex);
  const rule = hop?.security?.deciding_rule;
  const where = result.branches.length > 1 ? ` (branch ${branchId})` : "";
  return (
    <Typography variant="body1" sx={{ color: m3.onSurface }}>
      <Box component="span" sx={{ fontWeight: 600 }}>{label} hop {hopIndex}{where}</Box>
      {hop ? <> on <Box component="span" sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize }}>{hop.node_id}</Box></> : null}
      {hop ? ` · ${explain(hop.verdict_reason)}` : null}
      {rule && hop?.verdict !== "UNKNOWN" ? ` · rule ${ruleLabel(rule)}` : null}
    </Typography>
  );
}

function BranchColumn({
  branch,
  multi,
  result,
  device,
  highlightedHopIndex = null,
}: {
  readonly branch: PathBranch;
  readonly multi: boolean;
  readonly result: PathResult;
  readonly device: DeviceView;
  readonly highlightedHopIndex?: number | null;
}) {
  const notices = [...new Set([...branch.warnings, ...branch.caveats])];
  const ret = branch.return_path;
  const asymmetric = ret.return_status === "ASYMMETRIC";
  return (
    <Stack spacing={1.5} component="section" aria-label={`Branch ${branch.branch_id}`} data-testid={`path-branch-${branch.branch_id}`} sx={{ minWidth: 0 }}>
      {(multi || branch.verdict !== result.verdict) && (
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <Typography component="h4" sx={{ ...TYPE.section, m: 0 }}>{multi ? `Branch ${branch.branch_id}` : "Path"}</Typography>
          <StatusPill tone={verdictTone(branch.verdict)} word={verdictWord(branch.verdict)} dense fact="branch-verdict" />
          <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{explain(branch.termination)}</Typography>
        </Stack>
      )}
      {asymmetric && (
        <Box role="alert" data-testid="path-asymmetry-warning" sx={{ p: 1.5, borderRadius: `${RADIUS.card}px`, bgcolor: m3.warningContainer, color: m3.onWarningContainer, border: `1px solid ${m3.outlineVar}` }}>
          <Typography variant="body1" sx={{ fontWeight: 600 }}>Asymmetric return path</Typography>
          <Typography variant="body2">{explain("ASYMMETRIC_RETURN")} {explain("OUT_OF_STATE_DROP_LIKELY")} The forward verdict is not claimed as ALLOWED.</Typography>
        </Box>
      )}
      {notices.length > 0 && <CodeList title="Branch notes" codes={notices} tone="attn" testId={`branch-notices-${branch.branch_id}`} />}
      <Stack spacing={1} component="ol" sx={{ listStyle: "none", m: 0, p: 0 }} aria-label={`Hops of branch ${branch.branch_id}`}>
        {branch.hops.map((hop) => (
          <HopCard key={hop.hop_index} hop={hop} branch={branch} blocking={branch.blocking_hop_index === hop.hop_index}
            highlighted={highlightedHopIndex === hop.hop_index}
            device={hopValidation(device.validation, device.branchId, branch, hop)} />
        ))}
      </Stack>
      <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
        End of branch: {explain(branch.termination)}
      </Typography>
      <ReturnPath branch={branch} />
    </Stack>
  );
}

export function HopCard({
  hop,
  branch,
  blocking,
  highlighted = false,
  device = { kind: "none" },
}: {
  readonly hop: PathHop;
  readonly branch: PathBranch;
  readonly blocking: boolean;
  readonly highlighted?: boolean;
  readonly device?: ValidationHopView;
}) {
  const upstream = dependsOn(branch, hop);
  const stale = hop.policy_stale || hop.reasons.includes("STALE_OR_OUT_OF_SYNC_POLICY");
  const codes = [...new Set([...hop.reasons.filter((r) => r !== hop.verdict_reason || hop.verdict === "UNKNOWN"), ...hop.caveats, ...hop.notes, ...hop.flags])];
  const sideColor = HOP_VERDICT_SIDE[hop.verdict] ?? m3.outline;
  return (
    <Card component="li" id={`path-hop-${branch.branch_id}-${hop.hop_index}`} data-hop-row={`${branch.branch_id}-${hop.hop_index}`}
      data-testid={`path-hop-${hop.hop_index}`} data-hop-verdict={hop.verdict} data-blocking={blocking ? "true" : undefined}
      data-highlighted={highlighted ? "true" : undefined}
      data-device={device.kind === "validated" ? device.hop.result : device.kind === "beyond_cap" ? "NOT_ASKED" : undefined}
      sx={{ border: `1px solid ${blocking ? m3.error : highlighted ? m3.primary : m3.outlineVar}`, borderLeft: `4px solid ${sideColor}`,
            outline: highlighted ? `2px solid ${m3.primary}` : "none", boxShadow: "none",
            borderRadius: `${RADIUS.card}px`, p: 2, bgcolor: m3.scLowest, display: "flex", flexDirection: "column", gap: 1.25 }}>
      <Stack direction="row" spacing={1.25} alignItems="center" flexWrap="wrap" useFlexGap>
        <Box component="span" sx={{ width: 28, height: 28, borderRadius: "14px", display: "grid", placeItems: "center", bgcolor: m3.secondaryContainer, color: m3.onSecondaryContainer, fontWeight: 700, fontSize: 13, flex: "none" }} aria-label={`Hop ${hop.hop_index}`}>
          {hop.hop_index}
        </Box>
        <VendorBadge vendor={hop.vendor} />
        <Typography component="h5" sx={{ ...TYPE.section, m: 0, fontFamily: MONO, fontSize: 14, overflowWrap: "anywhere" }}>{hop.node_id}</Typography>
        <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap" useFlexGap sx={{ ml: "auto" }}>
          <StatusPill tone={verdictTone(hop.verdict)} word={verdictWord(hop.verdict)} fact="hop-verdict"
            suffix={hop.verdict === "CONDITIONAL" ? `own: ${verdictWord(hop.own_verdict)}` : blocking ? "blocking hop" : null} />
          <HopDeviceBadge view={device} />
        </Stack>
      </Stack>
      {hop.verdict === "CONDITIONAL" && (
        <Typography variant="body2" data-testid={`hop-${hop.hop_index}-conditional`} sx={{ color: m3.onAttentionContainer, bgcolor: m3.attentionContainer, px: 1, py: 0.5, borderRadius: `${RADIUS.pill}px` }}>
          Conditional: this hop's own evaluation ({verdictWord(hop.own_verdict)}) holds only if {upstream.length > 0 ? `hop ${upstream.join(" and hop ")}` : "the earlier UNKNOWN hop"} resolves favourably.
        </Typography>
      )}
      <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))" }}>
        <Fact label="Ingress → egress">
          <EndpointText e={hop.ingress} /> → <EndpointText e={hop.egress} />
        </Fact>
        <Fact label="Packet in → out">
          <TupleText t={hop.packet_in} />{hop.packet_out && tupleChanged(hop.packet_in, hop.packet_out) ? <> → <TupleText t={hop.packet_out} /></> : null}
        </Fact>
        <Fact label="Route used"><RouteText route={hop.route} /></Fact>
        <Fact label="NAT">
          {!hop.nat ? <Muted>Not evaluated</Muted>
            : hop.nat.applied
              ? <>Applied: {hop.nat.nat_rules.map((r) => ruleLabel(r)).join("; ") || "rule withheld"}</>
              : hop.nat.nat_status === "UNKNOWN"
                ? <>UNKNOWN · {explain(hop.nat.nat_reason)}{hop.nat.nat_undecidable_rules.length > 0 ? ` (could match: ${hop.nat.nat_undecidable_rules.map(ruleLabel).join("; ")})` : ""}</>
                : <Muted>{explain(hop.nat.nat_reason)}</Muted>}
        </Fact>
        <Fact label={hop.post_nat_security ? "Rule (pre-NAT address)" : "Matched rule"}>
          <SecurityText security={hop.security} stale={stale} />
        </Fact>
        {hop.post_nat_security && (
          <Fact label="Rule (post-NAT address)"><SecurityText security={hop.post_nat_security} stale={stale} /></Fact>
        )}
        <Fact label="Context">
          {hop.context}{hop.routing_context ? ` · ${hop.routing_context}` : ""}
          {hop.policy_collected_at ? <> · policy collected <Timestamp at={hop.policy_collected_at} age /></> : null}
        </Fact>
      </Box>
      {stale && (
        <Typography variant="body2" data-testid={`hop-${hop.hop_index}-stale`} sx={{ color: m3.onWarningContainer, bgcolor: m3.warningContainer, px: 1, py: 0.5, borderRadius: `${RADIUS.pill}px` }}>
          {explain("STALE_OR_OUT_OF_SYNC_POLICY")}
        </Typography>
      )}
      <Typography variant="body1" data-testid={`hop-${hop.hop_index}-reason`} sx={{ color: m3.onSurface }}>
        {explain(hop.verdict_reason)}
      </Typography>
      {codes.length > 0 && <CodeList codes={codes} tone="neutral" dense testId={`hop-${hop.hop_index}-codes`} />}
      <HopDeviceCheck view={device} hopIndex={hop.hop_index} />
    </Card>
  );
}

function ReturnPath({ branch }: { readonly branch: PathBranch }) {
  const [open, setOpen] = useState(false);
  const ret = branch.return_path;
  const hops = ret.branches.flatMap((b) => b.hops);
  const tone = ret.return_status === "SYMMETRIC" ? "ok" : ret.return_status === "ASYMMETRIC" ? "warn" : ret.return_status === "INCOMPLETE" ? "attn" : "neutral";
  return (
    <Box data-testid={`return-path-${branch.branch_id}`} sx={{ border: `1px dashed ${m3.outlineVar}`, borderRadius: `${RADIUS.card}px`, p: 1.5 }}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <Typography variant="body1" sx={{ fontWeight: 600 }}>Return path</Typography>
        <StatusPill tone={tone} word={`Return ${ret.return_status.toLowerCase().replace(/_/g, " ")}`} dense fact="return-status" />
        <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>{explain(ret.return_reason)}</Typography>
        {hops.length > 0 && (
          <Box sx={{ ml: "auto" }}>
            <M3Button emphasis="text" onClick={() => setOpen((v) => !v)}>{open ? "Hide return hops" : `Show return hops · ${hops.length}`}</M3Button>
          </Box>
        )}
      </Stack>
      <Collapse in={open} unmountOnExit>
        <Stack spacing={1} component="ol" sx={{ listStyle: "none", m: 0, mt: 1.5, p: 0 }} aria-label={`Return hops of branch ${branch.branch_id}`}>
          {ret.branches.map((rb, i) => rb.hops.map((hop) => <ReturnHopRow key={`${i}-${hop.hop_index}`} hop={hop} />))}
        </Stack>
      </Collapse>
    </Box>
  );
}

function ReturnHopRow({ hop }: { readonly hop: PathReturnHop }) {
  return (
    <Box component="li" sx={{ display: "grid", gap: 1.5, gridTemplateColumns: "28px repeat(auto-fit, minmax(180px, 1fr))", alignItems: "start", p: 1, borderRadius: `${RADIUS.pill}px`, bgcolor: m3.scLow }}>
      <Box component="span" sx={{ width: 24, height: 24, borderRadius: "12px", display: "grid", placeItems: "center", border: `1px solid ${m3.outline}`, fontSize: 12, fontWeight: 700 }} aria-label={`Return hop ${hop.hop_index}`}>{hop.hop_index}</Box>
      <Typography component="span" sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize, overflowWrap: "anywhere" }}>{hop.node_id}</Typography>
      <Typography component="span" variant="body2">{hop.src} → {hop.dst}{hop.reverse_translated ? " · reverse-translated" : ""}</Typography>
      <Typography component="span" variant="body2"><RouteText route={hop.route} /> · out <EndpointText e={hop.egress} /></Typography>
      {hop.reasons.length > 0 && <Typography component="span" variant="body2" sx={{ color: m3.onSurfaceVar }}>{hop.reasons.map(explain).join(" ")}</Typography>}
    </Box>
  );
}

function SecurityText({ security, stale }: { readonly security: PathSecurity | null; readonly stale: boolean }) {
  if (!security) return <Muted>Not evaluated</Muted>;
  const rule = security.deciding_rule;
  const withheld = ruleWithheld(security.security_verdict, rule);
  return (
    <Stack spacing={0.25}>
      <Box component="span">
        <StatusPill tone={verdictTone(security.security_verdict)} word={verdictWord(security.security_verdict)} dense fact="rule-verdict" />
        {stale ? <Box component="span" sx={{ ml: 1, color: m3.onSurfaceVar }}>last known, context only</Box> : null}
      </Box>
      {rule ? (
        <Box component="span" sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize }}>
          {security.security_verdict === "UNKNOWN" ? "First proven match after uncertainty: " : ""}{ruleLabel(rule)}
        </Box>
      ) : withheld ? (
        <Box component="span" data-testid="rule-withheld" sx={{ color: m3.onSurfaceVar, fontStyle: "italic" }}>Rule withheld</Box>
      ) : null}
      {security.security_reason && security.security_reason !== "FIRST_MATCHING_RULE" && (
        <Typography component="span" variant="body2" sx={{ color: m3.onSurfaceVar }}>{explain(security.security_reason)}</Typography>
      )}
      {security.undecidable_rules.map((r, i) => (
        <Typography key={`${r.rule_id ?? i}`} component="span" variant="body2" sx={{ color: m3.onSurfaceVar }}>
          UNKNOWN: rule {ruleLabel(r)} could match{r.lookup_reason ? ` (${explain(r.lookup_reason)})` : ""}.
        </Typography>
      ))}
    </Stack>
  );
}

function RouteText({ route }: { readonly route: PathRoute | null }) {
  if (!route) return <Muted>No route</Muted>;
  if (route.next_hop_kind === "DISCARD") return <>{route.destination} · discard route ({route.protocol})</>;
  return (
    <>
      {route.destination}
      {route.next_hop ? ` via ${route.next_hop}` : route.next_hop_kind === "CONNECTED" ? " · connected" : ""}
      {route.interface_name ? ` on ${route.interface_name}` : ""}
      {` (${route.protocol}${route.ecmp_branch ? ", equal-cost" : ""})`}
    </>
  );
}

function EndpointText({ e }: { readonly e: { readonly interface_name: string | null; readonly zone: string | null; readonly segment_id: string | null } }) {
  if (!e.interface_name && !e.zone && !e.segment_id) return <Muted>none</Muted>;
  return <>{e.interface_name ?? "interface UNKNOWN"}{e.zone ? ` (${e.zone})` : ""}</>;
}

function TupleText({ t }: { readonly t: PathTuple | null }) {
  if (!t) return <Muted>UNKNOWN</Muted>;
  return <Box component="span" sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize }}>{t.src} → {t.dst} {t.protocol}/{t.port}</Box>;
}

function tupleChanged(a: PathTuple | null, b: PathTuple | null): boolean {
  if (!a || !b) return Boolean(a) !== Boolean(b);
  return a.src !== b.src || a.dst !== b.dst || a.protocol !== b.protocol || a.port !== b.port;
}

function Fact({ label, children }: { readonly label: string; readonly children: ReactNode }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography component="div" sx={{ ...LABEL_UPPER, color: m3.onSurfaceVar, mb: 0.25 }}>{label}</Typography>
      <Typography component="div" variant="body1" sx={{ overflowWrap: "anywhere" }}>{children}</Typography>
    </Box>
  );
}

function Muted({ children }: { readonly children: ReactNode }) {
  return <Box component="span" sx={{ color: m3.onSurfaceVar }}>{children}</Box>;
}

/** Codes with their one-line meaning; the code stays visible in mono so the engineer can quote it. */
function CodeList({ title, codes, tone, dense = false, testId }: { readonly title?: string; readonly codes: readonly string[]; readonly tone: "warn" | "attn" | "neutral"; readonly dense?: boolean; readonly testId?: string }) {
  const ink = tone === "warn" ? m3.onWarningContainer : tone === "attn" ? m3.onAttentionContainer : m3.onSurface;
  const bg = tone === "warn" ? m3.warningContainer : tone === "attn" ? m3.attentionContainer : m3.scLow;
  return (
    <Box data-testid={testId} sx={{ p: dense ? 1 : 1.5, borderRadius: `${RADIUS.card}px`, bgcolor: bg, color: ink }}>
      {title && <Typography component="div" sx={{ ...LABEL_UPPER, mb: 0.5 }}>{title}</Typography>}
      <Stack component="ul" spacing={0.25} sx={{ m: 0, pl: 0, listStyle: "none" }}>
        {codes.map((code) => (
          <Typography component="li" key={code} variant="body2" data-code={code}>
            <Box component="span" sx={{ fontFamily: MONO, fontSize: TYPE.mono.fontSize, mr: 1 }}>{code}</Box>
            {explain(code) !== code ? explain(code) : <Muted>No explanation recorded for this code.</Muted>}
          </Typography>
        ))}
      </Stack>
    </Box>
  );
}
