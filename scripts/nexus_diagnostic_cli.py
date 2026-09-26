#!/usr/bin/env python3
"""Submit one closed neXus diagnostic job; no device transport or raw-output handling."""

import argparse
import getpass
import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


TERMINAL = {"COMPLETED", "FAILED", "REJECTED", "CANCELLED", "OUTCOME_UNKNOWN"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device-id", required=True, help="Opaque enrolled device ID")
    parser.add_argument("--port", required=True, help="Port from the device's collected inventory")
    parser.add_argument("--mechanism", choices=("local", "ldap"), default="local")
    args = parser.parse_args()
    base = os.environ.get("NEXUS_UI_URL", "http://127.0.0.1").rstrip("/")
    parsed = urllib.parse.urlparse(base)
    if parsed.username or parsed.password:
        parser.error("NEXUS_UI_URL must not contain credentials")
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in ("127.0.0.1", "localhost")):
        parser.error("HTTP is permitted only on loopback; set NEXUS_UI_URL to trusted HTTPS elsewhere")
    if not args.port or not all(c.isascii() and (c.isalnum() or c in "_.-") for c in args.port) or len(args.port) > 31:
        parser.error("port must be one interface token")

    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))

    def request(path: str, method: str = "GET", body: dict | None = None, csrf: str | None = None) -> dict:
        headers = {"Content-Type": "application/json"}
        if csrf:
            headers["X-CSRF-Token"] = csrf
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try:
            with opener.open(req, timeout=15) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"neXus refused the request (HTTP {error.code})") from None

    try:
        identity = getpass.getpass("neXus identity (hidden): ")
        password = getpass.getpass("neXus password (hidden): ")
        request("/login", "POST", {"username": identity, "password": password, "mechanism_id": args.mechanism})
        password = ""
        status = request("/session/status")
        if "role:security_admin" not in status.get("role_tokens", []):
            raise RuntimeError("super administrator role required")
        csrf = status["csrf_token"]
        query = urllib.parse.urlencode({"device_id": args.device_id, "port": args.port})
        preview = request("/api/v2/diagnostics/preview?" + query)
        command = "diagnose fmnetwork interface detail " + args.port
        if preview.get("templateId") != "fmg_interface_detail" or preview.get("command") != command:
            raise RuntimeError("server preview did not match the closed command template")
        print(f"Target: {preview['target']} · Port: {args.port} · Gate V{preview['gateRevision']}")
        print(f"Sending through neXus job: {command}")
        print("Expected safe result: status token, bounded line count, shape ID; no raw response")
        answer = request("/api/v2/diagnostics", "POST", {
            "device_id": args.device_id, "port": args.port, "request_id": str(uuid.uuid4())}, csrf)
        job_id = answer["job_id"]
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            result = request("/api/v2/diagnostics/" + urllib.parse.quote(job_id, safe=""))
            if result.get("state") in TERMINAL:
                print(f"Job: {result['state']} · Status field present: {bool(result.get('statusPresent'))} "
                      f"· Token: {result.get('statusToken') or 'UNKNOWN'} "
                      f"· Lines: {result.get('lineCount')} · Shape: {result.get('shapeId') or 'UNKNOWN'}")
                print("Physical link: UNKNOWN until vendor semantics are proven")
                return 0 if result["state"] == "COMPLETED" else 1
            time.sleep(2)
        print("Job did not reach a terminal state within 300 seconds; continue monitoring the recorded job", file=sys.stderr)
        return 1
    except (RuntimeError, urllib.error.URLError, KeyError, ValueError) as error:
        print(str(error) if isinstance(error, RuntimeError) else "neXus CLI request failed", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
