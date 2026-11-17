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
  getPolicyInstallStatus,
  type PolicyInstallHaParity,
  type PolicyInstallState,
  type PolicyInstallStatusResponse,
  type PolicyInstallStatusRow,
} from "../auth/adminApi";
import { RestrictedPanel, StatePanel, isRestricted } from "../shell/States";
import { StatusPill } from "../shell/StatusPill";
import { Timestamp } from "../shell/Timestamp";
import { vendorLabel } from "../shell/presenters";
import { m3, MONO } from "../theme/m3Theme";
import { RADIUS, TYPE, type StateRole } from "../theme/tokens";

export const POLICY_INSTALL_CONTRACT_PATH =
  "docs/design/OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md";

export const POLICY_INSTALL_STATES: readonly PolicyInstallState[] = [
  "INSTALLED",
  "OUT_OF_DATE",
  "NOT_INSTALLED",
  "SAVED",
  "UNSAVED",
  "NOT_APPLICABLE",
  "UNKNOWN",
] as const;

export const POLICY_INSTALL_VENDORS: readonly [string, string][] = [
  ["", "All vendors"],
  ["check_point", "Check Point"],
  ["palo_alto", "Palo Alto"],
  ["cisco_asa", "Cisco ASA"],
  ["fortinet", "FortiGate"],
] as const;

export function stateTone(state: PolicyInstallState): StateRole {
  switch (state) {
    case "INSTALLED":
    case "SAVED":
      return "ok";
    case "OUT_OF_DATE":
    case "UNSAVED":
      return "warn";
    case "NOT_INSTALLED":
      return "bad";
    case "NOT_APPLICABLE":
      return "neutral";
    case "UNKNOWN":
    default:
      return "unknown";
  }
}

export function parityLabel(parity: PolicyInstallHaParity | null): string | null {
  if (!parity) return null;
  switch (parity) {
    case "EQUAL":
      return "checksums equal";
    case "DIFFER":
      return "checksums differ";
    case "UNKNOWN":
    default:
      return "checksums unknown";
  }
}

export function parityTone(parity: PolicyInstallHaParity | null): StateRole {
  switch (parity) {
    case "EQUAL":
      return "ok";
    case "DIFFER":
      return "warn";
    case "UNKNOWN":
    default:
      return "unknown";
  }
}

