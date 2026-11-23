import type {
  ConfigurationDeviationEntry,
  ConfigurationIndexEntry,
  ConfigurationOverride,
} from "../auth/adminApi";
import { categoryWord } from "../screens/BackupDeviationCard";
import { FORTIGATE_SECTIONS } from "../screens/configurationProjection";
import type { SourceRead } from "../screens/overview/attentionRules";
import { plural } from "../shell/plural";
import { statusWord } from "../shell/presenters";
import type { BadgeState } from "./estateModel";

export const CONFIG_SECTIONS_CAP = 10;
export const CONFIG_CHANGES_CAP = 8;
export const CONFIG_OVERRIDES_CAP = 6;

export type ConfigurationChangedSection = ConfigurationDeviationEntry;

export interface DeviceConfigurationDetail {
  readonly device_id?: string;
  readonly collected_at: string | null;
  readonly vendor?: string | null;
  readonly read_kind?: string | null;
  readonly canonical_hash?: string | null;
  readonly change_state?: "changed" | "unchanged" | "first_run" | string | null;
  readonly change_deviation_summary?: {
    readonly status?: string;
    readonly entries?: readonly ConfigurationDeviationEntry[];
    readonly changed_sections?: readonly ConfigurationDeviationEntry[];
  } | null;
  readonly withheld_line_count?: number | null;
  readonly sanitized_text_available?: boolean;
  readonly text_readable?: boolean;
  readonly index?: readonly ConfigurationIndexEntry[] | null;
  readonly overrides?: readonly ConfigurationOverride[] | null;
  readonly supplementary_runs?: readonly unknown[];
}

export type EstateReadState<T> =
  | "loading"
  | { readonly state: "loading" }
  | SourceRead<T>;

export interface DossierChangedSectionRow {
  readonly context: string;
  readonly section: string;
  readonly kindWord: string;
  readonly severityWord: string;
  readonly badgeState: BadgeState;
  readonly oldCountText: string;
  readonly newCountText: string;
}

export interface DossierConfigurationModel {
  readonly badge: {
    readonly state: BadgeState;
    readonly word: string;
  };
  readonly evidenceAt: string | null;
  readonly summaryText: string;
  readonly readKind: string;
  readonly withheldLineCount: number;
  readonly withheldText: string;
  readonly hashPrefix: string;
  readonly totalEntryCount: number;
  readonly totalSectionCount: number;
  readonly sectionsShown: readonly ConfigurationIndexEntry[];
  readonly totalChangedCount: number;
  readonly changedShown: readonly DossierChangedSectionRow[];
  readonly totalOverrideCount: number;
  readonly overridesShown: readonly ConfigurationOverride[];
}

function emptyModel(
  badge: { readonly state: BadgeState; readonly word: string },
  summaryText: string,
): DossierConfigurationModel {
  return {
    badge,
    evidenceAt: null,
    summaryText,
    readKind: "UNKNOWN",
    withheldLineCount: 0,
    withheldText: `${plural(0, "secret-bearing line", "secret-bearing lines")} withheld`,
    hashPrefix: "UNKNOWN",
    totalEntryCount: 0,
    totalSectionCount: 0,
    sectionsShown: [],
    totalChangedCount: 0,
    changedShown: [],
    totalOverrideCount: 0,
    overridesShown: [],
  };
}

