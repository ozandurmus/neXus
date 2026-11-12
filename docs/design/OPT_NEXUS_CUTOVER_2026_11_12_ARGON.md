<!-- Argon consultation 2026-11-12T19:41:39Z, model gemini-4-argon-high, effort high; unaltered output -->

**Role:** `REVIEWER` (`READ_ONLY_AUDIT` — architecture / operations design review of `ops/opt-nexus-1112`).

---

## Summary Verdict

The core ownership model (`nexus:nexus` `2750` on `/opt/nexus` and `/opt/nexus.git`, deploy account reading via group `nexus` and writing exclusively via `sudo -n -u nexus`) and the 4-step symlink/group-write transition pattern are **architecturally sound**, and the 4 modified test suites pass (`252 passed, 4 skipped`).

However, there are **2 `BLOCKER`s** (one in `standalone_orchestrate.py` / Mac git remote configuration that will break ships after Step C, and one in Step A2/C1 `chmod` flags that can leave files unreadable to group `nexus`), **6 `SHOULD` fixes**, and **3 `NIT`s**.

---

## 1. Ownership & Write Model Review (`sudo -n -u nexus` vs. Group Write)

### Is the reasoning sound?
**Yes.** Choosing `sudo -n -u nexus` over persistent `2770` + `core.sharedRepository=group` is the right call:
1. **No ownership drift:** In a working tree (`/opt/nexus`), `core.sharedRepository=group` only governs `.git/` internals; working-tree files replaced by `git checkout`/`pull` and untracked outputs (`project/deploy_info.json`) are created owned by the effective UID of the process. Under group-write, every touched file in `/opt/nexus` drifts to `aiadmin:nexus`.
2. **Auditability:** Every mutation of `/opt/nexus.git` (`git-receive-pack`) and `/opt/nexus` (`git fetch`, `git checkout main`, `git pull origin main`, `tee project/deploy_info.json`) produces an explicit `sudo-rs` journal entry (`journalctl _COMM=sudo`).
3. **One caveat on `umask` (see `SHOULD-1` below):** The plan claims *"no umask dependency"*, which is only half true. Because `aiadmin` reads via group `nexus`, files written by `nexus` **must** get `g+r` (and directories `g+rx`). When `core.sharedRepository` is unset in Step C1, both `git` and `tee` fall back to the process `umask` (which `sudo-rs` preserves if the caller has a restrictive mask like `0077`). Keeping `core.sharedRepository = 0640` and setting `umask 0027` (or `0022`) in `run_build.sh` eliminates that dependency.

