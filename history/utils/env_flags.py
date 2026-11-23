import os

def env_bool(name: str, default: bool) -> bool:
    """Boolean env flag: unset gives default; any value except 0/false/no/off/disabled (case-insensitive, stripped) is true."""
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off", "disabled"}
