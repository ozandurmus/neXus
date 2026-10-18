import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { SessionContext } from "../src/auth/SessionContext";
import { JobButton, JobWindowContext, useGate, withGateSuffix } from "../src/shell/JobWindow";
import { M3Button } from "../src/shell/M3Widgets";
import { PLANE_ADMIN, PLANE_COLLECT, PLANE_OPERATIONS, planeGranted } from "../src/shell/actionAccess";

/** One suffix rule for every gated control (UI review 2026-10-18, F-7): " · Role: not permitted" or " · Next window: HH:MM". */
type WindowView = { open: boolean; label: string | null };
const closed: WindowView = { open: false, label: "Next window: 06:00" };
const open: WindowView = { open: true, label: null };
const withSession = (permissions: readonly string[], windowView: WindowView, node: React.ReactElement) => (
  <SessionContext.Provider value={{ displayName: "x", roleTokens: [], permissions, onSignOut: () => {} }}>
    <JobWindowContext.Provider value={windowView}>{node}</JobWindowContext.Provider>
  </SessionContext.Provider>
);

function Probe({ plane, deviceJob }: { plane?: string | readonly string[]; deviceJob?: boolean }) {
  const gate = useGate(plane, { deviceJob });
  return <span data-testid="probe">{withGateSuffix("Action", gate)}|{String(gate.locked)}</span>;
}

describe("planes", () => {
  it("accepts a list of planes of which any one is enough, and treats a session without a plane list as unrestricted", () => {
    expect(planeGranted(["Devices"], PLANE_COLLECT)).toBe(false);
    expect(planeGranted(["Devices", PLANE_OPERATIONS], PLANE_COLLECT)).toBe(true);
    expect(planeGranted(["Devices", PLANE_ADMIN], PLANE_COLLECT)).toBe(true);
    expect(planeGranted(undefined, PLANE_COLLECT)).toBe(true);
    expect(planeGranted([], PLANE_COLLECT)).toBe(true);
  });
});

describe("the one gate", () => {
  it.each([
    ["role refused beats a closed window", ["Devices"], closed, { plane: PLANE_COLLECT, deviceJob: true }, "Action · Role: not permitted|true"],
    ["closed window for a role that may act", [PLANE_OPERATIONS], closed, { plane: PLANE_COLLECT, deviceJob: true }, "Action · Next window: 06:00|true"],
    ["open window, role that may act", [PLANE_ADMIN], open, { plane: PLANE_COLLECT, deviceJob: true }, "Action|false"],
    ["a plain control ignores the window", [PLANE_ADMIN], closed, { plane: PLANE_ADMIN }, "Action|false"],
    ["a plain control still honours the role", ["Devices"], open, { plane: PLANE_ADMIN }, "Action · Role: not permitted|true"],
  ])("%s", (_name, permissions, windowView, props, expected) => {
    render(withSession(permissions, windowView, <Probe {...props} />));
    expect(screen.getByTestId("probe")).toHaveTextContent(expected);
  });

  it("reads the same on M3Button and JobButton", () => {
    render(withSession(["Devices"], closed, <><M3Button deviceJob plane={PLANE_COLLECT} emphasis="tonal">Collect now</M3Button><JobButton plane={PLANE_COLLECT}>Request</JobButton></>));
    expect(screen.getByRole("button", { name: "Collect now · Role: not permitted" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Request · Role: not permitted" })).toBeDisabled();
  });

  it("gives a fixed reason the same shape", () => {
    render(withSession([PLANE_ADMIN], open, <M3Button emphasis="tonal" lockedReason="Schedule authoring is not available yet">Schedule collection</M3Button>));
    expect(screen.getByRole("button", { name: "Schedule collection · Schedule authoring is not available yet" })).toBeDisabled();
  });
});
