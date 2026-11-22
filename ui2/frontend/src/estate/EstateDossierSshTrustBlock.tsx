import { useEffect, useState } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getSshHostKeyView } from "../auth/adminApi";
import { EstateCard, EvidenceStamp, StateBadge } from "./EstatePrimitives";
import { ESTATE_MONO, ev } from "./estateTokens";
import {
  buildSshTrustModel,
  SSH_TRUST_STANDARD_HREF,
  type SshTrustRead,
} from "./estateSshTrustModel";

/**
 * Read-only SSH host-key trust for one unit. Reads the same call the standard SSH trust panel makes, shows the
 * trust mode word, counts and one status badge per observation with its own evidence age. Never renders key
 * material, fingerprints or addresses, and carries no accept, reject or mode-change control.
 */
export function EstateDossierSshTrustBlock({ deviceId }: { readonly deviceId: string }) {
  const [read, setRead] = useState<SshTrustRead | "loading">("loading");

  useEffect(() => {
    let active = true;
    setRead("loading");
    getSshHostKeyView(deviceId).then(
      (view) => {
        if (active) setRead({ ok: true, view });
      },
      () => {
        if (active) setRead({ ok: false });
      },
    );
    return () => {
      active = false;
    };
  }, [deviceId]);

  const header = (
    <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", px: "18px", pt: "14px", pb: "10px", flexWrap: "wrap" }}>
      <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
        SSH trust
      </Typography>
      <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
        read-only: trust mode, counts and evidence age only
      </Typography>
    </Box>
  );
  const link = (
    <Box
      component="a"
      href={SSH_TRUST_STANDARD_HREF}
      sx={{ fontSize: "12.5px", color: ev.accent, textDecoration: "none", "&:hover": { textDecoration: "underline" } }}
    >
      Manage SSH trust (standard view) →
    </Box>
  );

  if (read === "loading") {
    return (
      <EstateCard sx={{ mt: "16px" }}>
        {header}
        <Typography sx={{ px: "18px", pb: "14px", fontSize: "12.5px", color: ev.ink3 }}>Reading SSH trust</Typography>
      </EstateCard>
    );
  }

  const m = buildSshTrustModel(read);
  const c = m.counts;

  return (
    <EstateCard sx={{ mt: "16px" }}>
      <Box data-testid="dossier-ssh-trust-block">
        {header}
        <Box sx={{ px: "18px", pb: "8px", display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
          <StateBadge state={m.badge} word={m.badgeWord} dense />
          <Typography sx={{ fontSize: "14px", fontWeight: 650 }}>{m.headline}</Typography>
        </Box>
        {m.mode === null ? null : (
          <Box sx={{ px: "18px", pb: "8px", display: "flex", alignItems: "baseline", gap: "10px", flexWrap: "wrap" }}>
            <Typography sx={{ fontSize: "12px", color: ev.ink3, fontWeight: 600, minWidth: "150px" }}>Trust mode</Typography>
            <Typography sx={{ fontSize: "14px", fontWeight: 650, fontFamily: ESTATE_MONO }}>{m.mode}</Typography>
          </Box>
        )}
        {m.counts.observations === 0 ? null : (
          <Typography data-testid="ssh-trust-counts" sx={{ px: "18px", pb: "8px", fontSize: "12.5px", color: ev.ink2 }}>
            {m.counts.observations} observation{m.counts.observations === 1 ? "" : "s"} · {c.open} open · {c.rejected} rejected ·{" "}
            {c.accepted} accepted{c.unrecognised > 0 ? ` · ${c.unrecognised} unrecognised` : ""}
          </Typography>
        )}
        <Box sx={{ px: "18px", pb: "8px" }}>
          {m.rows.map((row, i) => (
            <Box
              key={row.observationId}
              data-ssh-trust-row={row.word}
              sx={{ display: "flex", alignItems: "baseline", gap: "10px", flexWrap: "wrap", py: "8px", borderTop: `1px solid ${ev.line}` }}
            >
              <Typography sx={{ fontSize: "12px", color: ev.ink3, fontWeight: 600, minWidth: "150px" }}>
                Observation {i + 1}
              </Typography>
              <StateBadge state={row.badge} word={row.word} dense />
              <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
                {row.seenCount === null
                  ? "seen count not reported"
                  : `seen ${row.seenCount} ${row.seenCount === 1 ? "time" : "times"}`}
              </Typography>
              <Box sx={{ ml: "auto" }}>
                <EvidenceStamp at={row.lastSeen} source="last seen" />
              </Box>
            </Box>
          ))}
          {m.hiddenCount > 0 ? (
            <Typography sx={{ fontSize: "12.5px", color: ev.ink3, py: "6px" }}>+{m.hiddenCount} more</Typography>
          ) : null}
        </Box>
        <Box sx={{ px: "18px", pb: "14px", pt: "4px" }}>{link}</Box>
      </Box>
    </EstateCard>
  );
}
