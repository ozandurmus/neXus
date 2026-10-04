"""Generate ui2-e2e-canary's sha256 payload from stdin, never printing source values."""
import hashlib
import ipaddress
import sys


def is_special_address(value):
    """Protocol-scoped IPv4 values are routing semantics, not estate identities."""
    try:
        address = ipaddress.ip_address(value.split("/", 1)[0])
    except ValueError:
        return False
    if not isinstance(address, ipaddress.IPv4Address):
        return False
    return (int(address) in (0, 0xFFFFFFFF)
            or address in ipaddress.IPv4Network("127.0.0.0/8")
            or int(address) >> 16 == (169 << 8 | 254)
            or address in ipaddress.IPv4Network("224.0.0.0/4"))


def canary_digests(values):
    digests = set()
    for value in values:
        value = value.strip()
        if not value or is_special_address(value):
            continue
        try:
            value = str(ipaddress.ip_address(value.split("/", 1)[0]))
        except ValueError:
            pass  # Non-address identity tokens (e.g. serials) remain exact.
        digests.add(hashlib.sha256(value.encode("utf-8")).hexdigest())
    return sorted(digests)


if __name__ == "__main__":
    for digest in canary_digests(sys.stdin):
        print(digest)
