import { useCallback, useEffect, useRef, useState } from "react";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import MenuItem from "@mui/material/MenuItem";
import Stack from "@mui/material/Stack";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";

import type { PolicyInventoryObject, PolicyMetadata, PolicyObject } from "../../auth/adminApi";
import { m3 } from "../../theme/m3Theme";
import { RADIUS } from "../../theme/tokens";
import { Hint, mono } from "./zoneMatrixUi";
import {
  fetchAndResolveServiceObjectPorts,
  formatPolicyOptionLabel,
  loadAvailablePolicies,
  searchPolicyServiceObjects,
  type ServicePortExtractionResult,
  type ServiceObjectSearchResult,
} from "./serviceObjectPorts";

export interface ServiceObjectPortCopierProps {
  readonly disabled?: boolean;
  /**
   * Copies resolved canonical entries into the draft textarea and returns how many new lines were added.
   */
  readonly onCopyEntries: (entries: readonly string[], sourceObjectName: string) => number;
}

interface ResolvedSelection {
  readonly object: PolicyObject;
  readonly result: ServicePortExtractionResult;
}

function errorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === "object" && "body" in err) {
    const body = (err as { body?: { reason?: unknown; error?: unknown; message?: unknown } }).body;
    if (typeof body?.reason === "string" && body.reason.trim()) return body.reason;
    if (typeof body?.message === "string" && body.message.trim()) return body.message;
    if (typeof body?.error === "string" && body.error.trim()) return body.error;
  }
  if (err instanceof Error && err.message.trim()) return err.message;
  return fallback;
}

/**
 * Contract 4.2 helper: lets an editor search collected service objects and copy their resolved TCP/UDP
 * destination ports into the draft without storing any vendor object reference. Collapsed by default so
 * mounting never issues extra API calls.
 */
