import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import { PRODUCT_PLANES, type Role } from "../screens/CustomRolesPanel";
import { EvidenceStamp } from "./EstatePrimitives";
import { capList } from "./estatePolicyBrowserModel";
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

/** The custom (non-system) roles, in the order the server sent them. A non-array body is a failed read, never an empty list. */
export function customRolesOf(body: unknown): readonly Role[] | null {
  if (!Array.isArray(body)) return null;
  return (body as Role[]).filter((role) => !(role.isSystem ?? role.is_system));
}

/** The product planes a role carries, as plain words from PRODUCT_PLANES; empty when it carries none. */
export function planeWordsOf(role: Role): string {
  const granted = new Set(role.permissions ?? []);
  const words = PRODUCT_PLANES.filter((plane) => granted.has(plane));
  return words.length > 0 ? words.join(", ") : "no product planes";
}

function countLine(n: number): string {
  return n === 1 ? "1 role" : `${n} roles`;
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
        <Box role="alert" sx={{ fontSize: 13, color: ev.ink2 }}>{FAILED_NOTE}</Box>
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
  const capped = capList(roles, ESTATE_ROLES_ROW_CAP);
  return (
    <>
      <Box sx={{ fontSize: 12.5, color: ev.ink2 }} data-testid="estate-system-roles-count">
        {countLine(roles.length)} (custom roles)
      </Box>
      {roles.length === 0 ? null : (
        <Box component="ul" aria-label="Custom roles" sx={{ m: 0, p: 0 }}>
          {capped.shown.map((role) => (
            <Box component="li" key={role.id} data-testid="estate-system-role-row"
              sx={{ listStyle: "none", py: "4px", borderTop: `1px solid ${ev.line}`, display: "flex", flexDirection: "column", gap: "2px" }}>
              <Box sx={{ fontFamily: ESTATE_MONO, fontSize: 12.5, color: ev.ink, overflowWrap: "anywhere" }}>{role.name}</Box>
              <Box sx={{ fontSize: 12, color: ev.ink3 }}>Planes: {planeWordsOf(role)}</Box>
            </Box>
          ))}
          {capped.moreLabel ? (
            <Box component="li" data-testid="estate-system-roles-more" sx={{ listStyle: "none", pt: "4px", fontSize: 12, color: ev.ink3 }}>
              {capped.moreLabel}
            </Box>
          ) : null}
        </Box>
      )}
      <EvidenceStamp at={readAt} source="roles" now={now} />
    </>
  );
}
