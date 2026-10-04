#!/usr/bin/env python3
"""Authenticated ZAP baseline only. Token/cookie stay in memory or private tmpfs."""
import http.cookies
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request

BASE = "http://ui2-service-internal.ui2.svc.cluster.local:8080"
LOGIN = "http://ui2-service-internal.ui2.svc.cluster.local:8086/internal/machine-session"
WORK = Path("/work")
CONFIG = Path("/tmp/zap-session.properties")
TOKEN = Path("/run/machine/token")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, url):
        return None  # A redirect must never forward the machine token to another origin.


def main():
    os.umask(0o077)
    rc = 3
    config = CONFIG
    try:
        token = TOKEN.read_text().strip()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        request = urllib.request.Request(LOGIN, data=b"", headers={"X-Nexus-Machine-Token": token}, method="POST")
        with opener.open(request, timeout=30) as response:
            cookies = http.cookies.SimpleCookie(response.headers.get("Set-Cookie", ""))
            if response.status != 200 or "ui2_session" not in cookies or "csrf_token" not in json.load(response):
                raise ValueError("machine session refused")
        cookie = "ui2_session=" + cookies["ui2_session"].value
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", cookies["ui2_session"].value):
            raise ValueError("invalid session cookie")
        # Fail before scanning if the minted session cannot read the application.
        with opener.open(urllib.request.Request(BASE + "/session/status", headers={"Cookie": cookie}), timeout=30) as response:
            status = json.load(response)
            if response.status != 200 or status.get("authenticated") is not True or set(status.get("role_tokens", [])) != {"role:viewer", "role:replay_viewer"}:
                raise ValueError("session validation failed")
        config.write_text("replacer.full_list(0).description=machine-session\n"
                          "replacer.full_list(0).enabled=true\nreplacer.full_list(0).matchtype=REQ_HEADER\n"
                          "replacer.full_list(0).matchstr=Cookie\nreplacer.full_list(0).regex=false\n"
                          "replacer.full_list(0).replacement=" + cookie + "\n"
                          "replacer.full_list(0).url=http://ui2-service-internal\\.ui2\\.svc\\.cluster\\.local:8080/.*\n"
                          "spider.processForm=false\nspider.postForm=false\n")
        config.chmod(0o600)
        (WORK / "zap-home").mkdir(exist_ok=True)
        # -I only makes warnings nonfatal; HIGH handling belongs to our baseline gate.
        rc = subprocess.run(["zap-baseline.py", "-t", BASE, "-m", "1", "-T", "10", "-I",
                             "-J", "zap.json", "-z", "-dir /work/zap-home -configfile " + str(config)],
                            cwd="/zap/wrk", stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            timeout=1200).returncode
    except (OSError, ValueError, KeyError, TypeError, AttributeError, subprocess.TimeoutExpired):
        rc = 3
    finally:
        config.unlink(missing_ok=True)
        (WORK / "zap.json.exit").write_text(str(rc))
    # The summary owns failure reporting, after all scan stages.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
