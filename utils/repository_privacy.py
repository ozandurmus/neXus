"""Local, offline repository privacy gate for SecurityExpert.

The scanner reports only rule/category and file location. Matched values are
never returned or printed. It intentionally scans the repository candidate,
not runtime data, and performs no network access.
"""

from __future__ import annotations

from dataclasses import dataclass
import ast
import ipaddress
from pathlib import Path
import re
from typing import Iterable


class RepositoryPrivacyError(RuntimeError):
    """Scanner/configuration failure (distinct from a privacy finding)."""


@dataclass(frozen=True)
class PrivacyFinding:
    path: str
    line: int
    rule: str


@dataclass(frozen=True)
class PrivacyReport:
    files_scanned: int
    files_skipped: int
    findings: tuple[PrivacyFinding, ...]

    @property
    def gate(self) -> str:
        return "PASS" if not self.findings else "FAIL"


FORBIDDEN_ROOT_DIRS = {
    "data",
    "output",
    "logs",
    "runtime",
    "state",
    "cas",
    ".cas",
    "support_bundles",
}
FORBIDDEN_DIRS_ANYWHERE = {
    "__pycache__",
    ".pytest_cache",
    ".mypy_cache",
    ".ruff_cache",
    ".cache",
    ".copilot",
    ".serena",
    ".venv",
    "venv",
}
FORBIDDEN_SUFFIXES = {
    ".zip": "ARCHIVE_OR_PACKAGE",
    ".7z": "ARCHIVE_OR_PACKAGE",
    ".rar": "ARCHIVE_OR_PACKAGE",
    ".sqlite": "DATABASE_ARTIFACT",
    ".sqlite3": "DATABASE_ARTIFACT",
    ".db": "DATABASE_ARTIFACT",
    ".pcap": "PACKET_CAPTURE",
    ".pcapng": "PACKET_CAPTURE",
    ".pem": "PRIVATE_OR_TRUST_MATERIAL",
    ".p12": "PRIVATE_OR_TRUST_MATERIAL",
    ".pfx": "PRIVATE_OR_TRUST_MATERIAL",
    ".jks": "PRIVATE_OR_TRUST_MATERIAL",
    ".key": "PRIVATE_OR_TRUST_MATERIAL",
    ".bak": "BACKUP_OR_TEMPORARY",
    ".tmp": "BACKUP_OR_TEMPORARY",
}
FORBIDDEN_NAMES = {
    ".env": "LOCAL_CONFIGURATION",
    "known_hosts": "LOCAL_TRUST_MATERIAL",
    "thumbs.db": "GENERATED_OR_OS_ARTIFACT",
    ".ds_store": "GENERATED_OR_OS_ARTIFACT",
}
TEXT_SUFFIXES = {
    ".py", ".pyi", ".sh", ".ps1", ".md", ".txt", ".json", ".yaml",
    ".yml", ".toml", ".ini", ".cfg", ".conf", ".html", ".css", ".js",
    ".xml", ".csv", ".gitignore",
}

_PRIVATE_KEY_RE = re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----")
_WINDOWS_USER_PATH_RE = re.compile(r"(?i)\b[A-Z]:[\\/]Users[\\/](?!<USER>|USER|REALUSER|example|test)[^\\/\s]+[\\/]")
_EMAIL_RE = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
_IPV4_RE = re.compile(r"(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])")
_HIGH_RISK_ASSIGNMENT_RE = re.compile(
    r"(?i)\b(password|passwd|api[_-]?key|access[_-]?token|secret|community|psk)\b\s*[:=]\s*[\"']([^\"']+)[\"']"
)
_SAFE_LITERAL_VALUES = {
    "", "none", "null", "redacted", "[redacted]", "[auth_secret:redacted]",
    "synthetic-secret", "synthetic-password", "example", "test", "changeme",
}


def _is_text_candidate(path: Path) -> bool:
    return path.name == ".gitignore" or path.suffix.lower() in TEXT_SUFFIXES


def _safe_test_fixture(path: Path) -> bool:
    return bool(path.parts and path.parts[0] == "tests")


def _is_repository_safe_ip(value: str, *, test_fixture: bool) -> bool:
    try:
        ip = ipaddress.ip_address(value)
    except ValueError:
        return True
    if ip.is_loopback or ip.is_unspecified:
        return True
    # RFC 5737 documentation networks.
    documentation = (
        ipaddress.ip_network("192.0.2.0/24"),
        ipaddress.ip_network("198.51.100.0/24"),
        ipaddress.ip_network("203.0.113.0/24"),
    )
    if any(ip in network for network in documentation):
        return True
    # Existing tests intentionally exercise private-address parsing/redaction.
    if test_fixture and (ip.is_private or ip.is_link_local):
        return True
    return not (ip.is_private or ip.is_link_local)


