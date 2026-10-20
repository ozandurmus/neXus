/**
 * 13G local identity administration: thin fetch client for
 * `/local-identities*` and the existing `/role-bindings*` endpoints.
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

export interface GlobalSearchDevice {
  readonly device_id: string;
  readonly name: string | null;
  readonly serial: string | null;
  readonly model: string | null;
  readonly software_version: string | null;
  readonly cluster: string | null;
  readonly management_address: string | null;
  readonly vendor: string | null;
  readonly href: string;
}

export interface GlobalSearchSetting {
  readonly device: string | null;
  readonly cluster: string | null;
  readonly section: string;
  readonly setting: string;
  readonly value_excerpt: string;
  /** How many devices carry a matching value for this setting (one hit per section/setting). */
  readonly device_count?: number;
  readonly href: string;
}

export interface GlobalSearchEvidence {
  readonly kind: "job" | "backup";
  readonly label: string;
  readonly detail: string;
  readonly href: string;
}

export type IpSearchGroup = "interfaces" | "routes" | "policy_objects";
export interface GlobalSearchInterface {
  readonly device_id: string;
  readonly device: string | null;
  readonly context: string;
  readonly interface: string;
  readonly address: string;
  readonly href: string;
}
export interface GlobalSearchRoute extends Omit<GlobalSearchInterface, "address"> {
  readonly destination: string;
  readonly next_hop: string | null;
  readonly protocol: string;
  readonly route_table: string | null;
  readonly default_fallback: boolean;
}
export interface GlobalSearchPolicyObject {
  readonly id: string;
  readonly name: string;
  readonly type: string;
  readonly rule_count: number;
  readonly href: string;
}
export interface GlobalSearchResponse {
  readonly interfaces?: readonly GlobalSearchInterface[];
  readonly routes?: readonly GlobalSearchRoute[];
  readonly policy_objects?: readonly GlobalSearchPolicyObject[];
  readonly counts?: Partial<Record<IpSearchGroup, number>>;
  readonly devices: readonly GlobalSearchDevice[];
  readonly settings: readonly GlobalSearchSetting[];
  readonly evidence: readonly GlobalSearchEvidence[];
}

export function globalSearch(q: string, limit = 20, group?: IpSearchGroup, offset = 0): Promise<GlobalSearchResponse> {
  return call(`/api/v2/search?q=${encodeURIComponent(q)}&limit=${limit}${group ? `&group=${group}&offset=${offset}` : ""}`, "GET");
}

async function csrfToken(): Promise<string | undefined> {
  try {
    const response = await fetchWithTimeout("/session/status", { credentials: "include" }, 20_000);
    const body = await response.json();
    return typeof body?.csrf_token === "string" ? body.csrf_token : undefined;
  } catch {
    return undefined;
  }
}

function fetchWithTimeout(input: RequestInfo | URL, init: RequestInit, timeoutMs: number): Promise<Response> {
  const controller = new AbortController();
  let timeoutId: ReturnType<typeof setTimeout> | undefined;
  const request = fetch(input, { ...init, signal: controller.signal });
  const timeout = new Promise<never>((_, reject) => {
    timeoutId = setTimeout(() => {
      controller.abort();
      reject({ status: 0, body: { error: "TIMEOUT" } } satisfies ApiError);
    }, timeoutMs);
  });
  return Promise.race([request, timeout]).finally(() => {
    if (timeoutId !== undefined) clearTimeout(timeoutId);
  });
}

/**
 * PO decision 2026-10-20: an administrator's manual device job inside business hours answers 409
 * BUSINESS_HOURS_CONFIRM_REQUIRED and admits nothing. {@link call} then asks the registered confirmation (the
 * BusinessHoursConfirmProvider dialog) and, on "Continue", repeats the same request once with the acknowledgement
 * header. A "Continue" covers the requests of the next minute, so a bulk run asks once, not once per cluster.
 */
export const BUSINESS_HOURS_CONFIRM_REQUIRED = "BUSINESS_HOURS_CONFIRM_REQUIRED";
export const BUSINESS_HOURS_ACK_HEADER = "X-Nexus-Business-Hours-Ack";
export const BUSINESS_HOURS_DEFAULT_MESSAGE = "Business hours (08:00\u201320:00 GMT+3). This sends commands to the device. Continue?";
const BUSINESS_HOURS_ACK_REMEMBER_MS = 60_000;
type BusinessHoursConfirm = (message: string) => Promise<boolean>;
let businessHoursConfirm: BusinessHoursConfirm | null = null;
let businessHoursPrompt: Promise<boolean> | null = null;
let businessHoursAckUntil = 0;
/** Registers the dialog that asks; returns the unregister function. Without one, the 409 is reported as an error. */
export function setBusinessHoursConfirm(confirm: BusinessHoursConfirm | null): () => void {
  businessHoursConfirm = confirm;
  businessHoursAckUntil = 0;
  return () => { if (businessHoursConfirm === confirm) businessHoursConfirm = null; };
}
async function confirmBusinessHours(message: string): Promise<boolean> {
  if (Date.now() < businessHoursAckUntil) return true;
  if (!businessHoursConfirm) return false;
  // Concurrent requests share one dialog.
  businessHoursPrompt ??= businessHoursConfirm(message).finally(() => { businessHoursPrompt = null; });
  const confirmed = await businessHoursPrompt;
  if (confirmed) businessHoursAckUntil = Date.now() + BUSINESS_HOURS_ACK_REMEMBER_MS;
  return confirmed;
}

async function call<T>(path: string, method: "GET" | "POST" | "PUT", body?: unknown, businessHoursAck = false): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (method !== "GET") {
    const token = await csrfToken();
    if (token) headers["X-CSRF-Token"] = token;
    if (businessHoursAck) headers[BUSINESS_HOURS_ACK_HEADER] = "true";
  }
  const response = await fetchWithTimeout(path, {
    method,
    credentials: "include",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  }, method === "GET" ? 20_000 : 60_000);
  noteMasking(response);
  const parsed = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 409 && parsed.code === BUSINESS_HOURS_CONFIRM_REQUIRED && method !== "GET" && !businessHoursAck) {
      if (await confirmBusinessHours(typeof parsed.message === "string" ? parsed.message : BUSINESS_HOURS_DEFAULT_MESSAGE)) {
        return call<T>(path, method, body, true);
      }
      parsed.message = "Not started: business hours were not confirmed.";
    }
    if (parsed.code === "OUTSIDE_JOB_WINDOW" || parsed.code === BUSINESS_HOURS_CONFIRM_REQUIRED) {
      parsed.error = parsed.message;
      parsed.reason = parsed.message;
    }
    const error: ApiError = { status: response.status, body: parsed };
    throw error;
  }
  return parsed as T;
}

export interface LifecycleCatalogEntry {
  catalog_id: string;
  vendor: string;
  kind: "HARDWARE" | "SOFTWARE";
  product: string;
  end_of_sale: string | null;
  end_of_support: string | null;
  end_of_engineering: string | null;
  source: "IMPORT" | "MANUAL";
  note: string | null;
  imported_by: string;
  imported_at: string;
}
export type LifecycleCatalogInput = Pick<LifecycleCatalogEntry, "vendor" | "kind" | "product" | "end_of_sale" | "end_of_support" | "end_of_engineering" | "note">;
export interface LifecycleMatch {
  status: "MATCHED" | "NO_LIFECYCLE_DATA";
  reason: string | null;
  basis: string | null;
  catalog: LifecycleCatalogEntry | null;
}
export interface LifecycleLicense {
  name?: string;
  kind?: string;
  member?: string;
  label: string;
  expiry?: string | null;
  status: string;
  days_remaining?: number | null;
}
export interface LifecycleDevice {
  device_id: string;
  hostname: string | null;
  cluster_member_ref: string | null;
  vendor: string;
  model: string | null;
  software_version: string | null;
  hardware: LifecycleMatch;
  software: LifecycleMatch;
  status: string;
  risk: "EXPIRED" | "HIGH" | "MEDIUM" | "LOW" | "UNKNOWN";
  support_days: number | null;
  next_milestone: { name: string; date: string; days_remaining: number } | null;
  nearest_license: LifecycleLicense | null;
  licenses: LifecycleLicense[];
  license_status: string;
  support_contract_status: string;
}
export interface LifecycleFleet {
  as_of: string;
  devices: LifecycleDevice[];
  summary: Record<string, number>;
}
export const getLifecycle = () => call<LifecycleFleet>("/api/v2/lifecycle", "GET");
export const getDeviceLifecycle = (id: string) => call<LifecycleDevice>(`/devices/${encodeURIComponent(id)}/lifecycle`, "GET");
export const getLifecycleCatalog = () => call<{ entries: LifecycleCatalogEntry[]; can_manage: boolean }>("/api/v2/lifecycle/catalog", "GET");
export const saveLifecycleCatalog = (entry: LifecycleCatalogInput, id?: string) =>
  call(id ? `/api/v2/lifecycle/catalog/${encodeURIComponent(id)}` : "/api/v2/lifecycle/catalog", id ? "PUT" : "POST", entry);
export const importLifecycleCatalog = (csv: string) => call<{ imported: number }>("/api/v2/lifecycle/catalog/import", "POST", { csv });
export const deleteLifecycleCatalog = (id: string) => call(`/api/v2/lifecycle/catalog/${encodeURIComponent(id)}/delete`, "POST");

/**
 * The server marks a masked (aiview) response with `X-Nexus-Masked: true`; the shell shows the mask chip on every
 * screen from that header alone -- the browser never infers masking from a role token.
 */
let maskedSeen = false;
export function responsesAreMasked(): boolean {
  return maskedSeen;
}
function noteMasking(response: Response) {
  if (!maskedSeen && response.headers?.get?.("X-Nexus-Masked") === "true") {
    maskedSeen = true;
    if (typeof window !== "undefined") window.dispatchEvent(new Event("nx-masked"));
  }
}

/** Like {@link call}, but for a {@code text/plain} response (the Check Point sanitized configuration view) -- never JSON-parsed. */
async function callText(path: string): Promise<string> {
  const response = await fetchWithTimeout(path, { method: "GET", credentials: "include" }, 20_000);
  const text = await response.text();
  if (!response.ok) {
    const error: ApiError = { status: response.status, body: { error: text || "NOT_FOUND" } };
    throw error;
  }
  return text;
}

export function listLocalIdentities(): Promise<{ identities: LocalIdentityView[] }> {
  return call("/local-identities", "GET");
}

export interface SessionView {
  readonly session_id: string;
  readonly actor_fingerprint: string;
  readonly created_at: string;
  readonly last_seen_at: string;
  readonly idle_deadline_at: string;
  readonly absolute_expires_at: string;
  readonly state: string;
  readonly end_reason: string | null;
  readonly ended_by_actor_fingerprint: string | null;
  readonly superseded_by_session_id: string | null;
}

export interface SessionsView {
  readonly sessions: SessionView[];
  readonly identity_labels: Record<string, string>;
  readonly idle_timeout_seconds: number;
  readonly absolute_lifetime_seconds: number;
}

export function listSessions(): Promise<SessionsView> {
  return call("/sessions", "GET");
}

export function revokeSession(sessionId: string): Promise<{ ok: boolean }> {
  return call("/sessions/revoke", "POST", { sessionId });
}

export interface BackupArtefact {
  readonly artefact_id: string;
  readonly device_id: string;
  /** From the artefact manifest itself (backup_artefact.vendor), never assumed. */
  readonly vendor?: string;
  readonly artefact_class?: string;
  readonly collected_at: string;
  readonly size_bytes: number;
  readonly digest_prefix: string;
  readonly validation_level: string;
  readonly deviation_state: "unchanged" | "changed" | "first" | null;
}

/** V70: ask for a backup whose device no longer exists to be removed (the worker deletes it within a minute). */
export function requestOrphanBackupDelete(artefactId: string, reason: string): Promise<{ queued: boolean }> {
  return call(`/backups/${encodeURIComponent(artefactId)}/delete`, "POST", { reason });
}

export function listDeviceBackups(deviceId: string): Promise<{ backups: BackupArtefact[]; baseline_artefact_id?: string | null }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/backups`, "GET");
}

/** Every recorded backup artefact across the fleet, newest first as the store returns them. */
export function listFleetBackups(): Promise<{ backups: BackupArtefact[]; baselines?: Record<string, string> }> {
  return call("/backups", "GET");
}

/** V44: the one audited policy row -- schedule (cron, UTC) and retention. */
export interface BackupPolicy {
  readonly policy_id: string;
  readonly schedule_enabled: boolean;
  readonly daily_backup_cron: string;
  readonly backup_retention_days: number;
  readonly snapshot_retention_depth: number;
  readonly last_scheduled_run_at?: string | null;
  readonly updated_at?: string;
}

export interface BackupPolicyUpdate {
  readonly schedule_enabled: boolean;
  readonly daily_backup_cron: string;
  readonly backup_retention_days: number;
  readonly snapshot_retention_depth: number;
}

export function updateBackupPolicy(update: BackupPolicyUpdate): Promise<BackupPolicy> {
  return call("/api/v2/backups/policies", "PUT", update);
}

/** V44: declare (or clear, with null) the artefact that is a device's reference point. */
export function setBackupBaseline(deviceId: string, artefactId: string | null): Promise<{ device_id: string; baseline_artefact_id: string | null; changed: boolean }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/backup-baseline`, "PUT", { artefact_id: artefactId });
}

export function getBackupPolicy(): Promise<BackupPolicy> {
  return call("/api/v2/backups/policies", "GET");
}

export interface BackupDeviations {
  readonly active_major_deviations: readonly unknown[];
  readonly total_deviations_checked: number;
}

export function getBackupDeviations(): Promise<BackupDeviations> {
  return call("/api/v2/backups/deviations", "GET");
}

/** BK-12 manual backup (14K BW-4): posts to the collect route with a required reason. */
export function collectDeviceBackup(deviceId: string, reason: string, type: "backup" | "snapshot" | "mds_export" = "backup"): Promise<{ job_id: string }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/backup/collect`, "POST", { reason, type });
}

/** V41: one archive member -- name, type, size, digest prefix; never content. */
export interface BackupArchiveEntry {
  readonly path: string;
  readonly type: "file" | "dir" | "symlink" | "other";
  readonly bytes: number;
  readonly digest_prefix?: string;
}

export interface BackupArchiveListing {
  readonly artefact_id: string;
  readonly listing_state: "LISTED" | "FAILED" | "NOT_LISTED";
  readonly reason?: string;
  readonly listed_at?: string;
  readonly entry_count?: number;
  readonly truncated?: boolean;
  readonly entries: readonly BackupArchiveEntry[];
}

export function listBackupEntries(artefactId: string): Promise<BackupArchiveListing> {
  return call(`/backups/${encodeURIComponent(artefactId)}/entries`, "GET");
}

/** "List now" for an artefact stored before listing existed; decrypts on the service, so it is role-gated like the download. */
export function relistBackupContents(artefactId: string): Promise<{ listing_state: "LISTED" | "FAILED"; entry_count?: number }> {
  return call(`/backups/${encodeURIComponent(artefactId)}/relist`, "POST", {});
}

export interface BackupCompareSide {
  readonly artefact_id: string;
  readonly device_id: string;
  readonly collected_at: string;
  readonly size_bytes: number;
  readonly vendor: string;
}

export interface BackupCompareResult {
  readonly left: BackupCompareSide;
  readonly right: BackupCompareSide;
  readonly identical: boolean;
  readonly unchanged: number;
  readonly added: readonly string[];
  readonly removed: readonly string[];
  readonly changed: readonly { readonly path: string; readonly left_bytes: number; readonly right_bytes: number }[];
}

/** Structural compare of two listings of one device: which members were added, removed or changed. */
export function compareBackups(leftId: string, rightId: string): Promise<BackupCompareResult> {
  return call(`/backups/${encodeURIComponent(leftId)}/compare?with=${encodeURIComponent(rightId)}`, "GET");
}

export interface BackupDownload {
  readonly blob: Blob;
  readonly fileName: string;
}

/**
 * PO decision record 2026-09-22: {@code POST /backups/{id}/download} -- the backup administrator role, a reason
 * of at least eight characters, audited server-side before the first byte. The response is the
 * decrypted archive itself, so it is read as a Blob, never JSON-parsed; the file name comes from
 * the server's Content-Disposition (vendor + opaque id prefix + collection time).
 */
/**
 * A browser-native download (2026-09-24): a single-use ticket for this artefact and reason, then the browser's own
 * download manager fetches it -- streamed to disk with its own progress bar, never buffered in the page.
 */
export function requestBackupDownloadTicket(artefactId: string, reason: string): Promise<{ ticket: string; href: string; expires_in_s: number }> {
  return call(`/backups/${encodeURIComponent(artefactId)}/download-ticket`, "POST", { reason });
}

export async function downloadBackupArtefact(artefactId: string, reason: string): Promise<BackupDownload> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  const token = await csrfToken();
  if (token) headers["X-CSRF-Token"] = token;
  const response = await fetch(`/backups/${encodeURIComponent(artefactId)}/download`, {
    method: "POST",
    credentials: "include",
    headers,
    body: JSON.stringify({ reason }),
  });
  if (!response.ok) {
    const parsed = await response.json().catch(() => ({}));
    const error: ApiError = { status: response.status, body: parsed };
    throw error;
  }
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const match = /filename="([^"]+)"/.exec(disposition);
  return { blob: await response.blob(), fileName: match ? match[1] : `nexus-backup-${artefactId.slice(0, 8)}.tgz` };
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

export interface RoleBindingView {
  readonly binding_id: string;
  readonly role_token: string;
  readonly binding_kind: string;
  readonly directory_profile_id: string;
  readonly group_reference: string;
  readonly created_at: string;
  readonly created_by_actor_fingerprint: string;
}

export function listRoleBindings(): Promise<RoleBindingView[]> {
  return call("/role-bindings", "GET");
}

export function createDirectoryRoleBinding(params: {
  roleToken: string;
  groupReference: string;
  directoryProfileId?: string;
}): Promise<{ binding_id: string }> {
  return call("/role-bindings", "POST", {
    role_token: params.roleToken,
    binding_kind: "DIRECTORY_GROUP",
    group_reference: params.groupReference,
    directory_profile_id: params.directoryProfileId || "default",
  });
}

/** LIA-3.4: this calls the existing role-bindings resource -- never re-implemented here. */
export function createRoleBinding(
  roleToken: string,
  localIdentityId: string,
): Promise<{ binding_id: string }> {
  return call("/role-bindings", "POST", {
    role_token: roleToken,
    local_identity_id: localIdentityId,
  });
}

export function revokeRoleBinding(bindingId: string): Promise<{ binding_id: string }> {
  return call("/role-bindings/revoke", "POST", { bindingId });
}

/**
 * 2026-09-14 PO decision record CS-1..CS-5: credential store administration.
 * `secret`/`passphrase` are write-only on every call below -- no function
 * here, and no server response it reads, ever returns one back.
 */
export interface SnmpSettings {
  readonly securityLevel: "noAuthNoPriv" | "authNoPriv" | "authPriv";
  readonly authProtocol: string | null;
  readonly privProtocol: string | null;
}

export interface CredentialView {
  readonly credential_id: string;
  readonly credential_reference_id: string;
  readonly display_name: string;
  readonly kind: "ssh_password" | "ssh_private_key" | "api_password" | "snmp_v1_v2c" | "snmp_v3";
  readonly snmp?: SnmpSettings;
  readonly community?: "set" | "not set";
  readonly auth_secret?: "set" | "not set";
  readonly priv_secret?: "set" | "not set";
  readonly username: string;
  readonly allows_check_point: boolean;
  readonly allows_palo_alto: boolean;
  readonly created_at: string;
  readonly secret_set_at: string;
}

export function listCredentials(): Promise<{ credentials: CredentialView[] }> {
  return call("/credentials", "GET");
}

export function createCredential(
  displayName: string,
  kind: string,
  username: string,
  allowsCheckPoint: boolean,
  allowsPaloAlto: boolean,
  secret: string,
  passphrase: string,
  snmp?: SnmpSettings,
): Promise<CredentialView> {
  return call("/credentials", "POST", {
    display_name: displayName,
    kind,
    username,
    allows_check_point: allowsCheckPoint,
    allows_palo_alto: allowsPaloAlto,
    secret,
    passphrase: passphrase || undefined,
    snmp,
  });
}

export function replaceCredentialSecret(
  credentialId: string,
  secret: string,
  passphrase: string,
  snmp?: SnmpSettings,
  usernameOverride?: string,
): Promise<CredentialView> {
  return call("/credentials/replace-secret", "POST", {
    credential_id: credentialId,
    username: usernameOverride,
    secret,
    passphrase: passphrase || undefined,
    snmp,
  });
}

export function deleteCredential(credentialId: string): Promise<{ credential_id: string; deleted: boolean }> {
  return call("/credentials/delete", "POST", { credential_id: credentialId });
}

/**
 * Device enrollment (NXS-LOCAL-0157). `add-single` is the one write path;
 * every other function here is a read against a device the backend already
 * knows about. `job.state` is a C2 job state machine -- callers must treat
 * an unrecognized value as `OUTCOME_UNKNOWN`-shaped rather than throwing.
 */
export type Vendor = "check_point" | "palo_alto" | "infoblox" | "radware" | "bluecoat" | "cisco_asa" | "fortinet" | "pulse_secure";

/** V64: bind a credential-store reference as a device's second secret (Radware's export passphrase); never a value. */
export function setDeviceSecret(deviceId: string, purpose: "export_passphrase" | "backup_receiver", credentialReferenceId: string): Promise<{ ok: boolean }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/secrets/${purpose}`, "POST", { credential_reference_id: credentialReferenceId });
}

