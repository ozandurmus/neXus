import { useEffect, useState } from "react";
import type { FormEvent } from "react";

import markLightOnDark from "../assets/app-mark-light-on-dark.svg";
import wordmarkLightOnDark from "../assets/wordmark-light-on-dark.svg";
import { hasBespokeWordmark, PRODUCT_NAME } from "../brand/product";
import { ConstellationCanvas } from "./ConstellationCanvas";
import "./LoginScreen.css";

// C3A contract §5.3: every local login failure -- unknown identity, wrong
// password, empty password, and an active lockout -- returns the identical
// body from the server. This screen mirrors that on the client: one
// message, regardless of which of those four caused the 401.
const GENERIC_ERROR = "Incorrect username or password.";
const CONFLICT_MESSAGE = "Another session is already active for this account.";
const UNEXPECTED_ERROR = "Something went wrong. Please try again.";

type Outcome =
  | { kind: "ok" }
  | { kind: "invalid" }
  | { kind: "conflict"; conflictToken: string }
  | { kind: "unexpected" };
type LoginMechanism = "local" | "ldap";

async function submitLogin(username: string, password: string, mechanism: LoginMechanism): Promise<Outcome> {
  try {
    const response = await fetch("/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ username, password, mechanism_id: mechanism }),
    });
    if (response.ok) return { kind: "ok" };
    if (response.status === 409) {
      const data = await response.json().catch(() => ({}));
      return { kind: "conflict", conflictToken: data.conflict_token || "" };
    }
    if (response.status === 401) return { kind: "invalid" };
    return { kind: "unexpected" };
  } catch {
    return { kind: "unexpected" };
  }
}

/**
 * Username, password, submit -- and one error presentation shared by every
 * failure mode (this movement's brief; C3A contract §5.3). No product
 * screen is reachable from here: a successful submission calls
 * onAuthenticated, which is the only way past this component (see AuthGate).
 */