export function PolicyInstallMonitoringPanel() {
  const [vendor, setVendor] = useState<string>("");
  const [stateFilter, setStateFilter] = useState<string>("");
  const [q, setQ] = useState<string>("");
  const [data, setData] = useState<PolicyInstallStatusResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    getPolicyInstallStatus({ vendor, state: stateFilter, q, page: 0 })
      .then((res) => {
        if (!cancelled) {
          setData(res);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err);
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [vendor, stateFilter, q]);

  if (error && isRestricted(error)) {
    return <RestrictedPanel area="Policy installation" />;
  }

  const counts = data?.counts ?? {
    INSTALLED: 0,
    OUT_OF_DATE: 0,
    NOT_INSTALLED: 0,
    SAVED: 0,
    UNSAVED: 0,
    NOT_APPLICABLE: 0,
    UNKNOWN: 0,
  };
  const rows: readonly PolicyInstallStatusRow[] = data?.rows ?? [];

  return (
    <Stack spacing={2} data-testid="policy-install-monitoring-panel">
      <Card
        sx={{
          p: 2,
          bgcolor: m3.scLowest,
          boxShadow: "none",
          border: `1px solid ${m3.outlineVar}`,
          borderRadius: `${RADIUS.card}px`,
        }}
      >
        <Stack spacing={1.5}>
          <Stack direction="row" justifyContent="space-between" alignItems="center" flexWrap="wrap" gap={1}>
            <Box>
              <Typography sx={{ ...TYPE.section, color: m3.onSurface }}>
                Policy installation monitoring (Phase 1 — read-only)
              </Typography>
              <Typography sx={{ ...TYPE.body, color: m3.onSurfaceVar }}>
                Unified read-only status across Check Point, Palo Alto Panorama, Cisco ASA, and FortiGate from stored evidence.
              </Typography>
            </Box>
          </Stack>

          <Box
            data-testid="policy-install-not-built-note"
            sx={{
              p: 1.5,
              borderRadius: `${RADIUS.pill}px`,
              bgcolor: m3.scLow,
              border: `1px solid ${m3.outlineVar}`,
            }}
          >
            <Typography sx={{ ...TYPE.body, color: m3.onSurface }}>
              <strong>Not built yet:</strong> Policy verification (P2) and manual or scheduled policy installation (P3–P4) are not built yet and remain DRAFT. This screen is strictly read-only under{" "}
              <Box
                component="a"
                href={`/${POLICY_INSTALL_CONTRACT_PATH}`}
                sx={{ ...TYPE.mono, color: m3.primary, textDecoration: "underline" }}
              >
                {POLICY_INSTALL_CONTRACT_PATH}
              </Box>
              .
            </Typography>
          </Box>

          <Stack
            direction="row"
            spacing={1}
            useFlexGap
            flexWrap="wrap"
            alignItems="center"
            data-testid="policy-install-counts"
          >
            {POLICY_INSTALL_STATES.map((st) => (
              <Box
                key={st}
                data-state-count={st}
                sx={{
                  display: "inline-flex",
                  alignItems: "center",
                  gap: 0.75,
                  px: 1,
                  py: 0.5,
                  borderRadius: `${RADIUS.pill}px`,
                  bgcolor: m3.scLow,
                  border: `1px solid ${m3.outlineVar}`,
                }}
              >
                <StatusPill tone={stateTone(st)} word={st} dense />
                <Typography sx={{ ...TYPE.mono, fontWeight: 600, color: m3.onSurface }} data-state-count-value={st}>
                  {counts[st] ?? 0}
                </Typography>
              </Box>
            ))}
          </Stack>

          <Stack direction="row" spacing={1.5} useFlexGap flexWrap="wrap" alignItems="center">
            <TextField
              select
              size="small"
              label="Vendor"
              value={vendor}
              onChange={(e) => setVendor(e.target.value)}
              inputProps={{ "aria-label": "Vendor filter" }}
              sx={{ minWidth: 160 }}
            >
              {POLICY_INSTALL_VENDORS.map(([val, label]) => (
                <MenuItem key={val || "all"} value={val}>
                  {label}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              select
              size="small"
              label="State"
              value={stateFilter}
              onChange={(e) => setStateFilter(e.target.value)}
              inputProps={{ "aria-label": "State filter" }}
              sx={{ minWidth: 180 }}
            >
              <MenuItem value="">All states</MenuItem>
              {POLICY_INSTALL_STATES.map((st) => (
                <MenuItem key={st} value={st}>
                  {st}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              size="small"
              label="Search"
              placeholder="Filter target, package, policy, or reason…"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              inputProps={{ "aria-label": "Search policy installations" }}
              sx={{ minWidth: 260, flexGrow: 1 }}
            />
          </Stack>
        </Stack>
      </Card>

      {error ? (
        <StatePanel variant="error" title="Could not load policy installation status." />
      ) : loading && !data ? (
        <StatePanel variant="empty" title="Loading policy installation status…" />
      ) : rows.length === 0 ? (
        <StatePanel variant="empty" title="No policy installation rows match the current filters." />
      ) : (
        <Card
          sx={{
            p: 0,
            bgcolor: m3.scLowest,
            boxShadow: "none",
            border: `1px solid ${m3.outlineVar}`,
            borderRadius: `${RADIUS.card}px`,
            overflowX: "auto",
          }}
        >
          <Table size="small" aria-label="Policy installation status table">
            <TableHead>
              <TableRow>
                <TableCell>Target</TableCell>
                <TableCell>Vendor</TableCell>
                <TableCell>Container / package</TableCell>
                <TableCell>State</TableCell>
                <TableCell>Installed policy name</TableCell>
                <TableCell>Installed at</TableCell>
                <TableCell>Last change at</TableCell>
                <TableCell>Reason</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((row) => {
                const haText = parityLabel(row.ha_parity);
                return (
                  <TableRow key={row.row_id} data-row-id={row.row_id}>
                    <TableCell sx={{ ...TYPE.mono, fontWeight: 600 }}>
                      {row.hostname}
                      {row.context ? ` (VS ${row.context})` : ""}
                    </TableCell>
                    <TableCell>{vendorLabel(row.vendor)}</TableCell>
                    <TableCell>
                      <Typography sx={{ ...TYPE.body, fontWeight: 600, color: m3.onSurface }}>
                        {row.package_name || "—"}
                      </Typography>
                      {row.container_name ? (
                        <Typography sx={{ ...TYPE.label, color: m3.onSurfaceVar }}>
                          {row.container_name}
                        </Typography>
                      ) : null}
                    </TableCell>
                    <TableCell title={row.reason}>
                      <Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap" useFlexGap>
                        <StatusPill tone={stateTone(row.state)} word={row.state} title={row.reason} dense />
                        {haText ? (
                          <StatusPill tone={parityTone(row.ha_parity)} word={haText} title={row.reason} dense />
                        ) : null}
                      </Stack>
                    </TableCell>
                    <TableCell sx={{ ...TYPE.mono }}>
                      {row.policy_name ?? "—"}
                    </TableCell>
                    <TableCell>
                      {row.installed_at ? <Timestamp at={row.installed_at} /> : "—"}
                    </TableCell>
                    <TableCell>
                      {row.last_change_at ? <Timestamp at={row.last_change_at} /> : "—"}
                    </TableCell>
                    <TableCell title={row.reason}>
                      <Typography
                        title={row.reason}
                        sx={{
                          ...TYPE.body,
                          color: m3.onSurfaceVar,
                          maxWidth: 360,
                          overflow: "hidden",
                          textOverflow: "ellipsis",
                          whiteSpace: "nowrap",
                        }}
                      >
                        {row.reason}
                      </Typography>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Card>
      )}
    </Stack>
  );
}
