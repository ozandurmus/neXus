import { fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ThemeProvider } from "@mui/material/styles";
import { m3Theme } from "../src/theme/m3Theme";
import { PolicyScreen } from "../src/screens/PolicyScreen";
import { reportPolicyLoadError } from "../src/screens/PolicyRuleViewer";
import responses from "./fixtures/policy-live/aiview-responses.json";

const page = JSON.parse(responses["/api/v2/policy/policies/{id}?page=0&q="]);
const sources = JSON.parse(responses["/api/v2/policy/sources"]);
const rule = page.sections.flatMap((section: { rules: { id: string; name: string }[] }) => section.rules)[0];

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function mountLive(override?: (path: string) => Response | undefined) {
  const fetch = vi.fn(async (input: string) => {
    const url = new URL(input, "https://example.invalid");
    const overridden = override?.(url.pathname);
    if (overridden) return overridden;
    const key = url.pathname.endsWith("/history") ? "/api/v2/policy/policies/{id}/history?rule={r}&page=0"
      : url.pathname.startsWith("/api/v2/policy/policies/") ? "/api/v2/policy/policies/{id}?page=0&q="
      : url.pathname === "/api/v2/policy/tree" ? url.searchParams.get("container")
        ? "/api/v2/policy/tree?source={s}&container={c}" : "/api/v2/policy/tree?source={s}"
      : url.pathname;
    if (!(key in responses)) throw new Error("Unexpected fixture request");
    return new Response(responses[key as keyof typeof responses], { status: 200, headers: { "X-Nexus-Masked": "true" } });
  });
  vi.stubGlobal("fetch", fetch);
  const debug = vi.spyOn(console, "debug").mockImplementation(() => {});
  const { container } = render(<ThemeProvider theme={m3Theme}><PolicyScreen /></ThemeProvider>);
  return { fetch, debug, container };
}

async function navigate() {
  fireEvent.click(await screen.findByRole("button", { name: sources.sources.find((source: { sourceId: string }) => source.sourceId === page.metadata.sourceId).sourceName }));
  fireEvent.click(await screen.findByRole("button", { name: page.metadata.containerName }));
  fireEvent.click(await screen.findByRole("button", { name: `Policy ${page.metadata.name}` }));
}

it("renders the masked live policy page, rule details and empty history without load errors", async () => {
  const { fetch, debug, container } = mountLive();
  await navigate();
  const rulebase = await screen.findByRole("region", { name: "Policy rulebase" });
  expect(within(rulebase).getAllByRole("article").length).toBeGreaterThan(0);
  expect(screen.getByText("Page 1 of 5 · 866 rules")).toBeInTheDocument();
  expect(container.textContent).not.toMatch(/could not be loaded/i);
  fireEvent.click(within(rulebase).getByRole("button", { name: rule.name }));
  const drawer = await screen.findByRole("dialog", { name: "Rule details" });
  expect(within(drawer).getByText("Enabled: UNKNOWN")).toBeInTheDocument();
  fireEvent.click(within(drawer).getByRole("button", { name: "Rule history" }));
  expect(await within(drawer).findByText("No recorded changes.")).toBeInTheDocument();
  expect(fetch.mock.calls.some(([url]) => url.includes(`history?rule=${rule.id}&page=0`))).toBe(true);
  expect(document.body.textContent).not.toMatch(/could not be loaded/i);
  expect(debug).not.toHaveBeenCalled();
});

it.each([
  ["snapshots", "/api/v2/policy/sources", "Policy snapshots could not be loaded.", 503],
  ["rules", `/api/v2/policy/policies/${page.metadata.id}`, "Policy rules could not be loaded.", 503],
  ["object", "/api/v2/policy/objects/", "Object details could not be loaded.", 503],
  ["history", `/api/v2/policy/policies/${page.metadata.id}/history`, "Rule history could not be loaded.", 503],
  ["rules", `/api/v2/policy/policies/${page.metadata.id}`, "Policy rules could not be loaded.", 200],
  ["object", "/api/v2/policy/objects/", "Object details could not be loaded.", 200],
  ["history", `/api/v2/policy/policies/${page.metadata.id}/history`, "Rule history could not be loaded.", 200],
])("records a safe reason for %s load failures (%#)", async (scope, endpoint, message, status) => {
  const { debug } = mountLive(path => (scope === "object" ? path.startsWith(endpoint) : path === endpoint)
    ? new Response(JSON.stringify({ error: "Withheld in AIView" }), { status: Number(status) }) : undefined);
  if (scope !== "snapshots") await navigate();
  if (scope === "object" || scope === "history") {
    const rulebase = await screen.findByRole("region", { name: "Policy rulebase" });
    fireEvent.click(within(rulebase).getByRole("button", { name: rule.name }));
    const drawer = await screen.findByRole("dialog", { name: "Rule details" });
    fireEvent.click(within(drawer).getByRole("button", { name: scope === "object" ? "Objects" : "Rule history" }));
    if (scope === "object") fireEvent.click(within(drawer).getAllByRole("button").find(button => button.textContent === page.objects[0].name)!);
  }
  const error = await screen.findByText(String(message));
  const code = status === 200 ? "INVALID_RESPONSE_SHAPE" : "HTTP_503";
  expect(error.closest("[data-error-code]")).toHaveAttribute("data-error-code", code);
  expect(debug).toHaveBeenCalledWith("policy-load", scope, code);
  expect(debug.mock.calls.flat()).not.toContain("Withheld in AIView");
});

it.each([
  [new Error("Empty policy response"), "INVALID_RESPONSE_SHAPE"],
  [new Error("Invalid history response"), "INVALID_RESPONSE_SHAPE"],
  [new Error("Invalid object response"), "INVALID_RESPONSE_SHAPE"],
  [{ status: 0, body: { error: "TIMEOUT" } }, "TIMEOUT"],
  [new SyntaxError("Withheld in AIView"), "INVALID_JSON"],
  [new TypeError("Withheld in AIView"), "NETWORK_OR_TYPE_ERROR"],
  [new Error("Withheld in AIView"), "CLIENT_ERROR"],
])("categorizes errors without logging their contents (%#)", (error, code) => {
  const debug = vi.spyOn(console, "debug").mockImplementation(() => {});
  expect(reportPolicyLoadError("rules", error)).toBe(code);
  expect(debug).toHaveBeenCalledTimes(1);
  expect(debug).toHaveBeenCalledWith("policy-load", "rules", code);
});
