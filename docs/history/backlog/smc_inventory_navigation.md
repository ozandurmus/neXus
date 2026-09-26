# SMC managed-device navigation and configuration refresh visibility

status: in_progress · target: ui2/frontend/src/screens/InventoryPanels.tsx

Measured stored SMC evidence: one enrolled manager, ten completed inventory jobs, seven members in latest inventory, zero discovery runs/candidates. AIView confirmed children exist on Managed devices while default Interfaces incorrectly looked empty; listed-child clicks selected only the manager. Fix defaults and child selection/address visibility, dynamic vendor filters, and configuration projection/text refresh after collection. No device contacted. Fortinet configuration evidence remains missing on nine enrolled gateways after historical skipped onboarding; normal collection is required, not fabricated data.

Correction to coverage wording: one of ten enrolled Fortinet devices has configuration evidence; this does not establish that nine gateways lack it. UI tests 207/207 and production build passed. Changes are display/navigation/refresh fixes, not new collectors or device commands.
