# Quantum Spark SFTP receiver on HOST-A

Apply on HOST-A only after substituting the approved Spark source addresses and setting the receiver secret through the credential store. This file is a runbook, not a deploy script.

1. Create a dedicated group and account. The group ID matches the worker's supplemental group; choose a free ID if 2601 is occupied and update the worker manifest accordingly.

   ```sh
   sudo groupadd --gid 2601 nexusspark
   sudo useradd --system --home-dir /var/lib/nexus-spark --shell /usr/sbin/nologin --gid nexusspark nexus-spark
   sudo install -d -o root -g root -m 0755 /var/lib/nexus-spark
   sudo install -d -o nexus-spark -g nexusspark -m 2770 /var/lib/nexus-spark/in
   ```

2. Set a unique account password locally. Add its credential reference as the device's **Backup receiver credential**. Do not put the password in this repository, a manifest, or a command transcript.

3. Append this block to the end of sshd configuration, replacing the placeholder with the approved source list. The `Match Address` list and the firewall set must match. Validate with `sshd -t` before reloading sshd.

   ```text
   Match User nexus-spark Address <SPARK_SOURCE_1>,<SPARK_SOURCE_2>
       ChrootDirectory /var/lib/nexus-spark
       ForceCommand internal-sftp -u 0027
       PasswordAuthentication yes
       PermitTTY no
       AllowTcpForwarding no
       X11Forwarding no
   ```

4. Add only the approved Spark source addresses to the `sftp_push_allowed` set in the installed `/etc/nexus/firewall.nft`, then validate and reload the host firewall. Keep the repository placeholder `@SFTP_PUSH_SOURCES@` unchanged. The existing `UI2_CC_RECEIVER_HOST` ConfigMap provides the receiver address to the worker; `NEXUS_SPARK_INBOX` points at `/app/spark-inbox`.

5. Confirm `/var/lib/nexus-spark` is root owned and not writable by `nexus-spark`, while `in` is writable by that account and group. A backup upload should land only in `in`; the worker must be able to read and remove it.
