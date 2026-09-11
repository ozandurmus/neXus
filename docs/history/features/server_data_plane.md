# server_data_plane — Server Data Plane

## summary

Deliberate single-worker server data plane first: PostgreSQL when enabled, durable evidence payloads, and recovery storage; add queues or object storage only after measured scale requires them.

## why

Hardens local persistence for an internal server while avoiding premature distributed components that add operational and security surface.
