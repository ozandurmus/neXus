import re

file_path = "ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/CheckPointDiscoveryCandidateMapper.java"
with open(file_path, "r") as f:
    content = f.read()

old_str = """            case STANDALONE_VIRTUAL_SYSTEM, VIRTUAL_SYSTEM_MEMBER ->
                    row.hostLink().isPresent() && row.hostLink().get() instanceof HostLink.Linked;"""
new_str = """            case STANDALONE_VIRTUAL_SYSTEM, VIRTUAL_SYSTEM_MEMBER -> true; // Check Point API doesn't return host link in discovery, so we must allow standalone import"""

content = content.replace(old_str, new_str)

with open(file_path, "w") as f:
    f.write(content)