export type JobState =
  | "REQUESTED"
  | "CLAIMED"
  | "EXECUTING"
  | "COMPLETED"
  | "FAILED"
  | "REJECTED"
  | "CANCELLED"
  | "OUTCOME_UNKNOWN"
  | "RECONCILED";

export interface JobView {
  readonly job_id: string;
  readonly state: string;
  readonly outcome: string | null;
  readonly terminal_reason: string | null;
}

export interface DeviceFacts {
  readonly hostname: string | null;
  readonly model: string | null;
  readonly software_version: string | null;
  readonly ha_role: string | null;
}

export interface AddDeviceSingleResult {
  readonly device_id: string;
  readonly job_id: string;
  readonly enrollment_state: "DRAFT";
}

/** V77 onboarding flow (DEVICE_ONBOARDING_FLOW_CONTRACT): identity, then inventory, then configuration. */
export interface OnboardingView {
  readonly state: "RUNNING" | "COMPLETED" | "STOPPED";
  readonly step: "identity" | "inventory" | "configuration";
  readonly step_number: number;
  readonly step_total: number;
  readonly step_job_id: string | null;
  readonly reason: string | null;
  /** Steps this vendor/role has no read for, "step:CODE". */
  readonly skipped: readonly string[];
  readonly source: string;
  readonly started_at: string | null;
  readonly completed_at: string | null;
}

export function retryOnboarding(deviceId: string): Promise<{ admitted: boolean; step: string; job_id: string }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/onboarding/retry`, "POST", {});
}

export interface DeviceDetail {
  readonly device_id: string;
  readonly vendor_hint: string;
  readonly enrollment_state: string;
  readonly disabled: boolean;
  readonly facts: DeviceFacts | null;
  readonly peer_follow_outcome: "NONE" | "CORROBORATED" | "NOT_CONFIRMED" | null;
  readonly peer_follow_reason: string | null;
  readonly identity_mismatch_state: "NONE" | "OPEN";
  readonly cluster_member_ref: string | null;
  readonly job: JobView | null;
  /** null for a device added before the flow existed (treated as onboarded). */
  readonly onboarding?: OnboardingView | null;
  readonly identity_check?: IdentityCheckView;
}

/**
 * What "Identity verified" rests on (PO decision 2026-10-18): the worker's last comparison of the SSH host key or HTTPS
 * certificate the device presented with the fingerprint pinned for it. VERIFIED matched; MISMATCH did not match (or the
 * device's recorded first-contact identity is in conflict); NOT_VERIFIED means no comparison is on record (never
 * compared, pinned on first use, or a transport with no pin). A verdict and a time, never a fingerprint.
 */
export interface IdentityCheckView {
  readonly state: "VERIFIED" | "MISMATCH" | "NOT_VERIFIED";
  readonly basis: "SSH_HOST_KEY" | "HTTPS_CERTIFICATE" | "RECORDED_IDENTITY" | "NONE";
  /** The newest connection pinned the fingerprint itself, so there was nothing to compare yet. */
  readonly first_use: boolean;
  readonly checked_at: string | null;
}

export interface DeviceSummary {
  readonly device_id: string;
  readonly role?: string;
  readonly vendor_hint: string;
  readonly backup_receiver_supported?: boolean;
  readonly enrollment_state: string;
  readonly hostname: string | null;
  readonly model: string | null;
  readonly software_version: string | null;
  readonly ha_role: string | null;
  readonly cluster_member_ref: string | null;
  readonly ha_peer_unconfirmed?: boolean;
  readonly latest_job_state?: string | null;
  readonly latest_job_type?: string | null;
  readonly latest_job_terminal_reason?: string | null;
  readonly virtual_systems?: string | null;
  readonly management_ip?: string | null;
  readonly ip_addresses?: string | null;
  readonly backup_target?: boolean;
  /** Platform identity facts (V46); null = not collected, and the screen says UNKNOWN. */
  readonly serial_number?: string | null;
  readonly hotfix_level?: string | null;
  readonly platform_family?: string | null;
  readonly content_versions?: Readonly<Record<string, string>> | null;
  readonly uptime_text?: string | null;
  readonly platform_facts_observed_at?: string | null;
  /** Which read produced the platform facts (read-kind token; amendment A-2026-09-23). */
  readonly platform_facts_source?: string | null;
  /** Installed policy (V60), read every evening at 23:00 Europe/Istanbul: name (Check Point), time parsed and as reported, read time. */
  readonly policy_name?: string | null;
  readonly policy_installed_at?: string | null;
  readonly policy_installed_at_text?: string | null;
  readonly policy_read_at?: string | null;
  /** Newest inventory run of the device (Overview "inventory evidence age" filters). */
  readonly inventory_collected_at?: string | null;
  /** Present only while the device's onboarding flow is RUNNING or STOPPED. */
  readonly onboarding?: OnboardingView | null;
  /** The last pinned-identity comparison (PO decision 2026-10-18); absent from an older service, read as NOT_VERIFIED. */
  readonly identity_check?: IdentityCheckView;
}

export type DeviceRole = "gateway" | "management_server" | "appliance";

export function addDeviceSingle(
  address: string,
  role: DeviceRole,
  vendor: Vendor,
  credentialReferenceId: string,
  exportPassphraseCredentialReferenceId?: string,
): Promise<AddDeviceSingleResult> {
  // One call, one transaction: the device, its export passphrase (Radware) and its confirm job, or none of them.
  return call("/devices/add-single", "POST", {
    address,
    role,
    vendor,
    credential_reference_id: credentialReferenceId,
    ...(exportPassphraseCredentialReferenceId ? { export_passphrase_credential_reference_id: exportPassphraseCredentialReferenceId } : {}),
  });
}

/** Replace a device's login credential (a reference, never a value); the next job uses it. */
export function setDeviceCredential(deviceId: string, credentialReferenceId: string): Promise<{ ok: boolean; changed: boolean }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/credential`, "PUT", { credential_reference_id: credentialReferenceId });
}

export function getDevice(deviceId: string): Promise<DeviceDetail> {
  return call(`/devices/${encodeURIComponent(deviceId)}`, "GET");
}

export function setBackupTarget(deviceId: string, enabled: boolean): Promise<{ device_id: string; backup_target: boolean; changed: boolean }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/backup-target`, "PUT", { enabled });
}

export function requestFleetBackup(reason: string): Promise<{ targets: number; admitted: number; refused: number }> {
  return call("/backups/collect-all", "POST", { reason });
}

let devicesInFlight: Promise<{ devices: DeviceSummary[] }> | null = null;

/** Concurrent callers on one screen share one request (the Backups screen fetched it twice); settled results are not reused. */
export function listDevices(): Promise<{ devices: DeviceSummary[] }> {
  if (!devicesInFlight) {
    devicesInFlight = call<{ devices: DeviceSummary[] }>("/devices", "GET").finally(() => {
      devicesInFlight = null;
    });
  }
  return devicesInFlight;
}

export type BackupDisposition = "KEEP" | "REMOVE";

export function deleteDevice(deviceId: string, backupDisposition?: BackupDisposition): Promise<{ deleted: boolean; device_id: string; backup_artefact_count: number }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/delete`, "POST",
    backupDisposition ? { backup_disposition: backupDisposition } : undefined);
}

export interface TransportEntry {
  readonly endpoint_id: string;
  readonly transport_kind: string;
}

export interface TransportSummary {
  readonly presence: "PRESENT" | "MISSING";
  readonly transports: TransportEntry[];
}

export interface ActionAffordance {
  readonly outcome: "PERMITTED" | "NO_APPLICABLE_AUTHORITY" | "DENIED" | "AUTHZ_NOT_EVALUATED";
  readonly authority: string | null;
  readonly reason_code: string | null;
}

export interface DeviceWorkspaceView {
  readonly device_id: string;
  readonly vendor_hint: string;
  readonly registration_source: string;
  readonly created_at: string;
  readonly is_test_target: boolean;
  readonly credential_configured: boolean;
  readonly enrollment_state: "DRAFT" | "ENROLLED" | "UNREACHABLE" | "DEGRADED" | "NOT_EVALUABLE";
  readonly disabled: boolean;
  readonly transport: TransportSummary;
  readonly action_affordance: Record<string, ActionAffordance>;
}

export function getDeviceWorkspace(deviceId: string): Promise<DeviceWorkspaceView> {
  return call(`/devices/${encodeURIComponent(deviceId)}`, "GET");
}

/**
 * Inventory read model (NXS-LOCAL-0160): the READ CONTRACT shared with
 * NXS-LOCAL-0159's persistence layer. `collected_at` is `null` until a
 * device's first inventory run lands -- never a placeholder timestamp.
 */
export interface InventoryAddress {
  readonly address: string;
  readonly family: "ipv4" | "ipv6";
  readonly role: "member" | "cluster_virtual";
}

export interface InventoryInterface {
  readonly name: string;
  readonly parent: string | null;
  readonly kind: string;
  readonly state: string;
  readonly vlan_id: number | null;
  /** V148: the link-layer address the device reported for this interface; masked to its vendor prefix for aiview. */
  readonly mac_address?: string | null;
  /** V148: Palo Alto zone the interface is bound to; a pseudonym for aiview. */
  readonly zone?: string | null;
  readonly addresses: InventoryAddress[];
}

/** device_inventory_ha (migration V17): a context's HA role and cluster mode, when the run recorded one. */
export interface InventoryHa {
  readonly role: string;
  readonly cluster_mode: string | null;
}

export interface InventoryRoute {
  readonly destination: string;
  readonly next_hop: string | null;
  readonly interface: string | null;
  readonly protocol: string;
  readonly table: string | null;
}

export interface InventoryContext {
  readonly context: string;
  readonly vs_name?: string | null;
  readonly interfaces: InventoryInterface[];
  readonly routes: InventoryRoute[];
  readonly ha: InventoryHa | null;
}

/** V72: one Infoblox grid member as the Grid Manager reports it; the name arrives masked for aiview like a virtual system. */
export interface GridMemberView {
  readonly virtual_system: string;
  readonly address: string | null;
  readonly platform: string | null;
  readonly hardware_type: string | null;
  readonly hypervisor: string | null;
  readonly grid_master: boolean;
  readonly master_candidate: boolean;
  readonly ha_enabled: boolean;
  readonly ha_status: string | null;
  readonly node_status: string | null;
  readonly replication: string | null;
  readonly disk_percent: number | null;
  readonly memory_percent: number | null;
  readonly cpu_percent: number | null;
  readonly db_percent: number | null;
  readonly services: readonly { readonly service: string; readonly status: string }[];
}

export interface DeviceInventory {
  readonly device_id: string;
  readonly collected_at: string | null;
  readonly job: JobView | null;
  readonly contexts: InventoryContext[];
  readonly virtual_systems?: readonly string[] | string | null;
  readonly grid_members?: readonly GridMemberView[];
  readonly grid_summary?: GridSummaryView | null;
}

export interface GridSummaryView {
  readonly dns_views: number | null;
  readonly dns_views_at_least: boolean;
  readonly auth_zones: number | null;
  readonly auth_zones_at_least: boolean;
  readonly dhcp_networks: number | null;
  readonly dhcp_networks_at_least: boolean;
  readonly dhcp_ranges: number | null;
  readonly dhcp_ranges_at_least: boolean;
  readonly top_networks: readonly { readonly network: string; readonly percent: number }[] | null;
  readonly licenses: readonly { readonly member: string; readonly type?: string; readonly kind?: string; readonly expiry_date?: string }[] | null;
}

export interface ClusterMember {
  readonly device_id: string;
  readonly hostname: string | null;
  readonly ha_role?: string | null;
  readonly latest_job_state?: string | null;
  readonly latest_job_type?: string | null;
  readonly latest_job_terminal_reason?: string | null;
  readonly virtual_systems?: string | null;
}

/** `"all"` when every member has the row; otherwise the member device ids that do. */
export type Presence = "all" | string[];

export interface ClusterDifference {
  readonly device_id: string;
  readonly field: string;
  readonly value: string;
}

export interface ClusterInterface {
  readonly name: string;
  readonly kind: string;
  /** Present when the row came from a single device's own inventory (toClusterContexts); the cluster API does not carry it. */
  readonly vlan_id?: number | null;
  readonly addresses: InventoryAddress[];
  readonly presence: Presence;
  readonly differences: ClusterDifference[];
  readonly member_addresses?: Record<string, InventoryAddress[]>;
  readonly member_states?: Record<string, string>;
  /** V148: each member's own link-layer address (member-specific, never merged). */
  readonly member_macs?: Record<string, string>;
  /** V148: each member's zone for this interface. */
  readonly member_zones?: Record<string, string>;
}

export interface ClusterRoute {
  readonly destination: string;
  readonly next_hop: string | null;
  readonly interface: string | null;
  readonly protocol: string;
  readonly presence: Presence;
  readonly differences: ClusterDifference[];
}

export interface ClusterContext {
  readonly context: string;
  readonly vs_name?: string | null;
  readonly interfaces: ClusterInterface[];
  readonly routes: ClusterRoute[];
}

export interface ClusterInventory {
  readonly cluster_member_ref: string;
  readonly members: ClusterMember[];
  readonly contexts: ClusterContext[];
  readonly virtual_systems?: readonly string[];
}

export function getDeviceInventory(deviceId: string): Promise<DeviceInventory> {
  return call(`/devices/${encodeURIComponent(deviceId)}/inventory`, "GET");
}

export interface DiagnosticPreview {
  readonly templateId: string;
  readonly deviceId: string;
  readonly target: string;
  readonly port: string;
  readonly command: string;
  readonly gateRevision: number;
  readonly timeoutSeconds: number;
  readonly retry: string;
  readonly frequency: string;
}

export interface DiagnosticTarget {
  readonly deviceId: string;
  readonly target: string;
  readonly vendor: string;
  readonly cluster?: string | null;
  readonly virtualSystems?: readonly string[];
  readonly commands: ReadonlyArray<{ gate_id: string; command_template: string; description?: string; timeout_s: number; runnable: boolean }>;
}

export function listFmgDiagnosticTargets(): Promise<{ targets: DiagnosticTarget[]; canExecute: boolean }> {
  return call("/api/v2/diagnostics/targets", "GET");
}

export function getFmgDiagnosticPorts(deviceId: string): Promise<{ ports: string[] }> {
  return call(`/api/v2/diagnostics/ports?device_id=${encodeURIComponent(deviceId)}`, "GET");
}

export interface DiagnosticHistoryRow {
  jobId: string; targetDeviceId: string; target: string; command: string; actor: string;
  submittedAt: string; state: string; exitStatus: number | null;
  description?: string; startedAt?: string | null; finishedAt?: string | null; durationMs?: number | null;
}
export function diagnosticHistory(deviceId: string, page: number): Promise<{ runs: DiagnosticHistoryRow[] }> {
  return call(`/api/v2/diagnostics/history?page=${page}${deviceId ? `&device_id=${encodeURIComponent(deviceId)}` : ""}`, "GET");
}
export function runDiagnostic(deviceId: string, gateId: string, parameter: string | undefined, requestId: string): Promise<{ job_id: string }> {
  return call("/api/v2/diagnostics", "POST", { device_id: deviceId, gate_id: gateId,
    ...(parameter === undefined ? {} : { parameter }), request_id: requestId });
}

export interface DiagnosticResult extends DiagnosticHistoryRow {
  readonly queuePosition?: number | null;
  readonly terminalReason?: string | null;
  readonly output: string | null;
  readonly masked: boolean;
  readonly legacySummary: boolean;
  readonly jobId: string;
  readonly targetDeviceId: string;
  readonly port: string;
  readonly state: string;
  readonly statusToken: "UP" | "DOWN" | "OTHER" | "ABSENT" | null;
  readonly statusPresent: boolean;
  readonly lineCount: number | null;
  readonly shapeId: string | null;
  readonly maskedOutput: string | null;
}

export function previewFmgDiagnostic(deviceId: string, port: string): Promise<DiagnosticPreview> {
  return call(`/api/v2/diagnostics/preview?device_id=${encodeURIComponent(deviceId)}&port=${encodeURIComponent(port)}`, "GET");
}

export function runFmgDiagnostic(deviceId: string, port: string, requestId: string): Promise<{ job_id: string }> {
  return call("/api/v2/diagnostics", "POST", { device_id: deviceId, port, request_id: requestId });
}

export function getFmgDiagnostic(jobId: string): Promise<DiagnosticResult> {
  return call(`/api/v2/diagnostics/${encodeURIComponent(jobId)}`, "GET");
}

/** GET /devices/{id}/management-tree: what a Check Point management server manages (latest discovery run). */
export interface ManagementTreeNode {
  readonly kind: string;
  readonly cluster_member_ref?: string;
  readonly hostname?: string;
  readonly virtual_system?: string;
  readonly device_id: string | null;
  readonly imported: boolean;
  readonly ha_role: string | null;
  readonly enrollment_state: string | null;
  readonly last_collection_state: string | null;
  readonly last_collection_at: string | null;
  readonly category: "cluster" | "virtual_system" | "management_appliance" | "device";
  readonly model: string | null;
  readonly candidate_id: string | null;
  readonly ack_token: string | null;
  readonly acknowledged: boolean;
  readonly acknowledged_reason: string | null;
  readonly children: readonly ManagementTreeNode[];
}
export interface ManagementTree {
  readonly device_id: string;
  readonly vendor: string;
  readonly run_id: string | null;
  readonly discovered_at: string | null;
  readonly domains: ReadonlyArray<{ readonly domain: string | null; readonly nodes: readonly ManagementTreeNode[] }>;
  readonly counts: {
    readonly domains?: number; readonly clusters?: number; readonly gateways?: number; readonly virtual_systems?: number;
    readonly imported?: number; readonly management_appliances?: number; readonly not_in_nexus?: number; readonly acknowledged?: number;
  };
}

export function acknowledgeManagedItem(deviceId: string, token: string, acknowledge: boolean, reason?: string): Promise<{ ok: boolean }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/management-tree/acknowledge`, "POST", { token, acknowledge, reason });
}

export function getManagementTree(deviceId: string): Promise<ManagementTree> {
  return call(`/devices/${encodeURIComponent(deviceId)}/management-tree`, "GET");
}

export function getClusterInventory(clusterMemberRef: string): Promise<ClusterInventory> {
  return call(`/clusters/${encodeURIComponent(clusterMemberRef)}/inventory`, "GET");
}

export function requestInventoryCollect(deviceId: string, nonce?: string): Promise<{ job_id: string }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/inventory/collect`, "POST", nonce ? { nonce } : {});
}

