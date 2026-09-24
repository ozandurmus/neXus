# Vendor backup measurements — Backbox trails, 2026-09-22

## Status

**MEASUREMENT RECORD (FROZEN as a record of what was observed).** The Product
Owner supplied Backbox job trails for the eight vendor backlog items
(`vendor_*_backup`, P1) plus the two vendors already implemented. This document
is the measurement each vendor's contract must be written from (memory rule:
measurement record before contract; never let an implementation guess the
commands). Trail identifiers are Backbox's; hosts, addresses and secrets are
not reproduced.

## What every vendor needs from neXus, beyond the backup itself

A backup runs only against an `ENROLLED` device. Today only Check Point and
Palo Alto have a registration path (Add device / discovery), a confirm
capability (identity read) and inventory. Each new vendor therefore needs, in
order: vendor value + endpoint kind in Add device; a confirm capability (one
gated identity read); the backup capability; gate rows; the manifest vendor
value; and the screen's vendor label. Two transports cover all eight: the SSH
exec/interactive transport the worker has, and a **generic HTTPS client**
(cookie jar, basic auth, form POST, streamed download) that does not exist yet
and is the first thing to build for Infoblox, Radware, Pulse Secure, Blue Coat
and Panorama-over-HTTPS.

## Per vendor

### 1. Fortinet FortiGate — trail 23488971, "SSH (VDOM)", 14.4 MB trail
- Transport: SSH interactive, admin login, port 22.
- Sequence: `config global` → `config system console` → `set output standard` → `end` → `end` (pager off);
  `show` (VDOM list, parsed locally in Backbox by sed); then per VDOM: `config vdom` → `edit <vdom>` →
  `show full-configuration` → `end`. Inventory: `conf global`, `get hardware status | grep Model`,
  `get system status | grep Version:`.
- Artefact: the concatenated `show full-configuration` text per VDOM (secret-bearing: encrypted
  password hashes and PSKs appear in FortiOS config).
