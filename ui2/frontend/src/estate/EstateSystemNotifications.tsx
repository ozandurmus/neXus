import Box from "@mui/material/Box";
import type { NotificationSettingsView, SystemLogSettingsView } from "../auth/adminApi";
import { EstateCard, EvidenceStamp, MoreFooter, StateBadge } from "./EstatePrimitives";
import type { TimedRead } from "./estateSystemModel";
import {
  NOTIFICATIONS_STANDARD_HREF,
  summariseNotificationsAndLogging,
  SYSLOG_SETTINGS_STANDARD_HREF,
} from "./estateSystemNotificationsModel";
import { ESTATE_MONO, ev } from "./estateTokens";

const LINE_LABEL_SX = { color: ev.ink2, fontSize: 12.5, lineHeight: 1.35 } as const;
const LINE_VALUE_SX = {
  fontFamily: ESTATE_MONO,
  fontSize: 13.5,
  fontWeight: 600,
  color: ev.ink,
  wordBreak: "break-word",
} as const;

export interface EstateSystemNotificationsProps {
  readonly notifications: TimedRead<NotificationSettingsView>;
  readonly systemLogs: TimedRead<SystemLogSettingsView>;
  readonly now: Date;
}

/**
 * Read-only "Notifications and logging" card on the Estate System screen (Concept row 28).
 * Same source reads as the standard `NotificationSettingsPanel` and `SystemLogSettingsPanel`.
 */
export function EstateSystemNotifications({
  notifications,
  systemLogs,
  now,
}: EstateSystemNotificationsProps) {
  const summary = summariseNotificationsAndLogging(notifications, systemLogs);

  return (
    <EstateCard sx={{ p: "16px 18px", display: "flex", flexDirection: "column", gap: "10px", minWidth: 0 }}>
      <Box
        data-testid="estate-system-notifications"
        sx={{ display: "flex", flexDirection: "column", gap: "10px", minWidth: 0, flex: 1 }}
      >
        <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1 }}>
          <Box component="h2" sx={{ m: 0, fontSize: 16, fontWeight: 700 }}>
            Notifications and logging
          </Box>
          <StateBadge state={summary.badge} word={summary.word} dense />
        </Box>

        <Box
          component="dl"
          sx={{
            m: 0,
            display: "grid",
            gridTemplateColumns: "minmax(0, 1fr) auto",
            rowGap: "6px",
            columnGap: "12px",
          }}
        >
          {summary.lines.map((line, i) => (
            <Box key={`${line.label}:${i}`} sx={{ display: "contents" }}>
              <Box component="dt" sx={{ ...LINE_LABEL_SX, m: 0 }}>
                {line.label}
              </Box>
              <Box component="dd" sx={{ ...LINE_VALUE_SX, m: 0, textAlign: "right" }}>
                {line.value}
              </Box>
            </Box>
          ))}
        </Box>

        {summary.routeRowsShown.length > 0 ? (
          <Box component="ul" aria-label="Notification routes" sx={{ m: 0, p: 0 }}>
            {summary.routeRowsShown.map((row) => (
              <Box
                component="li"
                key={row.type}
                data-testid="estate-system-notification-route-row"
                sx={{
                  display: "grid",
                  gridTemplateColumns: "minmax(0, 1fr) auto",
                  gap: "4px 12px",
                  alignItems: "baseline",
                  py: "5px",
                  borderTop: `1px solid ${ev.line}`,
                  listStyle: "none",
                }}
              >
                <Box component="span" sx={{ fontSize: 12.5, color: ev.ink2, minWidth: 0 }}>
                  {row.title}
                </Box>
                <Box
                  component="span"
                  sx={{
                    fontFamily: ESTATE_MONO,
                    fontSize: 12.5,
                    fontWeight: 600,
                    color: row.hasError ? ev.watch : ev.ink,
                    textAlign: "right",
                  }}
                >
                  {`${row.enabledWord} · ${row.deliveryWord}`}
                </Box>
              </Box>
            ))}
            {summary.totalRoutes > summary.routeRowsShown.length ? (
              <Box component="li" sx={{ listStyle: "none", pt: "6px", fontSize: 12, color: ev.ink3 }}>
                <MoreFooter
                  shown={summary.routeRowsShown.length}
                  total={summary.totalRoutes}
                  noun="notification routes"
                  testId="estate-system-notifications-more"
                />
              </Box>
            ) : null}
          </Box>
        ) : null}

        <EvidenceStamp at={summary.evidenceAt} source="notifications and log collection" now={now} />

        <Box sx={{ mt: "auto", display: "flex", flexDirection: "column", gap: "4px" }}>
          <Box component="a" href={NOTIFICATIONS_STANDARD_HREF} sx={{ fontSize: 13, color: ev.accent, fontWeight: 600 }}>
            Open notifications (standard) →
          </Box>
          <Box component="a" href={SYSLOG_SETTINGS_STANDARD_HREF} sx={{ fontSize: 13, color: ev.accent, fontWeight: 600 }}>
            Open log collection (standard) →
          </Box>
        </Box>
      </Box>
    </EstateCard>
  );
}