export function retryDeviceConfirm(deviceId: string): Promise<{ admitted: boolean; job_id: string }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/confirm`, "POST", {});
}

export function requestBulkInventoryCollect(): Promise<{ enrolled_devices: number; admitted: number; refused: number }> {
  return call(`/devices/inventory/collect-all`, "POST", {});
}

export function requestBulkConfigurationCollect(): Promise<{ enrolled_devices: number; admitted: number; refused: number }> {
  return call(`/devices/configuration/collect-all`, "POST", {});
}

/**
 * Discovery from the UI (14F section 3): the management-server toggle's own
 * three routes. `outcome_summary` is counts-only (PR-3); a candidate row's
 * `own_address`/`management_address`/`display_name` are CLASS 2 and shown
 * only here, never logged. `import_outcome` is `null` until import runs.
 */
export type DiscoveryRunState = "REQUESTED" | "RUNNING" | "FINISHED" | "FAILED";

export interface DiscoveryCandidate {
  readonly candidate_id: string;
  readonly kind: string;
  readonly importable: boolean;
  readonly display_name: string | null;
  readonly own_address: string | null;
  readonly management_address: string | null;
  readonly cluster_reference: string | null;
  readonly parent_candidate_id: string | null;
  readonly model: string | null;
  readonly software_version: string | null;
  readonly connection_state: string | null;
  readonly import_outcome: "new" | "already_imported" | "conflicting" | null;
  /**
   * The read-time RD-5 projection (NXS-LOCAL-0173 AC-1): what the device
   * registry says right now, computed on every read -- distinct from
   * `import_outcome` above, which stays `null` until an import runs and then
   * records what that import actually did.
   */
  readonly registry_state: "new" | "already_imported" | "conflicting";
  readonly existing_device_id: string | null;
}

export interface DiscoveryRunView {
  readonly run_id: string;
  readonly vendor: Vendor;
  readonly state: DiscoveryRunState;
  readonly job_id: string | null;
  readonly outcome_summary: Record<string, number>;
  readonly candidates: DiscoveryCandidate[];
}

export interface StartDiscoveryRunResult {
  readonly run_id: string;
  readonly job_id: string;
}

export function startDiscoveryRun(
  managementAddress: string,
  vendor: Vendor,
  credentialReferenceId: string,
): Promise<StartDiscoveryRunResult> {
  return call("/discovery/runs", "POST", {
    management_address: managementAddress,
    vendor,
    credential_reference_id: credentialReferenceId,
  });
}

export function getDiscoveryRun(runId: string): Promise<DiscoveryRunView> {
  return call(`/discovery/runs/${encodeURIComponent(runId)}`, "GET");
}

export interface DiscoveryImportResult {
  readonly candidate_id: string;
  readonly outcome: "new" | "already_imported" | "conflicting" | "refused";
  readonly device_id: string | null;
  readonly job_id: string | null;
  readonly reason: string | null;
}

export function importDiscoveryCandidates(
  runId: string,
  candidateIds: string[],
  credentialReferenceId?: string,
  exportPassphraseCredentialReferenceId?: string,
): Promise<{ results: DiscoveryImportResult[] }> {
  return call(`/discovery/runs/${encodeURIComponent(runId)}/import`, "POST", {
    candidate_ids: candidateIds,
    credential_reference_id: credentialReferenceId,
    // Radware discovery (2026-09-24): Radware DefensePro imports carry the export passphrase credential (one for the selection)
    ...(exportPassphraseCredentialReferenceId ? { export_passphrase_credential_reference_id: exportPassphraseCredentialReferenceId } : {}),
  });
}

/**
 * Configuration collection read model (14G, movement NXS-LOCAL-0165):
 * mirrors the inventory read model's own shape above. `change_state` is
 * `null` until a device's first configuration run lands.
 */
export interface ConfigurationIndexEntry {
  readonly context: string;
  readonly section: string;
  readonly source: "tpl" | "dg" | "shared" | "local" | null;
  readonly entry_count: number;
  readonly has_override: boolean;
}

export interface ConfigurationOverride {
  readonly context: string;
  readonly category: string;
  readonly element_path: string;
  readonly panorama_source: string | null;
}

export interface ConfigurationSupplementaryRun {
  readonly read_kind: string;
  readonly collected_at: string;
  readonly canonical_hash: string;
  readonly change_state: "changed" | "unchanged" | "first_run";
}

/** V22 configuration_run_deviation_summary: what changed between this run and the previous one, by section. */
export interface ConfigurationDeviationEntry {
  readonly context: string;
  readonly section: string;
  readonly kind: string;
  readonly old_count: number | null;
  readonly new_count: number | null;
}

export interface ConfigurationDeviationSummary {
  readonly status: string;
  readonly entries: ConfigurationDeviationEntry[];
}

export interface DeviceConfiguration {
  readonly device_id: string;
  readonly collected_at: string | null;
  readonly vendor: string | null;
  readonly read_kind: string | null;
  readonly canonical_hash: string | null;
  readonly change_state: "changed" | "unchanged" | "first_run" | null;
  readonly change_deviation_summary?: ConfigurationDeviationSummary | null;
  readonly withheld_line_count: number;
  readonly sanitized_text_available: boolean;
  readonly text_readable: boolean;
  readonly index: ConfigurationIndexEntry[];
  readonly overrides: ConfigurationOverride[];
  readonly supplementary_runs: ConfigurationSupplementaryRun[];
}

export interface ConfigurationDeviceListEntry {
  readonly device_id: string;
  readonly hostname: string | null;
  readonly vendor: string;
  readonly last_collected_at: string | null;
  readonly change_state: "changed" | "unchanged" | "first_run" | null;
  /** Design language section 2: members are grouped under this cluster reference (null for a standalone device). */
  readonly cluster_member_ref?: string | null;
  /** Section 3: agreement between members is judged by these, never by eye. */
  readonly canonical_hash?: string | null;
  readonly projected_settings?: number | null;
}

export function getDeviceConfiguration(deviceId: string): Promise<DeviceConfiguration> {
  return call(`/devices/${encodeURIComponent(deviceId)}/configuration`, "GET");
}

export function getDeviceConfigurationText(deviceId: string): Promise<string> {
  return callText(`/devices/${encodeURIComponent(deviceId)}/configuration/text`);
}

export function requestConfigurationCollect(deviceId: string, nonce?: string): Promise<{ job_id: string }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/configuration/collect`, "POST", nonce ? { nonce } : {});
}

export function listConfigurations(): Promise<{ devices: ConfigurationDeviceListEntry[] }> {
  return call("/configuration", "GET");
}

export interface ConfigurationNotificationView {
  readonly notification_id: string;
  readonly device_id: string;
  readonly run_id: string;
  readonly kind: string;
  readonly summary: string;
  readonly override_paths: string[];
  readonly created_at: string;
  readonly read_at: string | null;
}

export function listNotifications(): Promise<{ notifications: ConfigurationNotificationView[] }> {
  return call("/notifications", "GET");
}

/**
 * Project plan read model (movement NXS-LOCAL-0174): the Administration
 * screen's "Project plan" tab, backed by a real read of the repository's
 * own roadmap/feature-registry/backlog/build-history sources -- the earlier
 * product's own payload envelope, field for field.
 */
export interface ProjectPlanFeature {
  readonly id: string;
  readonly title: string;
  readonly status: string;
  readonly introduced: string | null;
  readonly target: string | null;
  readonly weight: number;
  readonly summary: string | null;
  readonly why: string | null;
  readonly evidence: string | null;
  readonly progress_percent: number;
}

export interface ProjectPlanTrack {
  readonly id: string;
  readonly title: string;
  readonly theme: string | null;
  readonly status: string;
  readonly weight: number;
  readonly progress_percent: number;
  readonly done_features: number;
  readonly feature_count: number;
  readonly features: ProjectPlanFeature[];
}

export interface ProjectPlanHorizonEntry {
  readonly build: string;
  readonly title: string;
  readonly status: string;
  readonly goal?: string | null;
  readonly detail?: string | null;
}

export interface ProjectPlanNowNext {
  readonly horizon_contract?: string | null;
  readonly now?: ProjectPlanHorizonEntry | null;
  readonly next?: ProjectPlanHorizonEntry | null;
  readonly upcoming?: ProjectPlanHorizonEntry[];
}

export interface ProjectPlanBacklogItem {
  readonly classification?: string;
  readonly id: string;
  /** An opaque grouping key, never an enum. */
  readonly category: string;
  readonly title: string;
  readonly status: string;
  readonly priority: string | null;
  readonly target: string | null;
  readonly note: string | null;
}

export interface ProjectPlanBuild {
  readonly build: string;
  readonly status: string;
  readonly title: string;
  readonly summary: string | null;
  readonly detail: string | null;
}

export interface ProjectPlanLesson {
  readonly id: string;
  readonly title: string;
  readonly source_id: string;
  readonly source_status: string;
  readonly java_application: string;
  readonly target: string;
}

export interface ProjectPlanView {
  readonly product_scope?: string;
  readonly current_product_build?: string | null;
  /** The exact commit and UTC timestamp HOST-A's build script baked into the running image
   * (project/deploy_info.json, generated per build, never hand-authored) -- null until the
   * first build using that step has been deployed. */
  readonly deployed_commit?: string | null;
  readonly deployed_at?: string | null;
  readonly excluded_backlog_count?: number;
  readonly source_metadata?: {
    readonly revision: string;
    readonly reviewed_at: string | null;
    readonly reviewed_current_build: string | null;
    readonly freshness: string;
    readonly update_policy: string;
  };
  readonly converted_lessons?: ProjectPlanLesson[];
  readonly schema_version: string;
  readonly generated_at: string;
  readonly current_build: string | null;
  readonly current_track: string | null;
  readonly progress_contract: string | null;
  readonly overall_progress_percent: number;
  readonly current_track_progress_percent: number;
  readonly tracks: ProjectPlanTrack[];
  readonly now_next: ProjectPlanNowNext;
  readonly roadmap_notes: string[];
  readonly backlog: ProjectPlanBacklogItem[];
  readonly backlog_counts: Record<string, number>;
  readonly completed_features: ProjectPlanFeature[];
  readonly build_history: ProjectPlanBuild[];
  readonly archived_build_count: number;
  readonly metadata_warnings: string[];
}

export function getProjectPlan(): Promise<ProjectPlanView> {
  return call("/project-plan", "GET");
}

export interface AuditEventView {
  readonly id: string;
  readonly occurred_at: string;
  readonly actor: string;
  readonly action: string;
  readonly target: string;
  readonly outcome: string;
}

export function listAuditEvents(): Promise<{ events: AuditEventView[] }> {
  return call("/audit-logs", "GET");
}

export interface JobEventView {
  readonly job_id: string;
  readonly job_type: string;
  readonly target_device_id: string;
  /** Observed hostname at read time; absent when the device has none recorded. */
  readonly device_name?: string | null;
  readonly state: string;
  readonly outcome?: string | null;
  readonly terminal_reason?: string;
  readonly submitted_at: string;
  readonly finished_at?: string;
  readonly duration_ms?: number;
  readonly has_transcript: boolean;
  /** Log lines a system-log collection run stored (its chunk ledger); absent or null for other jobs and for a run without a chunk row. */
  readonly lines_stored?: number | null;
}

export interface JobTranscriptEntry {
  readonly seq: number;
  readonly at: string;
  readonly elapsedMs: number;
  readonly channel: "ssh" | "https" | "scp" | "sftp" | "backup" | "job";
  readonly kind: string;
  readonly text: string;
}

export async function getJobTranscript(jobId: string): Promise<JobTranscriptEntry[]> {
  const value = await call<unknown>(`/jobs/${encodeURIComponent(jobId)}/transcript`, "GET");
  // A truncated 200 response must be an error, never an empty transcript.
  if (!Array.isArray(value) || !value.every((entry) => entry && typeof entry === "object"
    && typeof entry.seq === "number" && typeof entry.at === "string" && typeof entry.elapsedMs === "number"
    && typeof entry.channel === "string" && typeof entry.kind === "string" && typeof entry.text === "string")) {
    throw { status: 200, body: { error: "TRANSCRIPT_PARSE_FAILED" } } satisfies ApiError;
  }
  return value as JobTranscriptEntry[];
}

export interface JobQueryParams {
  readonly state?: string;
  readonly job_type?: string;
  readonly device_id?: string;
  /** ISO-8601 instants. */
  readonly since?: string;
  readonly until?: string;
  readonly q?: string;
  readonly page?: number;
  readonly page_size?: number;
}

export const cancelJob = (id: string) => call<{ jobId: string; state: string; cancelRequested: boolean }>(`/api/v2/jobs/${encodeURIComponent(id)}/cancel`, "POST");

export interface JobPageView {
  readonly can_cancel?: boolean;
  readonly items: readonly JobEventView[];
  readonly page: number;
  readonly page_size: number;
  readonly total: number;
}

export interface JobFacetsView {
  readonly states: readonly string[];
  readonly job_types: readonly string[];
}

function jobQueryString(params: JobQueryParams): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && String(value) !== "") search.set(key, String(value));
  }
  const s = search.toString();
  return s ? `?${s}` : "";
}

/** Jobs screen (PO P0, 2026-09-22): the whole history, filtered and paged on the server. */
export function listJobs(params: JobQueryParams = {}): Promise<JobPageView> {
  return call(`/api/v2/jobs${jobQueryString(params)}`, "GET");
}

/** One window for every headline job count: terminal states FINISHED in the last 24 h (total_24h = the five below). */
export interface JobStatsView {
  readonly total: number;
  readonly total_24h: number;
  readonly completed_24h: number;
  readonly failed_24h: number;
  readonly outcome_unknown_24h?: number;
  readonly rejected_24h?: number;
  readonly cancelled_24h?: number;
  readonly submitted_24h?: number;
  readonly running: number;
  /** Every job ever recorded, by upper-case state word (the Jobs screen's state pills count from this, all time). */
  readonly by_state?: Readonly<Record<string, number>>;
}

/** The headline job counts in one request. */
export function getJobStats(): Promise<JobStatsView> {
  return call("/api/v2/jobs/stats", "GET");
}

export function jobFacets(): Promise<JobFacetsView> {
  return call("/api/v2/jobs/facets", "GET");
}

/** The same filter as a CSV file (newest first, at most 50 000 rows). */
export async function downloadJobsCsv(params: JobQueryParams): Promise<{ blob: Blob; fileName: string }> {
  const { page: _page, page_size: _pageSize, ...filters } = params;
  const response = await fetch(`/api/v2/jobs/export.csv${jobQueryString(filters)}`, { credentials: "include" });
  if (!response.ok) {
    const error: ApiError = { status: response.status, body: await response.json().catch(() => ({})) };
    throw error;
  }
  const match = /filename="([^"]+)"/.exec(response.headers.get("Content-Disposition") ?? "");
  return { blob: await response.blob(), fileName: match ? match[1] : "nexus-jobs.csv" };
}

export interface ComplianceOverview {
  readonly total_firewalls: number;
  readonly evaluated_firewalls: number;
  readonly assured_compliance_pct: number;
  readonly evidence_coverage_pct: number;
  readonly observed_compliance_pct: number;
  readonly critical_deficiencies: number;
  readonly data_gaps: number;
  /** pass + fail + unavailable over every control on every evaluated firewall; NOT_APPLICABLE is not a check. */
  readonly control_checks?: number;
  /** Devices shown with their previous evaluation while the background re-evaluates a changed configuration or rule set. */
  readonly pending_reevaluation?: number;
  readonly frameworks: readonly {
    readonly framework: string;
    readonly score_pct: number;
    readonly total_controls: number;
    readonly pass_count: number;
    readonly fail_count: number;
    readonly data_unavailable_count: number;
  }[];
}

export interface ComplianceFrameworkMapping {
  readonly framework: string;
  readonly reference?: string;
  readonly clauseId?: string;
  readonly version?: string;
  readonly frameworkVersion?: string;
  readonly profile?: string;
  readonly mappingStrength?: string;
  readonly relationship?: string;
}

export interface ComplianceControlItem {
  readonly control_id: string;
  readonly title: string;
  readonly description: string | null;
  readonly family?: string | null;
  readonly severity: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";
  readonly category: string;
  readonly frameworks: readonly ComplianceFrameworkMapping[];
  readonly status: "PASS" | "FAIL" | "DATA_UNAVAILABLE";
  readonly compliance_pct: number;
  readonly target_device_count: number;
  readonly pass_count: number;
  readonly fail_count: number;
  readonly data_unavailable_count: number;
  readonly missing_reason?: string;
  readonly affected_devices: readonly string[];
  /** Vendors whose devices this check was evaluated on (two vendors' checks can share a title). */
  readonly vendors?: readonly string[];
}

export interface ComplianceControlsResponse {
  readonly total_controls: number;
  readonly controls: readonly ComplianceControlItem[];
}

export interface ComplianceGuidance {
  readonly summary: string;
  readonly steps: readonly string[];
  readonly cli?: string | null;
  readonly references: readonly string[];
  readonly caution?: string | null;
}

export interface DeviceComplianceItem {
  readonly controlId: string;
  readonly title: string;
  readonly severity: string;
  readonly frameworks: readonly ComplianceFrameworkMapping[];
  readonly verdict: string;
  readonly reasonCode: string;
  readonly displayStatus: string;
  readonly missingEvidenceId?: string;
  readonly requiredGateEntry?: string;
  readonly message?: string;
  readonly observedValue?: string | null;
  readonly rationale?: string | null;
  readonly guidance?: ComplianceGuidance | null;
}

export interface DeviceComplianceResult {
  readonly device_id: string;
  readonly hostname: string;
  readonly vendor: string;
  readonly totalAssigned: number;
  readonly passCount: number;
  readonly failCount: number;
  readonly dataUnavailableCount: number;
  readonly observedCompliance: number;
  readonly evidenceCoverage: number;
  readonly assuredCompliance: number;
  readonly items: readonly DeviceComplianceItem[];
}

export function getComplianceOverview(): Promise<ComplianceOverview> {
  return call<ComplianceOverview>("/compliance/overview", "GET");
}

export function getComplianceControls(): Promise<ComplianceControlsResponse> {
  return call<ComplianceControlsResponse>("/compliance/controls", "GET");
}

export interface ComplianceControlDetail {
  readonly control_id: string;
  readonly title: string;
  readonly severity: string;
  readonly status: string;
  readonly rationale: string | null;
  readonly frameworks: readonly ComplianceFrameworkMapping[];
  readonly expected: readonly { vendor: string; text: string }[];
  readonly devices: readonly { device_id: string; hostname: string | null; vendor: string; status: string; observed_value: string | null; message: string | null; guidance?: ComplianceGuidance | null }[];
}

export function getComplianceControlDetail(id: string): Promise<ComplianceControlDetail> {
  return call<ComplianceControlDetail>(`/api/v2/compliance/controls/${encodeURIComponent(id)}`, "GET");
}

export function getDeviceCompliance(deviceId: string): Promise<DeviceComplianceResult> {
  return call<DeviceComplianceResult>(`/devices/${encodeURIComponent(deviceId)}/compliance`, "GET");
}

export function triggerComplianceEvaluation(deviceId?: string): Promise<ComplianceOverview> {
  return call<ComplianceOverview>("/compliance/evaluate", "POST", deviceId ? { device_id: deviceId } : {});
}

// ---------------------------------------------------------------------------------------------
// Administration › Notifications and System (2026-09-22)
// ---------------------------------------------------------------------------------------------

export interface NotificationSettingsView {
  syslog_enabled: boolean;
  syslog_host: string | null;
  syslog_port: number;
  syslog_protocol: "udp" | "tcp";
  syslog_facility: number;
  smtp_enabled: boolean;
  smtp_host: string | null;
  smtp_port: number;
  smtp_starttls: boolean;
  smtp_from: string | null;
  smtp_to: string | null;
  forward_audit_to_syslog: boolean;
  notify_job_failure: boolean;
  updated_at?: string | null;
  routes: NotificationRouteView[];
}

