import { createContext, createElement, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import {
  getBackupDeviations,
  getChangeFeed,
  getComplianceControls,
  getLifecycle,
  getOverview,
  getSystemHealth,
  listCpFailoverSummary,
  listDevices,
  listFleetBackups,
  type BackupArtefact,
  type BackupDeviations,
  type ChangeFeedPage,
  type ComplianceControlItem,
  type CpFailoverSummary,
  type DeviceSummary,
  type LifecycleFleet,
  type OverviewView,
  type SystemHealth,
} from "../auth/adminApi";
import { useSession } from "../auth/SessionContext";
import { planeGranted } from "../shell/actionAccess";
import { isRestricted } from "../shell/States";
import { CHANGE_WINDOW_DAYS, sourceWanted, type SourceRead } from "../screens/overview/attentionRules";
import { isSystemHealth } from "../screens/SystemHealthPanel";
import { buildEstateModel, type EstateModel } from "./estateModel";

export interface EstateDataState {
  readonly loading: boolean;
  readonly fatalError: { readonly message: string; readonly restricted: boolean } | null;
  readonly overview: OverviewView | null;
  readonly devices: SourceRead<readonly DeviceSummary[]>;
  readonly failover: SourceRead<readonly CpFailoverSummary[]>;
  readonly backups: SourceRead<{ readonly backups: readonly BackupArtefact[]; readonly deviations?: BackupDeviations | null }>;
  readonly lifecycle: SourceRead<LifecycleFleet>;
  readonly controls: SourceRead<readonly ComplianceControlItem[]>;
  readonly changes: SourceRead<ChangeFeedPage>;
  readonly health: SourceRead<SystemHealth>;
  readonly model: EstateModel | null;
  readonly refresh: () => void;
}

const EstateDataContext = createContext<EstateDataState | null>(null);

function settle<T>(promise: Promise<T>, valid?: (v: T) => boolean): Promise<SourceRead<T>> {
  return promise.then(
    (value) => (valid && !valid(value) ? ({ state: "failed" } as const) : ({ state: "ok", value } as const)),
    (err: unknown) => ({ state: isRestricted(err) ? "restricted" : "failed" } as const),
  );
}

export function useProvideEstateData(): EstateDataState {
  const session = useSession();
  const planesKey = (session?.permissions ?? []).join(",");
  const [loading, setLoading] = useState(true);
  const [fatalError, setFatalError] = useState<{ message: string; restricted: boolean } | null>(null);
  const [now, setNow] = useState(() => new Date());
  const [devices, setDevices] = useState<SourceRead<readonly DeviceSummary[]>>({ state: "skipped" });
  const [overview, setOverview] = useState<SourceRead<OverviewView>>({ state: "skipped" });
  const [failover, setFailover] = useState<SourceRead<readonly CpFailoverSummary[]>>({ state: "skipped" });
  const [backups, setBackups] = useState<
    SourceRead<{ readonly backups: readonly BackupArtefact[]; readonly deviations?: BackupDeviations | null }>
  >({ state: "skipped" });
  const [lifecycle, setLifecycle] = useState<SourceRead<LifecycleFleet>>({ state: "skipped" });
  const [controls, setControls] = useState<SourceRead<readonly ComplianceControlItem[]>>({ state: "skipped" });
  const [changes, setChanges] = useState<SourceRead<ChangeFeedPage>>({ state: "skipped" });
  const [health, setHealth] = useState<SourceRead<SystemHealth>>({ state: "skipped" });
  const [reloadTick, setReloadTick] = useState(0);

  const refresh = useCallback(() => {
    setReloadTick((n) => n + 1);
  }, []);

  useEffect(() => {
    let active = true;
    const grants = planesKey ? planesKey.split(",") : undefined;
    setLoading(true);

    const devicesP = settle(
      listDevices().then((r) => (Array.isArray(r?.devices) ? r.devices : [])),
    );
    const overviewP = getOverview().then(
      (v) => ({ ok: true as const, value: v }),
      (e: { status?: number; message?: string }) => ({
        ok: false as const,
        restricted: isRestricted(e),
        message: e?.message ?? `Overview read failed (status ${e?.status ?? "?"})`,
      }),
    );
    const failoverP = settle(listCpFailoverSummary(), (v) => Array.isArray(v));
    const backupsP = settle(
      Promise.all([
        listFleetBackups(),
        getBackupDeviations().catch(() => null),
      ]).then(([b, dev]) => ({
        backups: Array.isArray(b?.backups) ? b.backups : [],
        deviations: dev,
      })),
    );
    const lifecycleP: Promise<SourceRead<LifecycleFleet>> = sourceWanted("lifecycle", grants)
      ? settle(getLifecycle(), (v) => Array.isArray(v?.devices))
      : Promise.resolve({ state: "skipped" });
    const controlsP: Promise<SourceRead<readonly ComplianceControlItem[]>> = planeGranted(grants, "Compliance")
      ? settle(
          getComplianceControls().then((r) => (Array.isArray(r?.controls) ? r.controls : [])),
        )
      : Promise.resolve({ state: "skipped" });
    const changesP: Promise<SourceRead<ChangeFeedPage>> = sourceWanted("changes", grants)
      ? settle(
          getChangeFeed({
            from: new Date(Date.now() - CHANGE_WINDOW_DAYS * 86_400_000).toISOString(),
            kinds: ["USER"],
            limit: 200,
          }),
          (v) => Array.isArray(v?.events),
        )
      : Promise.resolve({ state: "skipped" });

    // Device health is the standard Operations read; a session without the system-log permission gets "restricted".
    const healthP = settle(getSystemHealth(), isSystemHealth);

    Promise.all([devicesP, overviewP, failoverP, backupsP, lifecycleP, controlsP, changesP, healthP]).then(
      ([dv, ov, fo, bk, lc, ct, ch, hl]) => {
        if (!active) return;
        setNow(new Date());
        setDevices(dv);
        setFailover(fo);
        setBackups(bk);
        setLifecycle(lc);
        setControls(ct);
        setChanges(ch);
        setHealth(hl);
        if (ov.ok && ov.value && typeof ov.value === "object") {
          setOverview({ state: "ok", value: ov.value });
          setFatalError(null);
        } else {
          const ovState: SourceRead<OverviewView> = !ov.ok && ov.restricted ? { state: "restricted" } : { state: "failed" };
          setOverview(ovState);
          if (dv.state !== "ok") {
            setFatalError({
              message: !ov.ok ? ov.message : "Estate reads unavailable",
              restricted: Boolean(!ov.ok && ov.restricted && dv.state === "restricted"),
            });
          } else {
            setFatalError(null);
          }
        }
        setLoading(false);
      },
    );

    return () => {
      active = false;
    };
  }, [planesKey, reloadTick]);

  useEffect(() => {
    const id = window.setInterval(() => setReloadTick((n) => n + 1), 60_000);
    return () => window.clearInterval(id);
  }, []);

  const permissions = useMemo(() => (planesKey ? planesKey.split(",") : undefined), [planesKey]);

  const model = useMemo(() => {
    if (loading && devices.state === "skipped" && overview.state === "skipped") return null;
    if (fatalError) return null;
    return buildEstateModel({
      now,
      devices,
      overview,
      failover,
      backups,
      lifecycle,
      controls,
      changes,
      health,
      permissions,
    });
  }, [loading, fatalError, now, devices, overview, failover, backups, lifecycle, controls, changes, health, permissions]);

  return useMemo(
    () => ({
      loading,
      fatalError,
      overview: overview.state === "ok" ? overview.value : null,
      devices,
      failover,
      backups,
      lifecycle,
      controls,
      changes,
      health,
      model,
      refresh,
    }),
    [loading, fatalError, overview, devices, failover, backups, lifecycle, controls, changes, health, model, refresh],
  );
}

export function EstateDataProvider({ children }: { readonly children: ReactNode }) {
  const value = useProvideEstateData();
  return createElement(EstateDataContext.Provider, { value }, children);
}

export function useEstateData(): EstateDataState {
  const ctx = useContext(EstateDataContext);
  if (!ctx) {
    throw new Error("useEstateData must be used inside EstateDataProvider");
  }
  return ctx;
}
