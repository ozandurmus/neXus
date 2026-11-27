import { vendorLabel } from "../../shell/presenters";
import type { Tone } from "../../shell/tone";

/**
 * Pure read-only types and view helpers for the policy-change event-trigger operator
 * and masked replay-viewer status panel.
 *
 * Mirrors:
 * - EventTriggerOperatorProjection.java
 * - EventTriggerKillSwitch.java
 * - POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md §7.1–§7.2
 */

export type UnitKind = "DOMAIN" | "DEVICE_GROUP" | "DEVICE" | "CLUSTER";

export type PendingDeferState =
  | "NONE"
  | "DEBOUNCING"
  | "DEFERRED_SOURCE_BUSY"
  | "DEFERRED_BACKUP_WINDOW"
  | "DEFERRED_WINDOW_GUARD";

export type KnownLivenessState =
  | "CONNECTED"
  | "HEARTBEAT_OK"
  | "DISCONNECTED"
  | "HEARTBEAT_STALE"
  | "PASSIVE_NO_HEARTBEAT"
  | "DEGRADED"
  | "NOT_ENROLLED";

export type LivenessState = KnownLivenessState | "UNKNOWN";

export const NO_CHANGE_EVENTS_CODE = "NO_CHANGE_EVENTS_N_DAYS";
export const NO_CHANGE_EVENTS_THRESHOLD_DAYS = 14;
export const GAP_DISPLAY_NAME = "GAP";

export interface ProjectedUnitRow {
  readonly unitKind: UnitKind;
  readonly displayName: string;
  readonly vendor: string;
  readonly livenessState: string | null | undefined;
  readonly livenessHealthy: boolean;
  readonly lastDeviceEventAt: string | null;
  readonly lastReceivedAt: string | null;
  readonly lastPolicyCollectedAt: string | null;
  readonly pendingDeferState: PendingDeferState;
  readonly pendingStatusText: string | null;
  readonly triggerDueAt: string | null;
  readonly noChangeEventsFlag: boolean;
  readonly noChangeEventsCode: string | null;
  readonly noChangeEventsMessage: string | null;
  readonly noChangeEventsAlarm: boolean;
}

export interface VendorFleetCoverage {
  readonly vendor: string;
  readonly healthyLiveUnits: number;
  readonly totalUnits: number;
}

export interface EventTriggerProjection {
  readonly masked: boolean;
  readonly projectedAt: string;
  readonly units: readonly ProjectedUnitRow[];
  readonly fleetCoverageByVendor: Readonly<Record<string, VendorFleetCoverage>>;
}

export interface EventTriggerKillSwitchSettingsSnapshot {
  readonly globalEnabled: boolean | null | undefined;
  readonly vendorEnabled: Readonly<Record<string, boolean | null | undefined>> | null;
  readonly eventSourceEnabled: Readonly<Record<string, boolean | null | undefined>> | null;
}

export interface EventTriggerKillSwitchSummary {
  readonly globalEnabled: boolean;
  readonly haltedVendors: readonly string[];
  readonly haltedSources: readonly string[];
  readonly failClosedUnknown: boolean;
}

export interface LivenessMeta {
  readonly state: LivenessState;
  readonly label: LivenessState;
  readonly tone: Tone;
  readonly healthy: boolean;
  readonly wording: string;
}

const LIVENESS_META: Readonly<Record<KnownLivenessState, LivenessMeta>> = {
  CONNECTED: {
    state: "CONNECTED",
    label: "CONNECTED",
    tone: "ok",
    healthy: true,
    wording: "Live TLS session connected.",
  },
  HEARTBEAT_OK: {
    state: "HEARTBEAT_OK",
    label: "HEARTBEAT_OK",
    tone: "ok",
    healthy: true,
    wording: "Relay heartbeat healthy.",
  },
  DISCONNECTED: {
    state: "DISCONNECTED",
    label: "DISCONNECTED",
    tone: "bad",
    healthy: false,
    wording: "TLS event stream disconnected; covered by 6h scheduled collection.",
  },
  HEARTBEAT_STALE: {
    state: "HEARTBEAT_STALE",
    label: "HEARTBEAT_STALE",
    tone: "warn",
    healthy: false,
    wording: "Event pipeline stalled (relay heartbeat or MDS Log Exporter unhealthy).",
  },
  PASSIVE_NO_HEARTBEAT: {
    state: "PASSIVE_NO_HEARTBEAT",
    label: "PASSIVE_NO_HEARTBEAT",
    tone: "neutral",
    healthy: false,
    wording: "Passive event source configured; liveness between change events is not measurable.",
  },
  DEGRADED: {
    state: "DEGRADED",
    label: "DEGRADED",
    tone: "warn",
    healthy: false,
    wording: "Event stream degraded (dropped or unmatched frames in last 7d); see source counters.",
  },
  NOT_ENROLLED: {
    state: "NOT_ENROLLED",
    label: "NOT_ENROLLED",
    tone: "neutral",
    healthy: false,
    wording: "Not covered by change events; collected on schedule (6h policy / 00:00 daily config).",
  },
};