export function LoginScreen({
  onAuthenticated,
  productName = PRODUCT_NAME,
  samlEnabled = false,
}: {
  readonly onAuthenticated: () => void;
  readonly productName?: string;
  /** Single sign-on through ADFS (SAML) is switched on: offer it beside the password form. */
  readonly samlEnabled?: boolean;
}) {
  if (typeof document !== "undefined" && document.title !== productName) {
    document.title = productName;
  }
  const [username, setUsername] = useState("");
  const [mechanism, setMechanism] = useState<LoginMechanism>("local");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [conflictToken, setConflictToken] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [showPassword, setShowPassword] = useState(false);

  // A single sign-on that found the account already signed in elsewhere comes back with the takeover token in the address.
  // It is taken out of the address at once and offered through the same prompt a password sign-in uses.
  useEffect(() => {
    if (typeof window === "undefined") return;
    const params = new URLSearchParams(window.location.search);
    const token = params.get("saml_conflict_token");
    if (!token) return;
    params.delete("saml_conflict_token");
    const rest = params.toString();
    window.history.replaceState(null, "", window.location.pathname + (rest ? `?${rest}` : "") + window.location.hash);
    setConflictToken(token);
    setError(CONFLICT_MESSAGE);
  }, []);

  async function handleSubmit(event?: FormEvent) {
    event?.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    setConflictToken(null);
    try {
      const outcome = await submitLogin(username, password, mechanism);
      switch (outcome.kind) {
        case "ok":
          onAuthenticated();
          return;
        case "conflict":
          setError(CONFLICT_MESSAGE);
          setConflictToken(outcome.conflictToken);
          return;
        case "invalid":
          setError(GENERIC_ERROR);
          return;
        default:
          setError(UNEXPECTED_ERROR);
      }
    } finally {
      if (mechanism === "ldap") setPassword("");
      setSubmitting(false);
    }
  }

  async function handleTakeover() {
    if (!conflictToken || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      const response = await fetch("/login/resolve", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify({ conflictToken, action: "takeover" }),
      });
      if (response.ok) {
        onAuthenticated();
      } else {
        setError(UNEXPECTED_ERROR);
        setConflictToken(null);
      }
    } catch {
      setError(UNEXPECTED_ERROR);
      setConflictToken(null);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="lx">
      <ConstellationCanvas />
      <div className="lx-vignette" aria-hidden="true" />
      <main className="lx-page">
        <section className="lx-hero" aria-label={productName}>
          <div className="lx-logo">
            <img className="lx-logo-mark" src={markLightOnDark} alt="" />
            {hasBespokeWordmark(productName) ? (
              <img className="lx-logo-word" src={wordmarkLightOnDark} alt={productName} />
            ) : (
              <span className="lx-logo-word lx-logo-word-text" role="img" aria-label={productName}>
                {productName}
              </span>
            )}
          </div>
          <div className="lx-meta">Isolated network · no outbound connections · all evidence stays on premises</div>
        </section>

        <section className="lx-panel-wrap">
          <form
            className="lx-panel"
            onSubmit={(event) => {
              if (conflictToken) {
                event.preventDefault();
                return;
              }
              void handleSubmit(event);
            }}
            noValidate
            aria-labelledby="lx-title"
          >
            <h2 id="lx-title">Sign in</h2>
            <p className="lx-sub">Use your operator account to continue.</p>

            <fieldset className="lx-seg" disabled={submitting}>
              <legend>Authentication</legend>
              <input
                type="radio"
                name="authentication"
                id="lx-auth-local"
                value="local"
                checked={mechanism === "local"}
                onChange={() => setMechanism("local")}
              />
              <label htmlFor="lx-auth-local">Local account</label>
              <input
                type="radio"
                name="authentication"
                id="lx-auth-ldap"
                value="ldap"
                checked={mechanism === "ldap"}
                onChange={() => setMechanism("ldap")}
              />
              <label htmlFor="lx-auth-ldap">LDAP account</label>
            </fieldset>

            {error ? (
              <div className="lx-alert" role="alert">
                <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
                  <circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" strokeWidth="1.5" />
                  <path d="M8 4.5v4.2M8 11.3v.2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
                </svg>
                <span>{error}</span>
              </div>
            ) : null}

            <div className="lx-field">
              <label htmlFor="lx-user">Username</label>
              <div className="lx-control">
                <input
                  id="lx-user"
                  name="username"
                  type="text"
                  autoComplete="username"
                  spellCheck={false}
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  autoFocus
                  aria-required="true"
                  aria-invalid={error ? true : undefined}
                />
              </div>
            </div>
            <div className="lx-field">
              <label htmlFor="lx-pass">Password</label>
              <div className="lx-control">
                <input
                  id="lx-pass"
                  name="password"
                  type={showPassword ? "text" : "password"}
                  autoComplete="current-password"
                  className="has-eye"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  aria-required="true"
                  aria-invalid={error ? true : undefined}
                />
                <button
                  type="button"
                  className="lx-eye"
                  aria-label="Show password"
                  aria-pressed={showPassword}
                  aria-controls="lx-pass"
                  onClick={() => setShowPassword((value) => !value)}
                >
                  {showPassword ? "Hide" : "Show"}
                </button>
              </div>
            </div>

            {conflictToken ? (
              <button type="button" className="lx-btn" onClick={handleTakeover} aria-busy={submitting || undefined}>
                {submitting ? "Signing in…" : "Terminate prior session & sign in"}
              </button>
            ) : (
              <button type="submit" className="lx-btn" aria-busy={submitting || undefined}>
                {submitting ? "Signing in…" : "Sign in"}
              </button>
            )}

            {samlEnabled ? (
              <a className="lx-sso" href="/saml2/authenticate/adfs">Sign in with SSO (ADFS)</a>
            ) : null}

            <div className="lx-foot">{productName} · on-premises</div>
          </form>
        </section>
      </main>
    </div>
  );
}
