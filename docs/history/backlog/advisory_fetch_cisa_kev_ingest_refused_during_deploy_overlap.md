# Advisory fetch cisa_kev ends INGEST_REFUSED when it overlaps a deploy

status: planned · target: advisory intake

2026-11-16 backlog refresh: found 2026-11-16. An advisory fetch for cisa_kev ended INGEST_REFUSED while a deploy was running. Open: confirm the refusal is the deploy overlap guard, that the next scheduled fetch succeeds, and whether the fetch should retry or skip quietly.