export interface NotificationRouteView {
  type: "admin_event" | "login_security" | "backup_failure" | "job_failure" | "config_change" | "compliance_regression" | "device_health" | "security_scan" | "device_crash_file";
  enabled: boolean;
  recipients: string | null;
  last_sent_at: string | null;
  last_error: string | null;
}

export function getNotificationSettings(): Promise<NotificationSettingsView> {
  return call("/api/v2/config/notifications", "GET");
}

export function saveNotificationSettings(settings: NotificationSettingsView): Promise<NotificationSettingsView> {
  return call("/api/v2/config/notifications", "PUT", settings);
}

export function testNotification(kind: "syslog" | "mail", type?: NotificationRouteView["type"], settings?: NotificationSettingsView): Promise<{ sent: boolean; detail: string }> {
  return call(`/api/v2/config/notifications/test-${kind}`, "POST", type ? { type, settings } : {});
}

export interface PodStatusView {
  readonly name: string;
  readonly component: string;
  readonly phase: string;
  readonly state: string;
  readonly ready: string;
  readonly restarts: number;
  readonly started_at: string | null;
  readonly image_digest: string | null;
  readonly cpu_millicores: number | null;
  readonly cpu_limit_millicores: number | null;
  readonly memory_bytes: number | null;
  readonly memory_limit_bytes: number | null;
}

export interface ModuleStatusView {
  readonly module: string;
  readonly owner: string;
  readonly quarantined_permits: number;
}

export interface PodsView {
  readonly available: boolean;
  readonly reason?: string;
  readonly metrics_note?: string | null;
  readonly read_at: string;
  readonly pods: readonly PodStatusView[];
  readonly modules?: readonly ModuleStatusView[];
  readonly quarantined_by_role?: ReadonlyArray<Pick<ModuleStatusView, "owner" | "quarantined_permits">>;
}

export function getSystemPods(): Promise<PodsView> {
  return call("/api/v2/system/pods", "GET");
}

export interface StorageView {
  readonly read_at: string;
  readonly backups: ReadonlyArray<{ vendor: string; class: string; artefacts: number; devices: number; stored_bytes: number; original_bytes: number; oldest: string | null; newest: string | null }>;
  readonly top_devices: ReadonlyArray<{ device_id: string; hostname: string | null; artefacts: number; stored_bytes: number }>;
  readonly configuration: { artefacts: number; stored_bytes: number; text_in_database_bytes: number };
  readonly database_bytes: number;
  readonly artefact_volume: { total_bytes?: number; usable_bytes?: number; note?: string };
}

export function getSystemStorage(): Promise<StorageView> {
  return call("/api/v2/system/storage", "GET");
}

// ---------------------------------------------------------------------------------------------
// Overview (OVERVIEW_EXCEPTION_SCREEN_CONTRACT, FROZEN 2026-09-23)
// ---------------------------------------------------------------------------------------------

export type EvidenceState = "OK" | "UNKNOWN" | "READ_FAILED";
export interface EvidenceChip { readonly at: string | null; readonly state: EvidenceState }
export interface CountTile { readonly count: number; readonly of?: number; readonly unknown?: number; readonly terminal_24h?: number; readonly other_terminal_24h?: number; readonly last_at?: string | null; readonly previous?: number | null; readonly state: EvidenceState }
export interface FailureReason { readonly reason: string; readonly count: number; readonly devices: number; readonly job_types: readonly string[]; readonly last_at: string | null }
export interface VersionSlice { readonly label: string | null; readonly count: number }
export interface OverviewView {
  readonly generated_at: string;
  readonly masked: boolean;
  readonly denominators: { active_devices: number; enrolled_devices?: number; gateways: number; clusters: number; clusters_enrolled?: number; backup_targets: number };
  readonly evidence: Record<"inventory" | "configuration" | "compliance" | "backup" | "jobs" | "platform_facts", EvidenceChip> & { state?: EvidenceState };
  readonly attention: {
    failed_jobs_24h: CountTile; stale_inventory: CountTile; cluster_diff: CountTile; config_changed: CountTile; backup_missing: CountTile; managed_not_enrolled?: CountTile;
    state?: EvidenceState;
  };
  readonly exceptions: {
    failed_jobs: { total: number; rows: Array<{ job_id: string; job_type: string; device_id: string; label: string | null; cluster?: string | null; terminal_reason: string | null; finished_at: string }>; reasons?: FailureReason[] };
    config_changes: { total: number; rows: Array<{ device_id: string; label: string | null; cluster?: string | null; run_id: string; sections_latest: number; collected_at: string }> };
    cluster_diff: { total: number; unknown: number; all_refs: string[]; rows: Array<{ cluster_ref: string; diff_section_count: number; diff_setting_count: number; diff_sections: string[]; computed_at: string }> };
    state?: EvidenceState;
  };
  readonly inventory_age: { lt24h: number; h24_72: number; gt72h: number; never: number; of: number; state?: EvidenceState };
  readonly compliance: {
    state: EvidenceState; reason?: string | null; evaluated?: number; of_firewalls?: number; observed_pct?: number; assured_pct?: number;
    coverage_pct?: number; critical_deficiencies?: number; data_gaps?: number; pending_reevaluation?: number;
    frameworks?: Array<{ name: string; pass: number; fail: number; unavailable: number; total: number; score_pct: number }>;
  };
  readonly platform: {
    devices: number; check_point: number; palo_alto: number; clusters: number;
    hotfix_levels: Array<{ level: string | null; count: number }>; evidence_at: string | null; state?: EvidenceState;
    /** Per vendor: major (CP software version / PAN x.y) and minor (CP jumbo take / PAN full version); null label = UNKNOWN. */
    versions?: Record<"check_point" | "palo_alto", { major: VersionSlice[]; minor: VersionSlice[]; model?: VersionSlice[] }>;
  };
  readonly policy_install?: {
    readonly state: EvidenceState; readonly of?: number; readonly today?: number; readonly yesterday?: number;
    readonly days_2_7?: number; readonly older?: number; readonly unknown?: number;
    readonly oldest_at?: string | null; readonly newest_at?: string | null; readonly last_read_at?: string | null;
  };
  readonly nexus: { completed_24h: number; running: number; oldest_running_submitted_at: string | null; last_inventory: { check_point: string | null; palo_alto: string | null }; state?: EvidenceState };
  /** Executive estate map (EXEC_OVERVIEW_DESIGN_2026_09_25_FABLE.md): every active device and its worst evidenced condition. */
  readonly estate?: EstateView;
}

export type EstateCondition = "critical" | "ageing" | "partly_assessed" | "not_assessed" | "clear";
/** Why a cell is not assessed (OverviewService.estateConditionReason); absent from older payloads. */
export type EstateConditionReason = "not_evaluated" | "no_evidence" | "no_critical_control";
export interface EstateView {
  readonly devices: ReadonlyArray<{ device_id: string; hostname: string | null; vendor: string; model: string | null;
    condition: EstateCondition; condition_reason?: EstateConditionReason | null; no_archive: boolean; critical_fail: number | null;
    last_read_at: string | null }>;
  readonly counts: Readonly<Record<EstateCondition, number>>;
  readonly no_archive: number;
  readonly compliance_state: EvidenceState;
  readonly facts: {
    readonly critical: { k?: number; n?: number; state?: string };
    readonly backups: { a: number; b: number };
    readonly evidence: { r: number; d: number; never: number };
  };
}

export function getOverview(): Promise<OverviewView> {
  return call("/api/v2/overview", "GET");
}

export type CpFailoverState = "PLANNED" | "PRECHECK" | "FAILING_OVER" | "SWITCHED" | "POSTCHECK" | "RETURNING" | "DONE" | "STOPPED";
export type CpFailoverCheckStatus = "PASS" | "FAIL" | "UNKNOWN" | "WARN";
export interface ReadinessMember { device_id: string; hostname: string | null; ha_role: string | null }
export interface CpFailoverUnit { maskedName?: string; maskedMembers?: Array<Omit<ReadinessMember, "hostname"> & { maskedLabel: string }>; masked?: boolean; members?: ReadinessMember[]; unitId: string; clusterId: string; cluster_member_ref: string; vendor?: "check_point" | "palo_alto"; virtual_system?: string; canApprove: boolean; canStart: boolean; canSchedule: boolean; mode?: string; recoveryMode?: "ACTIVE_UP" | "PRIMARY_UP" | "UNKNOWN"; refusalReason?: string | null; readinessObservedAt?: string | null }
export interface ReadinessCheck { device_id?: string; checkNo: number; title: string; member: string; result: CpFailoverCheckStatus; summary: string; blocking: boolean }
export interface CpFailoverSummary extends Pick<CpFailoverUnit, "unitId" | "clusterId" | "cluster_member_ref" | "vendor" | "virtual_system" | "members" | "masked" | "mode" | "refusalReason" | "readinessObservedAt"> {
  activeWindow: boolean; lastRunState: string | null; lastRunOutcome: string | null; lastRunAt: string | null; canRunReadiness: boolean;
  readiness: { status: "READY" | "NOT_READY" | "UNKNOWN"; observedAt: string; failedCheck: string; stopCode?: string; checks: Array<ReadinessCheck & { status: CpFailoverCheckStatus; derived: Record<string, unknown> }> } | null;
}
export interface CpFailoverApproval { approvalId: string; windowFrom: string; windowUntil: string; reason: string; revokedAt: string | null; requestId?: string; revision?: number; policy?: "ADMIN_SINGLE" | "OPERATION_ADMIN_TWO_PERSON"; approved?: boolean; executionNonce?: string; canStart?: boolean; canApprove?: boolean }
export interface CpFailoverRun {
  runId: string; approvalId: string; scheduledFor: string; state: CpFailoverState; step: string;
  outcome: string | null; failedCheck: string | null; message: string | null; steps: string[]; kind?: "FAILOVER" | "READINESS";
  mutationPossible?: boolean | null; incidentRef?: string | null; lastConfirmedRoles?: Record<string, string>;
}
/** Workers write "post_return" for the checks after the return step; "final" is the older name, still accepted. */
export interface CpFailoverCheck extends ReadinessCheck { phase: "pre" | "post" | "post_return" | "final"; device_id: string; hostname: string | null; cluster_member_ref: string; checkNo: 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15; status: CpFailoverCheckStatus; derived: string; observedAt: string }
export interface CpFailoverRunDetail extends CpFailoverRun { checks: CpFailoverCheck[] }

const failoverPath = (vendor?: string) => vendor === "palo_alto" ? "/api/v2/pan-failover" : "/api/v2/cp-failover";
const cpQuery = (unit: CpFailoverUnit) => `clusterId=${encodeURIComponent(unit.clusterId)}&unitId=${encodeURIComponent(unit.unitId)}`;
/** By a member's device id; display names are masked by the server for aiview. */
export const listCpFailoverUnits = (memberDeviceId: string, vendor?: string) => call<CpFailoverUnit[]>(`${failoverPath(vendor)}/units?memberDeviceId=${encodeURIComponent(memberDeviceId)}`, "GET");
export const listCpFailoverSummary = () => call<CpFailoverSummary[]>("/api/v2/cp-failover/summary", "GET");
export const listCpFailoverApprovals = (unit: CpFailoverUnit) => call<CpFailoverApproval[]>(`${failoverPath(unit.vendor)}/approvals?${cpQuery(unit)}`, "GET");
export const listCpFailoverRuns = (unit: CpFailoverUnit) => call<CpFailoverRun[]>(`${failoverPath(unit.vendor)}/runs?${cpQuery(unit)}`, "GET");
export const getCpFailoverRun = (runId: string, vendor?: string) => call<CpFailoverRunDetail>(`${failoverPath(vendor)}/runs/${encodeURIComponent(runId)}`, "GET");
export const approveCpFailover = (unit: CpFailoverUnit, from: string, until: string, reason: string, requestId: string) =>
  call<CpFailoverApproval>(`${failoverPath(unit.vendor)}/approvals`, "POST", { clusterId: unit.clusterId, unitId: unit.unitId, windowFrom: from, windowUntil: until, reason, requestId });
export const secondApproveCpFailover = (unit: CpFailoverUnit, requestId: string, revision: number) =>
  call<CpFailoverApproval>(`${failoverPath(unit.vendor)}/approvals/${encodeURIComponent(requestId)}/approve`, "POST", { clusterId: unit.clusterId, unitId: unit.unitId, revision });
export const revokeCpFailover = (approvalId: string, vendor?: string) => call<{ revoked: boolean }>(`${failoverPath(vendor)}/approvals/${encodeURIComponent(approvalId)}/revoke`, "POST");
export const startCpFailover = (unit: CpFailoverUnit, binding: { requestId: string; revision: number; executionNonce: string; warningConfirmed: true }) =>
  call<{ runId: string }>(`${failoverPath(unit.vendor)}/runs`, "POST", { clusterId: unit.clusterId, unitId: unit.unitId, scheduledFor: null, ...binding });
export const runCpFailoverReadiness = (unit: Pick<CpFailoverUnit, "clusterId" | "unitId" | "vendor">) =>
  call<{ runId: string }>(`${failoverPath(unit.vendor)}/units/${encodeURIComponent(unit.unitId)}/readiness`, "POST",
    { clusterId: unit.clusterId, unitId: unit.unitId });
/** One open failover incident, or a stopped run whose unresolved send blocks every failover in the estate. */
export interface FailoverIncident {
  incidentRef: string; clusterId: string; unitId: string; cluster_member_ref: string; maskedName?: string;
  vendor?: string | null; reason: string; openedAt: string; incidentActive: boolean; blocksEstate: boolean;
  unresolvedSends: number; members: Array<{ device_id: string }>;
}
export interface FailoverIncidents { canRelease: boolean; roleNote: string | null; incidents: FailoverIncident[] }
export const listFailoverIncidents = () => call<FailoverIncidents>("/api/v2/cp-failover/incidents", "GET");
/** security_admin only; the server takes the actor from the session, the body carries the reason only. */
export const releaseFailoverIncident = (incidentRef: string, reason: string) =>
  call<{ incidentRef: string; status: string }>(`/api/v2/cp-failover/runs/${encodeURIComponent(incidentRef)}/incident/release`, "POST", { reason });

export interface HttpsCertificateView {
  readonly available: boolean;
  readonly strict?: boolean;
  readonly certificate_changed?: boolean;
  readonly can_accept?: boolean;
  readonly can_set_strict?: boolean;
  readonly certificates?: readonly {
    trust_entry_id: string;
    status: "ACTIVE" | "PENDING";
    fingerprint_sha256: string;
    subject_cn: string;
    issuer_cn: string;
    not_after: string;
  }[];
}
export function getHttpsCertificate(deviceId: string): Promise<HttpsCertificateView> {
  return call(`/devices/${encodeURIComponent(deviceId)}/https-certificate`, "GET");
}
export function acceptHttpsCertificate(deviceId: string, trustEntryId: string): Promise<unknown> {
  return call(`/devices/${encodeURIComponent(deviceId)}/https-certificate/accept`, "POST", { trustEntryId });
}
export function setHttpsCertificateStrict(deviceId: string, strict: boolean): Promise<unknown> {
  return call(`/devices/${encodeURIComponent(deviceId)}/https-certificate/strict`, "PUT", { strict });
}

export interface PolicyTarget { deviceId: string; name: string; context: string; syncStatus: string }
export interface PolicyMetadata {
  id: string; sourceId: string; sourceName: string; vendor: string; containerId: string;
  containerName: string; name: string; collectedAt: string; artefactRef: string; targets: PolicyTarget[]; ruleCount?: number;
}
export interface PolicySchedule {
  kind: "one-time" | "recurring" | "unknown"; start: string | null; end: string | null;
  windows: { days: number[]; monthDays: number[]; months?: number[]; start: string; end: string }[];
  timezone: string; timezoneKnown: boolean;
}
export interface PolicyRevision {
  revision: number; ruleId: string; identityFallback: boolean; changeType: "added" | "removed" | "modified";
  collectedAt: string; changedOn: string | null; changedBy: string | null;
  changes: { field: string; before: unknown; after: unknown }[];
}
export const getPolicyHistory = (id: string, rule = "", page = 0) => call<{ revisions: PolicyRevision[]; page: number }>(
  `/api/v2/policy/policies/${encodeURIComponent(id)}/history?rule=${encodeURIComponent(rule)}&page=${page}`, "GET");
export interface PolicyObject {
  id: string; name: string; type: string; status: string; members?: string[]; values?: string[]; children?: PolicyObject[]; schedule?: PolicySchedule | null;
}
export interface PolicyCell { refs: string[]; negated: boolean }
export interface PolicyHitCounts {
  hits: number | null; firstHit: string | null; lastHit: string | null; source: "device" | "mds";
  collectedAt: string; level?: string | null;
  firewalls: { deviceId: string; context: string; hits: number | null; firstHit: string | null; lastHit: string | null;
    createdAt: string | null; modifiedAt: string | null; collectedAt: string }[];
}
export interface PolicyHygieneFinding { findingClass: string; severity: "HIGH" | "MEDIUM" | "LOW"; evidence: string }
export interface PolicyHygieneAssessment {
  shadowStatus: "UNKNOWN" | "NOT_SHADOWED" | "REDUNDANT" | "CONFLICT"; shadowReason: string | null; coveringRuleId: string | null;
  findings: PolicyHygieneFinding[]; permissiveness: NonNullable<PolicyRule["permissiveness"]>;
  timeStatus: string; expiring: boolean; counterWindow: string; hitSource: string; hitsCollectedAt: string | null;
}
export interface PolicyHygienePage {
  rows: { ruleId: string; number: number; name: string; sectionId: string; findings: PolicyHygieneFinding[]; hygiene: PolicyHygieneAssessment }[];
  counts: Record<string, number>; total: number; page: number; pageSize: number; budgetReached: boolean; days: number;
}
export interface PolicyRuleDetail { metadata: PolicyMetadata; rule: PolicyRule; section: PolicySection; objects: PolicyObject[] }
const hygieneParams = (findingClass: string, severity: string, days: number) => new URLSearchParams({ findingClass, severity, days: String(days) });
export const getPolicyHygiene = (id: string, page = 0, findingClass = "", severity = "", days = 90) =>
  call<PolicyHygienePage>(`/api/v2/policy/policies/${encodeURIComponent(id)}/hygiene?page=${page}&${hygieneParams(findingClass, severity, days)}`, "GET");
export const getPolicyRule = (id: string, rule: string, days = 90) =>
  call<PolicyRuleDetail>(`/api/v2/policy/policies/${encodeURIComponent(id)}/rules?ruleId=${encodeURIComponent(rule)}&days=${days}`, "GET");
export async function downloadPolicyHygiene(id: string, findingClass: string, severity: string, days: number) {
  const response = await fetch(`/api/v2/policy/policies/${encodeURIComponent(id)}/hygiene.csv?${hygieneParams(findingClass, severity, days)}`, { credentials: "include" });
  if (!response.ok) throw new Error("Hygiene export failed");
  return response.blob();
}
export interface PolicyRule {
  id: string; uuid: string; number: number; name: string; enabled: boolean | null;
  source: PolicyCell; destination: PolicyCell; service: PolicyCell; application: PolicyCell;
  action: string; log: string; comment: string; extras: Record<string, string[]>;
  hitCounts?: PolicyHitCounts | null;
  hygiene?: PolicyHygieneAssessment;
  identityFallback?: boolean; timeStatus?: string; schedules?: PolicySchedule[]; expiring?: boolean;
  permissiveness?: { level: "Low" | "Medium" | "High" | "Unknown"; reasons: string[]; score?: number };
}
export interface PolicySection { id: string; name: string; source: string; parentRuleId: string | null; rules: PolicyRule[]; total: number }
export interface PolicyPage { failures?: { layerRef: string; reason: string; layerName?: string; offset?: number }[]; policyKind?: "LOCAL_FIREWALL" | "MANAGEMENT"; metadata: PolicyMetadata; sections: PolicySection[]; objects: PolicyObject[]; page: number; pageSize: number; total: number }
export const listPolicies = () => call<{ policies: PolicyMetadata[]; devices: PolicyTarget[] }>("/api/v2/policy/devices", "GET");
export const getPolicy = (id: string, page: number, q: string, hitFilter = "", days = 90) => call<PolicyPage>(`/api/v2/policy/policies/${encodeURIComponent(id)}?page=${page}&q=${encodeURIComponent(q)}${hitFilter ? `&hitFilter=${hitFilter}&days=${days}` : ""}`, "GET");
export const getPolicyObject = (id: string, policy: string) => call<{ object: PolicyObject }>(`/api/v2/policy/objects/${encodeURIComponent(id)}?policy=${encodeURIComponent(policy)}`, "GET");

