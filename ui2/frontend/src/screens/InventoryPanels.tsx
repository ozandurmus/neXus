import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Stack from "@mui/material/Stack";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";

import { EmptyPanel } from "../shell/ScreenLayout";
import { M3Button, M3Tabs, StatusChip } from "../shell/M3Widgets";
import { jobPhaseLabel, isTerminalJobState } from "../shell/deviceCopy";
import {
  getDevice,
  getDeviceInventory,
  getClusterInventory,
  requestInventoryCollect,
  type ApiError,
  type ClusterContext,
  type ClusterDifference,
  type ClusterInterface,
  type ClusterInventory,
  type ClusterRoute,
  type DeviceInventory,
  type DeviceSummary,
  type InventoryAddress,
  type InventoryContext,
  type InventoryInterface,
  type InventoryRoute,
  type Presence,
  type BackupArtefact,
  listDeviceBackups,
} from "../auth/adminApi";
import { useFetchOnMount } from "../shell/useFetchOnMount";

const POLL_INTERVAL_MS = 1750;

function describeApiError(err: unknown): string {
  const apiErr = err as Partial<ApiError>;
  const serverError = typeof apiErr.body?.error === "string" ? (apiErr.body.error as string) : undefined;
  if (serverError) return serverError;
  return `request failed${apiErr.status ? ` (status ${apiErr.status})` : ""}`;
}

function AddressChip({ address }: { readonly address: InventoryAddress }) {
  return (
    <Stack direction="row" spacing={0.75} alignItems="center">
      <Typography variant="body2">{address.address}</Typography>
      {address.role === "cluster_virtual" && <StatusChip tone="mem" label="VIP" dense />}
    </Stack>
  );
}

