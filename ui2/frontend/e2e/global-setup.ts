import { request } from "@playwright/test";
import { baseURL, statePath } from "./settings.mjs";
import { isAiview } from "./safety";
import { writeFile } from "node:fs/promises";

export async function machineSetup(api: typeof request = request) {
  const token = process.env.NEXUS_E2E_MACHINE_TOKEN;
  if (!token) throw new Error("Machine token is missing");
  const url = process.env.NEXUS_E2E_MACHINE_URL
    ?? "http://ui2-service-internal.ui2.svc.cluster.local:8086/internal/machine-session";
  const context = await api.newContext();
  try {
    const response = await context.post(url, { headers: { "X-Nexus-Machine-Token": token }, timeout: 30_000 });
    if (response.status() !== 200 || typeof (await response.json()).csrf_token !== "string") {
      throw new Error("Machine session was refused");
    }
    const origin = new URL(baseURL());
    const cookie = /^ui2_session=([^;]+)/.exec(response.headers()["set-cookie"] ?? "");
    if (!cookie) {
      throw new Error("Machine session cookie is missing");
    }
    const state = { cookies: [{ name: "ui2_session", value: cookie[1], domain: origin.hostname,
      path: "/", expires: -1, httpOnly: true, secure: origin.protocol === "https:", sameSite: "Strict" }], origins: [] };
    await writeFile(statePath(), JSON.stringify(state), { mode: 0o600, flag: "wx" });
  } finally {
    await context.dispose();
  }
}

export default async function setup() {
  if (process.env.NEXUS_E2E_MACHINE_TOKEN) {
    await machineSetup();
    return;
  }
  // This is the run's first network operation. Never navigate to a product screen before it succeeds.
  const context = await request.newContext({ storageState: statePath() }).catch(() => {
    throw new Error("Cannot read E2E storage state. Run npm run e2e:login first.");
  });
  try {
    const response = await context.get(`${baseURL()}/session/status`, { maxRedirects: 0, timeout: 30_000 });
    if (response.status() !== 200 || !isAiview(await response.json())) throw new Error();
  } catch {
    throw new Error("E2E refused: /session/status must return an active role:replay_viewer session. Run e2e:login as aiview.");
  } finally {
    await context.dispose();
  }
}
