# PAN HA peer pairing does not match on IPv6

status: deferred · target: Dual-stack PAN estates

OP.0a.P7's peer-pairing closure extracts peer_ipv6 from the running-config XML (proven real via configuration/pan_semantic_policy.py's existing _MEMBER_SPECIFIC_EXACT_SUFFIXES) but utils.failover.assessment._derive_pan_units matches only on IPv4 (by_management_ip is a plain string-keyed dict). A dual-stack PAN HA pair gets no pairing benefit from peer_ipv6 today. Needs an explicit IPv6 address-normalization design decision, not smuggled into a narrow closure.
