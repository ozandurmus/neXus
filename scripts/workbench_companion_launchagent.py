"""C5 artifact generation only: never install, launch, notify or remove files.

The release contains bin/nexus-workbench-observer and scripts/workbench_companion.py.
Configuration is the observer's explicit private projection-input configuration.
"""

import argparse
import json
import os
from pathlib import Path
import plistlib
import sys

LABEL = "com.nexus.workbench-companion"
NOTIFICATION_STATUS = "UNSUPPORTED"


def _path(value):
    if not isinstance(value, (str, Path)):
        raise ValueError("INVALID_CONFIGURATION")
    text = str(value)
    path = Path(text)
    if (not path.is_absolute() or ".." in path.parts or path == Path("/")
            or any(ord(c) < 32 or ord(c) == 127 for c in text)
            or text != str(path)):
        raise ValueError("INVALID_CONFIGURATION")
    return path


def artifacts(home, release, configuration, snapshot_directory, *, uid=None):
    home, release, configuration, snapshot_directory = map(
        _path, (home, release, configuration, snapshot_directory))
    uid = os.getuid() if uid is None else uid
    if type(uid) is not int or uid <= 0:
        raise ValueError("INVALID_CONFIGURATION")
    checkout = Path(__file__).resolve().parent.parent
    paths = (release, configuration, snapshot_directory)
    if (any(not p.is_relative_to(home) or p == home or p.is_relative_to(checkout)
            for p in paths)
            or snapshot_directory.is_relative_to(release)
            or release.is_relative_to(snapshot_directory)
            or configuration.is_relative_to(snapshot_directory)
            or configuration.is_relative_to(release)):
        raise ValueError("INVALID_CONFIGURATION")
    executable = release / "bin" / "nexus-workbench-observer"
    plist = home / "Library" / "LaunchAgents" / (LABEL + ".plist")
    if any(p == plist or p.is_relative_to(plist.parent) for p in paths):
        raise ValueError("INVALID_CONFIGURATION")
    definition = {
        "Label": LABEL,
        "ProgramArguments": [str(executable), "--configuration", str(configuration)],
        "WorkingDirectory": str(release),
        "LimitLoadToSessionType": "Aqua",
        "RunAtLoad": True,
        "KeepAlive": True,
        "ThrottleInterval": 30,
        "Umask": 0o077,
        "StandardOutPath": "/dev/null",
        "StandardErrorPath": "/dev/null",
        "HardResourceLimits": {"Core": 0},
    }
    domain = f"gui/{uid}"
    service = f"{domain}/{LABEL}"
    launchctl = "/bin/launchctl"
    return {
        "plist": definition,
        "plist_path": str(plist),
        "plist_mode": 0o600,
        "notification_status": NOTIFICATION_STATUS,
        "commands": {"start": [[launchctl, "enable", service], [launchctl, "bootstrap", domain, str(plist)]],
                     "stop": [[launchctl, "disable", service], [launchctl, "bootout", service]],
                     "status": [[launchctl, "print", service]]},
        "uninstall": {"commands": [[launchctl, "disable", service], [launchctl, "bootout", service]],
                      "remove_after_stop": [str(plist), str(executable),
                                            str(release / "scripts" / "workbench_companion.py"),
                                            str(configuration)],
                      "preserve": [str(snapshot_directory)]},
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("format", choices=("plist", "plan"))
    for name in ("home", "release", "configuration", "snapshot-directory"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args(argv)
    try:
        result = artifacts(args.home, args.release, args.configuration, args.snapshot_directory)
    except ValueError:
        parser.exit(2, "INVALID_CONFIGURATION\n")
    if args.format == "plist":
        sys.stdout.buffer.write(plistlib.dumps(result["plist"]))
    else:
        print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
