# Quantum Spark SFTP receiver on HOST-A

Spark and the Cyber Controller share the existing `nexus-cc` account and inbox. The four approved Spark source addresses are already in the `nexus-cc` sshd Match rule and the `sftp_push_allowed` firewall set. No separate account or inbox is required.

Link the existing SFTP Receiver credential to each Spark device as its **Backup receiver credential**. The worker uses `UI2_CC_INBOX_DIR` (`/app/cc-inbox`) and reads or deletes only the file named by its own per-run token.