### Does anything in `run_build.sh` (or other host scripts) still write into the tree as the deploy account?
Tracing every script invoked on HOST-A:
- [run_build.sh](file:///Users/OzanDur/Codo/nexus-optnexus-1112/deploy/ui2-image-build/run_build.sh#L27-L64):
  - `export PYTHONDONTWRITEBYTECODE=1` prevents Python from creating `__pycache__/*.pyc` inside `/opt/nexus/tools/...`. (Even outside `run_build.sh` — e.g. `security-source-refresh.service`, `hosta_e2e.sh`, `hosta_integration_remote.py` — CPython's `importlib` catches `PermissionError` on `.pyc` writes and continues cleanly once Step C1 removes `g+w`.)
  - `module_deploy.py`, `hosta_e2e_image.py`, `hosta_restamp_image.py`, `hosta_packet_analysis_image.py`: read-only against `/opt/nexus` and `~/build-job-proxy.yaml`; state writes go to `~/.local/state/nexus-preview/reuse/`.
  - `release_snapshot.sh` -> [release_snapshot.py](file:///Users/OzanDur/Codo/nexus-optnexus-1112/tools/delivery/release_snapshot.py#L501): writes only to `~/release-snapshots/`.
  - [security_host.py](file:///Users/OzanDur/Codo/nexus-optnexus-1112/tools/security/security_host.py#L29-L65) (`snapshot`): locks `~/.config/nexus/security.lock` (in `$HOME`), runs `git -C /opt/nexus rev-parse HEAD`, `git -C /opt/nexus status --porcelain ...`, and `git -C /opt/nexus bundle create <tmp>/history.bundle --all`. `git bundle create` writes its `.lock` and bundle in `/tmp/security-source-*`, not in `/opt/nexus/.git`. `git status` opportunistically tries `.git/index.lock` if stat info is dirty and silently ignores `EACCES` (see `SHOULD-4` for the `ctime` performance trap after Step C1).
  - [hosta_preview_e2e.py](file:///Users/OzanDur/Codo/nexus-optnexus-1112/tools/e2e/hosta_preview_e2e.py#L166-L190): archives candidate into `mktemp -d` (`/tmp/tmp.*`), reads `/opt/nexus/ui2/.ca/*.pem` and `/opt/nexus/ui2/.gradle-home/.../*.zip` via `HOST_TREE.resolve()`, and writes `project/deploy_info.json` inside the `/tmp` candidate directory.
- **However, one caller on the Mac was missed:** [standalone_orchestrate.py](file:///Users/OzanDur/Codo/nexus-optnexus-1112/tools/delivery/standalone_orchestrate.py#L768) still pushes `main` to remote `hosta` without `--receive-pack` before calling `hosta_deploy.sh` — see **`BLOCKER-1`** below.

---

## 2–4. Concrete Findings (`BLOCKER` / `SHOULD` / `NIT`)

### `BLOCKER-1`: `standalone_orchestrate.py:768` and Mac git remote `hosta` still push to `~/nexus.git` as the deploy account without `sudo -n -u nexus`
- **Location:** [standalone_orchestrate.py](file:///Users/OzanDur/Codo/nexus-optnexus-1112/tools/delivery/standalone_orchestrate.py#L768), [test_ship_speed.py](file:///Users/OzanDur/Codo/nexus-optnexus-1112/tools/tests/test_ship_speed.py#L296), and Mac `.git/config` (`remote.hosta.url`).
- **Problem:**
  In `_ship()` ([standalone_orchestrate.py](file:///Users/OzanDur/Codo/nexus-optnexus-1112/tools/delivery/standalone_orchestrate.py#L768)), right after `gh pr merge` and **before** `_deploy()` invokes `tools/delivery/hosta_deploy.sh` (and also on `docs_only` fast-path ships, which skip `hosta_deploy.sh`), the orchestrator executes:
  ```python
  _git("push", "-q", "hosta", "main")
  ```
  In the local git config (`git config --get-regexp 'remote\.hosta\.'`), `remote.hosta.url` is still `ssh://<user>@<host>/home/<user>/nexus.git` and `remote.hosta.receivepack` is unset.
  1. **During Step B:** `standalone_orchestrate.py:768` pushes `ops/opt-nexus-1112` to `~/nexus.git` **as the deploy account (`aiadmin`), not `nexus`**. When `hosta_deploy.sh:70` subsequently runs `git push -q --receive-pack='sudo -n -u nexus git-receive-pack' "ssh://$HOST/opt/nexus.git" main`, `/opt/nexus.git` is already at `HEAD`, so `hosta_deploy.sh`'s push is a no-op and the objects/refs in `/opt/nexus.git` were written by `aiadmin`.
  2. **After Step C:** Once Step C drops group write (`chmod -R g-w /opt/nexus.git`) and deletes `~/nexus.git`, the **very next ship** (`standalone_orchestrate.py ship`, both full and docs-only) will fail at line 768 before `hosta_deploy.sh` is ever called — first because `/home/aiadmin/nexus.git` no longer exists, and second because `git push -q hosta main` does not invoke `sudo -n -u nexus git-receive-pack`.
- **Fix:**
  1. In [standalone_orchestrate.py](file:///Users/OzanDur/Codo/nexus-optnexus-1112/tools/delivery/standalone_orchestrate.py#L768), pass `--receive-pack`:
     ```python
     _git("push", "-q", "--receive-pack=sudo -n -u nexus git-receive-pack", "hosta", "main")
     ```
     (and update the predicate in [test_ship_speed.py](file:///Users/OzanDur/Codo/nexus-optnexus-1112/tools/tests/test_ship_speed.py#L296) if needed).
  2. In Step A (on the Mac, in `~/Codo/nexus` and `~/Codo/nexus-ship` if separate), update the `hosta` remote:
     ```bash
     git -C ~/Codo/nexus remote set-url hosta "ssh://$(head -1 ~/.config/nexus/hosta)/opt/nexus.git"
     git -C ~/Codo/nexus config remote.hosta.receivepack "sudo -n -u nexus git-receive-pack"
     ```

---

### `BLOCKER-2`: Step A2 (`chmod -R g+w`) and Step C1 (`chmod -R g-w`) do not ensure group read/traverse (`g+rX`)
- **Location:** Plan Step A2 and Step C1.
- **Problem:**
  - `sudo cp -a` preserves the original file modes from `/home/aiadmin/nexus` and `/home/aiadmin/nexus.git`.
  - Any file or directory that had mode `0600` or `0700` (e.g. created under `umask 0077`, host-local files under `ui2/.ca/*.pem` or `ui2/.gradle-home/`, or git files) was readable by `aiadmin` only because `aiadmin` was the **user owner (`u`)**.
  - After `sudo chown -R nexus:nexus /opt/nexus /opt/nexus.git`, `aiadmin` reads **only via group `nexus` (`g`)**.
  - `sudo chmod -R g+w` only adds group *write*, turning `0600` into `0620` (`-rw--w----`, **unreadable by group `nexus`**) and `0700` into `2720` (`drwx-ws---`, **untraversable by group `nexus`**). Then Step C1's `sudo chmod -R g-w` turns them into `0600` / `2700`, breaking `aiadmin` reads (`tar -cf - ui2 project docs`, `hosta_preview_e2e.py`'s `overlay_host_inputs`, or `git`).
  - Also, `sudo chmod o-rwx /opt/nexus /opt/nexus.git` in A2 is non-recursive, whereas explicit recursive `o-rwx` guarantees clean `2750`/`0640` permissions everywhere.
- **Fix:**
  - In **Step A2**, replace `sudo chmod -R g+w ...` and `sudo chmod o-rwx ...` with:
    ```bash
    sudo chmod -R u+rwX,g+rwX,o-rwx /opt/nexus /opt/nexus.git
    ```
  - In **Step C1**, replace `sudo chmod -R g-w ...` and `sudo chmod 2750 ...` with:
    ```bash
    sudo chmod -R u+rwX,g+rX,g-w,o-rwx /opt/nexus /opt/nexus.git
    sudo chmod 2750 /opt/nexus /opt/nexus.git
    ```
  - In **Step A7**, verify readability of every file (not just directory listing via `ls`):
    ```bash
    find /opt/nexus /opt/nexus.git ! -readable | head -5   # expect empty
    ```

---

### `SHOULD-1`: Keep `core.sharedRepository = 0640` in Step C1 and set `umask 0027` in `run_build.sh`
- **Location:** Plan Step C1 (`git config --unset core.sharedRepository`) and [run_build.sh](file:///Users/OzanDur/Codo/nexus-optnexus-1112/deploy/ui2-image-build/run_build.sh#L27).
- **Problem:**
  When `core.sharedRepository` is unset in Step C1, Git uses `PERM_UMASK` (`umask(2)`), and `tee project/deploy_info.json` also uses `umask(2)`. Because `sudo-rs 0.2.13` preserves a restrictive caller `umask` (e.g. if an operator shell or calling script has `umask 0077`), `sudo -n -u nexus` will create packfiles, refs, working-tree files, or `project/deploy_info.json` with mode `0600` (`nexus:nexus`, no group read), causing `aiadmin`'s subsequent read steps to fail with `Permission denied`.
  `git-config(1)` explicitly supports `core.sharedRepository = 0640` (*"0640 is a repository that is group-readable but not group-writable. 0xxx will override user's umask value"*).
- **Fix:**
  - In **Step C1**, replace `--unset core.sharedRepository` with:
    ```bash
    sudo -u nexus git -C /opt/nexus config core.sharedRepository 0640
    sudo -u nexus git -C /opt/nexus.git config core.sharedRepository 0640
    ```
  - In [run_build.sh](file:///Users/OzanDur/Codo/nexus-optnexus-1112/deploy/ui2-image-build/run_build.sh#L27), add `umask 0027` (or `umask 0022`) alongside `export PYTHONDONTWRITEBYTECODE=1` so working-tree checkouts and `tee project/deploy_info.json` always produce group-readable files.

---

### `SHOULD-2`: Step C2 removes `!/home/aiadmin/nexus` from AIDE while `~/nexus.pre-opt-1112` and `~/nexus.git.pre-opt-1112` remain on disk until Step D (+7 days)
- **Location:** [nexus-aide.conf](file:///Users/OzanDur/Codo/nexus-optnexus-1112/deploy/hosta/nexus-aide.conf#L18), Plan Step C2, and Plan Step D.
- **Problem:**
  - Using `!/opt/nexus(\.git)?(/|$)` in `deploy/hosta/nexus-aide.conf` is right (because AIDE selection rules are `^`-anchored prefix regexes, so a bare `!/opt/nexus` would have accidentally excluded `/opt/nexus-scan`).
  - However, the existing `/etc/aide/aide.conf.d/99_aide_nexus` had `!/home/aiadmin/nexus` (prefix rule, which also excluded `~/nexus.git`, `~/nexus.pre-opt-1112`, and `~/nexus.git.pre-opt-1112`).
  - When **Step C2** installs `/opt/nexus/deploy/hosta/nexus-aide.conf` to `/etc/aide/aide.conf.d/99_aide_nexus`, `!/home/aiadmin/nexus` is removed **7 days before Step D deletes `~/nexus.pre-opt-1112` and `~/nexus.git.pre-opt-1112`**.
  - Every daily run of `dailyaidecheck` between Step C and Step D will flag all ~12,500 files inside `/home/aiadmin/nexus.pre-opt-1112` and `/home/aiadmin/nexus.git.pre-opt-1112` as unexpected file additions under `/home/aiadmin`.
- **Fix:**
  Move `sudo install -m 0644 -o root -g root /opt/nexus/deploy/hosta/nexus-aide.conf /etc/aide/aide.conf.d/99_aide_nexus` from **Step C2** to **Step D** (right after `rm -rf ~/nexus.pre-opt-1112 ~/nexus.git.pre-opt-1112`), leaving Step A6's appended rule in place during the 7-day retention window.

---

### `SHOULD-3`: Step B's ownership check (`stat -c '%U %n' /opt/nexus/project/deploy_info.json /opt/nexus/.git/FETCH_HEAD`) is a false positive
- **Location:** Plan Step B verification.
- **Problem:**
  Both `/opt/nexus/project/deploy_info.json` and `/opt/nexus/.git/FETCH_HEAD` already exist when `cp -a` copies `~/nexus` in Step A2, so Step A2's `chown -R nexus:nexus` already sets their owner to `nexus`.
  Neither `tee project/deploy_info.json` nor `git fetch` (`fopen(".git/FETCH_HEAD", "w")`) unlinks the existing file; both open with `O_WRONLY | O_CREAT | O_TRUNC`, which truncates the existing inode in place and **keeps `st_uid = nexus` even if written by `aiadmin`**.
  By contrast, Git updates `.git/index` and `refs/heads/main` via `.lock` + `rename(2)` (creating a new inode owned by the writer's effective UID).
- **Fix:**
  In Step B verification, check files replaced via `.lock` + `rename` and verify no files in either repo are owned by `aiadmin`:
  ```bash
  stat -c '%U %n' /opt/nexus/.git/index /opt/nexus/.git/refs/heads/main /opt/nexus.git/refs/heads/main
  find /opt/nexus /opt/nexus.git ! -user nexus | head -5
  ```

---

### `SHOULD-4`: Refresh `/opt/nexus/.git/index` as `nexus` at the end of Step C1
- **Location:** Plan Step C1.
- **Problem:**
  `sudo chown -R` and `sudo chmod -R` in Step C1 update `st_ctime` on all 12,177 files in `/opt/nexus`. Because Git's index caches `ctime`, and Step C1 removes `aiadmin`'s write access to `/opt/nexus/.git/index.lock`, every subsequent read-only `git -C /opt/nexus status --porcelain` run by `aiadmin` ([security_host.py](file:///Users/OzanDur/Codo/nexus-optnexus-1112/tools/security/security_host.py#L34) during deploys and the daily `security-source-refresh.service`) will detect `ctime` mismatches on all 12,177 files, re-hash all 282 MB of tracked files from disk, fail (`EACCES`) to persist the refreshed index, and repeat the full 282 MB re-hash on every run until the next `sudo -u nexus git` working-tree command.
- **Fix:**
  Add at the end of Step C1:
  ```bash
  sudo -u nexus git -C /opt/nexus update-index -q --refresh
  ```

---

### `SHOULD-5`: `cd /` before running `sudo -u nexus git -C ...` in interactive host steps (A2, A7, C1, Rollback)
- **Location:** Plan Step A2, Step A7, Step C1, and §4 Rollback.
- **Problem:**
  An interactive SSH session as `aiadmin` starts with `cwd = /home/aiadmin` (mode `0750`, `aiadmin:aiadmin`). `sudo-rs 0.2.13` preserves `cwd`. User `nexus` is not in group `aiadmin` and cannot read or traverse `/home/aiadmin`, so any tool or Git code path that calls `getcwd(3)` / `open(".", O_RDONLY)` before processing `-C /opt/nexus` fails with `Permission denied` (`run_build.sh` avoids this because line 28 does `cd "$REPO"` before calling `as_tree_owner`).
- **Fix:**
  Prepend `cd /` (or `cd /opt/nexus`) before running `sudo -u nexus ...` commands in Steps A2, A7, C1, and Rollback.

---

### `SHOULD-6`: Rollback gaps at each stage (§4)
- **Location:** Plan §4 Rollback.
- **Problems & Fixes:**
  1. **Rollback after Step A (before B) if a ship from the 7-ship chain deployed between A and the rollback:**
     If a ship ran after Step A, `/opt/nexus.git` and `/opt/nexus` (and the live k3s cluster) advanced to a newer commit than `~/nexus.pre-opt-1112` and `~/nexus.git.pre-opt-1112`. Simply moving `.pre-opt-1112` back leaves `~/nexus` and `project/deploy_info.json` behind the live cluster until the next full code deploy, which breaks `hosta_integration.sh` ([hosta_integration_remote.py](file:///Users/OzanDur/Codo/nexus-optnexus-1112/tools/delivery/hosta_integration_remote.py#L24-L25) checks `HEAD == deploy_info.json["commit"]`), `hosta_e2e.sh`, and the 23:15 UTC `security-source-refresh.service`.
     - **Fix:** If rolling back Step A after a post-A deploy occurred, sync `~/nexus.git` and `~/nexus` from `/opt/nexus.git` (or re-push `main` from the Mac and run `git fetch && git checkout main && git pull origin main` + copy `/opt/nexus/project/deploy_info.json` into `~/nexus/project/deploy_info.json`), and clean up `~/.gitconfig` with `git config --global --unset-all safe.directory '^(/opt/nexus|/home/aiadmin/nexus)'`.
  2. **Rollback after Step C:**
     - Needs the fixed permission command (`sudo chmod -R u+rwX,g+rwX,o-rwx /opt/nexus /opt/nexus.git`), not just `chmod -R g+w`.
     - Needs Mac-side `remote.hosta` revert if `remote.hosta` was switched in Step A/C.
     - If falling back to the `.pre-opt-1112` copies as a last resort after Step C2 ran, `~/.config/systemd/user/security-source-refresh.service` (which C2 changed to `WorkingDirectory=/opt/nexus`) and `/etc/aide/aide.conf.d/99_aide_nexus` must also be restored to their pre-C2 versions (`WorkingDirectory=%h/nexus` + `systemctl --user daemon-reload`, and `!/home/aiadmin/nexus`).

---

### `NIT-1`: OpenSSH `ControlMaster` multiplexing can mask the new group in Step A7
- **Location:** Plan Step A7 (`in a NEW ssh session (group membership applies at login)`).
- **Problem:**
  If the Mac has OpenSSH multiplexing (`ControlMaster auto` / `ControlPersist`) enabled for HOST-A, a "new ssh session" from the Mac reuses the existing master `sshd` process spawned before `sudo usermod -aG nexus aiadmin` in Step A1, so `id -nG | grep -qw nexus` will fail.
- **Fix:**
  Before running Step A7 from the Mac, close any multiplexed master connection:
  ```bash
  ssh -O exit "$(head -1 ~/.config/nexus/hosta)" 2>/dev/null || true
  ```

---

### `NIT-2`: Broaden the idle gate `pgrep` in Step A0 and Step C0
- **Location:** Plan Step A0 and Step C0:
  `pgrep -fa 'run_build.sh|hosta_e2e|security_host.py|git-receive-pack|git-upload-pack' || echo idle`
- **Problem:**
  `hosta_integration.sh` invokes `python3 - ui2-integration-<hex>` over SSH stdin (so `hosta_integration` does not appear in `ps`), and Git 2.53 can spawn detached background `git gc` / `git maintenance` processes after a push or fetch.
- **Fix:**
  ```bash
  pgrep -fa 'run_build\.sh|hosta_e2e|ui2-integration-|module_deploy\.py|release_snapshot\.py|security_host\.py|git-(receive|upload)-pack|git (gc|maintenance)' || echo idle
  ```

---

### `NIT-3`: Historical `no sudo` note in `docs/operations/HOST_A_RUNBOOK.md` §1 vs. new §2
- **Location:** [HOST_A_RUNBOOK.md](file:///Users/OzanDur/Codo/nexus-optnexus-1112/docs/operations/HOST_A_RUNBOOK.md#L25-L30) vs. [HOST_A_RUNBOOK.md](file:///Users/OzanDur/Codo/nexus-optnexus-1112/docs/operations/HOST_A_RUNBOOK.md#L44-L49).
- **Problem:**
  Section 1 (lines 25–30) still carries the 2026-09-19 amendment (*"connect via SSH using the operator account without sudo for running in-cluster builds (`run_build.sh`)... Agents MUST NOT invoke `sudo`"*), whereas `PO_DECISION_RECORD_2026_09_24_HOST_A_OWNED_BY_NEXUS_AGENT_SUDO.md` (already cited in [HOST_A_REBUILD_RUNBOOK.md](file:///Users/OzanDur/Codo/nexus-optnexus-1112/docs/design/HOST_A_REBUILD_RUNBOOK.md#L41-L47)) granted the dedicated agent account `NOPASSWD:ALL` and §2 now states `run_build.sh` invokes `sudo -n -u nexus`.
- **Fix:**
  Add a short note in `HOST_A_RUNBOOK.md` §1 pointing to the 2026-09-24 decision record so §1 and §2 do not read as contradictory.
