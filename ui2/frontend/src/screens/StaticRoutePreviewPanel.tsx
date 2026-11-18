import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import {
  getStaticRoutePreview,
  listStaticRoutePreviews,
  submitStaticRoutePreview,
  type StaticRoutePreviewRecord,
  type StaticRouteTargetOption,
} from "../auth/adminApi";
import { M3Button } from "../shell/M3Widgets";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { StatusPill } from "../shell/StatusPill";
import { Timestamp } from "../shell/Timestamp";
import { vendorLabel } from "../shell/presenters";
import { m3, MONO } from "../theme/m3Theme";
import { RADIUS, TYPE, type StateRole } from "../theme/tokens";

export const STATIC_ROUTE_R0_BADGE = "Preview only -- nothing is sent to the device";

export function checkStatusTone(status: string): StateRole {
  switch (status) {
    case "PASS":
    case "OBSERVED":
    case "DONE":
      return "ok";
    case "WARN":
    case "PENDING":
    case "QUEUED":
    case "RUNNING":
      return "warn";
    case "FAIL":
    case "STOPPED":
      return "bad";
    case "UNKNOWN":
    default:
      return "unknown";
  }
}

function generateIdempotencyKey(): string {
  const rand = Math.random().toString(36).slice(2, 10);
  return `ui-srp-${Date.now()}-${rand}`;
}

