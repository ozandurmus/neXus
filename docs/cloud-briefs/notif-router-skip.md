Repo github.com/ozandurmus/neXus. Setup: git fetch origin cloud-base/1105 && git checkout -b cloud/notif-router-skip-1105 origin/cloud-base/1105. Read AGENTS.md first (binding: masking law, English, UNKNOWN over guessing). No access to the live system. Small task: finish within about 15 minutes.

TASK (small code): NotificationMailRouter throws on a route type it does not know, so adding a new type (for example the planned 'scheduled_report') breaks the minute digest.
Make the router skip unknown or handler-less types with one WARN log per type per process (no error recorded on the route), and keep NotificationRoute.TYPES and the digest titles list in sync through one source.
Add tests. Run `cd ui2 && ./gradlew :service:test :architecture-tests:test`.
Commit (messages end with 'Co-Authored-By: Claude'), push cloud/notif-router-skip-1105, no PR.