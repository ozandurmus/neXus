# The Draft filter chip on the inventory screen selects but does not filter the list

status: done · target: ui2 InventoryScreen filter chips

Withdrawn 2026-09-20: this was a false finding. The chip is wired (onClick sets filterMode to draft) and the predicate is correct (enrollment_state must equal DRAFT), and clicking it on the live deployment does filter the list to the 47 unconfirmed devices with no enrolled device left in view. The earlier claim came from reading a screenshot whose visible rows happened to be drafts already, so the list looked unchanged. Recorded rather than deleted so the mistake is visible: a screenshot that looks the same is not evidence that nothing happened.