export function StaticRoutePreviewPanel() {
  const [targets, setTargets] = useState<readonly StaticRouteTargetOption[]>([]);
  const [previews, setPreviews] = useState<readonly StaticRoutePreviewRecord[]>([]);
  const [activePreview, setActivePreview] = useState<StaticRoutePreviewRecord | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [loadError, setLoadError] = useState<unknown>(null);

  // Form state
  const [unitId, setUnitId] = useState<string>("");
  const [vendor, setVendor] = useState<"check_point" | "palo_alto" | "cisco_asa">("check_point");
  const [operation, setOperation] = useState<"ADD" | "DELETE">("ADD");
  const [routingContext, setRoutingContext] = useState<string>("gaia-default");
  const [destinationPrefix, setDestinationPrefix] = useState<string>("");
  const [nextHopIp, setNextHopIp] = useState<string>("");
  const [interfaceName, setInterfaceName] = useState<string>("");
  const [distance, setDistance] = useState<string>("");
  const [metric, setMetric] = useState<string>("");
  const [priority, setPriority] = useState<string>("");
  const [routeName, setRouteName] = useState<string>("");
  const [deviceComment, setDeviceComment] = useState<string>("");
  const [representativeHost, setRepresentativeHost] = useState<string>("");
  const [changeTicketRef, setChangeTicketRef] = useState<string>("");
  const [reason, setReason] = useState<string>("");
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    listStaticRoutePreviews()
      .then((res) => {
        if (cancelled) return;
        const targetList = res.targets ?? [];
        const previewList = res.previews ?? [];
        setTargets(targetList);
        setPreviews(previewList);
        if (targetList.length > 0) {
          const first = targetList[0];
          setUnitId(first.unit_id);
          setVendor(first.vendor);
          setRoutingContext(first.routing_contexts[0] ?? defaultContextForVendor(first.vendor));
          setInterfaceName(first.vendor === "cisco_asa" ? (first.interfaces[0] ?? "") : "");
        }
        if (previewList.length > 0) {
          setActivePreview(previewList[0]);
        }
        setLoading(false);
      })
      .catch((err) => {
        if (!cancelled) {
          setLoadError(err);
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Poll active preview while QUEUED or RUNNING
  useEffect(() => {
    if (!activePreview) return;
    if (activePreview.state !== "QUEUED" && activePreview.state !== "RUNNING") return;
    let cancelled = false;
    const timer = setInterval(() => {
      getStaticRoutePreview(activePreview.preview_id)
        .then((updated) => {
          if (cancelled) return;
          setActivePreview(updated);
          setPreviews((prev) => prev.map((p) => (p.preview_id === updated.preview_id ? updated : p)));
        })
        .catch(() => {});
    }, 1500);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [activePreview]);

  const selectedTarget = targets.find((t) => t.unit_id === unitId) ?? null;

  const handleUnitChange = (nextUnitId: string) => {
    setUnitId(nextUnitId);
    const found = targets.find((t) => t.unit_id === nextUnitId);
    if (found) {
      setVendor(found.vendor);
      setRoutingContext(found.routing_contexts[0] ?? defaultContextForVendor(found.vendor));
      setInterfaceName(found.vendor === "cisco_asa" ? (found.interfaces[0] ?? "") : "");
      setDistance("");
      setMetric("");
      setPriority("");
      setRouteName("");
      setDeviceComment("");
    }
  };

  const handleVendorChange = (nextVendor: "check_point" | "palo_alto" | "cisco_asa") => {
    setVendor(nextVendor);
    setRoutingContext(defaultContextForVendor(nextVendor));
    setInterfaceName("");
    setDistance("");
    setMetric("");
    setPriority("");
    setRouteName("");
    setDeviceComment("");
  };

  const handleSubmit = async () => {
    setSubmitError(null);
    const trimmedPrefix = destinationPrefix.trim();
    const trimmedNh = nextHopIp.trim();
    if (!unitId.trim()) {
      setSubmitError("Target unit is required.");
      return;
    }
    if (trimmedPrefix === "0.0.0.0/0" || trimmedPrefix.toLowerCase() === "default") {
      setSubmitError("DEFAULT_ROUTE_FORBIDDEN: Default route (0.0.0.0/0) is unconditionally forbidden.");
      return;
    }
    if (!trimmedPrefix) {
      setSubmitError("Destination prefix (CIDR) is required.");
      return;
    }
    if (!trimmedNh) {
      setSubmitError("Next-hop IPv4 address is required.");
      return;
    }
    if (vendor === "cisco_asa" && !interfaceName.trim()) {
      setSubmitError("INTERFACE_REQUIRED: Egress interface is required for Cisco ASA static routes.");
      return;
    }
    if (distance.trim()) {
      const distNum = Number(distance.trim());
      const maxDist = vendor === "cisco_asa" ? 254 : 255;
      if (!Number.isInteger(distNum) || distNum < 1 || distNum > maxDist) {
        setSubmitError(`INVALID_DISTANCE: Administrative distance for ${vendorLabel(vendor)} must be in 1..${maxDist}.`);
        return;
      }
    }

    setSubmitting(true);
    try {
      const created = await submitStaticRoutePreview({
        idempotency_key: generateIdempotencyKey(),
        unit_id: unitId.trim(),
        vendor,
        routing_context: routingContext.trim() || defaultContextForVendor(vendor),
        operation,
        destination_prefix: trimmedPrefix,
        next_hop_ip: trimmedNh,
        ...(interfaceName.trim() ? { interface_name: interfaceName.trim() } : {}),
        ...(distance.trim() ? { distance: Number(distance.trim()) } : {}),
        ...(metric.trim() ? { metric: Number(metric.trim()) } : {}),
        ...(priority.trim() ? { priority: Number(priority.trim()) } : {}),
        ...(routeName.trim() ? { route_name: routeName.trim() } : {}),
        ...(deviceComment.trim() ? { device_comment: deviceComment.trim() } : {}),
        ...(representativeHost.trim() ? { representative_host: representativeHost.trim() } : {}),
        ...(changeTicketRef.trim() ? { change_ticket_ref: changeTicketRef.trim() } : {}),
        ...(reason.trim() ? { reason: reason.trim() } : {}),
      });
      setActivePreview(created);
      setPreviews((prev) => [created, ...prev.filter((p) => p.preview_id !== created.preview_id)]);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Static-route preview request failed.";
      setSubmitError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  if (loadError && isRestricted(loadError)) {
    return <RestrictedPanel area="Static routes" />;
  }
  if (loadError) {
    return (
      <StatePanel
        variant="error"
        title="Static routes preview could not be loaded"
        body="The server did not return the static-route preview state."
      />
    );
  }

  return (
    <Stack spacing={2.5} sx={{ mt: 1 }}>
      <Card
        sx={{
          bgcolor: m3.scLowest,
          boxShadow: "none",
          border: `1px solid ${m3.outlineVar}`,
          borderRadius: `${RADIUS.card}px`,
          p: 2,
        }}
      >
        <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" alignItems={{ sm: "center" }} spacing={1}>
          <Box>
            <Typography sx={{ ...TYPE.section, color: m3.onSurface }}>
              Static routes — Phase R0 read-only preview
            </Typography>
            <Typography variant="body2" sx={{ color: m3.onSurfaceVar, mt: 0.25 }}>
              Runs approved CLASS_0 device reads, evaluates Stage A/B pre-checks, looks up the current forwarding path for
              the representative host, and compiles the deterministic command and inverse rollback plan.
            </Typography>
          </Box>
          <StatusPill word={STATIC_ROUTE_R0_BADGE} tone="neutral" />
        </Stack>
      </Card>

      <Card
        component="form"
        onSubmit={(e) => {
          e.preventDefault();
          void handleSubmit();
        }}
        aria-label="Static route preview form"
        sx={{
          bgcolor: m3.scLowest,
          boxShadow: "none",
          border: `1px solid ${m3.outlineVar}`,
          borderRadius: `${RADIUS.card}px`,
          p: 2.25,
        }}
      >
        <Typography sx={{ ...TYPE.section, mb: 1.5 }}>Route preview parameters</Typography>
        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: { xs: "1fr", sm: "repeat(2, 1fr)", md: "repeat(4, 1fr)" },
            gap: 1.5,
          }}
        >
          {targets.length > 0 ? (
            <TextField
              select
              size="small"
              label="Target unit"
              value={unitId}
              onChange={(e) => handleUnitChange(e.target.value)}
            >
              {targets.map((t) => (
                <MenuItem key={t.unit_id} value={t.unit_id}>
                  {t.target_label} ({vendorLabel(t.vendor)})
                </MenuItem>
              ))}
            </TextField>
          ) : (
            <TextField
              size="small"
              label="Target unit ID"
              placeholder="UUID or cluster ref"
              value={unitId}
              onChange={(e) => setUnitId(e.target.value)}
            />
          )}

          {targets.length === 0 && (
            <TextField
              select
              size="small"
              label="Vendor"
              value={vendor}
              onChange={(e) => handleVendorChange(e.target.value as "check_point" | "palo_alto" | "cisco_asa")}
            >
              <MenuItem value="check_point">Check Point</MenuItem>
              <MenuItem value="palo_alto">Palo Alto</MenuItem>
              <MenuItem value="cisco_asa">Cisco ASA</MenuItem>
            </TextField>
          )}

          <TextField
            select
            size="small"
            label="Operation"
            value={operation}
            onChange={(e) => setOperation(e.target.value as "ADD" | "DELETE")}
          >
            <MenuItem value="ADD">ADD</MenuItem>
            <MenuItem value="DELETE">DELETE</MenuItem>
          </TextField>

          {selectedTarget && selectedTarget.routing_contexts.length > 0 ? (
            <TextField
              select
              size="small"
              label="Routing context"
              value={routingContext}
              onChange={(e) => setRoutingContext(e.target.value)}
            >
              {selectedTarget.routing_contexts.map((ctx) => (
                <MenuItem key={ctx} value={ctx}>
                  {ctx}
                </MenuItem>
              ))}
            </TextField>
          ) : (
            <TextField
              size="small"
              label="Routing context"
              value={routingContext}
              onChange={(e) => setRoutingContext(e.target.value)}
            />
          )}

          <TextField
            size="small"
            label="Destination prefix (CIDR)"
            placeholder="198.51.100.0/24"
            value={destinationPrefix}
            onChange={(e) => setDestinationPrefix(e.target.value)}
          />

          <TextField
            size="small"
            label="Next-hop IP"
            placeholder="192.0.2.1"
            value={nextHopIp}
            onChange={(e) => setNextHopIp(e.target.value)}
          />

          {vendor !== "check_point" && (
            selectedTarget && selectedTarget.interfaces.length > 0 ? (
              <TextField
                select
                size="small"
                label={vendor === "cisco_asa" ? "Interface (required)" : "Interface (optional)"}
                value={interfaceName}
                onChange={(e) => setInterfaceName(e.target.value)}
              >
                {vendor === "palo_alto" && <MenuItem value="">None</MenuItem>}
                {selectedTarget.interfaces.map((iface) => (
                  <MenuItem key={iface} value={iface}>
                    {iface}
                  </MenuItem>
                ))}
              </TextField>
            ) : (
              <TextField
                size="small"
                label={vendor === "cisco_asa" ? "Interface (required)" : "Interface (optional)"}
                placeholder={vendor === "cisco_asa" ? "outside" : "ethernet1/1"}
                value={interfaceName}
                onChange={(e) => setInterfaceName(e.target.value)}
              />
            )
          )}

          {vendor === "check_point" && (
            <>
              <TextField
                size="small"
                label="Priority (1..8, optional)"
                value={priority}
                onChange={(e) => setPriority(e.target.value)}
              />
              <TextField
                size="small"
                label="Device comment (optional)"
                value={deviceComment}
                onChange={(e) => setDeviceComment(e.target.value)}
              />
            </>
          )}

          {vendor === "palo_alto" && (
            <>
              <TextField
                size="small"
                label="Route name (optional)"
                placeholder="nxs-route-1"
                value={routeName}
                onChange={(e) => setRouteName(e.target.value)}
              />
              <TextField
                size="small"
                label="Admin distance (1..255)"
                placeholder="10"
                value={distance}
                onChange={(e) => setDistance(e.target.value)}
              />
              <TextField
                size="small"
                label="Metric (1..65535)"
                placeholder="10"
                value={metric}
                onChange={(e) => setMetric(e.target.value)}
              />
            </>
          )}

          {vendor === "cisco_asa" && (
            <TextField
              size="small"
              label="Distance (1..254)"
              placeholder="1"
              value={distance}
              onChange={(e) => setDistance(e.target.value)}
            />
          )}

          <TextField
            size="small"
            label="Representative host (optional)"
            placeholder="First usable IP in prefix"
            value={representativeHost}
            onChange={(e) => setRepresentativeHost(e.target.value)}
          />

          <TextField
            size="small"
            label="Change ticket ref (optional)"
            placeholder="CHG-2026-0104"
            value={changeTicketRef}
            onChange={(e) => setChangeTicketRef(e.target.value)}
          />

          <TextField
            size="small"
            label="Justification (optional)"
            placeholder="Partner subnet cutover preview"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </Box>

        {submitError && (
          <Typography role="alert" variant="body2" sx={{ color: m3.error, mt: 1.5 }}>
            {submitError}
          </Typography>
        )}

        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mt: 2 }}>
          <M3Button emphasis="filled" disabled={submitting} onClick={() => void handleSubmit()}>
            {submitting ? "Running preview…" : "Preview"}
          </M3Button>
          <Typography variant="caption" sx={{ color: m3.onSurfaceVar }}>
            {STATIC_ROUTE_R0_BADGE}
          </Typography>
        </Stack>
      </Card>

      {loading && !activePreview && (
        <StatePanel variant="empty" title="Loading static-route previews…" />
      )}

      {activePreview && (
        <Stack spacing={2} role="region" aria-label="Static route preview result">
          <Card
            sx={{
              bgcolor: m3.scLowest,
              boxShadow: "none",
              border: `1px solid ${m3.outlineVar}`,
              borderRadius: `${RADIUS.card}px`,
              p: 2,
            }}
          >
            <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" spacing={1} sx={{ mb: 1.5 }}>
              <Box>
                <Typography sx={{ ...TYPE.section }}>
                  {activePreview.operation} {activePreview.destination_prefix} via {activePreview.next_hop_ip} on{" "}
                  {activePreview.target_label} ({vendorLabel(activePreview.vendor)}, context {activePreview.routing_context})
                </Typography>
                <Typography variant="caption" sx={{ color: m3.onSurfaceVar, fontFamily: MONO }}>
                  preview_id={activePreview.preview_id}
                  {activePreview.pre_route_table_sha256 ? ` · pre_route_table_sha256=${activePreview.pre_route_table_sha256}` : ""}
                </Typography>
              </Box>
              <Stack direction="row" spacing={1} alignItems="center">
                <StatusPill word={activePreview.state} tone={checkStatusTone(activePreview.state)} dense />
                <StatusPill word={`Verdict: ${activePreview.verdict}`} tone={checkStatusTone(activePreview.verdict)} dense />
                <StatusPill word={STATIC_ROUTE_R0_BADGE} tone="neutral" dense />
              </Stack>
            </Stack>

            <Typography sx={{ ...TYPE.label, mb: 1 }}>Pre-checks (Contract §4.1)</Typography>
            <Table size="small" aria-label="Static route pre-checks">
              <TableHead>
                <TableRow>
                  <TableCell>#</TableCell>
                  <TableCell>Stage</TableCell>
                  <TableCell>Check</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Reason code</TableCell>
                  <TableCell>Detail</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {activePreview.pre_checks.map((c) => (
                  <TableRow key={`${c.check_number}-${c.check_id}`}>
                    <TableCell>{c.check_number}</TableCell>
                    <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{c.stage}</TableCell>
                    <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{c.check_id}</TableCell>
                    <TableCell>
                      <StatusPill word={c.status} tone={checkStatusTone(c.status)} dense />
                    </TableCell>
                    <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{c.reason_code}</TableCell>
                    <TableCell>{c.detail}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Card>

          <Card
            sx={{
              bgcolor: m3.scLowest,
              boxShadow: "none",
              border: `1px solid ${m3.outlineVar}`,
              borderRadius: `${RADIUS.card}px`,
              p: 2,
            }}
          >
            <Typography sx={{ ...TYPE.section, mb: 1 }}>
              Before-state forwarding path (representative host {activePreview.representative_host})
            </Typography>
            {activePreview.before_path ? (
              <Table size="small" aria-label="Before-state path lookup">
                <TableHead>
                  <TableRow>
                    <TableCell>Representative host</TableCell>
                    <TableCell>Verdict</TableCell>
                    <TableCell>Selected prefix</TableCell>
                    <TableCell>Next hop</TableCell>
                    <TableCell>Egress interface</TableCell>
                    <TableCell>Protocol</TableCell>
                    <TableCell>Digest (SHA-256)</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  <TableRow>
                    <TableCell sx={{ fontFamily: MONO }}>{activePreview.before_path.representative_host}</TableCell>
                    <TableCell>
                      <StatusPill
                        word={activePreview.before_path.verdict}
                        tone={checkStatusTone(activePreview.before_path.verdict)}
                        dense
                      />
                    </TableCell>
                    <TableCell sx={{ fontFamily: MONO }}>{activePreview.before_path.selected_prefix ?? "—"}</TableCell>
                    <TableCell sx={{ fontFamily: MONO }}>{activePreview.before_path.next_hop_ip ?? "—"}</TableCell>
                    <TableCell sx={{ fontFamily: MONO }}>{activePreview.before_path.egress_interface ?? "—"}</TableCell>
                    <TableCell sx={{ fontFamily: MONO }}>{activePreview.before_path.protocol ?? "—"}</TableCell>
                    <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>
                      {activePreview.before_path.digest ?? activePreview.before_path.reason_code}
                    </TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            ) : (
              <Typography variant="body2" sx={{ color: m3.onSurfaceVar }}>
                Waiting for live CLASS_0 path lookup on the device…
              </Typography>
            )}
          </Card>

          {activePreview.command_plan && (
            <Card
              sx={{
                bgcolor: m3.scLowest,
                boxShadow: "none",
                border: `1px solid ${m3.outlineVar}`,
                borderRadius: `${RADIUS.card}px`,
                p: 2,
              }}
            >
              <Stack direction={{ xs: "column", sm: "row" }} justifyContent="space-between" spacing={1} sx={{ mb: 1.5 }}>
                <Box>
                  <Typography sx={{ ...TYPE.section }}>
                    Deterministic command plan (would be sent in R1/R2)
                  </Typography>
                  <Typography variant="caption" sx={{ color: m3.onSurfaceVar, fontFamily: MONO }}>
                    gate_version={activePreview.command_plan.gate_version} · request_digest=
                    {activePreview.command_plan.request_digest}
                  </Typography>
                </Box>
                <StatusPill word={STATIC_ROUTE_R0_BADGE} tone="neutral" dense />
              </Stack>

              <Table size="small" aria-label="Forward command plan">
                <TableHead>
                  <TableRow>
                    <TableCell>Step</TableCell>
                    <TableCell>Phase</TableCell>
                    <TableCell>Member</TableCell>
                    <TableCell>Exact command / payload</TableCell>
                    <TableCell>Command SHA-256</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {activePreview.command_plan.forward_steps.map((step) => (
                    <TableRow key={`fwd-${step.step_no}`}>
                      <TableCell>{step.step_no}</TableCell>
                      <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{step.phase}</TableCell>
                      <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{step.member_label}</TableCell>
                      <TableCell sx={{ fontFamily: MONO, fontSize: 12, whiteSpace: "pre-wrap" }}>
                        {step.command_text}
                      </TableCell>
                      <TableCell sx={{ fontFamily: MONO, fontSize: 11 }}>{step.command_sha256}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>

              <Typography sx={{ ...TYPE.section, mt: 2.5, mb: 1 }}>
                Deterministic inverse rollback plan
              </Typography>
              <Table size="small" aria-label="Inverse command plan">
                <TableHead>
                  <TableRow>
                    <TableCell>Step</TableCell>
                    <TableCell>Phase</TableCell>
                    <TableCell>Member</TableCell>
                    <TableCell>Exact inverse command / payload</TableCell>
                    <TableCell>Command SHA-256</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {activePreview.command_plan.inverse_steps.map((step) => (
                    <TableRow key={`inv-${step.step_no}`}>
                      <TableCell>{step.step_no}</TableCell>
                      <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{step.phase}</TableCell>
                      <TableCell sx={{ fontFamily: MONO, fontSize: 12 }}>{step.member_label}</TableCell>
                      <TableCell sx={{ fontFamily: MONO, fontSize: 12, whiteSpace: "pre-wrap" }}>
                        {step.command_text}
                      </TableCell>
                      <TableCell sx={{ fontFamily: MONO, fontSize: 11 }}>{step.command_sha256}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Card>
          )}
        </Stack>
      )}

      {previews.length > 0 && (
        <Card
          sx={{
            bgcolor: m3.scLowest,
            boxShadow: "none",
            border: `1px solid ${m3.outlineVar}`,
            borderRadius: `${RADIUS.card}px`,
            p: 2,
          }}
        >
          <Typography sx={{ ...TYPE.section, mb: 1 }}>Recent static-route previews</Typography>
          <Table size="small" aria-label="Recent static-route previews">
            <TableHead>
              <TableRow>
                <TableCell>Requested</TableCell>
                <TableCell>Target</TableCell>
                <TableCell>Operation</TableCell>
                <TableCell>Prefix</TableCell>
                <TableCell>Next hop</TableCell>
                <TableCell>State</TableCell>
                <TableCell>Verdict</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {previews.map((p) => (
                <TableRow
                  key={p.preview_id}
                  hover
                  selected={activePreview?.preview_id === p.preview_id}
                  onClick={() => setActivePreview(p)}
                  sx={{ cursor: "pointer" }}
                >
                  <TableCell>
                    <Timestamp at={p.requested_at} />
                  </TableCell>
                  <TableCell>{p.target_label}</TableCell>
                  <TableCell sx={{ fontFamily: MONO }}>{p.operation}</TableCell>
                  <TableCell sx={{ fontFamily: MONO }}>{p.destination_prefix}</TableCell>
                  <TableCell sx={{ fontFamily: MONO }}>{p.next_hop_ip}</TableCell>
                  <TableCell>
                    <StatusPill word={p.state} tone={checkStatusTone(p.state)} dense />
                  </TableCell>
                  <TableCell>
                    <StatusPill word={p.verdict} tone={checkStatusTone(p.verdict)} dense />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
    </Stack>
  );
}

function defaultContextForVendor(vendor: string): string {
  switch (vendor) {
    case "check_point":
      return "gaia-default";
    case "cisco_asa":
      return "single";
    case "palo_alto":
    default:
      return "default";
  }
}
