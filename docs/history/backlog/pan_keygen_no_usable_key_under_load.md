# Palo Alto keygen returned no usable key after ~8 s on 3 devices in the same minute (2026-09-22 bulk collect) -- check whether it follows concurrency; retry once with backoff before failing the inventory job

status: planned · target:

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Palo Alto keygen returned no usable key after ~8 s on 3 devices in the same minute (2026-09-22 bulk collect) -- check whether it follows concurrency; retry once with backoff before failing the inventory job

2026-11-14 backlog truth: still open (P3, size S). Today: Rare transient inventory failure; none in 14 days. Evidence: No retry in PanXmlApiTransport/InventoryCapabilityExecutor; 33 keygen failures 10-15..10-31, none since
