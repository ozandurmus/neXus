#!/usr/bin/env python3
"""Create the script-runner broker's credentials on HOST-A; host-side only.

docs/design/SCRIPT_EXECUTION_CONTRACT.md Amendment 2026-11-18 (b) item 5 and deploy/ui2/61-script-runner-deployment.yaml.

  db-login  creates the database role ui2_script_runner (NOLOGIN first if absent), gives it LOGIN with a new random
            password sent only as a SCRAM-SHA-256 verifier (the clear password never reaches PostgreSQL, its logs or any
            command line), applies V258's grants if V258 has already run (ui2_apply_script_runner_role_grants, guarded by
            to_regprocedure so db-login is safe both before and after V258), and writes Secret ui2-db-script-runner
            (keys user, password). Run as postgres on the database pod's local socket, because ui2_migrate has
            NOCREATEROLE on production clusters. Re-running rotates the password; restart ui2-script-runner afterwards.
  token     writes Secret ui2-script-runner-token (keys token, sha256): the broker's internal-port token and the digest
            ui2-service compares it with. Restart ui2-service and ui2-script-runner afterwards.

First-ship bootstrap order (safe in either order):
  1. Deploy ui2-service (runs V258, creates ui2_script_runner NOLOGIN when permitted, starts without the runner secret
     and reports runner_available=false; module_deploy.py applies script-runner RBAC/NetworkPolicies and defers the
     broker Deployment while its Secrets are absent).
  2. Run `python3 tools/delivery/script_runner_db_login.py db-login` and `python3 tools/delivery/script_runner_db_login.py token`.
  3. Restart ui2-service (`kubectl -n ui2 rollout restart deployment/ui2-service`, because UI2_SCRIPT_RUNNER_TOKEN_SHA256
     is an env secretKeyRef evaluated only at pod start, never hot-projected) and roll out `script-runner`
     (`module_deploy.py --targets script-runner --image <service-digest> --script-exec-image <exec-digest> --snapshot <dir>`).
  (Running step 2 before step 1 is also safe: db-login guards ui2_apply_script_runner_role_grants() with to_regprocedure,
  V258 applies the grants during migration, and step 1 rolls out ui2-script-runner immediately.)

Every secret value travels on stdin (kubectl apply -f -, psql reading SQL from stdin); nothing is printed but the
names of what was written. External database: the DBA runs the SQL of `db-login --print-sql` (verifier only).
"""
import argparse
import base64
import hashlib
import hmac
import json
import secrets
import subprocess
import sys

ROLE = "ui2_script_runner"
DB_SECRET = "ui2-db-script-runner"
TOKEN_SECRET = "ui2-script-runner-token"
NAMESPACE = "ui2"
SCRAM_ITERATIONS = 4096


def scram_verifier(password, salt=None, iterations=SCRAM_ITERATIONS):
    """PostgreSQL's SCRAM-SHA-256 verifier: SCRAM-SHA-256$<iter>:<salt>$<StoredKey>:<ServerKey> (RFC 5802/7677)."""
    salt = salt if salt is not None else secrets.token_bytes(16)
    salted = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    client_key = hmac.new(salted, b"Client Key", hashlib.sha256).digest()
    stored_key = hashlib.sha256(client_key).digest()
    server_key = hmac.new(salted, b"Server Key", hashlib.sha256).digest()
    b64 = lambda b: base64.b64encode(b).decode("ascii")  # noqa: E731
    return "SCRAM-SHA-256$%d:%s$%s:%s" % (iterations, b64(salt), b64(stored_key), b64(server_key))


def role_sql(verifier):
    if not verifier.startswith("SCRAM-SHA-256$") or "'" in verifier:
        raise ValueError("not a SCRAM verifier")
    return (
        "DO $do$ BEGIN\n"
        "  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + ROLE + "') THEN\n"
        "    CREATE ROLE " + ROLE + " NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;\n"
        "  END IF;\n"
        "END $do$;\n"
        "ALTER ROLE " + ROLE + " LOGIN PASSWORD '" + verifier + "';\n"
        "SELECT CASE WHEN to_regprocedure('ui2_apply_script_runner_role_grants(text)') IS NOT NULL\n"
        "            THEN ui2_apply_script_runner_role_grants() END;\n")


def secret_document(name, values):
    return json.dumps({
        "apiVersion": "v1", "kind": "Secret", "type": "Opaque",
        "metadata": {"name": name, "namespace": NAMESPACE,
                     "labels": {"app.kubernetes.io/part-of": "nexus-ui2", "app.kubernetes.io/component": "script-runner"}},
        "data": {k: base64.b64encode(v.encode("utf-8")).decode("ascii") for k, v in values.items()},
    })


def run(args, stdin, runner=subprocess.run):
    result = runner(list(args), input=stdin, capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        # Neither stdout nor stderr is echoed: they could quote the statement.
        raise RuntimeError(args[0] + " " + " ".join(a for a in args[1:3]) + " failed (exit %d)" % result.returncode)
    return result.stdout


def db_login(runner=subprocess.run, password=None):
    password = password or secrets.token_urlsafe(32)
    run(("kubectl", "-n", NAMESPACE, "exec", "-i", "ui2-db-0", "--", "psql", "-v", "ON_ERROR_STOP=1", "-X", "-q",
         "-U", "postgres", "-d", "ui2"), role_sql(scram_verifier(password)), runner)
    run(("kubectl", "-n", NAMESPACE, "apply", "-f", "-"), secret_document(DB_SECRET, {"user": ROLE, "password": password}),
        runner)
    return [DB_SECRET, "role " + ROLE]


def token(runner=subprocess.run, value=None):
    value = value or secrets.token_urlsafe(32)
    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()
    run(("kubectl", "-n", NAMESPACE, "apply", "-f", "-"), secret_document(TOKEN_SECRET, {"token": value, "sha256": digest}),
        runner)
    return [TOKEN_SECRET]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("action", choices=("db-login", "token"))
    parser.add_argument("--print-sql", action="store_true",
                        help="db-login only: print the SQL (with a fresh verifier) for an external DBA; writes nothing")
    args = parser.parse_args()
    try:
        if args.action == "db-login" and args.print_sql:
            pw = secrets.token_urlsafe(32)
            sys.stdout.write(role_sql(scram_verifier(pw)))
            sys.stderr.write("password for Secret %s (shown once): %s\n" % (DB_SECRET, pw))
        else:
            written = db_login() if args.action == "db-login" else token()
            print("written: " + ", ".join(written))
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        sys.exit("SCRIPT RUNNER CREDENTIALS: " + str(error))
