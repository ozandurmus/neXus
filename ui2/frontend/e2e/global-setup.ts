import { request } from "@playwright/test";
import { baseURL, statePath } from "./settings.mjs";
import { isAiview } from "./safety";

export default async function setup() {
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
