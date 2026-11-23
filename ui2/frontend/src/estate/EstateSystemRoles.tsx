import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import { PRODUCT_PLANES, type Role } from "../screens/CustomRolesPanel";
import { plural } from "../shell/plural";
import { isRecord } from "../shell/malformedResponse";
import { EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import { ESTATE_MONO, ev } from "./estateTokens";

/** Role rows shown before "+N more"; the standard Roles and permissions panel lists them all. */
export const ESTATE_ROLES_ROW_CAP = 10;

/** The standard view of this list; `standard=1` keeps it out of the Estate shell. */
export const ESTATE_ROLES_STANDARD_HREF = "?screen=administration&tab=access&section=roles-permissions&standard=1";

const RESTRICTED_NOTE = "Roles need the security_admin role";
const FAILED_NOTE = "Unable to load roles";

type RolesRead =
  | { readonly state: "loading" }
  | { readonly state: "restricted" }
  | { readonly state: "failed" }
  | { readonly state: "ok"; readonly roles: readonly Role[]; readonly readAt: string };

/** The custom (non-system) roles, in the order the server sent them. A non-array body or malformed item is a failed read, never an empty list. */
export function customRolesOf(body: unknown): readonly Role[] | null {
  if (!Array.isArray(body) || !body.every(isRecord)) return null;
  return (body as unknown as Role[]).filter((role) => !(role.isSystem ?? role.is_system));
}

/** The product planes a role carries, as plain words from PRODUCT_PLANES; empty when it carries none. */
export function planeWordsOf(role: Role): string {
  const granted = new Set(Array.isArray(role.permissions) ? role.permissions : []);
  const words = PRODUCT_PLANES.filter((plane) => granted.has(plane));
  return words.length > 0 ? words.join(", ") : "no product planes";
}

function roleNameOf(role: Role): string {
  const trimmed = typeof role.name === "string" ? role.name.trim() : "";
  return trimmed.length > 0 ? trimmed : "UNKNOWN";
}

/**
 * Read-only "Roles" block inside the Estate System Access section. Same read as the standard Roles and permissions
 * panel (`fetch("/roles")`). A refused read is a note; a failed read is visible text, never an empty list.
 */
export function EstateSystemRoles({ now }: { readonly now: Date }) {
  const [read, setRead] = useState<RolesRead>({ state: "loading" });

  useEffect(() => {
    let active = true;
    fetch("/roles")
      .then(async (response): Promise<RolesRead> => {
        if (response.status === 403) {
          await response.text().catch(() => {});
          return { state: "restricted" };
        }
        if (!response.ok) {
          await response.text().catch(() => {});
          return { state: "failed" };
        }
        const roles = customRolesOf(await response.json());
        return roles ? { state: "ok", roles, readAt: new Date().toISOString() } : { state: "failed" };
      })
      .catch((): RolesRead => ({ state: "failed" }))
      .then((next) => { if (active) setRead(next); });
    return () => { active = false; };
  }, []);

  return (
    <Box component="div" aria-label="Roles" sx={{ display: "flex", flexDirection: "column", gap: "6px", borderTop: `1px solid ${ev.line}`, pt: "10px" }}>
      <Box sx={{ fontSize: 13, fontWeight: 700, color: ev.ink }}>Roles</Box>
      {read.state === "loading" ? (
        <Box sx={{ fontSize: 13, color: ev.ink3 }}>Loading roles…</Box>
      ) : read.state === "restricted" ? (
        <Box sx={{ fontSize: 13, color: ev.ink2 }}>{RESTRICTED_NOTE}</Box>
      ) : read.state === "failed" ? (
        <>
          <Box role="alert" sx={{ display: "flex", alignItems: "center", gap: "6px", fontSize: 13, color: ev.ink2 }}>
            <StateBadge state="unk" word="UNKNOWN" dense />
            <span>{FAILED_NOTE}</span>
          </Box>
          <EvidenceStamp at={null} source="roles" missingLabel="UNKNOWN" />
        </>
      ) : (
        <RolesList roles={read.roles} readAt={read.readAt} now={now} />
      )}
      <Box component="a" href={ESTATE_ROLES_STANDARD_HREF} sx={{ fontSize: 13, color: ev.accent, fontWeight: 600 }}>
        Open roles and permissions (standard) →
      </Box>
    </Box>
  );
}

function RolesList({ roles, readAt, now }: { readonly roles: readonly Role[]; readonly readAt: string; readonly now: Date }) {
  const shown = roles.slice(0, ESTATE_ROLES_ROW_CAP);
  return (
    <>
      <Box sx={{ fontSize: 12.5, color: ev.ink2 }} data-testid="estate-system-roles-count">
        {plural(roles.length, "role", "roles") + " (custom roles)"}
      </Box>
      {roles.length === 0 ? null : (
        <>
          <Box component="ul" aria-label="Custom roles" sx={{ m: 0, p: 0 }}>
            {shown.map((role, index) => {
              const name = roleNameOf(role);
              return (
                <Box component="li" key={role.id || `${name}:${index}`} data-testid="estate-system-role-row"
                  sx={{ listStyle: "none", py: "4px", borderTop: `1px solid ${ev.line}`, display: "flex", flexDirection: "column", gap: "2px" }}>
                  <Box sx={{ fontFamily: ESTATE_MONO, fontSize: 12.5, color: ev.ink, overflowWrap: "anywhere" }}>{name}</Box>
                  <Box sx={{ fontSize: 12, color: ev.ink3 }}>Planes: {planeWordsOf(role)}</Box>
                </Box>
              );
            })}
          </Box>
          <MoreFooter shown={shown.length} total={roles.length} noun="roles" testId="estate-system-roles-more" />
        </>
      )}
      <EvidenceStamp at={readAt} source="roles" now={now} />
    </>
  );
}