- neXus fit: interactive shell, prompt changes per context (`(global) #`, `(vdom) #`) — the shell must
  re-learn the prompt per step (it already re-learns from each output's last line). VDOM enumeration
  must be parsed in the worker (no local sed). Alternative single read `execute backup config`
  needs a TFTP/FTP target and is not what Backbox measured.

### 2. Cisco ASA — trail 34409263, "SSH / SCP (Requires Privilege 15 User)", 2.9 MB
- Transport: SSH interactive; `enable` + enable password (a second secret: the credential store needs
  an "enable secret" field or a second credential reference); `terminal pager 0`.
- Sequence: `conf t` → `changeto system` → `more system:running-config` → `show startup-config` →
  `show version` → `show running-config | include ^context` (multi-context enumeration, then per
  context presumably `changeto context <name>` + the same reads; the trail's local sed/gotoLine loop
  hides the per-context commands — measure again on a multi-context box before freezing);
  `backup /noconfirm location disk0:<name>.backup.tar.gz` → SCP fetch of `disk0:/<name>` → (no delete
  seen in the trail: the archive stays on disk0). Inventory from `show version`.
- Artefact: the ASA `backup` tar.gz (running/startup config, certificates, keys — secret-bearing) plus
  the three text reads.
- neXus fit: SSH interactive + SFTP/SCP fetch (`fetchStreaming` is SFTP; ASA offers SCP — a `scp -f`
  over the exec channel is needed, or the ASA's `copy` to an SCP server, which neXus has no server
  for). Enable-mode handling is new.

### 3. Check Point Provider-1 / MDS — trail 34410129, "MDS Backup - No Logs (SSH)", 92 KB
- Transport: SSH, `expert` (second secret: expert password), `export TMOUT=36000`.
- Sequence: `blades_summary`; `clish -c "show inactivity-timeout"`; `uname -a`; `rm -rf /var/log/BackBox`;
  `mkdir -p /var/log/BackBox`; `fwm mds ver`; `mdsstat`; `cplic print -x`; `netstat -rn`; `/etc/cli.sh` →
  `lock database override` → `save configuration gaia_config.txt` → `Y` → `exit`;
  `tar zcvf /var/log/BackBox/CheckPoint_Configuration_Files.tar.gz /etc/hosts /etc/hosts.* /etc/resolv.conf …`;
  `$CPMDIR/scripts/mds_backup -b -l -d /var/log/BackBox > /var/log/BackBox/mdsbackup.log`;
  `ls /var/log/BackBox | grep mdsbk`; `tar -tf <mdsbk.tar>`; `tar -pczf /var/log/BackBox/CheckPoint_Provider.tar.gz *`;
  `md5sum` on the device; SCP fetch; `md5sum` locally and compare.
- Artefact: one tar.gz holding the mds_backup tar (`-l` = without logs), gaia config, /etc files
  (secret-bearing).
- neXus fit: the Gaia backup executor's shape (submit, digest, fetch, delete) with `mds_backup`
  as the submit; `-b` batch mode is non-interactive. Expert password handling is new (CP gateways
  here log in as Expert directly). Working directory under /var/log must be created and cleaned
  by name (as V45 does for archives).

### 4. Palo Alto Panorama — trail 34411025, "cURL (Device-Bundle)", 33 MB
- Transport: HTTPS web login (`/php/login.php`, CSRF from the login page, form POST with
  `prot=https%3A&server=<host>&authType=init&…`), then `GET /php/device/config.export.php?bundle=true`
  → `bundle.tgz`; then SSH: `set cli scripting-mode on`, `set cli pager off`, `configure`, `show`, `exit`.
  Inventory over SSH: `show system info`, `request license info`.
- Artefact: `bundle.tgz` (the Panorama device bundle: running config, device groups, templates) plus
  the set-format candidate configuration.
- neXus fit: the XML API `type=export&category=device-state` the PAN executor already uses is the
  documented equivalent of the bundle for Panorama too — measure it on the Panorama before choosing
  the web-form login (which is undocumented and CSRF-fragile). The SSH half is exactly V43's
  `PanSetConfigReader` (Panorama accepts `show config running` as well).

### 5. Infoblox Grid Manager — trail 34411065, "HTTPS-2", 8.8 KB
- Transport: HTTPS, basic auth, WAPI. `GET /wapidoc/` (WAPI version), `POST /wapi/v<ver>/fileop?_function=getgriddata`
  (JSON body; returns a token and a download URL), `GET <download url>` (`database.bak`, force-download),
  `POST /wapi/v<ver>/fileop?_function=downloadcomplete` with the token.
- Artefact: `database.bak` (the Grid backup; secret-bearing).
- neXus fit: generic HTTPS client with basic auth + streamed download; three gated calls; the WAPI
  version is read, not assumed.

### 6. Radware DefensePro — trail 34095224, "HTTPS", 192 KB
- Transport: HTTPS, basic auth (`wget --auth-no-challenge`), one POST:
  `/dynamic/File/Configuration/ReceivefromDevice` with `post-data='Download…'` → the configuration
  text file.
- Artefact: `DefensePro_backup_configuration.txt`.
- neXus fit: generic HTTPS client, one gated call.

### 7. Pulse Secure / Ivanti Secure Access — trail 34411066, "WGET", 56 KB
- Transport: HTTPS form login (`/dana-na/auth/url_admin/login.cgi`: `tz_offset`, `username`,
  `password`), a "continue the session" confirmation POST (`btnContinue`, `FormDataStr`), then
  `xsauth` token read from `/dana-admin/cached/config/config.cgi?type=system`.
- Sequence: `GET /dana-admin/sysinfo/sysinfo.cgi`; POST exports `type=system`, `type=user`, `type=ivs`
  (`op=Export`, empty export password) and `type=exportxml` (`action=Export` with checkbox flags);
  `GET /dana-na/auth/logout.cgi?xsauth=…`.
- Artefact: `system.cfg`, `users.cfg`, `ivs.cfg`, `export.xml` (users.cfg is secret-bearing).
- neXus fit: generic HTTPS client with cookie jar and form POSTs; four artefact members → one bundle
  (as the PAN bundle does). The empty export password means the .cfg files are unencrypted; the
  contract may choose to set one from the credential store.

### 8. Blue Coat — trail 30241437, "cURL", 34 KB — **SSL Visibility, not ProxySG**
- The trail is an SSL Visibility appliance (WebUI on :8082, `/json` login with CSRF cookie, three
  `/download/<id>` fetches). The backlog item names **ProxySG**, whose backup is
  `show configuration` over SSH or the `/archconf` HTTPS export — a different device and a
  different sequence. **Product Owner to confirm which device is meant** before a contract.

### Already implemented, for reference
- Check Point Gaia gateway — trail 34398424: neXus does `add backup local` (non-interactive) + poll
  + SFTP + sha256 + delete; Backbox additionally runs `lock database override`,
  `set inactivity-timeout 720`, ~20 diagnostic reads into /var/log/BackBox, `show configuration`,
  and `add backup local interactive`. Deltas recorded in the backlog note; none blocks the backup.
- Palo Alto firewall — trail 34411050: device-state export + set-format config: shipped (V40, V43).

## Proposed order of implementation (by fit, then value)

1. Generic HTTPS client in the worker (cookie jar, basic auth, form POST, streamed download,
   gate rows keyed by method+path) — unblocks 4 of 8.
2. Infoblox (3 calls, basic auth) and Radware (1 call) — smallest contracts.
3. Provider-1 MDS — the Gaia executor's shape with `mds_backup`.
4. FortiGate — interactive shell with per-VDOM enumeration.
5. Panorama — measure `device-state` over the XML API first.
6. Pulse Secure — form login + four exports.
7. Cisco ASA — enable mode + SCP fetch; measure the multi-context loop first.
8. Blue Coat — after the Product Owner names the device.

Each vendor also carries the onboarding items above (Add device, confirm read, screen label).

## Addendum 2026-09-24 — exact fields recovered from the trails (secrets withheld)

The summaries above shortened two load-bearing values to "…". Re-read from the Product Owner's trail files
(`trail_34095224.log`, `trail_34411065.log`); credentials, addresses, tokens and passphrases are withheld.

- **Radware DefensePro (34095224):** `POST https://<device>:443/dynamic/File/Configuration/ReceivefromDevice`,
  basic auth sent pre-emptively (`--auth-no-challenge`), TLS not verified, form body
  `DownloadFormat=cli&IncludePKeys=on&passphrase=<passphrase>`. `IncludePKeys=on` puts the private keys in the
  export, encrypted with that passphrase — so the passphrase is a **second secret** (credential store), never a
  literal in a gate row or in code. Output: one text file.
- **Infoblox Grid Manager (34411065):**
  1. `GET /wapidoc/` → 302 to `/wapidoc/index.html` (≈176 KB HTML); the WAPI version is the single-quoted value on
     the line containing `VERSION` (Sphinx `VERSION: '2.13.5'` on this appliance).
  2. `POST /wapi/v<ver>/fileop?_function=getgriddata`, `Content-Type: application/json`, body `{"type": "BACKUP"}`
     → JSON with `token` and `url` (the `url` is on the same appliance host).
  3. `GET <url>` → `database.bak`, ≈ 1 MB on this grid (996 KB) — so a "size > 1 MB" check is wrong; any non-empty
     body with the expected magic is the check.
  4. `POST /wapi/v<ver>/fileop?_function=downloadcomplete` with `{"token": "<token>"}`. **Backbox sends this to
     `/wapi/v/fileop` (empty version) and the appliance answers "Unknown WAPI version" — its cleanup fails on every
     run.** neXus sends it with the version read in step 1.