const UNKNOWN_LIVENESS_META: LivenessMeta = {
  state: "UNKNOWN",
  label: "UNKNOWN",
  tone: "unknown",
  healthy: false,
  wording: "Event pipeline liveness unknown",
};

/**
 * Normalizes a liveness token against the 7 closed states from §7.1.
 * Null, blank, or unrecognized values fail closed to `UNKNOWN`.
 */
export function normalizeLivenessState(raw: string | null | undefined): LivenessState {
  if (typeof raw !== "string") {
    return "UNKNOWN";
  }
  const trimmed = raw.trim();
  if (!trimmed || !Object.prototype.hasOwnProperty.call(LIVENESS_META, trimmed)) {
    return "UNKNOWN";
  }
  return trimmed as KnownLivenessState;
}

/**
 * Maps any liveness state to its §7.1 wording and chip tone, failing closed to `UNKNOWN`.
 */
export function describeLiveness(raw: string | null | undefined): LivenessMeta {
  const normalized = normalizeLivenessState(raw);
  if (normalized === "UNKNOWN") {
    return UNKNOWN_LIVENESS_META;
  }
  return LIVENESS_META[normalized];
}

export interface PendingDeferMeta {
  readonly state: PendingDeferState | "UNKNOWN";
  readonly badgeLabel: string;
  readonly tone: Tone;
  readonly isPending: boolean;
  readonly detailText: string | null;
}

const PENDING_DEFER_META: Readonly<Record<PendingDeferState, Omit<PendingDeferMeta, "detailText"> & { readonly defaultDetail: string | null }>> = {
  NONE: {
    state: "NONE",
    badgeLabel: "Idle",
    tone: "neutral",
    isPending: false,
    defaultDetail: null,
  },
  DEBOUNCING: {
    state: "DEBOUNCING",
    badgeLabel: "DEBOUNCING",
    tone: "brand",
    isPending: true,
    defaultDetail: "Change detected — collection debouncing",
  },
  DEFERRED_SOURCE_BUSY: {
    state: "DEFERRED_SOURCE_BUSY",
    badgeLabel: "DEFERRED_SOURCE_BUSY",
    tone: "warn",
    isPending: true,
    defaultDetail: "Deferred: management source busy (DEFERRED_SOURCE_BUSY)",
  },
  DEFERRED_BACKUP_WINDOW: {
    state: "DEFERRED_BACKUP_WINDOW",
    badgeLabel: "DEFERRED_BACKUP_WINDOW",
    tone: "warn",
    isPending: true,
    defaultDetail: "Held for 01:45–03:00 backup window (DEFERRED_BACKUP_WINDOW)",
  },
  DEFERRED_WINDOW_GUARD: {
    state: "DEFERRED_WINDOW_GUARD",
    badgeLabel: "DEFERRED_WINDOW_GUARD",
    tone: "warn",
    isPending: true,
    defaultDetail: "Held for scheduled collection window guard (DEFERRED_WINDOW_GUARD)",
  },
};

const DEBOUNCE_DUE_SUFFIX_RE = /^ \(due \d{4}-\d{2}-\d{2}T[\d:.]+Z\)$/;

// Free text from the projection is rendered only when it is the server's own closed wording.
function isKnownPendingWording(state: PendingDeferState, text: string | null | undefined): boolean {
  const fixed = PENDING_DEFER_META[state].defaultDetail;
  if (typeof text !== "string" || !fixed) {
    return false;
  }
  const trimmed = text.trim();
  if (trimmed === fixed) {
    return true;
  }
  return state === "DEBOUNCING" && trimmed.startsWith(fixed) && DEBOUNCE_DUE_SUFFIX_RE.test(trimmed.slice(fixed.length));
}