function InterfacesTable({ interfaces }: { readonly interfaces: readonly InventoryInterface[] }) {
  if (interfaces.length === 0) {
    return <EmptyPanel title="No interface evidence" body="This context has no collected interfaces yet." />;
  }
  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          <TableCell>Name</TableCell>
          <TableCell>Kind</TableCell>
          <TableCell>State</TableCell>
          <TableCell>VLAN</TableCell>
          <TableCell>Addresses</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {interfaces.map((iface) => (
          <TableRow key={iface.name}>
            <TableCell>{iface.name}</TableCell>
            <TableCell>{iface.kind}</TableCell>
            <TableCell>
              <StatusChip tone={iface.state === "up" ? "ok" : iface.state === "down" ? "bad" : "neutral"} label={iface.state} dense />
            </TableCell>
            <TableCell>{iface.vlan_id ?? "—"}</TableCell>
            <TableCell>
              <Stack spacing={0.5}>
                {iface.addresses.length === 0 ? "—" : iface.addresses.map((a) => <AddressChip key={a.address} address={a} />)}
              </Stack>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/** device_inventory_ha (migration V17): the selected context's own HA role, shown above its interfaces. */
function ContextHaBadge({ context }: { readonly context: InventoryContext | undefined }) {
  const ha = context?.ha;
  if (!ha) return null;
  return (
    <Stack direction="row" spacing={1} alignItems="center">
      <Typography variant="body2" color="text.secondary">HA role:</Typography>
      <StatusChip tone={ha.role === "ACTIVE" ? "ok" : "neutral"} label={ha.role} dense />
      {ha.cluster_mode && (
        <Typography variant="body2" color="text.secondary">
          {ha.cluster_mode}
        </Typography>
      )}
    </Stack>
  );
}

function RoutesTable({ routes }: { readonly routes: readonly InventoryRoute[] }) {
  if (routes.length === 0) {
    return <EmptyPanel title="No routing evidence" body="This context has no collected routes yet." />;
  }
  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          <TableCell>Destination</TableCell>
          <TableCell>Next hop</TableCell>
          <TableCell>Interface</TableCell>
          <TableCell>Protocol</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {routes.map((route, index) => (
          <TableRow key={`${route.destination}-${route.next_hop ?? ""}-${route.interface ?? ""}-${index}`}>
            <TableCell>{route.destination}</TableCell>
            <TableCell>{route.next_hop ?? "—"}</TableCell>
            <TableCell>{route.interface ?? "—"}</TableCell>
            <TableCell>
              <StatusChip tone="neutral" label={route.protocol} dense />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function presenceLabel(presence: Presence): string {
  return presence === "all" ? "All members" : presence.join(", ");
}

function DifferencesNote({ differences }: { readonly differences: readonly ClusterDifference[] }) {
  if (differences.length === 0) return null;
  return (
    <Stack spacing={0.25}>
      {differences.map((d) => (
        <Typography key={`${d.device_id}-${d.field}`} variant="body2" color="text.secondary">
          {d.device_id}: {d.field} = {d.value}
        </Typography>
      ))}
    </Stack>
  );
}

function ClusterInterfacesTable({ interfaces }: { readonly interfaces: readonly ClusterInterface[] }) {
  if (interfaces.length === 0) {
    return <EmptyPanel title="No interface evidence" body="This context has no collected interfaces yet." />;
  }
  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          <TableCell>Name</TableCell>
          <TableCell>Kind</TableCell>
          <TableCell>VIPs</TableCell>
          <TableCell>Presence</TableCell>
          <TableCell>Differences</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {interfaces.map((iface) => (
          <TableRow key={iface.name}>
            <TableCell>{iface.name}</TableCell>
            <TableCell>{iface.kind}</TableCell>
            <TableCell>
              <Stack spacing={0.5}>
                {iface.addresses.length === 0 ? "—" : iface.addresses.map((a) => <AddressChip key={a.address} address={a} />)}
              </Stack>
            </TableCell>
            <TableCell>
              <StatusChip tone={iface.presence === "all" ? "ok" : "warn"} label={presenceLabel(iface.presence)} dense />
            </TableCell>
            <TableCell>
              <DifferencesNote differences={iface.differences} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function ClusterRoutesTable({ routes }: { readonly routes: readonly ClusterRoute[] }) {
  if (routes.length === 0) {
    return <EmptyPanel title="No routing evidence" body="This context has no collected routes yet." />;
  }
  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          <TableCell>Destination</TableCell>
          <TableCell>Next hop</TableCell>
          <TableCell>Interface</TableCell>
          <TableCell>Protocol</TableCell>
          <TableCell>Presence</TableCell>
          <TableCell>Differences</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {routes.map((route, index) => (
          <TableRow key={`${route.destination}-${route.next_hop ?? ""}-${route.interface ?? ""}-${index}`}>
            <TableCell>{route.destination}</TableCell>
            <TableCell>{route.next_hop ?? "—"}</TableCell>
            <TableCell>{route.interface ?? "—"}</TableCell>
            <TableCell>
              <StatusChip tone="neutral" label={route.protocol} dense />
            </TableCell>
            <TableCell>
              <StatusChip tone={route.presence === "all" ? "ok" : "warn"} label={presenceLabel(route.presence)} dense />
            </TableCell>
            <TableCell>
              <DifferencesNote differences={route.differences} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/** More than one context renders as its own tab strip; exactly one renders inline, with no extra tab chrome. */
function ContextTabs({
  contexts,
  render,
}: {
  readonly contexts: readonly { context: string }[];
  readonly render: (contextName: string) => React.ReactNode;
}) {
  if (contexts.length <= 1) {
    return <>{render(contexts[0]?.context ?? "physical")}</>;
  }
  return (
    <M3Tabs
      ariaLabel="Inventory context"
      tabs={contexts.map((c) => ({ label: c.context, panel: render(c.context) }))}
    />
  );
}

export function InterfacesPanel({ contexts }: { readonly contexts: readonly InventoryContext[] }) {
  if (contexts.length === 0) {
    return (
      <EmptyPanel
        title="No interface evidence"
        body="This device has not been collected yet. Use Collect now to read its interfaces."
      />
    );
  }
  return (
    <ContextTabs
      contexts={contexts}
      render={(name) => {
        const context = contexts.find((c) => c.context === name);
        return (
          <Stack spacing={1}>
            <ContextHaBadge context={context} />
            <InterfacesTable interfaces={context?.interfaces ?? []} />
          </Stack>
        );
      }}
    />
  );
}

export function RoutesPanel({ contexts }: { readonly contexts: readonly InventoryContext[] }) {
  if (contexts.length === 0) {
    return (
      <EmptyPanel
        title="No routing evidence"
        body="This device has not been collected yet. Use Collect now to read its routes."
      />
    );
  }
  return (
    <ContextTabs
      contexts={contexts}
      render={(name) => <RoutesTable routes={contexts.find((c) => c.context === name)?.routes ?? []} />}
    />
  );
}

export function ClusterInterfacesPanel({ contexts }: { readonly contexts: readonly ClusterContext[] }) {
  if (contexts.length === 0) {
    return <EmptyPanel title="No interface evidence" body="No cluster member has been collected yet." />;
  }
  return (
    <ContextTabs
      contexts={contexts}
      render={(name) => (
        <ClusterInterfacesTable interfaces={contexts.find((c) => c.context === name)?.interfaces ?? []} />
      )}
    />
  );
}

export function ClusterRoutesPanel({ contexts }: { readonly contexts: readonly ClusterContext[] }) {
  if (contexts.length === 0) {
    return <EmptyPanel title="No routing evidence" body="No cluster member has been collected yet." />;
  }
  return (
    <ContextTabs
      contexts={contexts}
      render={(name) => <ClusterRoutesTable routes={contexts.find((c) => c.context === name)?.routes ?? []} />}
    />
  );
}

/** WORKER.md "Frontend": "a chip naming the members" -- the cluster row's own membership marker. */
export function ClusterMembersMarker({ inventory }: { readonly inventory: ClusterInventory }) {
  return (
    <Stack direction="row" spacing={1} sx={{ flexWrap: "wrap", gap: 1 }}>
      {inventory.members.map((member) => (
        <StatusChip key={member.device_id} tone="mem" label={member.hostname ?? member.device_id} dense />
      ))}
    </Stack>
  );
}

/**
 * "Collect now" (WORKER.md "Frontend"): submits {@code POST /devices/{id}/
 * inventory/collect}, then polls {@code GET /devices/{id}} until its job
 * reaches a terminal state -- the same cancellable-interval pattern
 * `AddDeviceDialog` uses to watch a submitted job.
 */
export function CollectNowButton({ deviceId, onCollected }: { readonly deviceId: string; readonly onCollected: () => void }) {
  const [phase, setPhase] = useState<"idle" | "submitting" | "polling" | "error">("idle");
  const [jobState, setJobState] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (phase !== "polling") return undefined;
    let cancelled = false;

    const tick = () => {
      getDevice(deviceId)
        .then((result) => {
          if (cancelled) return;
          setJobState(result.job?.state ?? null);
          if (result.job !== null && isTerminalJobState(result.job.state)) {
            setPhase("idle");
            onCollected();
          }
        })
        .catch(() => {
          // Transient poll failure: keep polling rather than abandoning the flow.
        });
    };

    tick();
    const intervalId = setInterval(tick, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(intervalId);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase, deviceId]);

  const handleClick = async () => {
    setError(null);
    setPhase("submitting");
    try {
      await requestInventoryCollect(deviceId);
      setPhase("polling");
    } catch (err) {
      setError(describeApiError(err));
      setPhase("error");
    }
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 0.5, alignItems: "flex-start" }}>
      <M3Button emphasis="outlined" icon="download" onClick={handleClick} disabled={phase === "submitting" || phase === "polling"}>
        {phase === "polling" ? jobPhaseLabel(jobState ?? "REQUESTED") : "Collect now"}
      </M3Button>
      {error && (
        <Typography variant="body2" color="error">
          {error}
        </Typography>
      )}
    </Box>
  );
}

/**
 * The detail panels for one selected device (WORKER.md "Frontend"):
 * fetches `GET /devices/{id}/inventory`, or the cluster view when the
 * device carries a `cluster_member_ref`. Remounted by its caller (`key={
 * device.device_id}`) on every selection change so `useFetchOnMount`'s own
 * mount effect re-runs -- it never re-triggers on a changed fetcher alone.
 */
export function DeviceInventoryPanels({ device }: { readonly device: DeviceSummary }) {
  const isCluster = device.cluster_member_ref !== null;
  const deviceInventoryFetch = useFetchOnMount<DeviceInventory>(
    () => getDeviceInventory(device.device_id),
    describeApiError,
  );
  const clusterInventoryFetch = useFetchOnMount<ClusterInventory>(
    () => getClusterInventory(device.cluster_member_ref as string),
    describeApiError,
  );

  const deviceInventory = deviceInventoryFetch.data;
  const clusterInventory = clusterInventoryFetch.data;
  const error = isCluster ? clusterInventoryFetch.error : deviceInventoryFetch.error;
  const refresh = () => {
    deviceInventoryFetch.refresh();
    if (isCluster) clusterInventoryFetch.refresh();
  };

  if (error) {
    return (
      <EmptyPanel title="Inventory unavailable" body={error}>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="outlined" onClick={refresh}>Retry</M3Button>
        </Box>
      </EmptyPanel>
    );
  }

  return (
    <Stack spacing={1.5}>
      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 1.5, flexWrap: "wrap" }}>
        {isCluster && clusterInventory ? <ClusterMembersMarker inventory={clusterInventory} /> : <Box />}
        <CollectNowButton deviceId={device.device_id} onCollected={refresh} />
      </Box>
      <M3Tabs
        ariaLabel="Device detail"
        tabs={[
          {
            label: "Interfaces",
            panel: isCluster
              ? <ClusterInterfacesPanel contexts={clusterInventory?.contexts ?? []} />
              : <InterfacesPanel contexts={deviceInventory?.contexts ?? []} />,
          },
          {
            label: "Routing",
            panel: isCluster
              ? <ClusterRoutesPanel contexts={clusterInventory?.contexts ?? []} />
              : <RoutesPanel contexts={deviceInventory?.contexts ?? []} />,
          },
          {
            label: "Cluster members",
            panel: isCluster && clusterInventory
              ? <ClusterMembersMarker inventory={clusterInventory} />
              : (
                <EmptyPanel
                  title="No cluster membership evidence"
                  body="Membership needs an identity-verified read from each peer; none has been collected
                        yet."
                />
              ),
          },
          {
            label: "Backup",
            panel: <BackupPanel deviceId={device.device_id} />,
          },
          {
            label: "Identity & provenance",
            panel: (
              <EmptyPanel
                title="No identity or provenance evidence"
                body="Identity requires a direct, verified device read; none has occurred yet."
              />
            ),
          },
        ]}
      />
    </Stack>
  );
}

export function BackupPanel({ deviceId }: { readonly deviceId: string }) {
  const fetcher = useFetchOnMount<{ backups: BackupArtefact[] }>(
    () => listDeviceBackups(deviceId),
    describeApiError,
  );

  if (fetcher.error) {
    return (
      <EmptyPanel title="Backup unavailable" body={fetcher.error}>
        <Box sx={{ display: "flex", justifyContent: "flex-end" }}>
          <M3Button emphasis="outlined" onClick={fetcher.refresh}>Retry</M3Button>
        </Box>
      </EmptyPanel>
    );
  }

  const backups = fetcher.data?.backups ?? [];

  if (backups.length === 0) {
    return (
      <EmptyPanel
        title="No backups"
        body="No backup has been retained for this device."
      />
    );
  }

  return (
    <Table size="small">
      <TableHead>
        <TableRow>
          <TableCell>Collected time</TableCell>
          <TableCell>Size</TableCell>
          <TableCell>Digest prefix</TableCell>
          <TableCell>Validation level</TableCell>
          <TableCell>Deviation state</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {backups.map((b) => (
          <TableRow key={b.artefact_id}>
            <TableCell>{b.collected_at}</TableCell>
            <TableCell>{b.size_bytes}</TableCell>
            <TableCell>{b.digest_prefix}</TableCell>
            <TableCell>{b.validation_level}</TableCell>
            <TableCell>
              {b.deviation_state === null ? (
                <StatusChip tone="neutral" label="not evaluated" dense />
              ) : (
                <StatusChip tone="neutral" label={b.deviation_state} dense />
              )}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
