import hashlib
import os
import time
import uuid

DEBUG = True
LOG_TO_FILE = True
RUN_ID = str(uuid.uuid4())[:8]

LOG_FILE = f"logs/run_{RUN_ID}.log"

def configure_log_root(logs_root):
    """Activate the external runtime log root after RuntimePaths bootstrap."""
    global LOG_FILE
    LOG_FILE = os.path.join(str(logs_root), f"run_{RUN_ID}.log")

# Exact runtime values to redact if they accidentally reach a log message.
# Values are kept in memory only for the lifetime of the Python process.
_SENSITIVE_VALUES = {}


###############################################
# INIT
###############################################
def init_logger(debug=True, log_to_file=True):
    global DEBUG, LOG_TO_FILE

    DEBUG = debug
    LOG_TO_FILE = log_to_file

    if LOG_TO_FILE:
        os.makedirs("logs", exist_ok=True)


def register_sensitive_value(value, replacement="[REDACTED]"):
    """Register an exact runtime value for log redaction.

    This does not persist the secret. It only prevents accidental console/file
    logging while the current process is alive.
    """
    if value:
        _SENSITIVE_VALUES[str(value)] = replacement


def principal_fingerprint(principal):
    """Return a non-secret short fingerprint for audit correlation."""
    if not principal:
        return "anonymous"
    digest = hashlib.sha256(str(principal).encode("utf-8")).hexdigest()
    return digest[:12]


def user_fingerprint(username):
    """Compatibility alias; new code should use principal_fingerprint()."""
    return principal_fingerprint(username)


def _redact(msg):
    text = str(msg)
    for value, replacement in sorted(
        _SENSITIVE_VALUES.items(), key=lambda item: len(item[0]), reverse=True
    ):
        if value:
            text = text.replace(value, replacement)
    return text


###############################################
# CORE LOGGER
###############################################
def _write(msg):
    if LOG_TO_FILE:
        log_dir = os.path.dirname(LOG_FILE)
        if log_dir:
            os.makedirs(log_dir, exist_ok=True)

        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(msg + "\n")


def log(msg, level="DEBUG"):

    if level == "DEBUG" and not DEBUG:
        return

    ts = time.strftime("%H:%M:%S")
    line = f"[{ts}] [{level}] {_redact(msg)}"

    print(line)
    _write(line)


###############################################
# SHORTCUTS
###############################################
def dbg(msg):
    log(msg, "DEBUG")


def info(msg):
    log(msg, "INFO")


def warn(msg):
    log(msg, "WARN")


def err(msg):
    log(msg, "ERROR")
