import type { ComplianceControlDetail } from "../auth/adminApi";
import { observedValue } from "../screens/ComplianceGuidance";
import type { SourceRead } from "../screens/overview/attentionRules";
import { vendorLabel } from "../shell/presenters";

/** Expected-configuration lines printed per control before the "+N more" footer. */
export const CONTROL_EXPECTED_CAP = 8;
/** Framework mappings printed per control before the "+N more" footer. */
export const CONTROL_FRAMEWORKS_CAP = 8;

const WITHHELD_MESSAGE = "Evidence withheld";

export interface ControlExpectedLine {
  readonly vendorWord: string;
  readonly text: string;
}

export interface ControlVerdictCounts {
  readonly fail: number;
  readonly unknown: number;
  readonly pass: number;
  readonly total: number;
}

export interface ControlVerdictRow {
  readonly deviceId: string;
  readonly hostname: string | null;
  readonly status: string;
  readonly vendorWord: string;
  readonly observedText: string;
  readonly messageText: string | null;
  readonly guidance: ComplianceControlDetail["devices"][number]["guidance"];
}

export type ControlDetailProjection =
  | { readonly state: "loading" }
  | { readonly state: "restricted" }
  | { readonly state: "failed" }
  | {
      readonly state: "ok";
      /** Trimmed rationale, or null when the detail carries none. */
      readonly rationale: string | null;
      readonly expectedShown: readonly ControlExpectedLine[];
      readonly totalExpected: number;
      readonly frameworksShown: readonly string[];
      readonly totalFrameworks: number;
      readonly verdictCounts: ControlVerdictCounts;
      /** Every non-PASS verdict, in source order; the screen caps what it prints. */
      readonly verdicts: readonly ControlVerdictRow[];
    };

/**
 * Projects one control-detail read for the Estate Controls lens. A read that is still loading, refused for the
 * role, failed, or returned a body without a devices list is never presented as a clean detail: it projects to
 * a non-ok state, and the screen shows UNKNOWN for it.
 */
export function projectControlDetail(
  detailRead: SourceRead<ComplianceControlDetail> | "loading" | undefined,
): ControlDetailProjection {
  if (detailRead === undefined || detailRead === "loading") return { state: "loading" };
  if (detailRead.state === "restricted") return { state: "restricted" };
  if (detailRead.state !== "ok") return { state: "failed" };

  const detail = detailRead.value;
  if (!Array.isArray(detail?.devices)) return { state: "failed" };

  const expected = Array.isArray(detail.expected) ? detail.expected : [];
  const frameworks = Array.isArray(detail.frameworks) ? detail.frameworks : [];
  const devices = detail.devices;

  const verdictCounts = { fail: 0, unknown: 0, pass: 0, total: devices.length };
  const verdicts: ControlVerdictRow[] = [];
  for (const d of devices) {
    if (d.status === "PASS") {
      verdictCounts.pass += 1;
      continue;
    }
    if (d.status === "FAIL") verdictCounts.fail += 1;
    else verdictCounts.unknown += 1;

    const withheld = d.message === WITHHELD_MESSAGE;
    verdicts.push({
      deviceId: d.device_id,
      hostname: d.hostname ?? null,
      status: d.status,
      vendorWord: d.vendor ? vendorLabel(d.vendor) : "UNKNOWN",
      observedText: withheld ? "withheld" : (observedValue(d.observed_value) || d.message || "No observed value"),
      messageText: d.message && !withheld && d.observed_value ? d.message : null,
      guidance: d.guidance ?? null,
    });
  }

  const rationale = typeof detail.rationale === "string" && detail.rationale.trim() ? detail.rationale.trim() : null;

  return {
    state: "ok",
    rationale,
    expectedShown: expected
      .slice(0, CONTROL_EXPECTED_CAP)
      .map((line) => ({ vendorWord: vendorLabel(line.vendor), text: line.text })),
    totalExpected: expected.length,
    frameworksShown: frameworks
      .slice(0, CONTROL_FRAMEWORKS_CAP)
      .map((f) => `${f.framework.replace(/_/g, " ")} ${f.reference || f.clauseId || ""}`.trim()),
    totalFrameworks: frameworks.length,
    verdictCounts,
    verdicts,
  };
}
