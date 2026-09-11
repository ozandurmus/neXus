# PAN default-route classification characterization xfail

status: automated_validated · target: 0.6.6A

0.6.6A AUTOMATED_VALIDATED 2026-08-27. parse_routes now classifies destination 0.0.0.0/0 as type=default before static/connected flag checks; former strict xfail converted to passing regression.
