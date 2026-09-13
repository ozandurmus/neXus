/**
 * 13G local identity administration: thin fetch client for
 * `/local-identities*` and the existing `/role-bindings*` endpoints. Every
 * mutating call needs the session's own CSRF token; until the parallel
 * movement (relay NXS-LOCAL-0152) extends `GET /session/status` to expose
 * one, `csrfToken()` resolves to `undefined` and the header is simply
 * omitted -- a disclosed, temporary limitation (see SESSION_CLOSE), not a
 * silent failure: the server's own GateChain still refuses the request and
 * this client surfaces that refusal exactly as the server states it.
 */

export interface LocalIdentityView {
  readonly local_identity_id: string;
  readonly local_identity_name: string;
  readonly enabled: boolean;
  readonly must_change_password: boolean;
  readonly created_at: string;
  readonly password_set_at: string;
}

export interface ApiError {
  readonly status: number;
  readonly body: Record<string, unknown>;
}

async function csrfToken(): Promise<string | undefined> {
  try {
    const response = await fetch("/session/status", { credentials: "include" });
    const body = await response.json();
    return typeof body?.csrf_token === "string" ? body.csrf_token : undefined;
  } catch {
    return undefined;
  }
}

async function call<T>(path: string, method: "GET" | "POST", body?: unknown): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (method === "POST") {
    const token = await csrfToken();
    if (token) headers["X-CSRF-Token"] = token;
  }
  const response = await fetch(path, {
    method,
    credentials: "include",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const parsed = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error: ApiError = { status: response.status, body: parsed };
    throw error;
  }
  return parsed as T;
}

export function listLocalIdentities(): Promise<{ identities: LocalIdentityView[] }> {
  return call("/local-identities", "GET");
}

export function createLocalIdentity(localIdentityName: string, initialPassword: string): Promise<LocalIdentityView> {
  return call("/local-identities", "POST", {
    local_identity_name: localIdentityName,
    initial_password: initialPassword,
  });
}

export function setLocalIdentityPassword(localIdentityId: string, newPassword: string): Promise<LocalIdentityView> {
  return call("/local-identities/set-password", "POST", {
    local_identity_id: localIdentityId,
    new_password: newPassword,
  });
}

export function disableLocalIdentity(localIdentityId: string): Promise<LocalIdentityView> {
  return call("/local-identities/disable", "POST", { local_identity_id: localIdentityId });
}

export function enableLocalIdentity(localIdentityId: string): Promise<LocalIdentityView> {
  return call("/local-identities/enable", "POST", { local_identity_id: localIdentityId });
}

/** LIA-3.4: this calls the existing role-bindings resource -- never re-implemented here. */
export function createRoleBinding(
  roleToken: string,
  groupReference: string,
  groupReferenceKeyId: string,
): Promise<{ binding_id: string }> {
  return call("/role-bindings", "POST", {
    roleToken,
    groupReference,
    groupReferenceKeyId,
  });
}

export function revokeRoleBinding(bindingId: string): Promise<{ binding_id: string }> {
  return call("/role-bindings/revoke", "POST", { bindingId });
}
