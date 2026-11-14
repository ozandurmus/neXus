import { isPolicyBusy, POLICY_BUSY_MESSAGE } from "../PolicyRuleViewer";
import { ROLE_REFUSAL } from "../../shell/actionAccess";

/** The server's fixed-form refusal (`CODE: sentence`), shown as it is; anything else is the caller's sentence. */
export function cutoverFailureText(failure: unknown, fallback: string): string {
  if (isPolicyBusy(failure)) return POLICY_BUSY_MESSAGE;
  const error = failure as { status?: number; body?: { error?: unknown } } | null;
  if (error?.status === 403) return ROLE_REFUSAL;
  if (error?.status === 0) return "The request timed out. Refresh before retrying; the server may have completed it.";
  const code = error?.body?.error;
  return typeof code === "string" && code.trim() ? code : fallback;
}

export const isTopologyNotBuilt = (failure: unknown) => {
  const code = (failure as { body?: { error?: unknown } } | null)?.body?.error;
  return typeof code === "string" && code.startsWith("TOPOLOGY_NOT_BUILT");
};
