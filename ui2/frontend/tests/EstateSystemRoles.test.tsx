import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { EstateSystemRoles } from "../src/estate/EstateSystemRoles";

// Synthetic fixtures only (FW-TANGO-04 style names). Role names must reach the DOM only as these synthetic labels.
const ROLE_NAME = "ROLE-TANGO-READONLY";

const role = (name: string, permissions: string[], isSystem = false) => ({
  id: `id-${name}`,
  name,
  description: "synthetic",
  isSystem,
  permissions,
});

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function stubRolesFetch(response: () => Response | Promise<Response>) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url !== "/roles") throw new Error(`unexpected fetch ${url}`);
    return response();
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

describe("Estate System Roles block (read-only)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("renders each custom role with its product planes and a count line that says roles", async () => {
    stubRolesFetch(() => jsonResponse(200, [
      role(ROLE_NAME, ["Devices", "Config"]),
      role("ROLE-TANGO-OPS", []),
      role("ROLE-TANGO-SYSTEM", ["Admin"], true),
    ]));

    const { container } = render(<EstateSystemRoles now={new Date("2026-11-22T09:00:00.000Z")} />);

    expect(await screen.findByText("2 roles (custom roles)")).toBeTruthy();
    expect(screen.getByText(ROLE_NAME)).toBeTruthy();
    expect(screen.getByText("Planes: Devices, Config")).toBeTruthy();
    expect(screen.getByText("Planes: no product planes")).toBeTruthy();
    expect(screen.queryByText("ROLE-TANGO-SYSTEM")).toBeNull();
    expect(container.querySelector("[data-evidence-stamp]")).not.toBeNull();
    expect(screen.getByRole("link", { name: /Open roles and permissions \(standard\)/ }).getAttribute("href"))
      .toBe("?screen=administration&tab=access&section=roles-permissions&standard=1");
  });

  it("caps the list at ten rows and prints +1 more roles for eleven roles", async () => {
    const roles = Array.from({ length: 11 }, (_, i) => role(`ROLE-TANGO-${String(i + 1).padStart(2, "0")}`, ["Operations"]));
    stubRolesFetch(() => jsonResponse(200, roles));

    render(<EstateSystemRoles now={new Date()} />);

    expect(await screen.findByText("11 roles (custom roles)")).toBeTruthy();
    expect(screen.getAllByTestId("estate-system-role-row")).toHaveLength(10);
    expect(screen.getByTestId("estate-system-roles-more").textContent).toBe("+1 more roles");
  });

  it("renders the security_admin note, not an error, when the read is refused with a 403", async () => {
    stubRolesFetch(() => jsonResponse(403, { error: "forbidden" }));

    const { container } = render(<EstateSystemRoles now={new Date()} />);

    expect(await screen.findByText("Roles need the security_admin role")).toBeTruthy();
    expect(screen.queryByText("Unable to load roles")).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(container.querySelectorAll("[data-testid='estate-system-role-row']")).toHaveLength(0);
  });

  it("renders Unable to load roles with UNKNOWN badge and a0 evidence stamp, not an empty list, when the read fails", async () => {
    stubRolesFetch(() => jsonResponse(500, { error: "synthetic" }));

    const { container } = render(<EstateSystemRoles now={new Date()} />);

    expect(await screen.findByText("Unable to load roles")).toBeTruthy();
    expect(container.querySelector("[data-state-badge='unk']")).not.toBeNull();
    expect(container.querySelector("[data-evidence-stamp='a0']")).not.toBeNull();
    expect(screen.queryByText("0 roles (custom roles)")).toBeNull();
    expect(screen.queryByText("Roles need the security_admin role")).toBeNull();
  });

  it("renders the failed/UNKNOWN state without throwing when the roles array contains a null entry", async () => {
    stubRolesFetch(() => jsonResponse(200, [null]));

    const { container } = render(<EstateSystemRoles now={new Date()} />);

    expect(await screen.findByText("Unable to load roles")).toBeTruthy();
    expect(container.querySelector("[data-state-badge='unk']")).not.toBeNull();
    expect(container.querySelector("[data-evidence-stamp='a0']")).not.toBeNull();
    expect(screen.queryByText(/0 roles/)).toBeNull();
  });

  it("renders UNKNOWN when a custom role has a blank name", async () => {
    stubRolesFetch(() => jsonResponse(200, [role("   ", ["Devices"])]));

    render(<EstateSystemRoles now={new Date("2026-11-22T09:00:00.000Z")} />);

    expect(await screen.findByText("1 role (custom roles)")).toBeTruthy();
    const row = screen.getByTestId("estate-system-role-row");
    expect(row.textContent).toContain("UNKNOWN");
    expect(row.textContent).toContain("Planes: Devices");
  });

  it("renders no button or input inside the block", async () => {
    stubRolesFetch(() => jsonResponse(200, [role(ROLE_NAME, ["Devices"])]));

    const { container } = render(<EstateSystemRoles now={new Date()} />);

    await waitFor(() => expect(screen.getByText(ROLE_NAME)).toBeTruthy());
    expect(container.querySelectorAll("button, input, select, textarea, [role='button'], [role='checkbox'], [role='switch']")).toHaveLength(0);
  });
});
