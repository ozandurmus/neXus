# Palo Alto Panorama: device-bundle export + running config (HTTPS + SSH)

status: planned · target: 

Product Owner, 2026-09-22: add as P1 with the Backbox trail as the measured reference. Backbox reference (trail 34411025, 2026-09-22): 'Palo Alto Networks -> Panorama -> Version 4 and above -> cURL (Device-Bundle)' -- web login (/php/login.php with csrf), /php/device/config.export.php?bundle=true -> bundle.tgz (per-device XML), then SSH: set cli scripting-mode on, set cli pager off, configure, show. Product already knows Panorama as a discovery/intent source (AGENTS.md Palo Alto); this item is the backup/export plane for Panorama itself. Prefer the XML API (keygen + export) over the PHP login path where the API offers the same bundle. Every new device command goes through the network-device command gate before implementation (AGENTS.md); credentials only via the credential store; outputs stored as artefacts through the existing backup plane.
