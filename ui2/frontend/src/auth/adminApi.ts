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

async function csrfToken(): Promise<string | undefined> {
  try {
    const response = await fetch("/session/status", { credentials: "include" });
    const body = await response.json();
    return typeof body?.csrf_token === "string" ? body.csrf_token : undefined;
  } catch {
    return undefined;
  }
}

async function call<T>(path: string, method: "GET" | "POST" | "PUT", body?: unknown): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (method !== "GET") {
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

/** Like {@link call}, but for a {@code text/plain} response (the Check Point sanitized configuration view) -- never JSON-parsed. */
async function callText(path: string): Promise<string> {
  const response = await fetch(path, { method: "GET", credentials: "include" });
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
export function collectDeviceBackup(deviceId: string, reason: string, type: "backup" | "snapshot" = "backup"): Promise<{ job_id: string }> {
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
export interface CredentialView {
  readonly credential_id: string;
  readonly credential_reference_id: string;
  readonly display_name: string;
  readonly kind: "ssh_password" | "ssh_private_key" | "api_password";
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
): Promise<CredentialView> {
  return call("/credentials", "POST", {
    display_name: displayName,
    kind,
    username,
    allows_check_point: allowsCheckPoint,
    allows_palo_alto: allowsPaloAlto,
    secret,
    passphrase: passphrase || undefined,
  });
}

export function replaceCredentialSecret(
  credentialId: string,
  secret: string,
  passphrase: string,
): Promise<CredentialView> {
  return call("/credentials/replace-secret", "POST", {
    credential_id: credentialId,
    secret,
    passphrase: passphrase || undefined,
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
export type Vendor = "check_point" | "palo_alto";

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
}

export interface DeviceSummary {
  readonly device_id: string;
  readonly role?: string;
  readonly vendor_hint: string;
  readonly enrollment_state: string;
  readonly hostname: string | null;
  readonly model: string | null;
  readonly software_version: string | null;
  readonly ha_role: string | null;
  readonly cluster_member_ref: string | null;
  readonly latest_job_state?: string | null;
  readonly latest_job_type?: string | null;
  readonly latest_job_terminal_reason?: string | null;
  readonly virtual_systems?: string | null;
  readonly management_ip?: string | null;
  readonly ip_addresses?: string | null;
  readonly backup_target?: boolean;
}

export type DeviceRole = "gateway" | "management_server";

export function addDeviceSingle(
  address: string,
  role: DeviceRole,
  vendor: Vendor,
  credentialReferenceId: string,
): Promise<AddDeviceSingleResult> {
  return call("/devices/add-single", "POST", {
    address,
    role,
    vendor,
    credential_reference_id: credentialReferenceId,
  });
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

export function listDevices(): Promise<{ devices: DeviceSummary[] }> {
  return call("/devices", "GET");
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

export interface DeviceInventory {
  readonly device_id: string;
  readonly collected_at: string | null;
  readonly job: JobView | null;
  readonly contexts: InventoryContext[];
  readonly virtual_systems?: readonly string[] | string | null;
}

export interface ClusterMember {
  readonly device_id: string;
  readonly hostname: string | null;
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
): Promise<{ results: DiscoveryImportResult[] }> {
  return call(`/discovery/runs/${encodeURIComponent(runId)}/import`, "POST", {
    candidate_ids: candidateIds,
    credential_reference_id: credentialReferenceId,
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

export interface JobPageView {
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
  readonly description: string;
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
}

export interface ComplianceControlsResponse {
  readonly total_controls: number;
  readonly controls: readonly ComplianceControlItem[];
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
  readonly observedValue?: string;
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

export function getDeviceCompliance(deviceId: string): Promise<DeviceComplianceResult> {
  return call<DeviceComplianceResult>(`/devices/${encodeURIComponent(deviceId)}/compliance`, "GET");
}

export function triggerComplianceEvaluation(deviceId?: string): Promise<ComplianceOverview> {
  return call<ComplianceOverview>("/compliance/evaluate", "POST", deviceId ? { device_id: deviceId } : {});
}
