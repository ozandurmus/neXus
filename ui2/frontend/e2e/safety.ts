import type { BrowserContext } from "@playwright/test";
import { createHash } from "node:crypto";

export function isAiview(body: unknown): boolean {
  if (!body || typeof body !== "object") return false;
  const status = body as Record<string, unknown>;
  return status.authenticated !== false && status.must_change_password !== true
    && Array.isArray(status.role_tokens) && status.role_tokens.includes("role:replay_viewer");
}

export function readAllowed(method: string, url: string, origin: string, _resourceType = "fetch"): boolean {
  // src/auth/ only reads /session/status. No session-maintenance POST exists.
  // Fonts and stylesheets are self-hosted; no cross-origin requests are allowed.
  return (method === "GET" || method === "HEAD") && new URL(url).origin === origin;
}

export function hasCanaryToken(text: string, digests: ReadonlySet<string>): boolean {
  return [...text.matchAll(/\b[A-Za-z0-9][A-Za-z0-9._:-]*\b/g)].some(([token]) =>
    digests.has(createHash("sha256").update(token).digest("hex")));
}

export const loadErrorText = /something went wrong|uncaught|unhandled (?:api|error)|failed to fetch|networkerror|(?:api|request|read|load) (?:failed|error)|could not be (?:read|loaded)|(?:inventory|overview|workspace|job logs|device registry) unavailable/i;

export async function installReadOnlyGuard(context: BrowserContext, origin: string, report: (message: string) => void) {
  await context.routeWebSocket("**/*", (socket) => {
    report("WebSocket blocked: not a read-only HTTP request");
    socket.close();
  });
  await context.route("**/*", async (route) => {
    const request = route.request();
    if (!readAllowed(request.method(), request.url(), origin, request.resourceType())) {
      report("Read-only guard aborted a non-GET/HEAD or cross-origin request (URL/body withheld)");
      await route.abort("blockedbyclient");
      return;
    }
    if (new URL(request.url()).pathname === "/session/status") {
      try {
        const response = await route.fetch({ maxRedirects: 0 });
        if (response.status() !== 200 || !isAiview(await response.json())) throw new Error();
        await route.fulfill({ response });
      } catch {
        report("Session is no longer an active aiview session");
        await route.abort("blockedbyclient");
      }
      return;
    }
    await route.continue();
  });
}

// Informational read-only notices are not error banners; unknown alert styles fail closed.
export function isErrorAlert(classes: string): boolean {
  return !/\bMuiAlert-(?:standard|outlined|filled)(?:Info|Success)\b/.test(classes);
}

/** Explicit warning-severity MUI alert (never error, never unknown/unclassified). */
export function isWarningAlert(classes: string): boolean {
  return /\bMuiAlert-(?:standard|outlined|filled)Warning\b/.test(classes);
}

/** An alert as the page renders it: its classes, operational status marker, and visible reason text. */
export type RenderedAlert = { className: string; statusAlert: string | null; text?: string | null };

/** Whether an alert is a legitimate operational warning that explicitly states its reason. */
export function isStatedOperationalWarning(alert: RenderedAlert): boolean {
  return alert.statusAlert === "operational"
    && isWarningAlert(alert.className)
    && (alert.text ?? "").trim().length > 0;
}

/**
 * Alerts that fail the screen check. Informational/success alerts never fail. On the Platform status screen,
 * legitimate operational warnings from the status API (disk headroom warning, artefact quota near-limit, quarantined
 * permits, unavailable pod status, daily scan warnings) carry `data-status-alert="operational"` and pass only when they
 * are warning-severity AND state a non-empty reason. Error-severity alerts (including operational errors such as disk
 * critical or quota exceeded), unmarked alerts, unknown alert styles, and reasonless warnings still fail.
 */
export function failingAlerts(alerts: readonly RenderedAlert[]): RenderedAlert[] {
  return alerts.filter(alert => isErrorAlert(alert.className) && !isStatedOperationalWarning(alert));
}

/** The top-bar PlatformHealthIndicator as rendered in the DOM (`[data-platform-health]`). */
export type RenderedPlatformHealth = {
  readonly state: string | null;
  readonly ariaLabel: string | null;
};

/** The inline health summary inside Administration > Platform status (`[data-testid="platform-health-summary"]`). */
export type RenderedPlatformSummary = {
  readonly label: string;
  readonly reason: string;
  readonly text: string;
};

/** Snapshot of Administration > Platform status and the top-bar health indicator for e2e assertions. */
export type PlatformStatusSnapshot = {
  readonly hasPanel: boolean;
  readonly hasStorageSection: boolean;
  readonly servicePodsHeading: string | null;
  readonly observedUnknown: boolean;
  readonly indicator: RenderedPlatformHealth | null;
  readonly summary: RenderedPlatformSummary | null;
  readonly alerts: readonly RenderedAlert[];
};

const UNKNOWN_WORD = /\bUNKNOWN\b/i;

function indicatorReason(ariaLabel: string | null): string {
  if (!ariaLabel) return "";
  const match = /\bstatus:\s*(?:OK|degraded|down)\.\s*(.+)$/i.exec(ariaLabel.trim());
  return match ? match[1].trim() : "";
}

