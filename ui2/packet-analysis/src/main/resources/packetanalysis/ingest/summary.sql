SELECT min(ts),max(ts),count(*),count(*) FILTER(WHERE caplen<wirelen),
                        count(*) FILTER(WHERE length>1500),count(*) FILTER(WHERE unsupported IS NOT NULL),
                        max(caplen),min(caplen) FILTER(WHERE caplen<wirelen),max(caplen) FILTER(WHERE caplen<wirelen)
                        FROM packets WHERE capture_id=?