export function ServiceObjectPortCopier({ disabled = false, onCopyEntries }: ServiceObjectPortCopierProps) {
  const [open, setOpen] = useState(false);
  const [policies, setPolicies] = useState<readonly PolicyMetadata[] | null>(null);
  const [policyRetry, setPolicyRetry] = useState(0);
  const [selectedPolicyId, setSelectedPolicyId] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResult, setSearchResult] = useState<ServiceObjectSearchResult | null>(null);
  const [resolvingObject, setResolvingObject] = useState(false);
  const [selection, setSelection] = useState<ResolvedSelection | null>(null);
  const [policyError, setPolicyError] = useState<string | null>(null);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [inspectError, setInspectError] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const searchSequence = useRef(0);
  const inspectSequence = useRef(0);
  const searching = useRef(false);
  const inspecting = useRef(false);

  const selectedPolicy = policies?.find((p) => p.id === selectedPolicyId) ?? null;

  useEffect(() => {
    if (!open || policies !== null) return;
    let cancelled = false;
    loadAvailablePolicies()
      .then((list) => {
        if (cancelled) return;
        setPolicies(list);
        setSelectedPolicyId(list[0]?.id ?? "");
      })
      .catch((err: unknown) => {
        if (!cancelled) setPolicyError(errorMessage(err, "Could not load collected policies."));
      });
    return () => { cancelled = true; };
  }, [open, policies, policyRetry]);

  const invalidateInspection = useCallback(() => {
    inspectSequence.current += 1;
    inspecting.current = false;
    setResolvingObject(false);
    setSelection(null);
    setInspectError(null);
  }, []);

  const resetSearch = useCallback(() => {
    searchSequence.current += 1;
    searching.current = false;
    setSearchResult(null);
    setSearchError(null);
    invalidateInspection();
  }, [invalidateInspection]);

  const search = useCallback(async (policy: PolicyMetadata, query: string) => {
    resetSearch();
    const request = searchSequence.current;
    searching.current = true;
    try {
      const result = await searchPolicyServiceObjects(policy, query);
      if (request === searchSequence.current) setSearchResult(result);
    } catch (err: unknown) {
      if (request === searchSequence.current) setSearchError(errorMessage(err, "Could not search service objects."));
    } finally {
      if (request === searchSequence.current) searching.current = false;
    }
  }, [resetSearch]);

  useEffect(() => {
    if (!open || !selectedPolicy) return;
    void search(selectedPolicy, "");
    return () => {
      searchSequence.current += 1;
      inspectSequence.current += 1;
    };
  }, [open, selectedPolicy, search]);

  const loadingObjects = !searchResult && !searchError;
  const runSearch = () => {
    if (disabled || !selectedPolicy || loadingObjects || searching.current) return;
    void search(selectedPolicy, searchQuery.trim());
  };

  const inspectObject = async (obj: PolicyInventoryObject) => {
    if (disabled || !selectedPolicy || searching.current || inspecting.current) return;
    const request = ++inspectSequence.current;
    inspecting.current = true;
    setResolvingObject(true);
    setInspectError(null);
    setStatusMessage(null);
    setSelection(null);
    try {
      const resolved = await fetchAndResolveServiceObjectPorts(obj, selectedPolicy, () => request === inspectSequence.current);
      if (request === inspectSequence.current) setSelection(resolved);
    } catch (err: unknown) {
      if (request === inspectSequence.current) setInspectError(errorMessage(err, `Could not resolve service object "${obj.name}".`));
    } finally {
      if (request === inspectSequence.current) {
        inspecting.current = false;
        setResolvingObject(false);
      }
    }
  };

  const confirmCopy = () => {
    if (!selection || !selection.result.ok) return;
    const added = onCopyEntries(selection.result.entries, selection.object.name);
    if (added > 0) {
      setStatusMessage(
        `Copied ${added} service entries from ${selection.object.name} (resolved values only; vendor object not linked).`,
      );
    } else {
      setStatusMessage(
        `All resolved service entries from ${selection.object.name} are already in the list (vendor object not linked).`,
      );
    }
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
      <Box>
        <Button
          variant="outlined"
          size="small"
          disabled={disabled}
          data-testid="toggle-copy-service-object"
          aria-expanded={open}
          onClick={() => {
            resetSearch();
            setPolicyError(null);
            setSearchQuery("");
            setOpen(!open);
          }}
          sx={{
            borderRadius: "20px",
            textTransform: "none",
            borderColor: m3.outline,
            color: m3.onSurface,
          }}
        >
          Add the ports of a service object
        </Button>
      </Box>

      {statusMessage ? (
        <Typography variant="body2" role="status" data-testid="copy-service-status" sx={{ color: m3.primary }}>
          {statusMessage}
        </Typography>
      ) : null}

      {open ? (
        <Box
          data-testid="copy-service-object-panel"
          sx={{
            border: `1px solid ${m3.outlineVar}`,
            borderRadius: `${RADIUS.card}px`,
            bgcolor: m3.scLow,
            p: 1.5,
            display: "flex",
            flexDirection: "column",
            gap: 1.25,
          }}
        >
          <Hint>
            Copies resolved TCP/UDP destination ports at this time. Vendor service objects are never linked to the matrix.
          </Hint>

          {policyError ? (
            <Box role="alert" sx={{ color: m3.error }}>
              Could not load collected policies: {policyError}{" "}
              <Button disabled={disabled} onClick={() => { setPolicyError(null); setPolicyRetry((n) => n + 1); }}>Retry policy load</Button>
            </Box>
          ) : policies === null ? (
            <Hint>Loading collected policies…</Hint>
          ) : policies.length === 0 ? (
            <Hint>No collected policies available.</Hint>
          ) : (
            <>
              <Stack direction="row" gap={1} flexWrap="wrap" alignItems="center">
                <TextField
                  select
                  size="small"
                  label="Policy / domain"
                  value={selectedPolicyId}
                  onChange={(e) => {
                    resetSearch();
                    setSelectedPolicyId(e.target.value);
                    setSearchQuery("");
                    setStatusMessage(null);
                  }}
                  sx={{ minWidth: 260 }}
                >
                  {policies.map((p) => (
                    <MenuItem key={p.id} value={p.id}>
                      {formatPolicyOptionLabel(p)}
                    </MenuItem>
                  ))}
                </TextField>

                <TextField
                  size="small"
                  label="Search service object"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  inputProps={{ maxLength: 1000 }}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      void runSearch();
                    }
                  }}
                  sx={{ minWidth: 220 }}
                />

                <Button
                  variant="outlined"
                  size="small"
                  disabled={disabled || loadingObjects || !selectedPolicy}
                  onClick={() => { void runSearch(); }}
                  sx={{ borderRadius: "20px", textTransform: "none", borderColor: m3.outline, color: m3.onSurface }}
                >
                  Search
                </Button>
              </Stack>

              {searchError ? (
                <Box role="alert" sx={{ color: m3.error }}>
                  Could not search service objects: {searchError}{" "}
                  <Button disabled={disabled} onClick={runSearch}>Retry search</Button>
                </Box>
              ) : loadingObjects ? (
                <Hint>Searching service objects…</Hint>
              ) : searchResult && searchResult.objects.length > 0 ? (
                <Box
                  component="ul"
                  aria-label="Matching service objects"
                  sx={{ m: 0, pl: 0, listStyle: "none", maxHeight: 180, overflowY: "auto", display: "flex", flexDirection: "column", gap: 0.5 }}
                >
                  {searchResult.objects.map((obj) => (
                    <li key={obj.id}>
                      <Button
                        variant="text"
                        size="small"
                        disabled={disabled || resolvingObject}
                        data-testid={`select-service-object-${obj.id}`}
                        onClick={() => { void inspectObject(obj); }}
                        sx={{
                          justifyContent: "flex-start",
                          textTransform: "none",
                          color: m3.onSurface,
                          width: "100%",
                          px: 1,
                        }}
                      >
                        <Box component="span" sx={{ fontWeight: 600, mr: 1 }}>{obj.name}</Box>
                        <Typography component="span" variant="caption" sx={{ color: m3.onSurfaceVar }}>
                          ({obj.type})
                        </Typography>
                      </Button>
                    </li>
                  ))}
                </Box>
              ) : searchResult?.more === 0 && searchResult.incompleteTypes.length === 0 ? (
                <Hint>No matching service objects in this policy domain.</Hint>
              ) : null}
              {searchResult && searchResult.more > 0 ? <Hint>+{searchResult.more} more (refine the search)</Hint> : null}
              {searchResult && searchResult.incompleteTypes.length > 0 ? (
                <Hint>Collected service-object inventory is incomplete: {searchResult.incompleteTypes.join(", ")}.</Hint>
              ) : null}
            </>
          )}

          {resolvingObject ? <Hint>Resolving service object ports…</Hint> : null}

          {selection ? (
            selection.result.ok ? (
              <Box
                data-testid="resolved-service-ports-preview"
                sx={{ borderTop: `1px solid ${m3.outlineVar}`, pt: 1, display: "flex", flexDirection: "column", gap: 1 }}
              >
                <Typography variant="body2">
                  Resolved ports for <strong>{selection.object.name}</strong>:{" "}
                  <Box component="span" sx={mono}>{selection.result.entries.join(", ")}</Box>
                </Typography>
                <Box>
                  <Button
                    variant="contained"
                    size="small"
                    disabled={disabled}
                    data-testid="confirm-copy-service-ports"
                    onClick={confirmCopy}
                    sx={{
                      borderRadius: "20px",
                      textTransform: "none",
                      bgcolor: m3.primary,
                      color: m3.onPrimary,
                    }}
                  >
                    Add resolved ports
                  </Button>
                </Box>
              </Box>
            ) : (
              <Box role="alert" data-testid="copy-service-refusal" sx={{ color: m3.error }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>
                  Cannot copy ports from {selection.object.name}: {selection.result.reason}
                </Typography>
              </Box>
            )
          ) : null}

          {inspectError ? (
            <Box role="alert" sx={{ color: m3.error }}>
              <Typography variant="body2">{inspectError}</Typography>
            </Box>
          ) : null}
        </Box>
      ) : null}
    </Box>
  );
}
