import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ChangeCredentialsDialog } from "../src/shell/ChangeCredentialsDialog";
import type { DeviceSummary } from "../src/auth/adminApi";

afterEach(() => vi.unstubAllGlobals());

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200 });

it("replaces a draft Radware device's login credential and checks it again (PO, 2026-09-24)", async () => {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn().mockImplementation((url: string, init?: RequestInit) => {
    calls.push(`${init?.method ?? "GET"} ${url}`);
    if (url === "/credentials") {
      return Promise.resolve(json({ credentials: [
        { credential_id: "c1", credential_reference_id: "ref-1", display_name: "API user", kind: "api_password", username: "u", allows_check_point: false, allows_palo_alto: false, secret_set_at: null },
        { credential_id: "c2", credential_reference_id: "ref-2", display_name: "Key", kind: "ssh_private_key", username: "u", allows_check_point: false, allows_palo_alto: false, secret_set_at: null },
      ] }));
    }
    return Promise.resolve(json({ ok: true, changed: true, admitted: true, job_id: "j1" }));
  }));
  const device = { device_id: "d1", vendor_hint: "radware", enrollment_state: "DRAFT", hostname: "FW-RADWARE-01" } as unknown as DeviceSummary;
  const onChanged = vi.fn();
  render(<ChangeCredentialsDialog device={device} onClose={() => {}} onChanged={onChanged} />);

  expect(screen.getByLabelText("Export passphrase credential")).toBeInTheDocument();
  fireEvent.mouseDown(screen.getByLabelText("Login credential"));
  const list = await screen.findByRole("listbox");
  expect(within(list).queryByText("Key")).toBeNull(); // an SSH key never logs in to Radware
  fireEvent.click(within(list).getByText("API user"));
  fireEvent.click(screen.getByRole("button", { name: "Save" }));

  await waitFor(() => expect(onChanged).toHaveBeenCalled());
  expect(calls).toContain("PUT /devices/d1/credential");
  expect(calls).toContain("POST /devices/d1/confirm");
});
