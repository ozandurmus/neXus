UPDATE captures SET state='stale',checkpoint=0,
                error='Reattach legacy offload captures to validate segmentation/fragment metadata'
                WHERE id IN (SELECT capture_id FROM packets WHERE proto='TCP' AND length>9000
                    AND unsupported LIKE 'Possible offload super-frame%') RETURNING id