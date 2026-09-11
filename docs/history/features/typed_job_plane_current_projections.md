# typed_job_plane_current_projections — Product Control Plane - typed job plane + current-state projections

## summary

Job definitions and runs keyed by device_id targets with independent cadences, closed job-type vocabulary, fail-closed policy, admission-coordinator entry, and projections carrying provenance/freshness/staleness with UNKNOWN/STALE first-class.

## why

Enrollment and collection are separate; schedules reference typed capabilities, never vendor commands; cached state never claims freshness it lacks.
