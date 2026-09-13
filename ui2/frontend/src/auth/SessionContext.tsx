import { createContext, useContext } from "react";

/**
 * The signed-in identity's own display facts (this movement's brief: "the
 * signed-in identity and its role tokens are visible in the shell"). Role
 * tokens are carried here purely as opaque strings to render -- nothing in
 * this module or any consumer branches on a specific token value
 * (NoRoleConditionalRenderingInFrontendTest, AG-J3).
 *
 * `null` outside an {@code AuthGate} (e.g. App's own standalone tests): a
 * consumer with no session context simply shows no identity block.
 */
export interface SessionInfo {
  readonly displayName: string;
  readonly roleTokens: readonly string[];
  readonly onSignOut: () => void;
}

export const SessionContext = createContext<SessionInfo | null>(null);

export function useSession(): SessionInfo | null {
  return useContext(SessionContext);
}