export interface PolicyInventoryObject extends Omit<PolicyObject, "status"> {
  uid: string; unused: boolean | null; emptyGroup: boolean; singleMember: boolean;
  duplicateId: string | null; ruleCount: number; groupCount: number;
  /** True only when a zero/total count is proven; otherwise counts are a lower bound and usageReasons explain why. */
  usageComplete?: boolean; usageReasons?: string[]; unusedReasons?: string[];
}
export interface PolicyTypeState { type: string; status: string; collectedAt: string; objects: number }
export interface PolicyObjectPage { objects: PolicyInventoryObject[]; total: number; page: number; pageSize: number; types: PolicyTypeState[]; usageGaps?: string[] }
export interface PolicyUsage {
  object: PolicyInventoryObject; rules: { policyId: string; policyName: string; layerRef: string; layerName: string; ruleId: string; number: number }[];
  groups: { id: string; uid: string; name: string; type: string }[]; ruleCount: number; groupCount: number; page: number; pageSize: number;
  usageComplete?: boolean; usageReasons?: string[];
}
export interface PolicyInstallation {
  id: string; policyId: string; policyName: string; name: string; deviceId: string;
  targeted: boolean | null; allTargets: boolean; installed: boolean | null;
}
export interface PolicyInstallationPage { installations: PolicyInstallation[]; total: number; page: number; pageSize: number; types: PolicyTypeState[] }
const policyDomainPath = (source: string, domain: string, view: string, page: number, q = "") =>
  `/api/v2/policy/domains/${encodeURIComponent(domain)}/${view}?source=${encodeURIComponent(source)}&page=${page}&q=${encodeURIComponent(q)}`;
export const getPolicyObjects = (source: string, domain: string, page = 0, q = "", type = "", hygiene = "") =>
  call<PolicyObjectPage>(`${policyDomainPath(source, domain, "objects", page, q)}&type=${encodeURIComponent(type)}&hygiene=${encodeURIComponent(hygiene)}`, "GET");
export const getPolicyUsage = (source: string, domain: string, uid: string, page = 0) =>
  call<PolicyUsage>(`${policyDomainPath(source, domain, "object-usage", page)}&uid=${encodeURIComponent(uid)}`, "GET");
export const getPolicyInstallations = (source: string, domain: string, page = 0, q = "", policy = "") =>
  call<PolicyInstallationPage>(`${policyDomainPath(source, domain, "installation", page, q)}&policy=${encodeURIComponent(policy)}`, "GET");

export type PolicyCollectionSource = { sourceId: string; sourceName: string; vendor: string; collection?: PolicyCollectionStatus };
export const listPolicySources = () => call<{ sources: PolicyCollectionSource[]; canCollect: boolean; canCancel?: boolean }>("/api/v2/policy/sources", "GET");
export type PolicyCollectionMode = "CHANGED_ONLY" | "FULL";
export const collectPolicies = (sourceId: string, domainRef = "", mode: PolicyCollectionMode = "CHANGED_ONLY") => call<{ jobId: string }>(
  `/api/v2/policy/sources/${encodeURIComponent(sourceId)}/collect`, "POST", { domainRef, mode });

export type PolicyContainer = { containerId: string; containerName: string };
export const getPolicyTree = (source = "", container = "", device = "") => call<{
  sources?: PolicyCollectionSource[]; devices?: PolicyTarget[]; containers?: PolicyContainer[]; policies?: PolicyMetadata[];
}>(`/api/v2/policy/tree?source=${encodeURIComponent(source)}&container=${encodeURIComponent(container)}&device=${encodeURIComponent(device)}`, "GET");
export type PolicyUnknownPart = { part: "NAT" | "UNKNOWN"; reasonCode: "VENDOR_NAME_NOT_UNIQUE" | "UNKNOWN"; units: number };
export type PolicyCollectionStatus = { domainsReused?: number; domainsCollected?: number; rulesReused?: number; domains?: { containerId: string; status: "COLLECTING" | "COLLECTED" | "REUSED"; complete?: boolean; rules: number; publishTime?: string; hitsCollectedAt?: string }[]; unknownParts?: PolicyUnknownPart[]; cancelRequested?: boolean; jobId: string; state: string; outcome?: string; reason: string; step: number; total: number; layer?: number; layers?: number; rulesFetched?: number; packagesDone?: number; packagesTotal?: number; domainsDone?: number; domainsTotal?: number; startedAt?: string; lastActivityAt?: string; readTimeoutSeconds?: number; gapUnits?: number; unitFailureCodes?: string[]; collectedAt?: string; hasTranscript?: boolean };
export const getPolicyCollectionStatus = (id: string) => call<PolicyCollectionStatus>(`/api/v2/policy/collections/${encodeURIComponent(id)}`, "GET");

/** Server-owned device job policy; no local timezone or duration guess. */
export function getJobWindow(): Promise<import("../shell/JobWindow").JobWindowStatus> {
  return call("/api/v2/job-window", "GET");
}

export interface SshHostKeyObservation {
  observation_id: string; device_id: string | null; hostname: string;
  key_algorithm: string; presented_fingerprint: string; expected_fingerprint_prefix: string;
  first_seen: string; last_seen: string; seen_count: number; status: "OPEN" | "ACCEPTED" | "REJECTED";
  strict_refusal: boolean; ssh_identity_unverified: boolean; model: string | null; software_version: string | null;
  serial_number: string | null; management_source: string;
}
export interface SshHostKeyView { mode: "WARN" | "STRICT"; observations: SshHostKeyObservation[] }
export async function getSshHostKeyView(deviceId?: string): Promise<SshHostKeyView> {
  const view = await call<SshHostKeyView>(`/api/v2/ssh-trust${deviceId ? `?device_id=${encodeURIComponent(deviceId)}` : ""}`, "GET");
  if (!Array.isArray(view.observations) || !["WARN", "STRICT"].includes(view.mode)) throw new Error("INVALID_SSH_TRUST_RESPONSE");
  return view;
}
export const setSshTrustMode = (mode: "WARN" | "STRICT") => call("/api/v2/ssh-trust/mode", "PUT", { mode });
export const acceptSshHostKey = (id: string, confirmation: string) => call(
  `/api/v2/ssh-trust/observations/${encodeURIComponent(id)}/accept`, "POST", { confirmation });
export const rejectSshHostKey = (id: string) => call(
  `/api/v2/ssh-trust/observations/${encodeURIComponent(id)}/reject`, "POST");

export interface PolicyLookupQuery {
  source: string; destination: string; service: string; sourceZone?: string; destinationZone?: string; target?: string; context?: string;
}
export interface PolicyLookupRule { ruleId: string; number: number; name: string; sectionId: string; sectionName: string; action: string; lookupReason?: string }
export interface PolicyLookupDecision {
  verdict: "ALLOWED" | "DENIED" | "PARTIAL" | "UNKNOWN"; lookupReason: string;
  decidingRule: PolicyLookupRule | null; undecidableRules: PolicyLookupRule[];
}
export interface PolicyLookupResult extends PolicyLookupDecision {
  policyId: string; collectedAt: string; limitations: string;
  query: { querySource: string; queryDestination: string; queryService: string };
  parts: (PolicyLookupDecision & { querySource: string; queryDestination: string })[];
}
export const lookupPolicy = (id: string, query: PolicyLookupQuery) =>
  call<PolicyLookupResult>(`/api/v2/policy/policies/${encodeURIComponent(id)}/lookup`, "POST", query);

export interface OfflineAnalysisSummary {
  id: string; policyId: string; fileName: string; uploader: string; createdAt: string;
  rowCount: number; periodStart: string; periodEnd: string;
}
export interface OfflineRule {
  ruleId: string; name: string; number: number; action: string; sectionName: string; flowCount: number;
  firstTime: string; lastTime: string; distinctSources: number; distinctDestinations: number; distinctServices: number;
  topObserved: { observedSource?: string; observedDestination?: string; observedService?: string; count: number }[];
  zeroTraffic: string; severity: string; memberStatus: string; lookupReason?: string;
  unseenMembers: { memberId: string; name: string; dimension: string }[];
  narrowing: { memberStatus?: string; suggestionNote?: string; observedSource?: string[]; observedDestination?: string[];
    observedService?: string[]; coveragePercent?: number; coveredFlows?: number; caution?: string };
}
export interface OfflineAnalysisResult extends OfflineAnalysisSummary {
  flowCount: number; attributedFlows: number; invalidRows: number; agreementCount: number; disagreementCount: number;
  comparisonUnknownCount: number; comparisonEnabled: boolean; comparisonNote: string; attributionNote: string; limitations: string; caution: string;
  parseStats: { statReason: string; count: number }[]; unknownStats: { statReason: string; count: number }[];
  verdictStats: { statReason: string; count: number }[]; rules: OfflineRule[];
}
export function listOfflineAnalyses(policy: string, page = 0): Promise<{ analyses: OfflineAnalysisSummary[]; canManage: boolean }> {
  return call(`/api/v2/policy/policies/${encodeURIComponent(policy)}/offline-analyses?page=${page}`, "GET");
}
export function getOfflineAnalysis(id: string): Promise<OfflineAnalysisResult> {
  return call(`/api/v2/policy/offline-analyses/${encodeURIComponent(id)}`, "GET");
}
async function offlineMutation<T>(path: string, method: "POST" | "DELETE", body?: FormData): Promise<T> {
  const token = await csrfToken();
  const response = await fetchWithTimeout(path, { method, credentials: "include", body,
    headers: token ? { "X-CSRF-Token": token } : {} }, 600_000);
  noteMasking(response);
  const parsed = await response.json().catch(() => ({}));
  if (!response.ok) throw { status: response.status, body: parsed } satisfies ApiError;
  return parsed as T;
}
export function uploadOfflineAnalysis(policy: string, file: File, mapping: Record<string, string>): Promise<{ id: string }> {
  const body = new FormData(); body.append("mapping", JSON.stringify(mapping)); body.append("file", file);
  return offlineMutation(`/api/v2/policy/policies/${encodeURIComponent(policy)}/offline-analyses`, "POST", body);
}
export function deleteOfflineAnalysis(id: string): Promise<{ deleted: boolean }> {
  return offlineMutation(`/api/v2/policy/offline-analyses/${encodeURIComponent(id)}`, "DELETE");
}
/** includeInitial also lists a policy's initial baseline capture (every rule "added" when no baseline existed). */
export interface PolicyChangeFilters { from: string; to: string; policy: string; device: string; actor: string; changeType: string; field: string; q: string; includeInitial?: boolean }
export interface PolicyChangeRow {
  revision: string; currentSnapshotLabel?: boolean; policyId: string; policyName: string; targets: PolicyTarget[]; ruleId: string;
  number: number | null; name: string; changedOn: string; observedAtCollection: boolean;
  changedBy: string; changeType: string; changedFields: string[]; changes: { field: string; before: unknown; after: unknown }[];
  /** The collection that recorded the revision (UTC ISO); groups the list. */
  collectedAt?: string; initialCapture?: boolean;
}
export interface PolicyChangesPage {
  rows: PolicyChangeRow[]; summary: { total: number; actors: number; rules: number; counts: Record<string, number>; initialCaptures?: number; initialCapturesIncluded?: boolean };
  nextCursor: string; rangeStart: string; rangeEnd: string;
}
const changeParams = ({ includeInitial, ...filters }: PolicyChangeFilters) => {
  const params = new URLSearchParams({ ...filters });
  if (includeInitial) params.set("includeInitial", "true");
  return params;
};
export const getPolicyChanges = (filters: PolicyChangeFilters, cursor = "") =>
  call<PolicyChangesPage>(`/api/v2/policy/changes?${changeParams(filters)}&cursor=${encodeURIComponent(cursor)}`, "GET");
export async function downloadPolicyChanges(filters: PolicyChangeFilters) {
  const response = await fetch(`/api/v2/policy/changes.csv?${changeParams(filters)}`, { credentials: "include" });
  if (!response.ok) throw new Error("Change export failed");
  return response.blob();
}

// ---- Change feed (Operations > Change feed; mockup 2026-10-17 screen 3, PO 2026-10-20) -------------------------
export type ChangeKind = "USER" | "NEXUS_JOB" | "DEVICE_EVENT";
export interface ChangeFeedEvent {
  readonly key: string;
  readonly kind: ChangeKind;
  readonly source: string;
  /** The ordering instant (ISO). For a DEVICE_LOCAL event this is the device text read as Europe/Istanbul, for ordering only. */
  readonly time: string;
  readonly time_basis: "UTC" | "DEVICE_LOCAL";
  /** The verbatim device-clock text when `time_basis` is DEVICE_LOCAL; null otherwise. */
  readonly device_time: string | null;
  readonly vendor: string | null;
  readonly device_id: string | null;
  readonly device_name: string | null;
  readonly actor: string | null;
  readonly what: string;
  readonly detail: string | null;
  readonly tone: "OK" | "WARN" | "BAD" | "NEUTRAL" | "UNKNOWN";
  readonly diff: readonly { readonly op: "-" | "+" | " "; readonly text: string }[];
  readonly links: { readonly device: string | null; readonly policy_id: string | null; readonly rule_id: string | null; readonly job_id: string | null; readonly job_type: string | null };
}
export interface ChangeFeedCoverage { readonly source: string; readonly kind: ChangeKind; readonly state: string; readonly note: string; readonly events: number }
export interface ChangeFeedPage {
  readonly events: readonly ChangeFeedEvent[];
  readonly next_cursor: string;
  readonly range_start: string;
  readonly range_end: string;
  readonly kinds: readonly ChangeKind[];
  readonly vendor: string | null;
  readonly device_id: string | null;
  readonly masked: boolean;
  readonly ordering_note: string;
  readonly coverage: readonly ChangeFeedCoverage[];
}
export interface ChangeFeedQuery { from?: string; to?: string; kinds?: readonly ChangeKind[]; vendor?: string; device?: string; cursor?: string; limit?: number }
export const getChangeFeed = (query: ChangeFeedQuery) => {
  const params = new URLSearchParams();
  if (query.from) params.set("from", query.from);
  if (query.to) params.set("to", query.to);
  if (query.kinds && query.kinds.length > 0) params.set("kinds", query.kinds.join(","));
  if (query.vendor) params.set("vendor", query.vendor);
  if (query.device) params.set("device", query.device);
  if (query.cursor) params.set("cursor", query.cursor);
  if (query.limit) params.set("limit", String(query.limit));
  const text = params.toString();
  return call<ChangeFeedPage>(`/api/v2/changes/feed${text ? `?${text}` : ""}`, "GET");
};

// ---- Packet Analysis (PO decision record 2026-10-15) -------------------------------------------------------
/** What the server says this session may do; the screen renders it and decides nothing itself. */
export interface PacketAnalysisPermissions { readonly run: boolean; readonly raw_view: boolean; readonly masked: boolean }
export interface PacketCase {
  readonly case_id: string; readonly title: string; readonly created_by?: string; readonly created_at: string;
  readonly expires_at: string; readonly state: "OPEN" | "QUEUED" | "ANALYZING" | "ANALYZED" | "FAILED";
  readonly total_bytes: number; readonly capture_count: number; readonly payload_zeroing: boolean;
  readonly latest_job_id: string | null;
}
export interface PacketCapture {
  readonly capture_id: string; readonly display_name: string; readonly format: "pcap" | "pcapng";
  readonly size_bytes: number; readonly sha256?: string; readonly uploaded_by?: string; readonly uploaded_at: string;
}
export type PacketTelemetryFormat = "sar" | "vmstat" | "pidstat" | "snapshots" | "syslog" | "unknown";
/**
 * A host telemetry text file attached to a case (server-side sar, vmstat, pidstat, qdisc/softnet snapshots, syslog). The
 * format is detected from the content. A file whose time zone is not confirmed is read as UTC by the expert report,
 * which says so.
 */
export interface PacketTelemetry {
  readonly telemetry_id: string; readonly display_name: string; readonly size_bytes: number;
  readonly detected_format: PacketTelemetryFormat; readonly recognized: boolean; readonly recognition_note: string | null;
  readonly time_zone: string | null; readonly zone_confirmed: boolean; readonly offset_seconds: number;
  readonly anchor_local: string | null; readonly sha256?: string; readonly uploaded_by?: string;
  readonly uploaded_at: string; readonly aligned_at: string | null;
}
/** What the user confirms about a telemetry file's clock; a null zone means "not confirmed" (UTC assumed). */
export interface PacketTelemetryAlignment {
  readonly time_zone: string | null; readonly offset_seconds: number; readonly anchor_local: string | null;
}
export interface PacketStageSummary { readonly stage: string; readonly verdict: string; readonly reason?: string }
/** The window the budget guard chose for a range over budget (numbers and fixed words; absent within budget). */
export interface PacketAnalysisWindow {
  readonly mode?: string; readonly reason?: string; readonly start?: number | null; readonly end?: number | null;
  readonly seconds?: number | null; readonly range_max_packets_per_capture?: number; readonly max_packets_per_capture?: number;
}
export interface PacketRunSummary {
  readonly verdict?: string; readonly reason?: string; readonly stages?: readonly PacketStageSummary[];
  readonly capture_count?: number; readonly total_bytes?: number; readonly finding_count?: number;
  readonly analysis_window?: PacketAnalysisWindow;
}
export interface PacketRun {
  readonly job_id: string; readonly case_id: string; readonly state: "REQUESTED" | "RUNNING" | "COMPLETED" | "FAILED";
  readonly requested_by?: string; readonly requested_at: string; readonly started_at: string | null;
  readonly finished_at: string | null; readonly engine_version: string | null; readonly failure_reason: string | null;
  readonly summary: PacketRunSummary | null;
}
export interface PacketCaseList {
  readonly cases: readonly PacketCase[]; readonly permissions: PacketAnalysisPermissions;
  readonly limits: { readonly max_file_bytes: number; readonly max_store_bytes: number; readonly used_bytes: number; readonly retention_days: number;
    readonly max_telemetry_bytes?: number };
}
export interface PacketCaseDetail extends PacketCase {
  readonly captures: readonly PacketCapture[]; readonly runs: readonly PacketRun[]; readonly permissions: PacketAnalysisPermissions;
  readonly telemetry?: readonly PacketTelemetry[];
}
export interface PacketReport {
  readonly job_id: string; readonly case_id: string; readonly projection: "full" | "masked";
  readonly report?: Record<string, unknown>; readonly summary?: PacketRunSummary | null;
}
const PA = "/api/v2/packet-analysis";
export const listPacketCases = () => call<PacketCaseList>(`${PA}/cases`, "GET");
export const createPacketCase = (title: string, payloadZeroing: boolean) =>
  call<PacketCase>(`${PA}/cases`, "POST", { title, payload_zeroing: payloadZeroing });
export const getPacketCase = (caseId: string) => call<PacketCaseDetail>(`${PA}/cases/${encodeURIComponent(caseId)}`, "GET");
export const analyzePacketCase = (caseId: string) =>
  call<{ job_id: string; state: string }>(`${PA}/cases/${encodeURIComponent(caseId)}/analyze`, "POST", {});
