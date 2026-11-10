# HOST-A keeps its own cri-containerd AppArmor profile: kernels -42 and -43 still stack the label; remove it when Ubuntu restores the old labelling (ledger entry 5/6)

status: planned · target: HOST_LEDGER_HOST-A entries 5-6

2026-11-10 (plan refresh): kernel 7.0.0-42 (2026-10-31) and 7.0.0-43 (2026-11-10) both label container tasks cri-containerd.apparmor.d//&unconfined, so containerd's generated signal rule no longer matches and Postgres cannot signal its checkpointer. HOST-A loads deploy/hosta/apparmor/cri-containerd.apparmor.d before k3s. Revert per ledger entry 5: delete the profile, restart k3s, confirm no operation=signal denials in the audit log. Check on every kernel update. Also recorded for HOST-A (PO 2026-11-10): the corporate vulnerability scanner keeps a permanent allow in the host firewall (set scanner_allowed in /etc/nexus/firewall.nft, address on the host only).
