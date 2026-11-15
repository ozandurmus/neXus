import { useSession } from "../auth/SessionContext";

/**
 * Presentation-only role affordance. The server decides every request (a refused call still shows the server's
 * own reason); this only stops a control from looking usable when the session's product-plane list, which the
 * server supplies in `/session/status`, shows that the plane the control belongs to is not granted. No role token
 * is read or compared here (AG-J3): the same `permissions` list the navigation rail filters by is the input.
 *
 * Like the navigation rail, a session with no plane list at all (standalone previews, tests, a session that
 * carries none) is treated as unrestricted rather than locked.
 */
export const PLANE_ADMIN = "Admin";
export const PLANE_OPERATIONS = "Operations";

/** The one short role reason, worded as the Sanitizer and Packet Analysis refusals already are. */
export const ROLE_REFUSAL = "Your role does not allow this action.";
/** The compact form appended to a locked button's label (the window-gated buttons append "Next window: 00:00"). */
export const ROLE_LOCK_LABEL = "Role: not permitted";

/**
 * Planes whose holders may start a device read (inventory, configuration, logs): the onboarding/security admins hold
 * Admin, the operators and backup admins hold Operations. A read-only role holds neither, so it sees the control locked.
 */
export const PLANE_COLLECT: readonly string[] = [PLANE_ADMIN, PLANE_OPERATIONS];

/** A single plane, or a list of which any one is enough. */
export function planeGranted(permissions: readonly string[] | undefined, plane: string | readonly string[]): boolean {
  if (!permissions || permissions.length === 0) return true;
  return typeof plane === "string" ? permissions.includes(plane) : plane.some((p) => permissions.includes(p));
}

/** True when this session may use a control that belongs to `plane` (see the module note: presentation only). */
export function useRoleAllows(plane: string | readonly string[] | undefined): boolean {
  const session = useSession();
  return !plane || planeGranted(session?.permissions, plane);
}

/**
 * The planes that, held together, mark the roles the server lets reconcile an unknown-outcome job (the Security Admin and
 * the Backup Admin): an operator holds Operations but not Admin, an onboarding or compliance admin holds Admin but not
 * Operations. Presentation only, like every other control here; the server still decides, and its reason is shown.
 */
export const PLANES_RECONCILE: readonly string[] = [PLANE_ADMIN, PLANE_OPERATIONS];

/** True when every one of `planes` is granted (an absent or empty plane list is unrestricted, as in {@link planeGranted}). */
export function planesAllGranted(permissions: readonly string[] | undefined, planes: readonly string[]): boolean {
  return planes.every((plane) => planeGranted(permissions, plane));
}

/** True when this session should be offered the Reconcile action (see {@link PLANES_RECONCILE}). */
export function useCanReconcileJobs(): boolean {
  const session = useSession();
  return planesAllGranted(session?.permissions, PLANES_RECONCILE);
}
