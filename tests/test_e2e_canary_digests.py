"""Offline canary generation tests; all identity examples are synthetic."""
import hashlib
import subprocess
import sys
from pathlib import Path

from scripts.e2e_canary_digests import canary_digests, is_special_address


def test_excludes_exact_special_ipv4_classes_with_cidr():
    values = ["0.0.0.0/0", "255.255.255.255", "127.0.0.0", "127.255.255.255/8",
              "169.254.0.0", "169.254.255.255/16", "224.0.0.0", "239.255.255.255/4"]
    assert all(is_special_address(value) for value in values)
    assert canary_digests(values) == []
    assert not is_special_address("192.0.2.10")
    assert not is_special_address("synthetic-serial-01")
    assert not is_special_address("2001:db8::1")


def test_hashes_identity_tokens_and_address_without_prefix():
    values = ["192.0.2.10/24", "192.0.2.10", "synthetic-serial-01", "", "  "]
    expected = sorted(hashlib.sha256(v.encode()).hexdigest()
                      for v in ["192.0.2.10", "synthetic-serial-01"])
    assert canary_digests(values) == expected


def test_cli_outputs_only_digests():
    result = subprocess.run([sys.executable, str(Path(__file__).parents[1] / "scripts/e2e_canary_digests.py")],
                            input="192.0.2.10/24\n127.0.0.1\nsynthetic-serial-01\n", text=True,
                            capture_output=True, check=True)
    assert result.stdout.splitlines() == canary_digests(["192.0.2.10", "synthetic-serial-01"])
    assert result.stderr == ""