/**
 * Maps a `PendingDeferState` to badge label, chip tone, and human-readable status text.
 */
export function describePendingDefer(
  state: PendingDeferState | string | null | undefined,
  pendingStatusText?: string | null,
): PendingDeferMeta {
  if (typeof state !== "string" || !Object.prototype.hasOwnProperty.call(PENDING_DEFER_META, state)) {
    return {
      state: "UNKNOWN",
      badgeLabel: "UNKNOWN",
      tone: "unknown",
      isPending: false,
      detailText: "Pending defer state unknown",
    };
  }
  const spec = PENDING_DEFER_META[state as PendingDeferState];
  const customDetail = isKnownPendingWording(state as PendingDeferState, pendingStatusText)
    ? pendingStatusText!.trim()
    : null;
  return {
    state: spec.state,
    badgeLabel: spec.badgeLabel,
    tone: spec.tone,
    isPending: spec.isPending,
    detailText: spec.isPending ? (customDetail ?? spec.defaultDetail) : null,
  };
}

export interface NoChangeEventsNotice {
  readonly code: typeof NO_CHANGE_EVENTS_CODE;
  readonly message: string;
  readonly tone: "neutral";
  readonly alarm: false;
}

const VALID_SILENCE_MESSAGE_RE = /^No policy-install events received for \d+ days$/;

export const NO_CHANGE_EVENTS_UNKNOWN_DAYS_MESSAGE =
  "No policy-install events received (day count unavailable)";

/**
 * Returns the strictly informational `NO_CHANGE_EVENTS_N_DAYS` notice when the flag is raised,
 * or `null` when absent or `NOT_ENROLLED`. A missing or malformed message never gets an
 * invented day count.
 * Never returns an alarm tone and never emits the phrase "no changes".
 */
export function describeNoChangeEvents(row: ProjectedUnitRow): NoChangeEventsNotice | null {
  const liveness = normalizeLivenessState(row.livenessState);
  if (!row.noChangeEventsFlag || liveness === "NOT_ENROLLED") {
    return null;
  }
  const rawMessage = typeof row.noChangeEventsMessage === "string" ? row.noChangeEventsMessage.trim() : "";
  const safeMessage = VALID_SILENCE_MESSAGE_RE.test(rawMessage)
    ? rawMessage
    : NO_CHANGE_EVENTS_UNKNOWN_DAYS_MESSAGE;

  return {
    code: NO_CHANGE_EVENTS_CODE,
    message: safeMessage,
    tone: "neutral",
    alarm: false,
  };
}

/**
 * Formats per-vendor fleet coverage as `healthyLiveUnits / totalUnits`, failing closed
 * to `"UNKNOWN"` if counts are invalid.
 */
export function isCoverageKnown(coverage: VendorFleetCoverage | null | undefined): boolean {
  return formatCoverageRatio(coverage) !== "UNKNOWN";
}

export function isProjectionMasked(projection: EventTriggerProjection | null | undefined): boolean {
  return projection?.masked === true;
}

export function formatCoverageRatio(coverage: VendorFleetCoverage | null | undefined): string {
  if (!coverage) {
    return "UNKNOWN";
  }
  const { healthyLiveUnits, totalUnits } = coverage;
  if (
    !Number.isInteger(healthyLiveUnits)
    || !Number.isInteger(totalUnits)
    || healthyLiveUnits < 0
    || totalUnits < 0
    || healthyLiveUnits > totalUnits
  ) {
    return "UNKNOWN";
  }
  return `${healthyLiveUnits} / ${totalUnits}`;
}

const VENDOR_ALIASES: Readonly<Record<string, string>> = {
  checkpoint: "check_point",
  paloalto: "palo_alto",
};

/**
 * Normalizes event-trigger vendor identifiers (`checkpoint` / `check_point`, etc.) to
 * display names via the shared presenter map.
 */
export function eventTriggerVendorLabel(vendor: string | null | undefined): string {
  const trimmed = (vendor ?? "").trim();
  if (!trimmed) {
    return "UNKNOWN";
  }
  const canonical = Object.prototype.hasOwnProperty.call(VENDOR_ALIASES, trimmed)
    ? VENDOR_ALIASES[trimmed]
    : trimmed;
  return vendorLabel(canonical);
}

const UNIT_KIND_LABELS: Readonly<Record<UnitKind, string>> = {
  DOMAIN: "Domain",
  DEVICE_GROUP: "Device group",
  DEVICE: "Device",
  CLUSTER: "Cluster",
};

