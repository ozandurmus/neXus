import { useCallback, useEffect, useMemo, useState, type MouseEvent } from "react";
import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { navigateTo, useUrlParamSync, writeUrlParams } from "../shell/urlParams";
import {
  countLensStates,
  ESTATE_LENSES,
  isActOrUnknown,
  LENS_META,
  readEstateWatchlist,
  sortUnitsForLens,
  toggleEstateWatchlist,
  type EstateUnit,
  type TileState,
} from "./estateModel";
import {
  EstateCard,
  EvidenceStamp,
  HaPairTiles,
  HonestBar,
  StateBadge,
  TileSwatch,
  UnitTile,
} from "./EstatePrimitives";
import {
  backupsHref,
  complianceHref,
  dossierHref,
  failoverHref,
  parseEstateLens,
  standardBackupsHref,
  standardHaHref,
  type EstateLens,
} from "./estateRouting";
import { ESTATE_MONO, ESTATE_SANS, ev } from "./estateTokens";
import { useEstateData } from "./useEstateData";

export function EstateWallScreen({
  initialLens = "worst",
  initialClusterRef = null,
}: {
  readonly initialLens?: EstateLens;
  readonly initialClusterRef?: string | null;
}) {
  const { loading, fatalError, overview, model, refresh } = useEstateData();
  const [lens, setLensState] = useState<EstateLens>(initialLens);
  const [pinnedId, setPinnedId] = useState<string | null>(null);
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [onlyAttention, setOnlyAttention] = useState<boolean>(false);
  const [vendorFilter, setVendorFilter] = useState<string>("");
  const [query, setQuery] = useState<string>("");
  const [watchlist, setWatchlist] = useState<string[]>(() => readEstateWatchlist());

  useEffect(() => {
    setLensState(initialLens);
  }, [initialLens]);

  useUrlParamSync(
    "lens",
    useCallback((raw) => {
      setLensState(parseEstateLens(raw));
    }, []),
  );

  // Preselect first member of initialClusterRef when arriving with ?cluster_ref=...
  useEffect(() => {
    if (!initialClusterRef || !model) return;
    const cl = model.clustersByRef.get(initialClusterRef);
    if (cl && cl.members.length > 0) {
      setPinnedId(cl.members[0].deviceId);
    }
  }, [initialClusterRef, model]);

  const changeLens = (next: EstateLens) => {
    setLensState(next);
    writeUrlParams({ lens: next === "worst" ? null : next }, "replace");
  };

  const activeUnit: EstateUnit | null = useMemo(() => {
    if (!model) return null;
    const id = pinnedId ?? hoveredId;
    if (id && model.byId.has(id)) return model.byId.get(id)!;
    return null;
  }, [model, pinnedId, hoveredId]);

  const handleTileSelect = useCallback(
    (unit: EstateUnit, e: MouseEvent<HTMLElement>) => {
      e.preventDefault();
      if (pinnedId === unit.deviceId) {
        navigateTo(dossierHref(unit.deviceId), "push");
        return;
      }
      setPinnedId(unit.deviceId);
    },
    [pinnedId],
  );

  const handleTileHover = useCallback(
    (unit: EstateUnit) => {
      if (!pinnedId) setHoveredId(unit.deviceId);
    },
    [pinnedId],
  );

  const sortedRows = useMemo(
    () => (model ? sortUnitsForLens(model.units, lens, { onlyAttention, query, vendor: vendorFilter }) : []),
    [model, lens, onlyAttention, query, vendorFilter],
  );

  if (fatalError) {
    return (
      <EstateCard sx={{ p: 3 }}>
        <Typography component="h1" sx={{ fontSize: "24px", fontWeight: 650, mb: 1 }}>
          Estate wall
        </Typography>
        <Box sx={{ mb: 1.5 }}>
          <StateBadge state={fatalError.restricted ? "na" : "unk"} word={fatalError.restricted ? "Restricted" : "UNKNOWN"} />
        </Box>
        <Typography sx={{ color: ev.ink2, mb: 2 }}>{fatalError.message}</Typography>
        {!fatalError.restricted ? (
          <Box
            component="button"
            type="button"
            onClick={refresh}
            sx={{
              height: "34px",
              px: "14px",
              borderRadius: "8px",
              border: `1px solid ${ev.lineStrong}`,
              bgcolor: ev.surface,
              color: ev.ink,
              fontWeight: 600,
              cursor: "pointer",
            }}
          >
            Retry
          </Box>
        ) : null}
      </EstateCard>
    );
  }

  if (loading && !model) {
    return (
      <EstateCard sx={{ p: 3 }}>
        <Typography component="h1" sx={{ fontSize: "24px", fontWeight: 650, mb: 0.5 }}>
          Estate wall
        </Typography>
        <Typography sx={{ color: ev.ink2 }}>Reading enrolled units and lens evidence…</Typography>
      </EstateCard>
    );
  }

  if (!model) return null;

  const counts = countLensStates(model.units, lens);
  const badOrUnknownCount = model.units.filter((u) => isActOrUnknown(u.lenses[lens].tileState)).length;

  const legendSpecs: ReadonlyArray<readonly [TileState, string]> =
    lens === "age"
      ? [
          ["fresh", "Under 6 h"],
          ["day", "6–24 h"],
          ["old", "24–72 h"],
          ["stale", "Over 72 h"],
          ["unk", "Never read (UNKNOWN)"],
        ]
      : [
          ["act", "Act (!)"],
          ["watch", "Review (~)"],
          ["ok", "Fine"],
          ["unk", "UNKNOWN (?)"],
          ["na", "Not applicable"],
        ];

  // Newest and oldest evidence time of the lens answers on screen; never falls back to the inventory read age
  // under a Backups/Controls/Exposure/Failover/Policy heading.
  const lensStamp = (() => {
    const src =
      lens === "failover"
        ? "readiness"
        : lens === "backups"
          ? "backup"
          : lens === "compliance"
            ? "compliance"
            : lens === "exposure"
              ? "lifecycle"
              : lens === "install"
                ? "policy read"
                : "inventory";
    const ats = model.units
      .map((u) => u.lenses[lens]?.evidenceAt)
      .filter((x): x is string => Boolean(x))
      .sort();
    const fleetAt =
      lens === "backups"
        ? overview?.evidence?.backup?.at ?? null
        : lens === "compliance"
          ? overview?.evidence?.compliance?.at ?? null
          : null;
    const newest = ats.at(-1) ?? fleetAt;
    const oldest = ats.length > 1 && ats[0] !== ats.at(-1) ? ats[0] : null;
    return { at: newest, oldest, src };
  })();
  const lensSourceStamp = { at: lensStamp.at, src: lensStamp.src };

  const distinctVendors = [...new Map(model.units.map((u) => [u.vendor, u.vendorName] as const)).entries()].sort(
    (a, b) => a[1].localeCompare(b[1]),
  );

  const isWatched = activeUnit ? watchlist.includes(activeUnit.deviceId) : false;

  return (
    <Box data-testid="estate-wall-screen">
      {/* Page Header */}
      <Box
        sx={{
          display: "flex",
          flexWrap: "wrap",
          alignItems: "flex-end",
          gap: "16px",
          mb: "18px",
        }}
      >
        <Box sx={{ minWidth: 0, flex: "1 1 520px" }}>
          <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap" }}>
            <Typography
              sx={{
                fontSize: "11px",
                letterSpacing: "0.09em",
                textTransform: "uppercase",
                color: ev.ink3,
                fontWeight: 650,
              }}
            >
              Estate
            </Typography>
            <EvidenceStamp at={overview?.generated_at ?? model.newestEvidenceAt} source="estate" now={model.now} />
          </Box>
          <Typography component="h1" sx={{ fontSize: "26px", fontWeight: 680, lineHeight: 1.2, mt: "2px" }}>
            {model.units.length} {model.units.length === 1 ? "unit" : "units"}, one wall
            {model.clusters.length > 0 ? ` · ${model.clusters.length} HA ${model.clusters.length === 1 ? "pair" : "pairs"} joined` : ""}
          </Typography>
          <Typography sx={{ fontSize: "14.5px", color: ev.ink2, mt: "5px", maxWidth: "820px" }}>
            Every active firewall, management server and appliance is one tile; HA cluster members sit joined together.
            Switch the lens to recolour the wall with one question, or click a tile for its dossier.
          </Typography>
        </Box>

        <Box sx={{ display: "flex", gap: "8px", flexWrap: "wrap", ml: "auto" }}>
          <Box
            component="a"
            href={standardHaHref()}
            sx={{
              display: "inline-flex",
              alignItems: "center",
              height: "32px",
              px: "12px",
              borderRadius: "8px",
              border: `1px solid ${ev.lineStrong}`,
              bgcolor: ev.surface,
              color: ev.ink,
              fontSize: "12.5px",
              fontWeight: 580,
              textDecoration: "none",
              "&:hover": { borderColor: ev.ink2, textDecoration: "none" },
            }}
          >
            Standard HA readiness →
          </Box>
          <Box
            component="a"
            href={standardBackupsHref()}
            sx={{
              display: "inline-flex",
              alignItems: "center",
              height: "32px",
              px: "12px",
              borderRadius: "8px",
              border: `1px solid ${ev.lineStrong}`,
              bgcolor: ev.surface,
              color: ev.ink,
              fontSize: "12.5px",
              fontWeight: 580,
              textDecoration: "none",
              "&:hover": { borderColor: ev.ink2, textDecoration: "none" },
            }}
          >
            Standard Backups →
          </Box>
        </Box>
      </Box>

      {/* Optional lens-specific honest summary bar */}
      {lens === "compliance" && overview?.compliance?.state === "OK" && (overview.compliance.frameworks?.length ?? 0) > 0 ? (
        <EstateCard sx={{ p: "14px 18px", mb: "16px" }}>
          <Box sx={{ display: "flex", alignItems: "baseline", gap: "12px", mb: "8px", flexWrap: "wrap" }}>
            <Typography component="h2" sx={{ fontSize: "15px", fontWeight: 650 }}>
              Honest control coverage ({overview.compliance.frameworks![0].name})
            </Typography>
            <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
              no-evidence checks stay hatched as their own share — never merged into pass
            </Typography>
            <Box sx={{ ml: "auto" }}>
              <EvidenceStamp at={overview.evidence?.compliance?.at} source="compliance" now={model.now} />
            </Box>
          </Box>
          <HonestBar
            height={16}
            ariaLabel={`${overview.compliance.frameworks![0].name} checks`}
            segments={[
              { key: "pass", label: "Pass", count: overview.compliance.frameworks![0].pass, state: "ok" },
              { key: "fail", label: "Fail", count: overview.compliance.frameworks![0].fail, state: "act" },
              {
                key: "unavailable",
                label: "No evidence (UNKNOWN)",
                count: overview.compliance.frameworks![0].unavailable,
                state: "unk",
              },
            ]}
          />
        </EstateCard>
      ) : null}

      {/* Main Wall Card */}
      <EstateCard>
        {/* Lens switcher & legend summary */}
        <Box
          sx={{
            display: "flex",
            flexWrap: "wrap",
            gap: "16px",
            alignItems: "center",
            px: "18px",
            py: "14px",
            borderBottom: `1px solid ${ev.line}`,
          }}
        >
          <Box>
            <Typography
              sx={{
                fontSize: "11px",
                letterSpacing: "0.09em",
                textTransform: "uppercase",
                color: ev.ink3,
                fontWeight: 650,
                mb: "5px",
              }}
            >
              Colour the wall by
            </Typography>
            <Box
              role="group"
              aria-label="Colour the wall by"
              sx={{
                display: "inline-flex",
                flexWrap: "wrap",
                border: `1px solid ${ev.lineStrong}`,
                borderRadius: "9px",
                overflow: "hidden",
              }}
            >
              {ESTATE_LENSES.map((k, idx) => {
                const on = lens === k;
                return (
                  <Box
                    key={k}
                    component="button"
                    type="button"
                    aria-pressed={on}
                    onClick={() => changeLens(k)}
                    sx={{
                      border: 0,
                      borderLeft: idx > 0 ? `1px solid ${ev.line}` : "none",
                      bgcolor: on ? ev.ink : ev.surface,
                      color: on ? ev.surface : ev.ink2,
                      px: "11px",
                      py: "6px",
                      fontSize: "12.5px",
                      fontWeight: on ? 650 : 560,
                      fontFamily: ESTATE_SANS,
                      cursor: "pointer",
                      whiteSpace: "nowrap",
                      "&:hover": { color: on ? ev.surface : ev.ink },
                    }}
                  >
                    {LENS_META[k].label}
                  </Box>
                );
              })}
            </Box>
          </Box>

          <Box sx={{ minWidth: "200px" }}>
            <Typography sx={{ fontSize: "20px", fontWeight: 700, letterSpacing: "-0.015em", lineHeight: 1.2 }}>
              {LENS_META[lens].question}
            </Typography>
            <Box sx={{ display: "flex", alignItems: "center", gap: "10px", flexWrap: "wrap", mt: "3px" }}>
              <Typography sx={{ fontSize: "12.5px", color: ev.ink2 }}>
                <b style={{ fontFamily: ESTATE_MONO, color: ev.ink }}>{badOrUnknownCount}</b>{" "}
                {badOrUnknownCount === 1 ? "unit needs" : "units need"} action or {badOrUnknownCount === 1 ? "is" : "are"} UNKNOWN on this lens
              </Typography>
              <EvidenceStamp at={lensSourceStamp.at} source={`${lensSourceStamp.src} (newest)`} now={model.now} />
              {lensStamp.oldest ? (
                <EvidenceStamp at={lensStamp.oldest} source={`${lensStamp.src} (oldest)`} now={model.now} />
              ) : null}
              {lens === "failover" || lens === "backups" || lens === "compliance" ? (
                <Box
                  component="a"
                  href={lens === "failover" ? failoverHref() : lens === "backups" ? backupsHref() : complianceHref()}
                  sx={{
                    fontSize: "12.5px",
                    fontWeight: 620,
                    color: ev.accent,
                    textDecoration: "none",
                    "&:hover": { textDecoration: "underline" },
                  }}
                >
                  {lens === "failover"
                    ? "Open Failover board →"
                    : lens === "backups"
                      ? "Open Backups lens →"
                      : "Open Controls lens →"}
                </Box>
              ) : null}
            </Box>
          </Box>

          <Box
            sx={{
              ml: { xs: 0, xl: "auto" },
              display: "flex",
              gap: "14px",
              flexWrap: "wrap",
              alignItems: "center",
              fontSize: "12.5px",
              color: ev.ink2,
            }}
          >
            {legendSpecs.map(([st, label]) => {
              const n = counts[st] ?? 0;
              return (
                <Box key={st} component="span" sx={{ display: "inline-flex", alignItems: "center", gap: "6px" }}>
                  <TileSwatch state={st} size={16} label={label} />
                  <span>{label}</span>
                  <Box component="b" sx={{ fontFamily: ESTATE_MONO, color: ev.ink }}>
                    {n}
                  </Box>
                </Box>
              );
            })}
          </Box>
        </Box>

        {/* Two-column Wall + Selected Unit Inspector */}
        <Box
          sx={{
            display: "grid",
            gridTemplateColumns: { xs: "1fr", lg: "minmax(0, 1fr) 324px" },
          }}
        >
          <Box data-testid="estate-tile-wall">
            {model.vendorGroups.length === 0 ? (
              <Box sx={{ p: 3 }}>
                <Typography sx={{ color: ev.ink2 }}>No active enrolled units are recorded yet.</Typography>
              </Box>
            ) : (
              model.vendorGroups.map((grp, grpIndex) => (
                <Box
                  key={grp.key}
                  data-vendor-group={grp.key}
                  sx={{
                    px: "18px",
                    py: "12px",
                    borderTop: grpIndex > 0 ? `1px solid ${ev.line}` : "none",
                  }}
                >
                  <Box sx={{ display: "flex", alignItems: "baseline", gap: "10px", mb: "8px", flexWrap: "wrap" }}>
                    <Typography component="h3" sx={{ fontSize: "14px", fontWeight: 650 }}>
                      {grp.title}
                    </Typography>
                    <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "12px", color: ev.ink3 }}>
                      {grp.unitCount} {grp.unitCount === 1 ? "unit" : "units"}
                      {grp.clusters.length > 0
                        ? ` · ${grp.clusters.length} ${grp.clusters.length === 1 ? "cluster" : "clusters"}`
                        : ""}
                    </Typography>
                    <Box sx={{ ml: "auto" }}>
                      <EvidenceStamp at={grp.newestReadAt} source="newest" now={model.now} />
                    </Box>
                  </Box>

                  <Box sx={{ display: "flex", flexWrap: "wrap", gap: "6px", alignItems: "center" }}>
                    {grp.clusters.length > 0 ? (
                      <>
                        <Typography
                          sx={{
                            fontSize: "11px",
                            color: ev.ink3,
                            letterSpacing: "0.05em",
                            textTransform: "uppercase",
                            width: "100%",
                            mt: "2px",
                          }}
                        >
                          HA clusters
                        </Typography>
                        {grp.clusters.map((cl) => (
                          <HaPairTiles
                            key={cl.ref}
                            cluster={cl}
                            lens={lens}
                            selectedId={activeUnit?.deviceId ?? null}
                            now={model.now}
                            onSelect={handleTileSelect}
                            onHover={handleTileHover}
                          />
                        ))}
                      </>
                    ) : null}

                    {grp.standaloneGateways.length > 0 ? (
                      <>
                        <Typography
                          sx={{
                            fontSize: "11px",
                            color: ev.ink3,
                            letterSpacing: "0.05em",
                            textTransform: "uppercase",
                            width: "100%",
                            mt: grp.clusters.length > 0 ? "6px" : "2px",
                          }}
                        >
                          Standalone gateways
                        </Typography>
                        {grp.standaloneGateways.map((u) => (
                          <UnitTile
                            key={u.deviceId}
                            unit={u}
                            lens={lens}
                            selected={activeUnit?.deviceId === u.deviceId}
                            now={model.now}
                            onSelect={handleTileSelect}
                            onHover={handleTileHover}
                          />
                        ))}
                      </>
                    ) : null}

                    {grp.appliances.length > 0 ? (
                      <>
                        <Typography
                          sx={{
                            fontSize: "11px",
                            color: ev.ink3,
                            letterSpacing: "0.05em",
                            textTransform: "uppercase",
                            width: "100%",
                            mt: grp.clusters.length > 0 || grp.standaloneGateways.length > 0 ? "6px" : "2px",
                          }}
                        >
                          Appliances
                        </Typography>
                        {grp.appliances.map((u) => (
                          <UnitTile
                            key={u.deviceId}
                            unit={u}
                            lens={lens}
                            selected={activeUnit?.deviceId === u.deviceId}
                            now={model.now}
                            onSelect={handleTileSelect}
                            onHover={handleTileHover}
                          />
                        ))}
                      </>
                    ) : null}

                    {grp.managers.length > 0 ? (
                      <>
                        <Typography
                          sx={{
                            fontSize: "11px",
                            color: ev.ink3,
                            letterSpacing: "0.05em",
                            textTransform: "uppercase",
                            width: "100%",
                            mt:
                              grp.clusters.length > 0 || grp.standaloneGateways.length > 0 || grp.appliances.length > 0
                                ? "6px"
                                : "2px",
                          }}
                        >
                          Management servers
                        </Typography>
                        {grp.managers.map((u) => (
                          <UnitTile
                            key={u.deviceId}
                            unit={u}
                            lens={lens}
                            selected={activeUnit?.deviceId === u.deviceId}
                            now={model.now}
                            onSelect={handleTileSelect}
                            onHover={handleTileHover}
                          />
                        ))}
                      </>
                    ) : null}
                  </Box>
                </Box>
              ))
            )}
          </Box>

          {/* Selected / Hovered Unit Inspector */}
          <Box
            component="aside"
            aria-label="Selected unit summary"
            data-testid="estate-unit-inspector"
            sx={{
              borderLeft: { xs: "none", lg: `1px solid ${ev.line}` },
              borderTop: { xs: `1px solid ${ev.line}`, lg: "none" },
              p: "14px 18px 16px",
            }}
          >
            <Typography
              sx={{
                fontSize: "11px",
                letterSpacing: "0.09em",
                textTransform: "uppercase",
                color: ev.ink3,
                fontWeight: 650,
              }}
            >
              {pinnedId ? "Pinned unit" : "Selected"}
            </Typography>
            {!activeUnit ? (
              <>
                <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "16px", fontWeight: 650, mt: "4px" }}>
                  Hover or click a tile
                </Typography>
                <Typography sx={{ fontSize: "13px", color: ev.ink3, mt: "8px" }}>
                  Inspects every lens for one operational unit, each with the age of its stored evidence. Click once to
                  pin, click again or press Open dossier for the full unit dossier.
                </Typography>
              </>
            ) : (
              <>
                <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1, mt: "4px" }}>
                  <Typography sx={{ fontFamily: ESTATE_MONO, fontSize: "16px", fontWeight: 700 }}>
                    {activeUnit.name}
                  </Typography>
                  {pinnedId ? (
                    <Box
                      component="button"
                      type="button"
                      onClick={() => setPinnedId(null)}
                      sx={{
                        border: 0,
                        background: "none",
                        color: ev.ink3,
                        fontSize: "12px",
                        cursor: "pointer",
                      }}
                    >
                      Unpin
                    </Box>
                  ) : null}
                </Box>
                <Typography sx={{ fontSize: "12.5px", color: ev.ink3, mb: "8px" }}>
                  {activeUnit.vendorName}
                  {activeUnit.model ? ` ${activeUnit.model}` : ""}
                  {activeUnit.version ? ` · ${activeUnit.version}` : ""}
                  {activeUnit.clusterRef
                    ? ` · ${activeUnit.clusterRef}${activeUnit.haRole ? ` ${activeUnit.haRole.toLowerCase()}` : ""}`
                    : ` · ${activeUnit.role.replace("_", " ")}`}
                </Typography>

                {ESTATE_LENSES.slice(1).map((k, idx) => {
                  const ans = activeUnit.lenses[k];
                  return (
                    <Box
                      key={k}
                      sx={{
                        display: "flex",
                        flexDirection: "column",
                        gap: "4px",
                        py: "7px",
                        borderTop: idx > 0 ? `1px solid ${ev.line}` : "none",
                        fontSize: "12.5px",
                      }}
                    >
                      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "8px" }}>
                        <span style={{ color: ev.ink2, fontWeight: 560 }}>{LENS_META[k].label}</span>
                        <StateBadge state={ans.badgeState} word={ans.badgeWord} dense />
                      </Box>
                      <Box sx={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "8px" }}>
                        <Typography sx={{ fontSize: "12px", color: ev.ink3, minWidth: 0 }} noWrap title={ans.summary}>
                          {ans.summary}
                        </Typography>
                        <EvidenceStamp at={ans.evidenceAt} now={model.now} />
                      </Box>
                    </Box>
                  );
                })}

                <Box sx={{ mt: "12px", display: "flex", gap: "8px", alignItems: "center", flexWrap: "wrap" }}>
                  <Box
                    component="a"
                    href={dossierHref(activeUnit.deviceId)}
                    sx={{
                      display: "inline-flex",
                      alignItems: "center",
                      height: "30px",
                      px: "12px",
                      borderRadius: "8px",
                      bgcolor: ev.accent,
                      color: ev.accentInk,
                      fontSize: "12.5px",
                      fontWeight: 620,
                      textDecoration: "none",
                      "&:hover": { textDecoration: "none", opacity: 0.92 },
                    }}
                  >
                    Open dossier
                  </Box>
                  <Box
                    component="button"
                    type="button"
                    aria-pressed={isWatched}
                    onClick={() => setWatchlist(toggleEstateWatchlist(activeUnit.deviceId))}
                    sx={{
                      height: "30px",
                      px: "10px",
                      borderRadius: "8px",
                      border: `1px solid ${ev.lineStrong}`,
                      bgcolor: isWatched ? ev.surface2 : ev.surface,
                      color: ev.ink,
                      fontSize: "12px",
                      fontWeight: 600,
                      cursor: "pointer",
                    }}
                  >
                    {isWatched ? "Watching" : "Watch"}
                  </Box>
                  <EvidenceStamp at={activeUnit.lastReadAt} source="last read" now={model.now} missingLabel="never read" />
                </Box>
              </>
            )}
          </Box>
        </Box>
      </EstateCard>

      {/* As a list */}
      <EstateCard sx={{ mt: "16px" }}>
        <Box
          sx={{
            display: "flex",
            flexWrap: "wrap",
            alignItems: "center",
            gap: "12px",
            px: "18px",
            pt: "14px",
            pb: "10px",
          }}
        >
          <Typography component="h2" sx={{ fontSize: "17px", fontWeight: 650 }}>
            As a list
          </Typography>
          <Typography sx={{ fontSize: "12.5px", color: ev.ink3 }}>
            sorted by {LENS_META[lens].label.toLowerCase()}, worst first; UNKNOWN sorts with problems, never with fine
          </Typography>
          <Box sx={{ ml: "auto", display: "flex", gap: "8px", flexWrap: "wrap", alignItems: "center" }}>
            <Box
              component="button"
              type="button"
              aria-pressed={onlyAttention}
              onClick={() => setOnlyAttention((v) => !v)}
              sx={{
                height: "28px",
                px: "11px",
                borderRadius: "999px",
                border: `1px solid ${onlyAttention ? ev.ink : ev.line}`,
                bgcolor: ev.surface,
                color: onlyAttention ? ev.ink : ev.ink2,
                fontWeight: onlyAttention ? 650 : 500,
                fontSize: "12.5px",
                boxShadow: onlyAttention ? `inset 0 0 0 1px ${ev.ink}` : "none",
                cursor: "pointer",
              }}
            >
              Only units needing attention
            </Box>
            <Box
              component="select"
              aria-label="Filter by vendor"
              value={vendorFilter}
              onChange={(e) => setVendorFilter(e.target.value)}
              sx={{
                height: "28px",
                border: `1px solid ${ev.line}`,
                borderRadius: "8px",
                bgcolor: ev.bg,
                color: ev.ink,
                px: "8px",
                fontSize: "12.5px",
                fontFamily: ESTATE_SANS,
              }}
            >
              <option value="">All vendors</option>
              {distinctVendors.map(([v, label]) => (
                <option key={v} value={v}>
                  {label}
                </option>
              ))}
            </Box>
            <Box
              component="input"
              aria-label="Filter units by name or cluster"
              placeholder="Filter by name or cluster"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              sx={{
                height: "28px",
                border: `1px solid ${ev.line}`,
                borderRadius: "8px",
                bgcolor: ev.bg,
                color: ev.ink,
                px: "10px",
                fontSize: "12.5px",
                fontFamily: ESTATE_SANS,
                minWidth: "170px",
              }}
            />
          </Box>
        </Box>

        <Box sx={{ overflowX: "auto", maxHeight: "560px" }}>
          <Box
            component="table"
            aria-label="Estate units"
            sx={{
              width: "100%",
              borderCollapse: "collapse",
              fontSize: "13px",
              "& th": {
                textAlign: "left",
                fontSize: "11px",
                letterSpacing: "0.07em",
                textTransform: "uppercase",
                color: ev.ink3,
                fontWeight: 650,
                p: "8px 10px",
                borderBottom: `1px solid ${ev.line}`,
                whiteSpace: "nowrap",
                position: "sticky",
                top: 0,
                bgcolor: ev.surface,
                zIndex: 1,
              },
              "& td": {
                p: "9px 10px",
                borderBottom: `1px solid ${ev.line}`,
                verticalAlign: "middle",
              },
              "& tr:last-of-type td": { borderBottom: 0 },
              "& tbody tr:hover td": { bgcolor: ev.bg },
            }}
          >
            <thead>
              <tr>
                <th>Unit</th>
                <th>Vendor · model</th>
                <th>Version</th>
                <th>Cluster / role</th>
                <th>{LENS_META[lens].label} (lens)</th>
                <th>Failover</th>
                <th>Backups</th>
                <th>Controls</th>
                <th>Exposure</th>
                <th>Evidence</th>
              </tr>
            </thead>
            <tbody>
              {sortedRows.length === 0 ? (
                <tr>
                  <td colSpan={10} style={{ color: ev.ink3 }}>
                    No unit matches the current filters.
                  </td>
                </tr>
              ) : (
                sortedRows.map((u) => {
                  const lensAns = u.lenses[lens];
                  return (
                    <tr key={u.deviceId} data-unit-row={u.name}>
                      <td>
                        <Box
                          component="a"
                          href={dossierHref(u.deviceId)}
                          sx={{
                            fontFamily: ESTATE_MONO,
                            fontWeight: 650,
                            fontSize: "12.5px",
                            color: ev.accent,
                            textDecoration: "none",
                            "&:hover": { textDecoration: "underline" },
                          }}
                        >
                          {u.name}
                        </Box>
                      </td>
                      <td>
                        <span>{u.vendorName}</span>
                        {u.model ? <span style={{ color: ev.ink3 }}> · {u.model}</span> : null}
                      </td>
                      <td style={{ fontFamily: ESTATE_MONO, fontSize: "12px" }}>{u.version ?? "UNKNOWN"}</td>
                      <td>
                        {u.clusterRef ? (
                          <>
                            <span style={{ fontFamily: ESTATE_MONO, fontSize: "12px" }}>{u.clusterRef}</span>
                            {u.haRole ? <span style={{ color: ev.ink3 }}> · {u.haRole.toLowerCase()}</span> : null}
                          </>
                        ) : (
                          <span style={{ color: ev.ink2 }}>{u.role.replace("_", " ")}</span>
                        )}
                      </td>
                      <td>
                        <Box sx={{ display: "inline-flex", alignItems: "center", gap: "6px", flexWrap: "wrap" }}>
                          <StateBadge state={lensAns.badgeState} word={lensAns.badgeWord} dense />
                          <span style={{ fontSize: "12px", color: ev.ink2 }}>{lensAns.summary}</span>
                        </Box>
                      </td>
                      <td>
                        <StateBadge state={u.lenses.failover.badgeState} word={u.lenses.failover.badgeWord} dense />
                      </td>
                      <td>
                        <StateBadge state={u.lenses.backups.badgeState} word={u.lenses.backups.badgeWord} dense />
                      </td>
                      <td>
                        <StateBadge state={u.lenses.compliance.badgeState} word={u.lenses.compliance.badgeWord} dense />
                      </td>
                      <td>
                        <StateBadge state={u.lenses.exposure.badgeState} word={u.lenses.exposure.badgeWord} dense />
                      </td>
                      <td>
                        <EvidenceStamp at={u.lastReadAt} now={model.now} missingLabel="never read" />
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </Box>
        </Box>
      </EstateCard>
    </Box>
  );
}
