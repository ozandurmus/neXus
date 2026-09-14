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

export interface BackupArtefact {
  readonly artefact_id: string;
  readonly device_id: string;
  readonly collected_at: string;
  readonly size_bytes: number;
  readonly digest_prefix: string;
  readonly validation_level: string;
  readonly deviation_state: "unchanged" | "changed" | "first" | null;
}

export function listDeviceBackups(deviceId: string): Promise<{ backups: BackupArtefact[] }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/backups`, "GET");
}

/** BK-12 manual backup (14K BW-4): posts to the collect route with a required reason. */
export function collectDeviceBackup(deviceId: string, reason: string): Promise<{ job_id: string }> {
  return call(`/devices/${encodeURIComponent(deviceId)}/backup/collect`, "POST", { reason });
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
  readonly vendor_hint: string;
  readonly enrollment_state: string;
  readonly hostname: string | null;
  readonly model: string | null;
  readonly software_version: string | null;
  readonly ha_role: string | null;
  readonly cluster_member_ref: string | null;
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

export function listDevices(): Promise<{ devices: DeviceSummary[] }> {
  return call("/devices", "GET");
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
  readonly interfaces: InventoryInterface[];
  readonly routes: InventoryRoute[];
  readonly ha: InventoryHa | null;
}

export interface DeviceInventory {
  readonly device_id: string;
  readonly collected_at: string | null;
  readonly job: JobView | null;
  readonly contexts: InventoryContext[];
}

export interface ClusterMember {
  readonly device_id: string;
  readonly hostname: string | null;
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
  readonly addresses: InventoryAddress[];
  readonly presence: Presence;
  readonly differences: ClusterDifference[];
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
  readonly interfaces: ClusterInterface[];
  readonly routes: ClusterRoute[];
}

export interface ClusterInventory {
  readonly cluster_member_ref: string;
  readonly members: ClusterMember[];
  readonly contexts: ClusterContext[];
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

export interface DeviceConfiguration {
  readonly device_id: string;
  readonly collected_at: string | null;
  readonly vendor: string | null;
  readonly read_kind: string | null;
  readonly canonical_hash: string | null;
  readonly change_state: "changed" | "unchanged" | "first_run" | null;
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

export interface ProjectPlanView {
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
