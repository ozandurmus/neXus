# Device administrator accounts and their privileges, collected and shown per device

status: planned · target: 

Product Owner request 2026-09-14: the configuration screen (or its own menu entry) should list the administrator accounts defined on each firewall and the privileges each carries. Not urgent. Source is the configuration read we already take: Check Point Gaia 'show configuration' carries the user and role lines (the measured section histogram showed 'set user', 'set user admin', 'set user monitor' and the aaa radius-servers block); Palo Alto's effective-running carries mgt-config users and their role/profile assignments. So no new device command is needed for a first version -- it is a projection over the artefact we already hold, which keeps it inside the current gate. Secret-bearing lines (password hashes, keys) stay withheld as they are today; only the account name, its role or profile, and whether it is enabled are shown.
