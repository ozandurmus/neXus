import { useEffect, useRef, useState, type FormEvent } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Checkbox from "@mui/material/Checkbox";
import FormControlLabel from "@mui/material/FormControlLabel";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import { queryPath, responsesAreMasked, type PathQueryRequest, type PathResult } from "../auth/adminApi";
import { M3Button } from "../shell/M3Widgets";
import { EmptyPanel, ScreenHeader, ScreenRoot } from "../shell/ScreenLayout";
import { RestrictedPanel, StatePanel } from "../shell/States";
import { m3 } from "../theme/m3Theme";
import { RADIUS } from "../theme/tokens";
import { PathResultView } from "./pathanalysis/PathResultView";
import { classifyPathFailure, pathFormMissing, type PathFailure, type PathFormValues } from "./pathanalysis/pathWords";
import { usePathValidation } from "./pathanalysis/usePathValidation";

export { PathResultView } from "./pathanalysis/PathResultView";
export { classifyPathFailure, pathFormMissing } from "./pathanalysis/pathWords";

/**
 * Path analysis (PATH_ANALYSIS_DESIGN.md §5, stage A5b; Fable design §5): "can A reach B on tcp/443, and if not, where
 * and why?". The form asks for the five-tuple the engine takes (IPv4 hosts, tcp/udp, a destination port), an optional
 * ingress hint and the "up to the edge" mode (D-4). The answer is `PathResultView`. Every refusal the API makes has its
 * own honest state here -- a topology that was never built, a masked value the server could not resolve, a busy
 * bulkhead, the per-minute limit, a query record that could not be written -- none of them is dressed as an empty
 * result. The browser decides nothing about roles: a masked session is known only from the server's own masking header.
 * A computed result can be verified on the devices (stage A6, `PathValidationPanel`): the same query goes to
 * `POST /api/v2/path-analysis/validate`, the record is polled until complete, and each hop shows the device's word.
 */

const EMPTY_FORM: PathFormValues = { src: "", dst: "", protocol: "tcp", port: "443", ingressNode: "", ingressInterface: "", edge: false };

/** The product's one wording of a masked session's input rule (D-3): aiview types the values it is shown, nothing else. */
export const MASKED_INPUT_HINT =
  "This session sees masked names and addresses. Type values exactly as this product shows them to you (unit names like FW-TANGO-04, addresses like 192.0.2.10): the server resolves them against the current topology and refuses a raw, unknown or ambiguous value without storing it.";
export const INPUT_HINT = "IPv4 hosts only (no prefix); the source port is not modelled. The answer is policy and routing intent from stored evidence, never observed traffic.";

function useMasked(): boolean {
  const [masked, setMasked] = useState(responsesAreMasked());
  useEffect(() => {
    const on = () => setMasked(true);
    window.addEventListener("nx-masked", on);
    return () => window.removeEventListener("nx-masked", on);
  }, []);
  return masked;
}

export function toRequest(values: PathFormValues): PathQueryRequest {
  const request: { -readonly [K in keyof PathQueryRequest]: PathQueryRequest[K] } = {
    src: values.src.trim(), dst: values.dst.trim(), protocol: values.protocol, port: Number(values.port),
  };
  if (values.ingressNode.trim()) request.ingress_node_id = values.ingressNode.trim();
  if (values.ingressNode.trim() && values.ingressInterface.trim()) request.ingress_interface_name = values.ingressInterface.trim();
  if (values.edge) request.edge_mode = true;
  return request;
}

