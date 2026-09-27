import { homedir, tmpdir } from "node:os";
import { dirname, isAbsolute, relative, resolve } from "node:path";
import { existsSync, realpathSync } from "node:fs";
import { fileURLToPath } from "node:url";

export const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(frontendRoot, "../..");

export function baseURL() {
  const value = process.env.NEXUS_E2E_BASE_URL;
  if (!value) throw new Error("Set NEXUS_E2E_BASE_URL to the deployment origin before running E2E commands.");
  let url;
  try { url = new URL(value); } catch { throw new Error("NEXUS_E2E_BASE_URL must be an HTTP(S) origin."); }
  if (!/^https?:$/.test(url.protocol) || url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
    throw new Error("NEXUS_E2E_BASE_URL must be an HTTP(S) origin without credentials, path, query or fragment.");
  }
  return url.origin;
}

export function statePath() {
  if (process.env.NEXUS_E2E_MACHINE_TOKEN) return resolve(tmpdir(), "nexus-e2e-state.json");
  const value = process.env.NEXUS_E2E_STATE ?? "~/.nexus-e2e/aiview.json";
  const path = resolve(value.startsWith("~/") ? resolve(homedir(), value.slice(2)) : value);
  // Resolve existing ancestors too, so a symlink cannot put session material in the checkout.
  let ancestor = path;
  while (!existsSync(ancestor)) ancestor = dirname(ancestor);
  const resolved = resolve(realpathSync(ancestor), relative(ancestor, path));
  const fromRepo = relative(realpathSync(repositoryRoot), resolved);
  if (!fromRepo.startsWith(`..${process.platform === "win32" ? "\\" : "/"}`) && !isAbsolute(fromRepo)) {
    throw new Error("NEXUS_E2E_STATE must be outside the repository.");
  }
  return resolved;
}

// Playwright DOM failure snapshots can contain the privacy violation being tested.
process.env.PLAYWRIGHT_NO_COPY_PROMPT = "1";