def _python_literal_findings(rel: Path, text: str) -> Iterable[PrivacyFinding]:
    if rel.suffix.lower() != ".py" or _safe_test_fixture(rel):
        return ()
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return ()
    findings: list[PrivacyFinding] = []
    high_risk_names = {"password", "passwd", "api_key", "apikey", "access_token", "secret", "community", "psk"}
    for node in ast.walk(tree):
        target_names: list[str] = []
        value = None
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name):
                    target_names.append(target.id.lower())
                elif isinstance(target, ast.Attribute):
                    target_names.append(target.attr.lower())
            value = node.value
        elif isinstance(node, ast.AnnAssign):
            target = node.target
            if isinstance(target, ast.Name):
                target_names.append(target.id.lower())
            elif isinstance(target, ast.Attribute):
                target_names.append(target.attr.lower())
            value = node.value
        if not target_names or value is None or not any(name in high_risk_names for name in target_names):
            continue
        if isinstance(value, ast.Constant) and isinstance(value.value, str):
            normalized = value.value.strip().lower()
            if normalized not in _SAFE_LITERAL_VALUES and not normalized.startswith("["):
                findings.append(PrivacyFinding(rel.as_posix(), getattr(node, "lineno", 0), "CREDENTIAL_LITERAL"))
    return findings


def scan_repository(repository_root: Path) -> PrivacyReport:
    root = Path(repository_root).resolve()
    if not root.is_dir():
        raise RepositoryPrivacyError("repository root is not a directory")

    findings: list[PrivacyFinding] = []
    scanned = 0
    skipped = 0

    for child in root.iterdir():
        if child.is_dir() and child.name.lower() in FORBIDDEN_ROOT_DIRS:
            findings.append(PrivacyFinding(child.name, 0, "RUNTIME_DIRECTORY_PRESENT"))

    for path in sorted(root.rglob("*")):
        rel = path.relative_to(root)
        parts_lower = [part.lower() for part in rel.parts]
        if ".git" in parts_lower:
            skipped += 1
            continue
        if any(part in FORBIDDEN_DIRS_ANYWHERE for part in parts_lower):
            # Known tool/cache directories are intentionally ignored by Git and
            # are not part of the repository candidate. Do not scan their
            # contents and do not turn routine local test execution into a gate
            # failure. Runtime roots are handled separately and do fail closed.
            skipped += 1
            continue
        if path.is_dir():
            continue

        lower_name = path.name.lower()
        if lower_name.startswith(".env") and lower_name != ".env.example":
            findings.append(PrivacyFinding(rel.as_posix(), 0, "LOCAL_CONFIGURATION"))
        elif lower_name.startswith("known_hosts") and lower_name != "known_hosts.example":
            findings.append(PrivacyFinding(rel.as_posix(), 0, "LOCAL_TRUST_MATERIAL"))
        elif lower_name in FORBIDDEN_NAMES:
            findings.append(PrivacyFinding(rel.as_posix(), 0, FORBIDDEN_NAMES[lower_name]))

        suffix = path.suffix.lower()
        if suffix in FORBIDDEN_SUFFIXES:
            findings.append(PrivacyFinding(rel.as_posix(), 0, FORBIDDEN_SUFFIXES[suffix]))
            skipped += 1
            continue
        if lower_name.endswith((".db-wal", ".db-shm", ".journal")):
            findings.append(PrivacyFinding(rel.as_posix(), 0, "DATABASE_ARTIFACT"))
            skipped += 1
            continue
        if not _is_text_candidate(path):
            skipped += 1
            continue

        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            raise RepositoryPrivacyError(f"cannot safely inspect text candidate: {rel.as_posix()}") from exc
        scanned += 1
        test_fixture = _safe_test_fixture(rel)
        scanner_implementation = rel.as_posix() == "utils/repository_privacy.py"
        for line_no, line in enumerate(text.splitlines(), 1):
            if not test_fixture and not scanner_implementation and _PRIVATE_KEY_RE.search(line):
                findings.append(PrivacyFinding(rel.as_posix(), line_no, "PRIVATE_KEY_MATERIAL"))
            if _WINDOWS_USER_PATH_RE.search(line):
                findings.append(PrivacyFinding(rel.as_posix(), line_no, "LOCAL_USER_PATH"))
            if not test_fixture and _EMAIL_RE.search(line):
                findings.append(PrivacyFinding(rel.as_posix(), line_no, "EMAIL_OR_OPERATOR_IDENTITY"))
            if not test_fixture:
                for match in _IPV4_RE.finditer(line):
                    if not _is_repository_safe_ip(match.group(0), test_fixture=False):
                        findings.append(PrivacyFinding(rel.as_posix(), line_no, "PRIVATE_ENDPOINT_LITERAL"))
                        break
            if not test_fixture:
                match = _HIGH_RISK_ASSIGNMENT_RE.search(line)
                if match:
                    normalized = match.group(2).strip().lower()
                    if normalized not in _SAFE_LITERAL_VALUES and not normalized.startswith("["):
                        findings.append(PrivacyFinding(rel.as_posix(), line_no, "CREDENTIAL_LITERAL"))
            # Known environment-coupling form: a repository default containing
            # concrete device identities. Values are deliberately not surfaced.
            if not test_fixture and not scanner_implementation and "SECURITYEXPERT_CP_EXCLUDED_DEVICE_NAMES" in line and ":-" in line:
                default_part = line.split(":-", 1)[1].rsplit("}", 1)[0].strip()
                if default_part:
                    findings.append(PrivacyFinding(rel.as_posix(), line_no, "ENVIRONMENT_IDENTITY_LITERAL"))
        findings.extend(_python_literal_findings(rel, text))

    # Deduplicate without ever retaining matched values.
    unique = sorted(set(findings), key=lambda item: (item.path, item.line, item.rule))
    return PrivacyReport(scanned, skipped, tuple(unique))