export function PathAnalysisScreen() {
  const [values, setValues] = useState<PathFormValues>(EMPTY_FORM);
  const [result, setResult] = useState<PathResult | null>(null);
  const [failure, setFailure] = useState<PathFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; }, []);
  const masked = useMasked();
  // Stage A6: a verification belongs to one computed result; a new result (or a refusal) resets it.
  const verification = usePathValidation(result);

  const missing = pathFormMissing(values);
  const reason = missing.length ? `Needs ${missing.join(", ")}` : null;
  const set = <K extends keyof PathFormValues>(key: K, value: PathFormValues[K]) => setValues((v) => ({ ...v, [key]: value }));

  const run = async () => {
    if (inFlight.current || reason) return;
    inFlight.current = true; setBusy(true); setFailure(null);
    try {
      const answer = await queryPath(toRequest(values));
      if (!mounted.current) return;
      if (answer?.status !== "COMPUTED" || !Array.isArray(answer.branches) || !answer.executive_summary) {
        setResult(null); setFailure({ kind: "error", code: "UNEXPECTED_RESULT_SHAPE", status: 200 });
      } else {
        setResult(answer);
      }
    } catch (error) {
      if (!mounted.current) return;
      setResult(null); setFailure(classifyPathFailure(error));
    } finally {
      inFlight.current = false;
      if (mounted.current) setBusy(false);
    }
  };
  const submit = (event: FormEvent) => { event.preventDefault(); void run(); };

  return (
    <ScreenRoot screenType="tool">
      <ScreenHeader title="Path analysis"
        subtitle="Can A reach B on a port, and if not, where and why? Computed from the held topology and the stored policies, hop by hop: management intent, not observed traffic." />
      <Card component="form" onSubmit={submit} aria-label="Path query"
        sx={{ border: `1px solid ${m3.outlineVar}`, boxShadow: "none", borderRadius: `${RADIUS.card}px`, p: 2.5, bgcolor: m3.scLowest }}>
        <Stack spacing={2}>
          <Typography variant="body2" data-testid="path-input-hint" data-masked={masked ? "true" : "false"}
            sx={{ color: masked ? m3.onPrimaryContainer : m3.onSurfaceVar, bgcolor: masked ? m3.primaryContainer : "transparent", px: masked ? 1.5 : 0, py: masked ? 1 : 0, borderRadius: `${RADIUS.card}px`, maxWidth: 880 }}>
            {masked ? MASKED_INPUT_HINT : INPUT_HINT}
          </Typography>
          <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", alignItems: "start" }}>
            <TextField label="Source" value={values.src} required fullWidth helperText="IPv4 host" inputProps={{ maxLength: 15, inputMode: "decimal" }}
              onChange={(e) => set("src", e.target.value)} />
            <TextField label="Destination" value={values.dst} required fullWidth helperText="IPv4 host" inputProps={{ maxLength: 15, inputMode: "decimal" }}
              onChange={(e) => set("dst", e.target.value)} />
            <TextField select label="Protocol" value={values.protocol} fullWidth helperText="TCP or UDP"
              onChange={(e) => set("protocol", e.target.value === "udp" ? "udp" : "tcp")}>
              <MenuItem value="tcp">TCP</MenuItem>
              <MenuItem value="udp">UDP</MenuItem>
            </TextField>
            <TextField label="Port" value={values.port} required fullWidth helperText="Destination port, 1–65535" inputProps={{ maxLength: 5, inputMode: "numeric" }}
              onChange={(e) => set("port", e.target.value.replace(/[^\d]/g, ""))} />
            <TextField label="Ingress unit" value={values.ingressNode} fullWidth helperText="Optional · the unit the traffic enters, when the source could belong to several" inputProps={{ maxLength: 128 }}
              onChange={(e) => set("ingressNode", e.target.value)} />
            <TextField label="Ingress interface" value={values.ingressInterface} fullWidth helperText="Optional · needs the ingress unit" inputProps={{ maxLength: 128 }}
              onChange={(e) => set("ingressInterface", e.target.value)} />
          </Box>
          <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
            <FormControlLabel control={<Checkbox checked={values.edge} onChange={(e) => set("edge", e.target.checked)} />}
              label="Up to the edge: accept the answer where the path leaves the known topology (ALLOWED_TO_EDGE), instead of UNKNOWN" />
          </Stack>
          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            <Box component="button" type="submit" disabled={busy || reason !== null} data-testid="path-submit"
              sx={{ font: "inherit", fontWeight: 500, fontSize: 14, border: 0, cursor: busy || reason ? "default" : "pointer", borderRadius: `${RADIUS.button}px`, px: 3, height: 40,
                    bgcolor: m3.primary, color: m3.onPrimary, "&:disabled": { opacity: 0.6 } }}>
              {busy ? "Evaluating…" : reason ? `Evaluate path · ${reason}` : "Evaluate path"}
            </Box>
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>Up to 30 queries a minute. Each query is recorded with its verdict; the result itself is not stored.</Typography>
          </Stack>
        </Stack>
      </Card>

      {failure ? <FailureState failure={failure} retry={() => void run()} /> : null}
      {!failure && result ? <PathResultView result={result} verification={verification} /> : null}
      {!failure && !result && !busy ? (
        <EmptyPanel title="No path evaluated yet"
          body="Enter a source, a destination and a service, then evaluate. The answer is the verdict first, then every unit on the path with its route, NAT, matched rule and reasons; nothing is sent to a device." />
      ) : null}
    </ScreenRoot>
  );
}