function isReasonStated(reason: string): boolean {
  const trimmed = reason.trim();
  if (trimmed.length === 0) return false;
  if (/^no reason given\.?$/i.test(trimmed) || /:\s*no reason given\.?$/i.test(trimmed)) return false;
  if (/^unknown\.?$/i.test(trimmed)) return false;
  return true;
}

/**
 * Checks the top-bar PlatformHealthIndicator (`[data-platform-health]`).
 * Returns a privacy-safe violation classification (never raw text), or `null` when valid:
 *  - `ok` with a stated non-UNKNOWN reason passes;
 *  - `degraded` (WARN/ATTENTION) with a stated reason passes;
 *  - `down` (error state), missing/unknown state, reasonless state, or `ok` carrying UNKNOWN fails.
 */
export function platformHealthIndicatorViolation(indicator: RenderedPlatformHealth | null): string | null {
  if (!indicator) return "Platform health indicator is missing";
  const state = (indicator.state ?? "").trim();
  if (state === "down") return "Platform health indicator reports error state (down)";
  if (state !== "ok" && state !== "degraded") return "Platform health indicator reports an invalid or unknown state";
  const reason = indicatorReason(indicator.ariaLabel);
  if (!isReasonStated(reason)) return `Platform health indicator (${state}) has no stated reason`;
  if (state === "ok" && UNKNOWN_WORD.test(reason)) return "Platform health indicator reports healthy (ok) with UNKNOWN reason";
  return null;
}

/**
 * Checks Administration > Platform status as a whole. Every violation message is a static classification
 * (no raw page text or identities). Passes when:
 *  - the panel and its Storage and Service pods sections are rendered;
 *  - the top-bar health indicator is present and healthy (`ok`) or WARN/ATTENTION (`degraded`) with a stated reason;
 *  - no error-severity, unmarked, or reasonless alert is present;
 *  - an UNKNOWN observation (service pods UNKNOWN, observation time UNKNOWN, or UNKNOWN summary) is never presented as healthy;
 *  - when service pods are available, the inline health summary matches the indicator and states a reason;
 *  - when service pods are unavailable (`Service pods (UNKNOWN)`), the indicator is `degraded` (not `ok`) and an
 *    operational warning alert explains why.
 */
export function platformStatusViolations(snapshot: PlatformStatusSnapshot): string[] {
  const violations: string[] = [];
  if (!snapshot.hasPanel) violations.push("Platform status panel is missing");
  if (!snapshot.hasStorageSection) violations.push("Platform status Storage section is missing");

  const heading = (snapshot.servicePodsHeading ?? "").trim();
  const validPodsHeading = /^Service pods \((?:\d+|UNKNOWN)\)$/.test(heading);
  const servicePodsUnknown = /^Service pods \(UNKNOWN\)$/.test(heading);
  if (!validPodsHeading) violations.push("Platform status Service pods section is missing");

  if (failingAlerts(snapshot.alerts).length > 0) {
    violations.push("Platform status exposes an error, unmarked, or reasonless alert");
  }

  const indicatorIssue = platformHealthIndicatorViolation(snapshot.indicator);
  if (indicatorIssue) violations.push(indicatorIssue);

  const indicatorState = (snapshot.indicator?.state ?? "").trim();
  if (indicatorState === "ok" && servicePodsUnknown) {
    violations.push("Platform status shows healthy (ok) while service pods are UNKNOWN");
  }
  if (indicatorState === "ok" && snapshot.observedUnknown) {
    violations.push("Platform status shows healthy (ok) while observation time is UNKNOWN");
  }

  if (servicePodsUnknown) {
    if (!snapshot.alerts.some(isStatedOperationalWarning)) {
      violations.push("Platform status reports UNKNOWN service pods without an operational warning reason");
    }
    if (snapshot.summary && /\bOK\b/.test(snapshot.summary.label)) {
      violations.push("Platform status summary reports OK while service pods are UNKNOWN");
    }
  } else if (validPodsHeading) {
    if (!snapshot.summary) {
      violations.push("Platform status health summary is missing");
    } else {
      const label = snapshot.summary.label.trim();
      const summaryState = /\bOK\b/.test(label) ? "ok"
        : /\bdegraded\b/i.test(label) ? "degraded"
        : /\bdown\b/i.test(label) ? "down"
        : null;
      if (summaryState === "down") {
        violations.push("Platform status summary reports error state (down)");
      } else if (summaryState === null) {
        violations.push("Platform status summary has an invalid or unknown state");
      } else {
        if (!isReasonStated(snapshot.summary.reason)) {
          violations.push(`Platform status summary (${summaryState}) has no stated reason`);
        }
        if (summaryState === "ok" && (UNKNOWN_WORD.test(snapshot.summary.text) || snapshot.observedUnknown)) {
          violations.push("Platform status summary reports healthy (OK) alongside UNKNOWN");
        }
        if ((indicatorState === "ok" || indicatorState === "degraded") && summaryState !== indicatorState) {
          violations.push("Platform status summary disagrees with the top-bar health indicator");
        }
      }
    }
  }

  return violations;
}
