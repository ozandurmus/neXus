Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/notify-raw-names-audit-1105 origin/cloud-base/1105. Read AGENTS.md first (binding). No access to the live system. Keep it small: finish within about 15 minutes.

TASK (document plus a small fix if clear-cut): today's notification e-mails (NotificationMailRouter, SmtpRelaySender) and syslog forwarding (SyslogSender, NotificationForwarder) put raw device hostnames (coalesce(d.observed_hostname, ...)) into messages that leave the appliance.
Write docs/design/NOTIFICATION_IDENTITY_EXPOSURE_2026_11_05.md listing every field that carries an operational identity (hostnames, IPs, serials), per notification type, with file:line.
Propose an option: a setting 'notification identity: masked | raw', default masked, using TopologyNamePseudonymizer.
If the change is small and safe, implement the setting with tests. Otherwise stop at the document.
Run `cd ui2 && ./gradlew :service:test :architecture-tests:test` if code changed.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/notify-raw-names-audit-1105, no PR.