export function unitKindLabel(kind: UnitKind | string | null | undefined): string {
  if (typeof kind === "string" && Object.prototype.hasOwnProperty.call(UNIT_KIND_LABELS, kind)) {
    return UNIT_KIND_LABELS[kind as UnitKind];
  }
  return "UNKNOWN";
}

export function safeUnitDisplayName(displayName: string | null | undefined): string {
  const trimmed = (displayName ?? "").trim();
  return trimmed || GAP_DISPLAY_NAME;
}

/**
 * Derives a read-only `EventTriggerKillSwitchSummary` from a `SettingsSnapshot`.
 * Fails closed (`failClosedUnknown: true`) when snapshot or its maps are missing.
 */
export function summarizeKillSwitchSettings(
  snapshot: EventTriggerKillSwitchSettingsSnapshot | null | undefined,
): EventTriggerKillSwitchSummary {
  if (!snapshot || typeof snapshot.globalEnabled !== "boolean"
    || snapshot.vendorEnabled == null || snapshot.eventSourceEnabled == null) {
    return {
      globalEnabled: false,
      haltedVendors: [],
      haltedSources: [],
      failClosedUnknown: true,
    };
  }
  const haltedVendors = Object.entries(snapshot.vendorEnabled)
    .filter(([, enabled]) => enabled !== true)
    .map(([vendor]) => vendor);
  const haltedSources = Object.entries(snapshot.eventSourceEnabled)
    .filter(([, enabled]) => enabled !== true)
    .map(([sourceId]) => sourceId);
  return {
    globalEnabled: snapshot.globalEnabled,
    haltedVendors,
    haltedSources,
    failClosedUnknown: false,
  };
}

export type KillSwitchBannerStatus = "ACTIVE" | "GLOBAL_HALTED" | "PARTIAL_HALTED" | "UNKNOWN";

export interface KillSwitchBannerMeta {
  readonly status: KillSwitchBannerStatus;
  readonly pillLabel: string;
  readonly tone: Tone;
  readonly title: string;
  readonly detail: string;
}

/**
 * Converts a `EventTriggerKillSwitchSummary` into operator banner metadata.
 * Null/undefined or `failClosedUnknown` always resolves fail-closed to `UNKNOWN`.
 */
export function describeKillSwitchBanner(
  summary: EventTriggerKillSwitchSummary | null | undefined,
): KillSwitchBannerMeta {
  if (!summary || summary.failClosedUnknown) {
    return {
      status: "UNKNOWN",
      pillLabel: "UNKNOWN",
      tone: "unknown",
      title: "Event-trigger kill-switch state unknown (failing closed)",
      detail: "Kill-switch settings snapshot is unavailable. New event-triggered submissions and unclaimed exemption claims are halted fail-closed; scheduled 6h policy and 00:00 daily configuration runs remain unaffected.",
    };
  }
  if (!summary.globalEnabled) {
    return {
      status: "GLOBAL_HALTED",
      pillLabel: "GLOBAL HALTED",
      tone: "bad",
      title: "Event-triggered collection globally halted",
      detail: "Global kill-switch is disabled. New event-triggered submissions and unclaimed trigger_window_exemption claims are halted immediately; scheduled 6h policy and 00:00 daily configuration runs continue.",
    };
  }
  const haltedVendors = summary.haltedVendors ?? [];
  const haltedSources = summary.haltedSources ?? [];
  if (haltedVendors.length > 0 || haltedSources.length > 0) {
    const parts: string[] = [];
    if (haltedVendors.length > 0) {
      parts.push(`Halted vendors: ${haltedVendors.map(eventTriggerVendorLabel).join(", ")}`);
    }
    if (haltedSources.length > 0) {
      parts.push(`Halted sources: ${haltedSources.join(", ")}`);
    }
    return {
      status: "PARTIAL_HALTED",
      pillLabel: "PARTIALLY HALTED",
      tone: "warn",
      title: "Event-triggered collection halted for selected vendors or sources",
      detail: `${parts.join(". ")}. Scheduled 6h policy and 00:00 daily configuration runs continue.`,
    };
  }
  return {
    status: "ACTIVE",
    pillLabel: "ACTIVE",
    tone: "ok",
    title: "Event-trigger kill-switch active",
    detail: "Global, vendor, and enrolled event-source switches permit event-triggered collection.",
  };
}