export const getPacketRun = (jobId: string) => call<PacketRun>(`${PA}/runs/${encodeURIComponent(jobId)}`, "GET");
export const getPacketReport = (jobId: string) => call<PacketReport>(`${PA}/runs/${encodeURIComponent(jobId)}/report`, "GET");
export async function deletePacketCase(caseId: string): Promise<{ deleted: boolean }> {
  const token = await csrfToken();
  const response = await fetchWithTimeout(`${PA}/cases/${encodeURIComponent(caseId)}`, { method: "DELETE", credentials: "include",
    headers: token ? { "X-CSRF-Token": token } : {} }, 60_000);
  noteMasking(response);
  const parsed = await response.json().catch(() => ({}));
  if (!response.ok) throw { status: response.status, body: parsed } satisfies ApiError;
  return parsed as { deleted: boolean };
}
/** The browser's own download (streamed to disk); the server ledgers it before the first byte. */
export const packetCaptureDownloadHref = (captureId: string) => `${PA}/captures/${encodeURIComponent(captureId)}/download`;
/** XMLHttpRequest upload with progress (fetch reports none): the browser streams the file from disk, never into page memory. */
async function uploadPacketFile<T>(path: string, file: File, onProgress: (sent: number, total: number) => void,
  makeRequest: () => XMLHttpRequest): Promise<T> {
  const token = await csrfToken();
  const body = new FormData();
  body.append("file", file, file.name);
  return new Promise((resolve, reject) => {
    const xhr = makeRequest();
    xhr.open("POST", path);
    xhr.withCredentials = true;
    if (token) xhr.setRequestHeader("X-CSRF-Token", token);
    xhr.upload.onprogress = event => { if (event.lengthComputable) onProgress(event.loaded, event.total); };
    xhr.onload = () => {
      let parsed: Record<string, unknown> = {};
      try { parsed = JSON.parse(xhr.responseText || "{}"); } catch { parsed = {}; }
      if (xhr.status >= 200 && xhr.status < 300) resolve(parsed as T);
      else reject({ status: xhr.status, body: parsed } satisfies ApiError);
    };
    xhr.onerror = () => reject({ status: 0, body: { error: "NETWORK" } } satisfies ApiError);
    xhr.send(body);
  });
}
/** Upload one capture with progress. */
export function uploadPacketCapture(caseId: string, file: File, onProgress: (sent: number, total: number) => void,
  makeRequest: () => XMLHttpRequest = () => new XMLHttpRequest()): Promise<{ capture_id: string; size_bytes: number; format: string }> {
  return uploadPacketFile(`${PA}/cases/${encodeURIComponent(caseId)}/captures`, file, onProgress, makeRequest);
}
/** Attach one host telemetry text file to a case (200 MB at most); the answer says what the content was recognised as. */
export function uploadPacketTelemetry(caseId: string, file: File, onProgress: (sent: number, total: number) => void,
  makeRequest: () => XMLHttpRequest = () => new XMLHttpRequest()): Promise<PacketTelemetry> {
  return uploadPacketFile(`${PA}/cases/${encodeURIComponent(caseId)}/telemetry`, file, onProgress, makeRequest);
}
/** Replace the clock alignment of one telemetry file. */
export const alignPacketTelemetry = (telemetryId: string, alignment: PacketTelemetryAlignment) =>
  call<PacketTelemetry>(`${PA}/telemetry/${encodeURIComponent(telemetryId)}`, "PUT", alignment);
export async function deletePacketTelemetry(telemetryId: string): Promise<{ deleted: boolean }> {
  const token = await csrfToken();
  const response = await fetchWithTimeout(`${PA}/telemetry/${encodeURIComponent(telemetryId)}`, { method: "DELETE", credentials: "include",
    headers: token ? { "X-CSRF-Token": token } : {} }, 60_000);
  noteMasking(response);
  const parsed = await response.json().catch(() => ({}));
  if (!response.ok) throw { status: response.status, body: parsed } satisfies ApiError;
  return parsed as { deleted: boolean };
}

// ---- Data Sanitizer (docs/design/DATA_SANITIZER_CONTRACT.md §3.1) ---------------------------------------------
/** What the server says this session may do; the screen renders it and decides nothing itself. */
export interface SanitizerPermissions { readonly run: boolean; readonly revert: boolean; readonly revert_any_job: boolean }
export interface SanitizerInfo {
  readonly permissions: SanitizerPermissions;
  readonly limits: { readonly max_file_bytes: number; readonly vault_retention_days: number;
    readonly output_ttl_minutes: number; readonly vault_available: boolean };
}
export type SanitizerState = "RUNNING" | "SUCCEEDED" | "FAILED";
export interface SanitizeJob {
  readonly job_id: string; readonly state: SanitizerState; readonly file_type: string | null;
  readonly created_at: string; readonly finished_at: string | null; readonly input_bytes: number | null;
  readonly output_bytes: number | null; readonly counts: Record<string, number>; readonly failure_reason: string | null;
  readonly output_state: string; readonly vault_entries: number; readonly vault_expires_at: string; readonly vault_pruned: boolean;
}
export interface RevertJob {
  readonly revert_id: string; readonly state: SanitizerState; readonly scope: string; readonly source_job_id: string | null;
  readonly file_type: string | null; readonly created_at: string; readonly finished_at: string | null;
  readonly input_bytes: number | null; readonly output_bytes: number | null; readonly counts: Record<string, number>;
  readonly failure_reason: string | null; readonly output_state: string;
}
const SANITIZE = "/api/v2/sanitize";
export const getSanitizerInfo = () => call<SanitizerInfo>(`${SANITIZE}/jobs`, "GET");
export const getSanitizeJob = (jobId: string) => call<SanitizeJob>(`${SANITIZE}/jobs/${encodeURIComponent(jobId)}`, "GET");
export const getRevertJob = (revertId: string) => call<RevertJob>(`${SANITIZE}/reverts/${encodeURIComponent(revertId)}`, "GET");
/** The browser's own download (streamed to disk); the server serves each result once and then deletes it. */
export const sanitizeDownloadHref = (jobId: string) => `${SANITIZE}/jobs/${encodeURIComponent(jobId)}/download`;
export const revertDownloadHref = (revertId: string) => `${SANITIZE}/reverts/${encodeURIComponent(revertId)}/download`;

/**
 * Upload one file with progress (XMLHttpRequest: fetch reports no upload progress); the browser streams it from disk.
 * Form fields go before the file part, as the server reads them.
 */
export async function uploadForSanitizer(route: "sanitize" | "revert", file: File, fields: Record<string, string>,
  onProgress: (sent: number, total: number) => void,
  makeRequest: () => XMLHttpRequest = () => new XMLHttpRequest()): Promise<{ job_id?: string; revert_id?: string; state: string }> {
  const token = await csrfToken();
  const body = new FormData();
  for (const [name, value] of Object.entries(fields)) body.append(name, value);
  body.append("file", file, file.name);
  return new Promise((resolve, reject) => {
    const xhr = makeRequest();
    xhr.open("POST", route === "sanitize" ? SANITIZE : `${SANITIZE}/revert`);
    xhr.withCredentials = true;
    if (token) xhr.setRequestHeader("X-CSRF-Token", token);
    xhr.upload.onprogress = event => { if (event.lengthComputable) onProgress(event.loaded, event.total); };
    xhr.onload = () => {
      let parsed: Record<string, unknown> = {};
      try { parsed = JSON.parse(xhr.responseText || "{}"); } catch { parsed = {}; }
      if (xhr.status >= 200 && xhr.status < 300) resolve(parsed as { job_id?: string; revert_id?: string; state: string });
      else reject({ status: xhr.status, body: parsed } satisfies ApiError);
    };
    xhr.onerror = () => reject({ status: 0, body: { error: "NETWORK" } } satisfies ApiError);
    xhr.send(body);
  });
}
export interface PacketRef {
  readonly point: string;
  readonly file: string;
  readonly capture_id: string;
  readonly frame: number;
  readonly display_filter: string;
  readonly content_filter?: string | null;
  readonly flow_filter?: string | null;
  readonly filter_note?: string;
  readonly observed_time: number | null;
  readonly corrected_time: number | null;
  readonly vendor?: Record<string, string>;
  readonly byte_ranges?: readonly { readonly start_seq: number; readonly end_seq: number; readonly bytes: number }[];
  readonly range_note?: string;
  readonly sequence_translation?: {
    readonly observed_seq: number;
    readonly observed_ack: number;
    readonly canonical_seq: number;
    readonly canonical_ack: number;
    readonly seq_offset: number;
    readonly ack_offset: number;
    readonly reason: string | null;
  };
}

export interface PacketTopologyPoint {
  readonly id: string;
  readonly label: string;
  readonly device: string;
  readonly kind: string;
  readonly side: string;
  readonly stage_capture?: boolean;
  readonly clock_domain?: string | null;
  readonly host_capture?: boolean;
  readonly capture_id: string;
  readonly interface: number | null;
  readonly vendor?: string;
  readonly vendor_stage?: string | null;
  readonly vendor_interface?: string | null;
  readonly inspection_complete?: boolean;
  readonly matching_layer?: "inner" | "outer" | null;
  readonly source_cidr: string | null;
  readonly payload_transform?: "none" | "proxy" | "alg" | "ssl_inspection";
  readonly translation: string;
  readonly x: number;
  readonly y: number;
}

export interface PacketNatMapping {
  readonly point_a: string;
  readonly point_b: string;
  readonly tuple_a: string;
  readonly tuple_b: string;
  readonly samples?: number;
  readonly evidence?: readonly PacketRef[];
}

export interface PacketTopology {
  readonly points: readonly PacketTopologyPoint[];
  readonly forward: readonly string[];
  readonly reverse: readonly string[];
  readonly client_cidrs: readonly string[];
  readonly nat_mappings: readonly PacketNatMapping[];
  readonly clock_overrides?: Record<string, { readonly offset_ms: number; readonly drift_ppm: number }>;
  readonly min_eligible_ratio: number;
  readonly duplicate_us?: number;
  readonly stall_ms?: number;
  readonly match_window_ms?: number;
  readonly start?: number | null;
  readonly end?: number | null;
}

export interface PacketCaptureInventory {
  readonly id: string;
  readonly path: string;
  readonly name: string;
  readonly state: string;
  readonly checkpoint: number;
  readonly error: string | null;
  readonly inventory: {
    readonly warnings?: readonly string[];
    readonly timestamps_validated?: boolean;
    readonly start?: number | null;
    readonly end?: number | null;
    readonly duration?: number | null;
    readonly packet_count?: number;
    readonly prefilter_total?: number | null;
    readonly prefilter_retained?: number | null;
    readonly prefilter_excluded?: number;
    readonly prefilter_skipped?: boolean;
    readonly truncated?: number;
    readonly possible_offload?: number;
    readonly ifdrop?: number | null;
    readonly osdrop?: number | null;
    readonly format?: string;
  };
}

export interface PacketFinding {
  readonly id: string;
  readonly type: string;
  readonly severity: string;
  readonly hop: string;
  readonly direction: string;
  readonly time_range: readonly number[];
  readonly confidence: string;
  readonly summary: string;
  readonly headline?: string;
  readonly downstream_summary?: string;
  readonly downstream_count?: number;
  readonly chronic_loss?: boolean;
  readonly evidence: readonly PacketRef[];
  readonly evidence_note: string;
  readonly tooltip?: string;
  readonly metrics: Record<string, number>;
}

export interface PacketSegment {
  readonly id: string;
  readonly point_a: string;
  readonly point_b: string;
  readonly label: string;
  readonly location: string;
  readonly direction: string;
  readonly reason: string | null;
  readonly matched: number;
  readonly p50_ms: number | null;
  readonly p95_ms: number | null;
  readonly max_ms: number | null;
  readonly loss_percent: number | null;
  readonly eligible_packets: number;
  readonly eligible_ratio: number | null;
  readonly excluded_counts: Record<string, number>;
  readonly classes: Record<string, number>;
  readonly offset_uncertainty_ms: number | null;
  readonly latency_uncertainty_ms?: number;
  readonly quality_notes?: readonly string[];
  readonly loss_suspect?: boolean;
  readonly chronic_loss?: boolean;
  readonly symptom_note?: string;
  readonly headline?: string;
  readonly onset_status?: string;
  readonly onset_quality_notes?: { readonly capture_misses: number; readonly unknown_events: number; readonly reasons: readonly string[] };
  readonly onset_reasons?: readonly { readonly metric: string; readonly reason: string }[];
}

export interface PacketQualityPoint {
  readonly point: string;
  readonly file?: string;
  readonly ifdrop: number | null;
  readonly osdrop: number | null;
  readonly packets: number;
  readonly excluded: number;
  readonly prefilter_excluded?: number;
  readonly truncated: number;
  readonly possible_offload: number;
  readonly unknown_direction: number;
  readonly index_scope?: {
    readonly input_frames: number;
    readonly retained_frames: number;
    readonly input_flows: number;
    readonly retained_flows: number;
    readonly excluded_frames: number;
    readonly excluded_flows: number;
  };
  readonly evidence: readonly PacketRef[];
}