/** Every refusal in its own words. The code stays visible in mono beneath, so the engineer can quote it. */
export function FailureState({ failure, retry }: { readonly failure: PathFailure; readonly retry: () => void }) {
  const retryButton = <M3Button emphasis="outlined" onClick={retry}>Retry</M3Button>;
  switch (failure.kind) {
    case "not_built":
      return (
        <StatePanel variant="not_evaluated" title="Topology not built" code={failure.code} announce="status"
          body="No topology is held in memory, so no path can be evaluated. A query never builds one."
          nextStep="A Security Administrator can build it with a topology rebuild from the stored inventory (no device is contacted); then evaluate again." />
      );
    case "refused":
      return (
        <StatePanel variant="empty" title="Query refused: a value was not recognised" code={failure.code} announce="status"
          body="In a masked session the query takes only the masked values this product shows: unit names like FW-TANGO-04, addresses like 192.0.2.10. A raw value, an unknown one and an ambiguous one are refused in one and the same way, and nothing about them is stored. Which value it was is not reported here."
          nextStep="Check the source, destination and ingress against the Devices and Policy screens as they appear in this session, then evaluate again." />
      );
    case "busy":
      return (
        <StatePanel variant="error" title="Path analysis is busy" code={failure.code} action={retryButton}
          body="Every analysis slot is taken by a policy or path computation. This is a wait, not a fault of the inputs: retry in a few seconds." />
      );
    case "rate_limited":
      return (
        <StatePanel variant="error" title="Query limit reached" code={failure.code} action={retryButton}
          body="At most 30 path queries a minute per user. Retry after one minute; the inputs are unchanged." />
      );
    case "audit":
      return (
        <StatePanel variant="error" title="Result withheld: the query record could not be written" code={failure.code} action={retryButton}
          body="Every path query is recorded (the masked query and its verdict) before a result is shown. The record could not be written, so no result is shown. Retry; if it persists, the audit store is unavailable." />
      );
    case "invalid":
      return (
        <StatePanel variant="error" title="The query was not accepted" code={failure.code} announce="status"
          body="Source and destination are IPv4 hosts without a prefix, the protocol tcp or udp, the port 1–65535, and an ingress interface needs its unit. Correct the form and evaluate again." />
      );
    case "restricted":
      return <RestrictedPanel area="Path analysis queries" />;
    case "offline":
      return (
        <StatePanel variant="error" title="The request did not complete" code={failure.code} action={retryButton}
          body="No answer arrived from the server in time. Check the connection and retry." />
      );
    default:
      return (
        <StatePanel variant="error" title="The path could not be evaluated" code={failure.code ?? `HTTP ${failure.status}`} action={retryButton}
          body="The server answered with a failure this screen does not know. No verdict is claimed." />
      );
  }
}
