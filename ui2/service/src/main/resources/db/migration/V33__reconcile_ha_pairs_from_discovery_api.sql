-- V33__reconcile_ha_pairs_from_discovery_api.sql
-- Reconcile Palo Alto HA pairs with verified API discovery candidate evidence.
-- Corrects crossed pairs (GARTEST vs HOST) and links unattached pairs (TAKASNETWEB, TAKASNETAPP).

SELECT set_config('app.actor_fingerprint', 'migration:V33_reconcile_ha_pairs_from_discovery_api', true);
SELECT set_config('app.action_id', 'reconcile_ha_pairs_from_discovery_api', true);

-- 1. Correct FW-PALT-GARTEST reciprocal HA pair (Serials: 026109000729 | 026109000751):
UPDATE devices
SET cluster_member_ref = '026109000729|026109000751', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('b6c64284-e6cc-4da7-9d29-fd04d2643112', 'a23ee338-bd26-4c30-a66f-0e4b22139c57');

-- 2. Correct FW-PALT-HOST.AA reciprocal HA pair (Serials: 026109000730 | 026109000839):
UPDATE devices
SET cluster_member_ref = '026109000730|026109000839', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('7bc7c931-4bb8-4f6c-9dae-993a74c4e223', '3c0ccf30-1f51-4634-809f-88a7d8a14baf');

-- 3. Link FW-PALT-TAKASNETWEB reciprocal HA pair (Serials: 024409002576 | 024409002647):
UPDATE devices
SET cluster_member_ref = '024409002576|024409002647', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('061ef452-ead6-466e-b9a2-762ecaea1434', 'c0b788d9-9da4-4dad-9486-dcb54478b469');

-- 4. Complete metadata and link FW-PALT-TAKASNETAPP reciprocal HA pair (Serials: 024409002545 | 024409002564):
UPDATE devices
SET observed_hostname = 'FW-PALT-TAKASNETAPP.1',
    recorded_identity_primary = '024409002564',
    cluster_member_ref = '024409002545|024409002564',
    peer_follow_outcome = 'CORROBORATED'
WHERE device_id = '50b75d21-fa8f-4485-a926-18bbf8d1b1c8';

UPDATE devices
SET observed_hostname = 'FW-PALT-TAKASNETAPP.2',
    recorded_identity_primary = '024409002545',
    cluster_member_ref = '024409002545|024409002564',
    peer_follow_outcome = 'CORROBORATED'
WHERE device_id = 'da491718-bbd7-4f3d-84a5-9a8bc8812d13';
