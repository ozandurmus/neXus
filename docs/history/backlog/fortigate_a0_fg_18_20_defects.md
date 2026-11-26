# FortiGate lookup contradicts A0-FG-18 and A0-FG-20 (disabled DEFECT tests); A0-FG-11 and A0-FG-19 not modelled

status: planned · target: A0-FG-18 and A0-FG-20 fixed in b2aec1f5; A0-FG-11 and A0-FG-19 still not modelled

2026-11-26 (G746-D27): Retired 2 @Disabled tests in FortiGateParsersTest that contradicted LIFECYCLE_LICENSE_STORAGE_CONTRACT.md section 2 (fgt_autoupdate_versions) and pinned parser + LifecycleProjection behaviour: expired date -> VALID + expires_on + detail, block without contract line -> no item and skipped=0, n/a -> UNKNOWN, unlabellable title -> skipped=1; projection past VALID -> EXPIRED (license_problem=true), future VALID -> DATED (license_problem=false), undated VALID SUBSCRIPTION -> status=VALID/label=VALID (reported as projection observation, production code unchanged). Open PO question: whether autoupdate blocks without Contract Expiry Date should remain unstored or emit UNKNOWN.
