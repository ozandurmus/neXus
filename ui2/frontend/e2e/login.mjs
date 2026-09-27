import { chromium } from "@playwright/test";
import { mkdir, stat, open } from "node:fs/promises";
import { dirname } from "node:path";
import { baseURL, statePath } from "./settings.mjs";
import { isAiview, readAllowed } from "./safety.ts";

const origin = baseURL();
const path = statePath();

async function login() {
  const browser = await chromium.launch({ channel: "chrome", headless: false });
  try {
    const context = await browser.newContext({ serviceWorkers: "block", acceptDownloads: false });
    let wrongRole = false;
    // Only the human login form may POST. No handlers inspect request bodies, fields or passwords.
    await context.route("**/*", async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      const loginPost = request.method() === "POST" && url.origin === origin
        && ["/login", "/login/resolve"].includes(url.pathname) && !url.search;
      if (!readAllowed(request.method(), request.url(), origin, request.resourceType()) && !loginPost) return route.abort();
      if (url.pathname === "/session/status") {
        const response = await route.fetch({ maxRedirects: 0 });
        if (response.status() === 200 && !isAiview(await response.json())) {
          wrongRole = true;
          return route.abort();
        }
        // Keep the product unmounted during login; the helper's status read below owns completion.
        return route.fulfill({ status: 401, contentType: "application/json", body: "{}" });
      }
      await route.continue();
    });
    await context.routeWebSocket("**/*", (socket) => socket.close());
    const page = await context.newPage();
    await page.goto(origin);
    console.log("Sign in as aiview in Chrome. Waiting up to five minutes; no credentials are recorded.");
    const deadline = Date.now() + 5 * 60_000;
    while (Date.now() < deadline && !page.isClosed()) {
      const response = await context.request.get(`${origin}/session/status`, { maxRedirects: 0, timeout: 10_000 });
      if (wrongRole) throw new Error("Login refused: role:replay_viewer is required.");
      if (response.status() === 200) {
        if (!isAiview(await response.json())) throw new Error("Login refused: role:replay_viewer is required.");
        await mkdir(dirname(path), { recursive: true, mode: 0o700 });
        if (((await stat(dirname(path))).mode & 0o777) !== 0o700) {
          throw new Error("The state directory must have mode 0700; choose a private directory.");
        }
        const file = await open(path, "w", 0o600);
        try {
          await file.chmod(0o600);
          await file.writeFile(JSON.stringify(await context.storageState()));
        } finally { await file.close(); }
        console.log("Aiview session saved outside the repository with mode 0600.");
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    throw new Error("Login did not complete within five minutes or Chrome was closed.");
  } finally { await browser.close(); }
}

login().catch(() => {
  console.error("Login failed. Check the base URL, system Chrome and an active aiview login; session state was not confirmed saved.");
  process.exitCode = 1;
});