export function projectDossierConfiguration(
  read: EstateReadState<DeviceConfigurationDetail>,
  opts: { readonly vendor: string | null; readonly role: string | null },
): DossierConfigurationModel {
  const state = read === "loading" ? "loading" : read.state;
  if (state === "loading") {
    return emptyModel({ state: "unk", word: "Loading" }, "Reading configuration snapshot…");
  }
  if (state === "restricted") {
    return emptyModel({ state: "na", word: "Restricted" }, "Restricted for this role.");
  }
  if (state !== "ok" || typeof read === "string" || !("value" in read) || !read.value) {
    return emptyModel(
      { state: "unk", word: "UNKNOWN" },
      "Configuration read failed — UNKNOWN (not assumed aligned).",
    );
  }

  const cfg = read.value;
  const role = (opts.role ?? "").toLowerCase();
  if (!cfg.collected_at && role === "appliance") {
    return emptyModel(
      { state: "na", word: "Not applicable" },
      "Appliance — configuration collection does not target management appliances.",
    );
  }
  if (!cfg.collected_at) {
    return emptyModel(
      { state: "unk", word: "UNKNOWN" },
      "No configuration snapshot collected yet — UNKNOWN (not assumed aligned).",
    );
  }

  const vendor = (opts.vendor ?? cfg.vendor ?? "").toLowerCase();
  const rawIndex = Array.isArray(cfg.index) ? cfg.index : [];
  const filteredIndex =
    vendor === "fortinet"
      ? rawIndex.filter((row) => Object.prototype.hasOwnProperty.call(FORTIGATE_SECTIONS, row.section))
      : rawIndex;

  const rawChanged = cfg.change_deviation_summary?.changed_sections ?? cfg.change_deviation_summary?.entries;
  const changedSections = Array.isArray(rawChanged) ? rawChanged : [];
  const overrides = Array.isArray(cfg.overrides) ? cfg.overrides : [];

  const badge: { readonly state: BadgeState; readonly word: string } =
    cfg.change_state === "changed" || changedSections.length > 0 || overrides.length > 0
      ? { state: "watch", word: "Changed" }
      : cfg.change_state === "unchanged" || cfg.change_state === "first_run"
        ? { state: "ok", word: cfg.change_state === "first_run" ? "First run" : "Aligned" }
        : { state: "unk", word: "UNKNOWN" };

  const readKind = cfg.read_kind ?? "UNKNOWN";
  const withheldLineCount = cfg.withheld_line_count ?? 0;
  const withheldText = `${plural(withheldLineCount, "secret-bearing line", "secret-bearing lines")} withheld`;
  const hashPrefix = cfg.canonical_hash ? cfg.canonical_hash.slice(0, 12) : "UNKNOWN";
  const totalEntryCount = filteredIndex.reduce((sum, row) => sum + (row.entry_count ?? 0), 0);
  const totalSectionCount = filteredIndex.length;
  const sectionsShown = filteredIndex.slice(0, CONFIG_SECTIONS_CAP);
  const totalChangedCount = changedSections.length;
  const changedShown: readonly DossierChangedSectionRow[] = changedSections
    .slice(0, CONFIG_CHANGES_CAP)
    .map((s) => ({
      context: s.context,
      section: s.section,
      kindWord: statusWord(s.kind),
      severityWord: s.category ? `Major · ${categoryWord(s.category)}` : "Minor",
      badgeState: s.category ? "watch" : "na",
      oldCountText:
        s.old_count === null || s.old_count === undefined ? "none" : plural(s.old_count, "entry", "entries"),
      newCountText:
        s.new_count === null || s.new_count === undefined ? "none" : plural(s.new_count, "entry", "entries"),
    }));
  const totalOverrideCount = overrides.length;
  const overridesShown = overrides.slice(0, CONFIG_OVERRIDES_CAP);

  const summaryText = `${plural(totalSectionCount, "section", "sections")} indexed · ${plural(totalEntryCount, "entry", "entries")} · ${plural(totalChangedCount, "changed section", "changed sections")} · ${plural(totalOverrideCount, "local override", "local overrides")}`;

  return {
    badge,
    evidenceAt: cfg.collected_at,
    summaryText,
    readKind,
    withheldLineCount,
    withheldText,
    hashPrefix,
    totalEntryCount,
    totalSectionCount,
    sectionsShown,
    totalChangedCount,
    changedShown,
    totalOverrideCount,
    overridesShown,
  };
}