export interface PacketAnalysisReport {
  readonly verdict: string;
  readonly scope: string;
  readonly flow_count: number;
  readonly window: {
    readonly start: number | null;
    readonly end: number | null;
    readonly common_start: number | null;
    readonly common_end: number | null;
  };
  readonly clocks: Record<string, {
    readonly offset: number | null;
    readonly drift_ppm: number;
    readonly epoch: number;
    readonly uncertainty: number | null;
    readonly confidence: string;
    readonly reason: string;
    readonly samples: number;
    readonly evidence: readonly PacketRef[];
  }>;
  readonly coverage: readonly { readonly point: string; readonly start: number | null; readonly end: number | null }[];
  readonly quality: readonly PacketQualityPoint[];
  readonly nat_suggestions: readonly PacketNatMapping[];
  readonly segments: readonly PacketSegment[];
  readonly findings: readonly PacketFinding[];
  readonly limitations: readonly string[];
  readonly auto_order_suggestion?: { readonly points: readonly string[]; readonly reason: string };
  readonly onsets?: {
    readonly status: string;
    readonly summary: string;
    readonly directions?: Record<string, {
      readonly prime_suspects: readonly string[];
      readonly propagation_order: readonly { readonly time: number; readonly segments: readonly string[] }[];
      readonly caveat: string;
    }>;
    readonly items: readonly {
      readonly segment: string;
      readonly metric: string;
      readonly bucket: number;
      readonly time: number;
      readonly explanation: string;
      readonly scope?: string;
      readonly evidence: readonly PacketRef[];
    }[];
  };
  readonly cycles?: PacketCycleReport;
  readonly incident?: {
    readonly headline?: string;
    readonly quality_note?: string;
    readonly window?: { readonly start: number; readonly end: number } | null;
    readonly timeline?: readonly Record<string, number | null>[];
    readonly loss_groups?: readonly { readonly hop: string; readonly headline: string; readonly counts: Record<string, number>; readonly evidence?: readonly PacketRef[] }[];
    readonly quality?: readonly { readonly point_a: string; readonly point_b: string; readonly direction: string; readonly evidence: readonly PacketRef[] }[];
    readonly hold_release?: readonly {
      readonly id: string; readonly hop: string; readonly time_range: readonly number[]; readonly summary: string; readonly tooltip: string;
      readonly metrics: { readonly wire_spacing_ms: readonly number[]; readonly receive_spacing_ms: readonly number[] };
      readonly evidence: readonly PacketRef[];
    }[];
  };
  readonly host_path?: {
    readonly note?: string;
    readonly scope?: string;
    readonly window_reason?: string | null;
    readonly local_service?: { readonly p95_ms?: number | null; readonly cycle_aggregate?: { readonly p95_ms?: number | null } };
    readonly items?: readonly {
      readonly hop: string; readonly direction: string; readonly max_ms: number; readonly uncertainty_ms: number;
      readonly headline?: string; readonly delay_share_percent?: number; readonly affected_flow_count?: number;
      readonly window: { readonly start?: number; readonly end?: number; readonly recovery_confirmed?: boolean; readonly status?: string; readonly reason?: string | null };
      readonly periods?: readonly { readonly period: string; readonly p95_ms: number | null; readonly max_ms: number | null; readonly bytes: number }[];
      readonly flow_details?: readonly { readonly flow: string; readonly p95_ms: number | null; readonly max_ms: number | null; readonly reason?: string; readonly evidence: readonly PacketRef[] }[];
      readonly evidence: readonly PacketRef[];
    }[];
  };
  readonly delivery?: {
    readonly note: string;
    readonly series: readonly {
      readonly point_a: string; readonly point_b: string; readonly direction: string; readonly start: number;
      readonly delayed: number; readonly dropped: number; readonly capture_misses: number; readonly unknown: number;
      readonly max_delay_ms: number | null; readonly max_recovery_ms: number | null;
    }[];
  };
  readonly sessions?: {
    readonly note: string; readonly scope: string; readonly timeout_seconds: number;
    readonly points: readonly {
      readonly point: string; readonly label: string; readonly status: string; readonly syn_sent: number | null;
      readonly synack_seen: number | null; readonly established: number | null; readonly closed: number | null;
      readonly tooltip: string; readonly evidence: readonly PacketRef[];
    }[];
    readonly buckets: readonly {
      readonly point: string; readonly start: number; readonly end: number; readonly coverage: string;
      readonly syns: number | null; readonly synacks: number | null; readonly established: number | null;
      readonly closed: number | null; readonly half_open: number | null;
    }[];
    readonly failed_handshake_count: number;
    readonly failed_handshakes: readonly { readonly point_a: string; readonly point_b: string; readonly time: number; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
  };
  readonly security?: {
    readonly note: string;
    readonly thresholds?: Record<string, number>;
    readonly quality?: readonly { readonly point: string; readonly status: string; readonly reason: string }[];
  };
  readonly microbursts?: {
    readonly note: string; readonly total: number; readonly window_ms: number;
    readonly points: readonly {
      readonly point: string; readonly label: string; readonly peak_observed_mbps: number | null; readonly baseline_mbps: number;
      readonly threshold_mbps: number; readonly burst_count: number | null; readonly max_duration_ms: number | null;
      readonly frequency_hz: number | null; readonly tooltip: string; readonly evidence: readonly PacketRef[];
    }[];
    readonly items: readonly {
      readonly point: string; readonly start: number; readonly end: number; readonly corrected_start: number | null;
      readonly peak_mbps: number; readonly duration_ms: number | null; readonly min_interarrival_us: number | null;
      readonly tooltip: string; readonly evidence: readonly PacketRef[];
      readonly loss_associations: readonly { readonly segment: string; readonly ordering: string; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
    }[];
  };
  readonly application_status?: readonly { readonly point: string; readonly reason: string | null; readonly cached: boolean }[];
  readonly proxies?: {
    readonly note: string;
    readonly transaction_count: number;
    readonly transactions: readonly {
      readonly device: string; readonly kind: string; readonly request?: string; readonly status: string; readonly reason: string | null;
      readonly client_flow?: string | null; readonly server_flow?: string | null;
      readonly request_dwell?: { readonly duration_ms: number | null; readonly uncertainty_ms: number | null; readonly reason: string | null };
      readonly response_dwell?: { readonly duration_ms: number | null; readonly uncertainty_ms: number | null; readonly reason: string | null };
      readonly evidence: readonly PacketRef[];
    }[];
    readonly legs?: readonly { readonly point: string; readonly flow: string; readonly packets: number; readonly retransmissions: number; readonly resets: number; readonly loss_by_class: Record<string, number> }[];
    readonly reset_notes?: readonly { readonly device: string; readonly status: string; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
  };
  readonly tls?: readonly { readonly status: string; readonly device?: string | null; readonly point?: string; readonly sni: string; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
  readonly confirmed_device_drop_count?: number;
  readonly confirmed_device_drops?: readonly { readonly device: string; readonly stage: string; readonly count: number }[];
  readonly vendor_device_event_count?: number;
  readonly vendor_device_events?: readonly { readonly device: string; readonly stage: string; readonly status: string; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
  readonly f5?: {
    readonly note?: string;
    readonly connection_count?: number;
    readonly pairs: readonly { readonly flowid: string; readonly peerid: string; readonly tmm: string; readonly role: string; readonly reason: string | null; readonly client_flow?: string; readonly server_flow?: string }[];
    readonly requests: readonly { readonly device: string; readonly request: string; readonly request_dwell_ms: number | null; readonly clock_uncertainty_ms: number; readonly reason: string | null; readonly note: string; readonly evidence: readonly PacketRef[] }[];
    readonly resets: readonly { readonly device: string; readonly reason: string; readonly time: number | null; readonly evidence: readonly PacketRef[] }[];
  };
  readonly udp?: {
    readonly note: string; readonly scope: string; readonly total: number;
    readonly summary: readonly {
      readonly point: string; readonly protocol: string; readonly endpoint: string; readonly requests: number; readonly answered: number;
      readonly unanswered: number; readonly unknown: number; readonly no_response_expected: number;
      readonly p50_ms: number | null; readonly p95_ms: number | null; readonly max_ms: number | null;
    }[];
    readonly items: readonly {
      readonly point: string; readonly frame: number; readonly protocol: string; readonly status: string; readonly latency_ms: number | null;
      readonly hop: string | null; readonly reason: string; readonly tooltip: string; readonly evidence: readonly PacketRef[];
    }[];
  };
  readonly rtp?: {
    readonly note: string; readonly scope: string;
    readonly streams: readonly {
      readonly point: string; readonly flow: string; readonly direction: string; readonly ssrc: number; readonly packets: number; readonly gaps: number;
      readonly duplicates: number; readonly reordered: number; readonly jitter_p95_ms: number | null; readonly jitter_max_ms: number | null;
      readonly evidence: readonly PacketRef[];
    }[];
    readonly one_way: readonly { readonly point: string; readonly seen_direction: string; readonly reason: string; readonly stopping_hop: string | null; readonly evidence: readonly PacketRef[] }[];
  };
  readonly sip?: {
    readonly note: string; readonly scope: string; readonly total: number;
    readonly comparisons: readonly {
      readonly call_id: string; readonly hop: string; readonly status: string; readonly reason: string; readonly media_status?: string;
      readonly changes: Record<string, { readonly before: unknown; readonly after: unknown }>; readonly evidence: readonly PacketRef[]; readonly tooltip: string;
    }[];
    readonly one_way: readonly { readonly call_id: string; readonly hop: string; readonly reason: string; readonly evidence: readonly PacketRef[]; readonly tooltip: string }[];
  };
  readonly sequence_translations?: readonly {
    readonly ingress: string; readonly egress: string; readonly flow: string | null; readonly status: string; readonly reason: string | null;
    readonly boundary_forward_offset: number | null; readonly boundary_reverse_offset: number | null; readonly samples: number;
    readonly evidence: readonly PacketRef[];
  }[];
  readonly offload_points?: readonly { readonly point: string; readonly large_frames: number; readonly note: string }[];
  readonly fragment_points?: readonly { readonly point: string; readonly status: string; readonly datagrams: number; readonly physical_fragments: number }[];
  readonly tunnel_points?: readonly { readonly point: string; readonly selected: number; readonly unselected: number; readonly layer: string | null; readonly truncated_inner: number }[];
  readonly nexus_correlation?: PacketNexusCorrelation | null;
}

export interface PacketCycleReport {
  readonly note?: string;
  readonly scope?: string;
  readonly stall_ms?: number;
  readonly stalled_total?: number;
  readonly flow_ports?: readonly {
    readonly point: string; readonly flow: string; readonly port: number; readonly cycles: number; readonly stalled: number;
    readonly p95_ms: number | null; readonly max_ms: number | null; readonly before_p95_ms?: number | null;
    readonly event_p95_ms?: number | null; readonly recovery_p95_ms?: number | null; readonly per_hop_added?: string | null;
  }[];
  readonly periods?: readonly { readonly point: string; readonly flow: string; readonly period: string; readonly cycles: number; readonly p95_ms: number | null; readonly reason?: string }[];
  readonly pairs?: readonly { readonly hop: string; readonly component: string; readonly added_ms: number | null; readonly uncertainty_ms: number; readonly reason?: string }[];
  readonly top?: readonly {
    readonly point: string; readonly cycle_id?: string; readonly flow?: string; readonly port?: number; readonly request_first?: number;
    readonly period?: string; readonly cycle_ms?: number | null; readonly turnaround_ms?: number | null; readonly reason?: string;
    readonly evidence?: readonly PacketRef[];
  }[];
}

export type PacketNexusCorrelationStatus = "MATCH" | "AMBIGUOUS" | "NOT_EVALUABLE" | "NO_MATCH";

export interface PacketHopCorrelationCandidate {
  readonly device_id: string;
  readonly hostname: string | null;
  readonly cluster_member_ref?: string | null;
  readonly virtual_system?: string | null;
  readonly interface_id: string;
  readonly interface_name: string | null;
  readonly zone: string | null;
  readonly mac_address: string | null;
  readonly interface_addresses: readonly string[];
  readonly evidence: readonly string[];
  readonly matched_addresses: readonly string[];
  readonly reason: string;
}

export interface PacketHopCorrelation {
  readonly point_id: string;
  readonly point_label: string;
  readonly capture_id: string | null;
  readonly capture_name: string | null;
  readonly side: string;
  readonly status: PacketNexusCorrelationStatus;
  readonly reason: string;
  readonly evidence: readonly string[];
  readonly device_id?: string | null;
  readonly hostname?: string | null;
  readonly cluster_member_ref?: string | null;
  readonly virtual_system?: string | null;
  readonly interface_id?: string | null;
  readonly interface_name?: string | null;
  readonly zone?: string | null;
  readonly mac_address?: string | null;
  readonly interface_addresses?: readonly string[];
  readonly matched_addresses?: readonly string[];
  readonly capture_proves: {
    readonly point_id: string;
    readonly point_label: string;
    readonly capture_id: string | null;
    readonly capture_name: string | null;
    readonly side: string;
    readonly vendor_interface: string | null;
    readonly observed_macs: readonly string[];
    readonly observed_addresses: readonly string[];
  };
  readonly configured_inventory_says: Record<string, unknown> | null;
  readonly candidates: readonly PacketHopCorrelationCandidate[];
}

export interface PacketFlowCorrelationFirewallMatch {
  readonly device_id: string;
  readonly hostname: string | null;
  readonly cluster_member_ref?: string | null;
  readonly ingress_interface_id?: string | null;
  readonly ingress_interface_name?: string | null;
  readonly source_zone?: string | null;
  readonly egress_interface_id?: string | null;
  readonly egress_interface_name?: string | null;
  readonly destination_zone?: string | null;
  readonly status: PacketNexusCorrelationStatus;
  readonly reason: string;
  readonly evidence: readonly string[];
  readonly policy_id?: string | null;
  readonly policy_name?: string | null;
  readonly policy_status?: string | null;
  readonly policy_stale?: boolean;
  readonly policy_incomplete?: boolean;
  readonly rule_id?: string | null;
  readonly rule_number?: number | null;
  readonly rule_name?: string | null;
  readonly section_id?: string | null;
  readonly section_name?: string | null;
  readonly action?: string | null;
  readonly matched_source_objects?: readonly string[];
  readonly matched_destination_objects?: readonly string[];
  readonly matched_service_objects?: readonly string[];
  readonly evaluated_src?: string | null;
  readonly evaluated_sport?: number | null;
  readonly evaluated_dst?: string | null;
  readonly evaluated_dport?: number | null;
  readonly evaluated_proto?: string | null;
  readonly nat_applied?: boolean;
}

export interface PacketFlowCorrelation {
  readonly flow: string;
  readonly tuple: string;
  readonly proto: string;
  readonly src: string;
  readonly sport: number | null;
  readonly dst: string;
  readonly dport: number | null;
  readonly nat_confirmed: boolean;
  readonly pre_nat_src: string;
  readonly pre_nat_sport: number | null;
  readonly pre_nat_dst: string;
  readonly pre_nat_dport: number | null;
  readonly post_nat_src: string;
  readonly post_nat_sport: number | null;
  readonly post_nat_dst: string;
  readonly post_nat_dport: number | null;
  readonly status: PacketNexusCorrelationStatus;
  readonly reason: string;
  readonly evidence: readonly string[];
  readonly device_id?: string | null;
  readonly hostname?: string | null;
  readonly cluster_member_ref?: string | null;
  readonly ingress_interface_id?: string | null;
  readonly ingress_interface_name?: string | null;
  readonly source_zone?: string | null;
  readonly egress_interface_id?: string | null;
  readonly egress_interface_name?: string | null;
  readonly destination_zone?: string | null;
  readonly policy_id?: string | null;
  readonly policy_name?: string | null;
  readonly policy_status?: string | null;
  readonly policy_stale?: boolean;
  readonly policy_incomplete?: boolean;
  readonly rule_id?: string | null;
  readonly rule_number?: number | null;
  readonly rule_name?: string | null;
  readonly section_id?: string | null;
  readonly section_name?: string | null;
  readonly action?: string | null;
  readonly capture_proves: {
    readonly flow: string;
    readonly tuple: string;
    readonly proto: string;
    readonly pre_nat_src: string;
    readonly pre_nat_sport: number | null;
    readonly pre_nat_dst: string;
    readonly pre_nat_dport: number | null;
    readonly nat_confirmed: boolean;
    readonly post_nat_src: string;
    readonly post_nat_sport: number | null;
    readonly post_nat_dst: string;
    readonly post_nat_dport: number | null;
    readonly observed_point_ids: readonly string[];
  };
  readonly configured_policy_says: Record<string, unknown> | null;
  readonly candidates: readonly PacketFlowCorrelationFirewallMatch[];
  readonly firewall_matches: readonly PacketFlowCorrelationFirewallMatch[];
}

export interface PacketNexusCorrelation {
  readonly case_id: string;
  readonly correlated_at: string;
  readonly inventory_status: string;
  readonly inventory_reason: string;
  readonly policy_snapshot_count: number;
  readonly evidence_separation_note: string;
  readonly hops: readonly PacketHopCorrelation[];
  readonly flows: readonly PacketFlowCorrelation[];
}

export interface PacketCaseWorkspaceState {
  readonly version: string;
  readonly project: string;
  readonly captures: readonly PacketCaptureInventory[];
  readonly topology: PacketTopology;
  readonly report: PacketAnalysisReport | null;
  readonly suggested_topology?: Record<string, unknown> | null;
  readonly nexus_correlation?: PacketNexusCorrelation | null;
}

export interface PacketFlowItem {
  readonly flow: string;
  readonly tuple: string;
  readonly start: number | null;
  readonly end: number | null;
  readonly bytes: number;
  readonly points: number;
  readonly confirmed_device_drop: number;
  readonly impactful_loss: number;
  readonly recovered_loss: number;
  readonly unrecovered_loss: number;
  readonly handshake_blocked: number;
  readonly unknown_events: number;
  readonly capture_miss: number;
  readonly retrans_observations: number;
  readonly max_stall_ms: number | null;
  readonly max_rtt_ms: number | null;
  readonly has_reset: boolean;
  readonly handshake_incomplete: boolean;
  readonly reset_origin?: string | null;
  readonly handshake_rtt_ms?: number | null;
  readonly matching_unknown_reason?: string | null;
}

export interface PacketFlowPage {
  readonly total: number;
  readonly items: readonly PacketFlowItem[];
}

export interface PacketLadderView {
  readonly total: number;
  readonly flow_filters?: readonly { readonly capture_id: string; readonly file: string; readonly display_filter: string }[];
  readonly local_metrics?: readonly {
    readonly point: string;
    readonly tuple: string;
    readonly retransmissions: number;
    readonly zero_windows: number;
    readonly resets: number;
    readonly max_rtt_ms: number | null;
    readonly handshake: {
      readonly syn_frame: number;
      readonly synack_frame: number;
      readonly syn_to_synack_ms: number;
      readonly synack_to_ack_ms: number | null;
    } | null;
  }[];
  readonly items: readonly {
    readonly packet_key: string;
    readonly ts: number | null;
    readonly retrans: boolean;
    readonly seq: number;
    readonly ack: number;
    readonly flags: number;
    readonly length: number;
    readonly direction: string;
    readonly evidence: readonly PacketRef[];
  }[];
  readonly events: readonly { readonly point_b: string; readonly kind: string; readonly ts: number; readonly packet_key: string }[];
}

export interface PacketWaterfallBar {
  readonly label: string;
  readonly kind: string;
  readonly duration_ms: number | null;
  readonly start_ms: number | null;
  readonly uncertainty_ms: number | null;
  readonly reason: string | null;
  readonly note: string;
  readonly evidence: readonly PacketRef[];
}

export interface PacketWaterfallView {
  readonly items: readonly { readonly kind: string; readonly title: string; readonly bars: readonly PacketWaterfallBar[]; readonly note?: string }[];
  readonly requests: readonly { readonly index: number; readonly label: string; readonly time: number | null }[];
  readonly reason: string | null;
}

export interface PacketDiagnosisItem {
  readonly flow: string;
  readonly direction: string;
  readonly classification: string;
  readonly reason: string;
  readonly tooltip: string;
  readonly evidence: readonly PacketRef[];
  readonly metrics: {
    readonly max_receive_window: number | null;
    readonly max_bytes_in_flight: number | null;
    readonly zero_windows: number;
    readonly window_full: number;
    readonly window_shrinks: number;
    readonly ack_delay_p95_ms: number | null;
    readonly path_p95_ms: number | null;
    readonly service_ms: number | null;
    readonly loss_count: number;
  };
}

export interface PacketRetryPoint {
  readonly flow: string;
  readonly point: string;
  readonly direction: string;
  readonly tooltip: string;
  readonly reason: string | null;
  readonly rto: number | null;
  readonly fast: number | null;
  readonly spurious: number | null;
  readonly sack: number | null;
  readonly unknown: number | null;
  readonly duplicate_acks: number | null;
}

export interface PacketDiagnosisView {
  readonly items: readonly PacketDiagnosisItem[];
  readonly reason: string | null;
  readonly note?: string;
  readonly retransmissions?: {
    readonly points: readonly PacketRetryPoint[];
    readonly segments: readonly (PacketRetryPoint & { readonly hop: string })[];
    readonly events: readonly { readonly reason: string; readonly retry_kind: string; readonly point: string; readonly evidence: readonly PacketRef[] }[];
    readonly reordering: readonly { readonly location: string; readonly hop: string; readonly tooltip: string; readonly reason: string; readonly evidence: readonly PacketRef[] }[];
    readonly note: string;
  };
}

export interface PacketStreamChartsView {
  readonly point?: string;
  readonly direction?: string;
  readonly aligned?: boolean;
  readonly points: readonly { readonly point: string; readonly label: string }[];
  readonly packets: readonly {
    readonly frame: number;
    readonly time: number;
    readonly kind: string;
    readonly sequence: number | null;
    readonly length: number;
    readonly window_edge: number | null;
    readonly retransmission: boolean | null;
  }[];
  readonly buckets: readonly {
    readonly time: number;
    readonly throughput_bps: number | null;
    readonly rtt_ms: number | null;
    readonly data_frame: number | null;
    readonly rtt_frame: number | null;
    readonly coverage: string;
  }[];
  readonly note?: string;
  readonly reason: string | null;
  readonly sampling_note?: string;
  readonly clock_uncertainty_ms?: number | null;
}

export interface PacketTimeseriesView {
  readonly start: number;
  readonly end: number;
  readonly bucket_seconds: number;
  readonly bucket_count: number;
  readonly metrics: Record<string, { readonly label: string; readonly unit: string; readonly tooltip: string }>;
  readonly items: readonly ({
    readonly segment: string;
    readonly direction: string;
    readonly bucket: number;
    readonly start: number;
    readonly end: number;
    readonly coverage: string;
    readonly reason: string | null;
  } & Record<string, number | string | null>)[];
}

export interface PacketTopologyOverlayView {
  readonly segments: readonly {
    readonly id: string;
    readonly loss_percent: number | null;
    readonly p95_ms: number | null;
    readonly retrans_observations: number;
    readonly reason: string | null;
    readonly classes: Record<string, number>;
  }[];
  readonly nodes: readonly {
    readonly device: string;
    readonly observations: number;
    readonly retrans_observations: number;
    readonly supported_loss_events: number;
    readonly confirmed_device_drops: number;
    readonly findings: number;
  }[];
  readonly note: string;
}

export interface PacketTopologyInspectView {
  readonly findings: readonly PacketFinding[];
  readonly flows: PacketFlowPage;
}

export interface PacketEventPageView {
  readonly total: number;
  readonly items: readonly {
    readonly id: string;
    readonly kind: string;
    readonly reason: string;
    readonly ts: number | null;
    readonly recovery_ms: number | null;
    readonly evidence: readonly PacketRef[];
  }[];
}

export interface PacketDnsView {
  readonly items: readonly {
    readonly point: string;
    readonly frame: number;
    readonly question: string | null;
    readonly resolver: string;
    readonly status: string;
    readonly reason: string;
    readonly tooltip: string;
    readonly latency_ms: number | null;
    readonly location?: string;
    readonly evidence: readonly PacketRef[];
    readonly corrected: number | null;
  }[];
  readonly total: number;
  readonly summary: readonly {
    readonly point: string;
    readonly resolver: string;
    readonly queries: number;
    readonly matchable_queries: number;
    readonly answered: number;
    readonly timed_out_queries: number;
    readonly unknown_queries: number;
    readonly p50_ms: number | null;
    readonly p95_ms: number | null;
    readonly max_ms: number | null;
  }[];
  readonly points: readonly { readonly point: string; readonly label: string; readonly kind: string }[];
  readonly timeout_ms?: number;
  readonly tooltip: string;
  readonly note?: string;
  readonly reason: string | null;
}

export interface PacketFieldDiffView {
  readonly note: string;
  readonly items: readonly {
    readonly location: string;
    readonly tooltip: string;
    readonly packet_key: string;
    readonly fields: Record<string, { readonly before: unknown; readonly after: unknown; readonly status: string }>;
    readonly evidence: readonly PacketRef[];
  }[];
}

function paQuery(params: Record<string, string | number | boolean | null | undefined | readonly (string | number)[]>): string {
  const search = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "") continue;
    if (Array.isArray(v)) {
      for (const item of v) search.append(k, String(item));
    } else {
      search.set(k, String(v));
    }
  }
  const s = search.toString();
  return s ? `?${s}` : "";
}

export const getPacketCaseState = (caseId: string) =>
  call<PacketCaseWorkspaceState>(`${PA}/cases/${encodeURIComponent(caseId)}/state`, "GET");
export const getPacketCaseCorrelation = (caseId: string) =>
  call<PacketNexusCorrelation>(`${PA}/cases/${encodeURIComponent(caseId)}/correlation`, "GET");
export const getPacketCaseFlows = (caseId: string, params: {
  offset?: number; limit?: number; search?: string; filter?: string; sort?: string; start?: number | null; end?: number | null;
} = {}) => call<PacketFlowPage>(`${PA}/cases/${encodeURIComponent(caseId)}/flows${paQuery(params)}`, "GET");
export const getPacketCaseFrames = (caseId: string, params: {
  flow: string; point: string; frames: readonly number[];
}) => call<readonly PacketRef[]>(`${PA}/cases/${encodeURIComponent(caseId)}/frames${paQuery(params)}`, "GET");
export const getPacketCaseDiagnosis = (caseId: string, flow: string) =>
  call<PacketDiagnosisView>(`${PA}/cases/${encodeURIComponent(caseId)}/diagnosis${paQuery({ flow })}`, "GET");
export const getPacketCaseCharts = (caseId: string, params: {
  flow: string; point?: string; direction?: string; start?: number | null; end?: number | null; local?: boolean;
}) => call<PacketStreamChartsView>(`${PA}/cases/${encodeURIComponent(caseId)}/charts${paQuery(params)}`, "GET");
export const getPacketCaseLadder = (caseId: string, params: {
  flow: string; offset?: number; limit?: number; start?: number | null; end?: number | null;
}) => call<PacketLadderView>(`${PA}/cases/${encodeURIComponent(caseId)}/ladder${paQuery(params)}`, "GET");
export const getPacketCaseWaterfall = (caseId: string, params: {
  flow: string; request_index?: number; start?: number | null; end?: number | null;
}) => call<PacketWaterfallView>(`${PA}/cases/${encodeURIComponent(caseId)}/waterfall${paQuery(params)}`, "GET");
export const getPacketCaseTimeseries = (caseId: string, params: {
  segment?: string; start?: number | null; end?: number | null;
} = {}) => call<PacketTimeseriesView>(`${PA}/cases/${encodeURIComponent(caseId)}/timeseries${paQuery(params)}`, "GET");
export const getPacketCaseTopologyOverlay = (caseId: string, params: {
  start?: number | null; end?: number | null;
} = {}) => call<PacketTopologyOverlayView>(`${PA}/cases/${encodeURIComponent(caseId)}/topology/overlay${paQuery(params)}`, "GET");
export const getPacketCaseTopologyInspect = (caseId: string, params: {
  point?: string; segment?: string; start?: number | null; end?: number | null;
}) => call<PacketTopologyInspectView>(`${PA}/cases/${encodeURIComponent(caseId)}/topology/inspect${paQuery(params)}`, "GET");
export const getPacketCaseFindings = (caseId: string, params: {
  hop?: string; start?: number | null; end?: number | null;
} = {}) => call<{ readonly items: readonly PacketFinding[] }>(`${PA}/cases/${encodeURIComponent(caseId)}/findings${paQuery(params)}`, "GET");
export const getPacketCaseEvents = (caseId: string, params: {
  point_a: string; point_b: string; direction: string; offset?: number; limit?: number; start?: number | null; end?: number | null;
}) => {
  // The service (like the reference /api/events) names the boundary points `a` and `b`.
  const { point_a, point_b, ...rest } = params;
  return call<PacketEventPageView>(
    `${PA}/cases/${encodeURIComponent(caseId)}/events${paQuery({ a: point_a, b: point_b, ...rest })}`, "GET");
};
export const getPacketCaseDns = (caseId: string, params: {
  offset?: number; limit?: number; point?: string; resolver?: string; start?: number | null; end?: number | null;
} = {}) => call<PacketDnsView>(`${PA}/cases/${encodeURIComponent(caseId)}/dns${paQuery(params)}`, "GET");
export const getPacketCaseFieldDiff = (caseId: string, packetKey: string) =>
  call<PacketFieldDiffView>(`${PA}/cases/${encodeURIComponent(caseId)}/field-diff${paQuery({ packet_key: packetKey })}`, "GET");
export const getPacketCaseFindingsExport = (caseId: string) =>
  call<Record<string, unknown>>(`${PA}/cases/${encodeURIComponent(caseId)}/export/findings`, "GET");
export const savePacketCaseTopology = (caseId: string, topology: PacketTopology) =>
  call<PacketCaseWorkspaceState>(`${PA}/cases/${encodeURIComponent(caseId)}/topology`, "PUT", topology);
export type PacketExportFormat = "html" | "json" | "csv" | "xlsx";
/** Browser download of the engine export (raw-view roles only; the server ledgers it before the first byte). */
export const packetCaseExportHref = (caseId: string, format: PacketExportFormat) =>
  `${PA}/cases/${encodeURIComponent(caseId)}/export/download${paQuery({ format })}`;
/**
 * Browser download of the expert report workbook (Özet, Zaman Çizelgesi, Hop Analizi, Örnek Olaylar, Yöntem ve Veri).
 * Every packet-analysis reader may use it: raw-view roles get the engine values, every other reader (the replay viewer
 * included) the masked projection -- the server decides, the page never does. {@code start}/{@code end} (epoch seconds)
 * set the event window; without them the engine's first onset splits before and after.
 */
export const packetCaseExpertReportHref = (
  caseId: string,
  params: { start?: number | null; end?: number | null; top?: number } = {},
) => `${PA}/cases/${encodeURIComponent(caseId)}/export/expert-report${paQuery(params)}`;
export const getPacketCaseSessionEvidence = (caseId: string, params: { point: string; start: number; end: number }) =>
  call<{ readonly evidence: readonly PacketRef[]; readonly note: string }>(
    `${PA}/cases/${encodeURIComponent(caseId)}/sessions/evidence${paQuery(params)}`, "GET");
export const getPacketCaseReferenceTemplate = (caseId: string) =>
  call<Record<string, unknown>>(`${PA}/cases/${encodeURIComponent(caseId)}/reference/template`, "GET");
export interface PacketCompareResult {
  readonly items?: readonly {
    readonly id: string; readonly description: string; readonly metric: string;
    readonly expected: unknown; readonly value: unknown; readonly status: string; readonly reason?: string | null;
  }[];
  readonly counts?: Record<string, number>;
  readonly diff?: {
    readonly metrics: readonly unknown[]; readonly findings: readonly unknown[];
    readonly metric_difference_count: number; readonly truncated: boolean; readonly note: string;
  };
}
export const comparePacketCase = (caseId: string, input: { reference?: unknown; against?: unknown }) =>
  call<PacketCompareResult>(`${PA}/cases/${encodeURIComponent(caseId)}/compare`, "POST", input);


// ---------------------------------------------------------------------------------------------------------------
// System logs, phase B read side (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 14). The server decides what a session
// may see: `can_view_unmasked` is a flag the server computes, and every text page says whether it was masked.
// ---------------------------------------------------------------------------------------------------------------

export interface SystemLogSource {
  readonly source_key: string;
  /** OK, BACKLOG, GAP, UNKNOWN, or another outcome word (ABSENT, UNSUPPORTED, TOO_LARGE, STORAGE_REFUSED). */
  readonly status: string;
  readonly last_outcome: string | null;
  readonly last_run_at: string | null;
  readonly last_job_id: string | null;
  readonly last_bytes_read: number | null;
  readonly last_bytes_stored: number | null;
  readonly pending_bytes: number;
  readonly fast_rotating: boolean;
  readonly retention_horizon_seconds: number | null;
  readonly cursor: { readonly offset_bytes: number | null; readonly device_epoch_at_read: number | null; readonly pan_last_receive_time: string | null; readonly updated_at: string | null } | null;
  readonly stored: { readonly chunks: number; readonly bytes: number; readonly lines: number; readonly newest_chunk_at: string | null };
}

export interface SystemLogSources {
  readonly device_id: string;
  /**
   * `DISABLED_ON_MANAGEMENT` and `VENDOR_UNSUPPORTED` are answers (200, no sources, with `code` and `message`), not
   * errors: the device is not one the collection covers. A GET never answers 409 for that.
   */
  readonly collection_state: "NO_COLLECTION_YET" | "COLLECTED" | "DISABLED_ON_MANAGEMENT" | "VENDOR_UNSUPPORTED";
  /** Present with the two "does not apply" states: the stable code and a sentence. */
  readonly code?: string;
  readonly message?: string;
  readonly can_view_unmasked: boolean;
  readonly default_page_bytes: number;
  readonly max_page_bytes: number;
  readonly last_job: {
    readonly job_id: string;
    readonly at: string | null;
    readonly outcomes: Record<string, number>;
    readonly window_from: string | null;
    readonly window_to: string | null;
    readonly window_time_basis: string;
  } | null;
  readonly sources: readonly SystemLogSource[];
}

export interface SystemLogChunk {
  readonly chunk_id: string;
  readonly source_key: string;
  readonly job_id: string;
  readonly collected_at: string | null;
  readonly log_day: string;
  readonly start_offset: number;
  readonly end_offset: number;
  readonly bytes: number;
  readonly lines: number;
  readonly first_line_time: string | null;
  readonly last_line_time: string | null;
  readonly line_time_basis: string;
  readonly redaction_count: number;
  readonly redaction_rule_set: string | null;
  readonly flags: readonly string[];
  readonly stored_bytes: number;
  readonly outcome: string | null;
  readonly pruned: boolean;
  readonly prune_reason: string | null;
}

export interface SystemLogChunks {
  readonly device_id: string;
  readonly source: string | null;
  readonly day: string | null;
  readonly truncated: boolean;
  readonly chunks: readonly SystemLogChunk[];
}

export interface SystemLogGap {
  readonly gap_id: string;
  readonly device_id: string;
  readonly source_key: string;
  readonly job_id: string;
  readonly kind: string;
  readonly known_lost_bytes: number;
  readonly pending_bytes: number;
  readonly extent_unknown: boolean;
  readonly window_start_epoch: number | null;
  readonly window_end_epoch: number | null;
  readonly recorded_at: string | null;
}

export interface SystemLogCrashFile {
  readonly entry_id: string;
  readonly device_id: string;
  readonly directory: string;
  readonly process: string;
  readonly size: number;
  readonly mtime_epoch: number;
  readonly state: "NEW" | "PRESENT" | "PRESENT_BEFORE_MONITORING" | "REMOVED";
  readonly first_seen_at: string | null;
  readonly removed_at: string | null;
}

export interface SystemLogCrashFiles {
  readonly device_id: string | null;
  readonly new_window_days: number;
  readonly counts: Record<string, number>;
  readonly truncated: boolean;
  readonly files: readonly SystemLogCrashFile[];
  readonly alerts: readonly { readonly alert_id: string; readonly process: string; readonly file_count: number; readonly newest_mtime_epoch: number; readonly recorded_at: string | null }[];
}

export interface SystemLogHcpFile {
  readonly file_id: string;
  readonly name_time: string | null;
  readonly size: number;
  readonly mtime_epoch: number;
  readonly status: string;
  readonly flags: readonly string[];
  readonly collected_at: string | null;
  readonly expires_at: string | null;
  readonly pruned: boolean;
  readonly stored_bytes: number;
}

export interface SystemLogHcp {
  readonly device_id: string;
  /** NOT_AVAILABLE until the service can list archive members behind the masking projection. */
  readonly member_listing: string;
  readonly files: readonly SystemLogHcpFile[];
}

/** One member-name class of a Spike Detective data directory (a class, never a name). */
export interface SystemLogSpikeMemberClass {
  readonly type: string;
  readonly class: string;
  readonly count: number;
  readonly bytes: number;
  readonly fetched: number;
}

export interface SystemLogSpikeDirectory {
  readonly directory_id: string;
  readonly name_class: string;
  readonly mtime_epoch: number;
  /** COLLECTED, NOTHING_TO_FETCH, TOO_LARGE, NAME_REJECTED or ABSENT (baseline rows are only counted). */
  readonly state: string;
  readonly member_count: number;
  readonly fetched_files: number;
  readonly member_bytes: number;
  readonly fetched_bytes: number;
  readonly member_classes: readonly SystemLogSpikeMemberClass[];
  readonly recorded_at: string | null;
  readonly expires_at: string | null;
  readonly pruned: boolean;
  readonly stored_bytes: number | null;
}

export interface SystemLogSpikeData {
  readonly device_id: string;
  readonly enabled: boolean;
  readonly retention_days?: number;
  /** Directory rows per state, BASELINE ("present before monitoring") included. */
  readonly counts: Record<string, number>;
  readonly truncated?: boolean;
  readonly directories: readonly SystemLogSpikeDirectory[];
}

export interface SystemLogTextPage {
  readonly text: string;
  /** What the server did, from its own header: never what the browser asked for. */
  readonly masked: boolean;
  readonly source: string;
  readonly offset: number;
  readonly next_offset: number;
  readonly eof: boolean;
  readonly lines: number;
  /** The masking rule set a masked page went through (`X-Syslog-Mask-Rules`, e.g. `logmask-v3`); null when unmasked or not sent. */
  readonly mask_rules: string | null;
}

const syslogBase = (deviceId: string) => `/api/v2/system-logs/${encodeURIComponent(deviceId)}`;

export const getSystemLogSources = (deviceId: string) => call<SystemLogSources>(`${syslogBase(deviceId)}/sources`, "GET");

export function getSystemLogChunks(deviceId: string, source?: string, day?: string): Promise<SystemLogChunks> {
  const q = new URLSearchParams();
  if (source) q.set("source", source);
  if (day) q.set("day", day);
  const qs = q.toString();
  return call<SystemLogChunks>(`${syslogBase(deviceId)}/chunks${qs ? `?${qs}` : ""}`, "GET");
}

export const getSystemLogGaps = (deviceId: string) =>
  call<{ readonly device_id: string | null; readonly truncated: boolean; readonly gaps: readonly SystemLogGap[] }>(
    `/api/v2/system-logs/gaps?deviceId=${encodeURIComponent(deviceId)}`, "GET");

export const getSystemLogCrashFiles = (deviceId: string) =>
  call<SystemLogCrashFiles>(`/api/v2/system-logs/crash-files?deviceId=${encodeURIComponent(deviceId)}`, "GET");

export const getSystemLogHcp = (deviceId: string) => call<SystemLogHcp>(`${syslogBase(deviceId)}/hcp`, "GET");

export const getSystemLogSpikeData = (deviceId: string) => call<SystemLogSpikeData>(`${syslogBase(deviceId)}/spike-data`, "GET");

// ---------------------------------------------------------------------------------------------------------------
// Operations › System health (`GET /api/v2/system-logs/health`, `syslog-health`; docs/design/SYSTEM_HEALTH_SCREEN_2026_10_20.md).
// Counters and state words over what the system-log programme already stores; the server computes a snapshot in the
// background and says so (`computation.state`): COMPUTING until the first snapshot, REFRESHING while a stale one is served.
// ---------------------------------------------------------------------------------------------------------------

export type HealthFactLevel = "CRITICAL" | "ATTENTION" | "NORMAL" | "UNKNOWN" | "NOT_APPLICABLE";
export type HealthState = "CRITICAL" | "ATTENTION" | "NORMAL" | "UNKNOWN";

export interface SystemHealthSarDay {
  readonly day: string;
  readonly status: "PARSED" | "UNREADABLE" | "CORRUPT" | string;
  readonly p95_percent: number | null;
  readonly max_percent: number | null;
  readonly samples: number | null;
  readonly cpus: number | null;
  readonly unparsed_rows: number | null;
}

export interface SystemHealthDevice {
  readonly device_id: string;
  readonly hostname: string | null;
  readonly vendor_hint: string;
  readonly role: string;
  readonly cluster_member_ref: string | null;
  readonly monitored: boolean;
  readonly state: HealthState;
  readonly state_reason: string | null;
  readonly cpu: {
    readonly level: HealthFactLevel; readonly reason: string | null; readonly threshold_percent: number;
    readonly newest_p95_percent: number | null; readonly days_over_threshold: number; readonly longest_run_over_threshold: number;
    readonly days: readonly SystemHealthSarDay[]; readonly day_basis: string;
  };
  readonly memory: { readonly level: "EVIDENCED" | "UNKNOWN" | "NOT_APPLICABLE"; readonly reason: string | null; readonly newest_used_p95_percent: number | null };
  readonly restarts: {
    readonly level: HealthFactLevel; readonly reason: string | null; readonly count: number | null; readonly max_in_one_hour: number | null;
    readonly termination_lines: number | null; readonly busiest_hour: string | null; readonly rule: string;
  };
  readonly crash: { readonly level: HealthFactLevel; readonly reason: string | null; readonly new_alerts: number | null; readonly newest_alert_at: string | null };
  readonly collection: {
    readonly state: "OK" | "FAILED" | "UNSUPPORTED" | "OFF" | "NEVER"; readonly reason: string | null;
    readonly last_job_at: string | null; readonly last_job_id: string | null; readonly outcomes: Readonly<Record<string, string>>;
  };
  readonly logs_day: string | null;
}

export interface SystemHealth {
  readonly computation: { readonly state: "COMPUTING" | "REFRESHING" | "READY"; readonly computed_at: string | null; readonly duration_ms: number | null; readonly snapshot_ttl_seconds: number; readonly store_available: boolean };
  readonly window_days: number;
  readonly cpu_p95_threshold_percent: number;
  readonly time_basis: string;
  readonly rules: {
    readonly critical: readonly string[]; readonly attention: readonly string[]; readonly normal: string; readonly unknown: string;
    readonly state_order: readonly HealthState[]; readonly p95: string;
    readonly restart_rule: { readonly id: string; readonly status: string; readonly text: string };
  };
  readonly kpis: {
    readonly monitored: { readonly devices: number; readonly by_vendor: Readonly<Record<string, number>>; readonly not_collected_listed: number } | null;
    readonly crash_alerts: { readonly devices: number; readonly alerts: number; readonly truncated: boolean } | null;
    readonly cpu_over_threshold: { readonly devices: number; readonly evidenced: number; readonly unknown: number; readonly not_applicable: number; readonly basis: string } | null;
    readonly collection_not_ok: { readonly devices: number; readonly by_state: Readonly<Record<string, number>> } | null;
  };
  readonly devices: readonly SystemHealthDevice[];
}

export const getSystemHealth = () => call<SystemHealth>("/api/v2/system-logs/health", "GET");

// ---------------------------------------------------------------------------------------------------------------
// System-log collection settings (contract section 14, `GET/PUT /api/v2/config/system-logs`; Administration › Platform ›
// System logs). One row: retention, caps and the two switches. `can_write` is the server's own decision about this
// session; the screen offers edit controls only when it is true and never decides that itself.
// ---------------------------------------------------------------------------------------------------------------

export const SYSTEM_LOG_SETTING_KEYS = [
  "raw_retention_days", "device_cap_bytes", "hcp_retention_days", "spike_data_retention_days", "spike_data_cap_bytes",
  "ledger_retention_days", "include_management_servers", "spike_data_enabled",
] as const;
export type SystemLogSettingKey = (typeof SYSTEM_LOG_SETTING_KEYS)[number];

export interface SystemLogSettingsView {
  readonly raw_retention_days: number;
  readonly device_cap_bytes: number;
  readonly hcp_retention_days: number;
  readonly spike_data_retention_days: number;
  readonly spike_data_cap_bytes: number;
  readonly ledger_retention_days: number;
  readonly include_management_servers: boolean;
  readonly spike_data_enabled: boolean;
  /** When the row was last written (the database clock), or null before any write. */
  readonly updated_at: string | null;
  /** Who wrote it last: an actor fingerprint, or the migration that seeded the defaults. */
  readonly updated_by: string | null;
  /** Whether this session may change the row (holds the write action and is not the aiview persona). */
  readonly can_write: boolean;
  /** The service's own bounds per key, so the form limits what it offers. */
  readonly bounds: Readonly<Record<SystemLogSettingKey, { readonly type: "integer" | "boolean"; readonly min?: number; readonly max?: number }>>;
}

/** Any subset of the documented keys; the service merges it over the stored row and refuses the whole body on any problem. */
export type SystemLogSettingsPatch = Partial<Pick<SystemLogSettingsView, SystemLogSettingKey>>;

export const getSystemLogSettings = () => call<SystemLogSettingsView>("/api/v2/config/system-logs", "GET");
export const saveSystemLogSettings = (patch: SystemLogSettingsPatch) =>
  call<SystemLogSettingsView>("/api/v2/config/system-logs", "PUT", patch);

/**
 * One page of a chunk's text. The page is `text/plain` with its paging facts in `X-Syslog-*` headers; a response
 * without them (a proxy that stripped them, a truncated body) is an error, never an empty page that reads as the end.
 */
export async function getSystemLogText(chunkId: string, options: { readonly offset?: number; readonly limit?: number; readonly unmasked?: boolean } = {}): Promise<SystemLogTextPage> {
  const q = new URLSearchParams({ offset: String(options.offset ?? 0) });
  if (options.limit) q.set("limit", String(options.limit));
  if (options.unmasked) q.set("unmasked", "true");
  const response = await fetchWithTimeout(`/api/v2/system-logs/chunks/${encodeURIComponent(chunkId)}/text?${q.toString()}`,
    { method: "GET", credentials: "include" }, 30_000);
  noteMasking(response);
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    const error: ApiError = { status: response.status, body };
    throw error;
  }
  const text = await response.text();
  const h = (name: string) => response.headers.get(name);
  const next = Number(h("X-Syslog-Next-Offset"));
  const masked = h("X-Syslog-Masked");
  if (h("X-Syslog-Eof") === null || masked === null || !Number.isFinite(next)) {
    throw { status: 200, body: { error: "SYSTEM_LOG_TEXT_HEADERS_MISSING" } } satisfies ApiError;
  }
  return {
    text, masked: masked === "true", source: h("X-Syslog-Source") ?? "", offset: Number(h("X-Syslog-Offset") ?? options.offset ?? 0),
    next_offset: next, eof: h("X-Syslog-Eof") === "true", lines: Number(h("X-Syslog-Lines") ?? 0),
    mask_rules: h("X-Syslog-Mask-Rules"),
  };
}
