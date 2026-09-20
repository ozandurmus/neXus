-- V32__cluster_ha_pair_reconciliation.sql
-- Reconcile and unify Palo Alto HA pairs with deterministic cluster references (serialA|serialB)

SELECT set_config('app.actor_fingerprint', 'migration:V32_cluster_ha_pair_reconciliation', true);
SELECT set_config('app.action_id', 'cluster_ha_pair_reconciliation_by_migration', true);

-- FW-PALT-PENDIKCAMPUS pair (user reported split bfe1f040... and 07e03889...):
UPDATE devices
SET cluster_member_ref = '025501001167|025509000707', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('6b890516-882a-4a27-b6f6-d3699a9ea20b', '94a7181f-680d-4a6e-8d3d-05634eea6726');

-- MigroFw pair (user reported e724dea5... and peer):
UPDATE devices
SET cluster_member_ref = '025201003352|025201003378', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('52f3d07a-09df-47ab-aa79-388ecac30a22', 'e724dea5-10fa-469a-acd9-bd5362c84e8a');

-- Additional reciprocal Palo Alto HA pairs:
UPDATE devices
SET cluster_member_ref = '025501001268|025501001306', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('787129df-d387-4a6b-b095-513425d2ca37', '2b9425c4-20a4-4f98-b98f-71847fde5ac8');

UPDATE devices
SET cluster_member_ref = '026109000515|026109000528', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('db7f19da-0dc7-4914-8044-7492427fcb22', 'db450687-d7ee-4b1e-9bdd-45b4e905d29b');

UPDATE devices
SET cluster_member_ref = '012501004813|012501004885', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('6cfeba1d-3040-426b-93a2-b4a5132b1f7e', 'e2fbc367-fe6c-4db6-9817-6be67dbf7cbd');

UPDATE devices
SET cluster_member_ref = '016301001387|016301001399', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('e8d0f911-a3e2-45c8-953e-3b1dfbccd2a0', '6700b0ac-7348-402a-9781-3c3b3ef790d7');

UPDATE devices
SET cluster_member_ref = '024409002570|024409002572', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('d40c460e-518c-41c1-99fd-11ae9eda0712', 'a541c739-2888-446d-a845-fc8fb943f528');

UPDATE devices
SET cluster_member_ref = '026109000745|026109000746', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('5e9aebfe-ddfe-4fbb-8a6b-6ee4a821bfea', '0cc9733c-50e0-47e6-9138-ab84177d33d9');

UPDATE devices
SET cluster_member_ref = '013201024067|013201024068', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('ac144180-49b2-40c2-a88a-0a83578c5023', 'c2f28087-3dd4-4b1a-a417-99f1b06ebd88');

UPDATE devices
SET cluster_member_ref = '013201019346|013201019379', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('98cbba8c-9f11-4ca8-be88-c7784ac7a543', '21315cee-3e6d-4764-aa31-fd568931c0a4');

UPDATE devices
SET cluster_member_ref = '024401000967|024401001041', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('3ae318a9-bc86-437c-a4ec-d21e41ff30a5', '89b24859-2643-4514-bebc-c557d1729b72');

UPDATE devices
SET cluster_member_ref = '013101014090|013101016683', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('b496a295-76d8-4c1e-90f6-930e418fc997', 'd05c9f80-17e6-4567-9f95-847ac44cd757');

UPDATE devices
SET cluster_member_ref = '013101014079|013101014170', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('2efba135-40a6-4ffa-97f6-16734ad87f7e', '9666e234-8e5c-458f-8e93-8d6fa8f61d11');

UPDATE devices
SET cluster_member_ref = '026109000751|026109000839', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('b6c64284-e6cc-4da7-9d29-fd04d2643112', '7bc7c931-4bb8-4f6c-9dae-993a74c4e223');

UPDATE devices
SET cluster_member_ref = '026109000729|026109000730', peer_follow_outcome = 'CORROBORATED'
WHERE device_id IN ('a23ee338-bd26-4c30-a66f-0e4b22139c57', '3c0ccf30-1f51-4634-809f-88a7d8a14baf');
