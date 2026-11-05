import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import Typography from "@mui/material/Typography";

import { STATUS, STATUS_INK } from "../shell/Charts";
import { FRESHNESS_COLOR } from "../shell/freshness";
import { StaleEvidenceBanner } from "../shell/StaleEvidenceBanner";
import { relativeAge } from "../shell/time";
import { m3 } from "../theme/m3Theme";
import { EXEC, HeroFact } from "../screens/overview/EstateMap";
import { attentionLines, evidencedShares, executiveVerdict, freshnessSentence, primaryFrameworksFirst } from "../screens/overview/executiveHome";
import { EXEC_HOME } from "./previewData";

const CARD = { borderRadius: "10px", bgcolor: m3.scLowest, border: `1px solid ${m3.outlineVar}`, p: 2.25, boxShadow: "none" } as const;

/**
 * The executive home (UI review 2026-11-05, C1) on synthetic, masked figures: the verdict word and freshness sentence,
 * the four pillars, at most five "needs attention" lines, then the framework bars and the change pulse below the fold.
 * The logic is the live screen's own (`overview/executiveHome.ts`); only the figures are synthetic.
 */
export function OverviewPreview() {
  const now = new Date();
  const ago = (h: number) => new Date(now.getTime() - h * 3_600_000).toISOString();
  const x = EXEC_HOME;
  const figures = { security: x.security, backups: x.backups, ha: x.ha, freshness: x.freshness };
  const verdict = executiveVerdict(figures);
  const newest = ago(x.newestReadHoursAgo);
  const oldest = ago(x.oldestReadHoursAgo);
  const lines = attentionLines({
    ...figures,
    ages: { haNotReadyAt: ago(x.readinessHoursAgo), haUnknownAt: ago(x.readinessHoursAgo), backupAt: ago(x.backupRunHoursAgo),
      complianceAt: ago(x.complianceHoursAgo), oldestReadAt: oldest },
    topControl: x.topControl,
  });
  const ink = verdict.word === "HEALTHY" ? STATUS_INK.good : verdict.word === "AT RISK" ? STATUS_INK.critical : m3.neutralInk;
  return (
    <Box sx={{ p: { xs: 2, md: 4 }, flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 2 }}>
      <Box>
        <Typography variant="h2">Overview</Typography>
        <Typography variant="body2">Estate assurance · {x.devices} enrolled devices · {x.clusters} clusters · synthetic preview figures</Typography>
      </Box>
      <StaleEvidenceBanner freshness={x.freshness} newestAt={newest} />
      <Card sx={CARD}>
        <Box sx={{ display: "flex", gap: 2, alignItems: "center", flexWrap: "wrap" }}>
          <Box sx={{ px: 2, py: 0.75, borderRadius: "10px", bgcolor: verdict.word === "HEALTHY" ? `${STATUS.good}1f` : verdict.word === "AT RISK" ? `${STATUS.critical}1f` : m3.sc, color: ink, fontSize: 26, fontWeight: 800, letterSpacing: "0.04em" }}>{verdict.word}</Box>
          <Box sx={{ minWidth: 0, flex: "1 1 260px" }}>
            <Typography sx={{ fontSize: 15, fontWeight: 600 }}>{verdict.reason}</Typography>
            <Typography sx={{ fontSize: 13.5, color: m3.onSurfaceVar }}>{freshnessSentence(x.freshness, newest, oldest, now)}</Typography>
          </Box>
        </Box>
      </Card>
      <Box sx={{ display: "grid", gap: 1.5, gridTemplateColumns: { xs: "1fr", sm: "1fr 1fr", lg: "repeat(4, 1fr)" } }}>
        <Card sx={{ ...CARD, p: 0.5 }}><HeroFact label="Security posture" value={x.security.failing} of={x.security.of} unit="firewalls" bad={x.security.failing}
          color={EXEC.red} sliverLabel="with a critical failure" sentence="firewalls with at least one critical check failing" href="?preview=compliance" /></Card>
        <Card sx={{ ...CARD, p: 0.5 }}><HeroFact label="Backup coverage" value={x.backups.archived} of={x.backups.of} unit="targets backed up" bad={x.backups.of - x.backups.archived}
          color={EXEC.red} sliverLabel="without a backup" sentence={`${x.backups.of - x.backups.archived} backup targets have no stored backup`} href="?preview=backups" /></Card>
        <Card sx={{ ...CARD, p: 0.5 }}><HeroFact label="HA readiness" value={x.ha.ready} of={x.ha.total} unit="clusters ready" bad={0} color={EXEC.red}
          segments={[{ count: x.ha.notReady, color: EXEC.red, label: "not ready" }, { count: x.ha.unknown, color: EXEC.hatch, label: "unknown" }]}
          sentence={`${x.ha.ready} ready of ${x.ha.total} clusters · ${x.ha.notReady} not ready · ${x.ha.unknown} unknown · ${x.ha.unsupported} not supported`} href="?preview=operations" /></Card>
        <Card sx={{ ...CARD, p: 0.5 }}><HeroFact label="Data freshness" value={x.freshness.fresh} of={x.freshness.of} unit="devices" bad={x.freshness.of - x.freshness.fresh}
          color={FRESHNESS_COLOR.ageing} sliverLabel="not read in 24 h" sentence="read in the last 24 h" href="?preview=inventory" /></Card>
      </Box>
      <Card sx={CARD}>
        <Typography sx={{ fontSize: 16, fontWeight: 700, mb: 1 }}>Needs attention</Typography>
        {lines.map((l, i) => (
          <Box key={l.key} sx={{ display: "flex", flexWrap: "wrap", alignItems: "baseline", columnGap: 1.25, rowGap: 0.25, py: 1, borderTop: i ? `1px solid ${m3.outlineVar}` : "none" }}>
            <Box sx={{ width: 10, height: 10, borderRadius: "50%", flex: "none", bgcolor: STATUS[l.severity], alignSelf: "center" }} />
            <Typography sx={{ fontSize: 14.5, fontWeight: 600, flex: "1 1 220px", minWidth: 0 }}>{l.text}</Typography>
            <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar, whiteSpace: "nowrap", ml: "auto" }}>{l.ageOf} {relativeAge(l.at, now)} →</Typography>
          </Box>
        ))}
      </Card>
      <Box sx={{ display: "grid", gap: 2, gridTemplateColumns: { xs: "1fr", lg: "1fr 1fr" } }}>
        <Card sx={CARD}>
          <Typography sx={{ fontSize: 16, fontWeight: 700 }}>Compliance by framework</Typography>
          <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar, mb: 1.5 }}>Technical checks. Not an audit certification.</Typography>
          {primaryFrameworksFirst(x.frameworks, (f) => f.name).map((f) => {
            const s = evidencedShares(f.pass, f.fail, f.unavailable);
            const t = f.pass + f.fail + f.unavailable;
            return (
              <Box key={f.name} sx={{ mb: 1.5 }}>
                <Box sx={{ display: "flex", justifyContent: "space-between", gap: 1, mb: 0.5, flexWrap: "wrap" }}>
                  <Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>{f.name}</Typography>
                  <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar }}><b>{s.passPct}%</b> of evidenced controls pass · {s.noEvidencePct}% have no evidence</Typography>
                </Box>
                <Box sx={{ display: "flex", height: 14, borderRadius: 7, overflow: "hidden", gap: "2px" }}>
                  {[[f.pass, EXEC.green], [f.fail, EXEC.red], [f.unavailable, EXEC.hatch]].map(([c, bg], i) => (
                    <Box key={i} sx={{ width: `${(100 * Number(c)) / t}%`, background: String(bg) }} />
                  ))}
                </Box>
              </Box>
            );
          })}
        </Card>
        <Card sx={CARD}>
          <Typography sx={{ fontSize: 16, fontWeight: 700 }}>Change pulse</Typography>
          <Typography sx={{ fontSize: 12.5, color: m3.onSurfaceVar, mb: 1 }}>what moved in the estate</Typography>
          {x.pulse.map((p, i) => (
            <Box key={p.label} sx={{ display: "flex", justifyContent: "space-between", gap: 1.5, py: 1, borderTop: i ? `1px solid ${m3.outlineVar}` : "none" }}>
              <Box><Typography sx={{ fontSize: 13.5, fontWeight: 600 }}>{p.label}</Typography><Typography sx={{ fontSize: 12, color: m3.onSurfaceVar }}>{p.hint}</Typography></Box>
              <Typography sx={{ fontSize: 14, fontWeight: 700, whiteSpace: "nowrap" }}>{p.value} →</Typography>
            </Box>
          ))}
        </Card>
      </Box>
    </Box>
  );
}
