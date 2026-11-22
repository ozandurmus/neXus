import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { getClusterMemberDiff, type ClusterMemberDiff } from "../auth/adminApi";
import { ClusterMemberDiffView } from "../screens/ClusterMemberDiffPanel";
import { useFetchOnMount } from "../shell/useFetchOnMount";
import { EstateCard, MoreFooter } from "./EstatePrimitives";
import { ev } from "./estateTokens";

/** Field rows shown before the "+N more" footer takes over. */
export const MEMBER_DIFF_ROW_CAP = 25;

export const MEMBER_DIFF_UNKNOWN_TEXT = "UNKNOWN: the member differences could not be read.";

/**
 * Dossier block for a cluster unit: the standard view's per-member difference table, read-only.
 * Member differences are MEMBER_SPECIFIC unless expected state proves otherwise, so this block
 * states what each member stores and makes no drift or health verdict. Without a cluster ref
 * it renders nothing and makes no call.
 */
export function EstateDossierMemberDiffBlock({
  clusterRef,
  labels,
}: {
  readonly clusterRef: string | null;
  readonly labels: Readonly<Record<string, string>>;
}) {
  if (!clusterRef) return null;
  return <MemberDiffLoader key={clusterRef} clusterRef={clusterRef} labels={labels} />;
}

function MemberDiffLoader({ clusterRef, labels }: { readonly clusterRef: string; readonly labels: Readonly<Record<string, string>> }) {
  const fetched = useFetchOnMount<ClusterMemberDiff>(
    () => getClusterMemberDiff(clusterRef),
    () => MEMBER_DIFF_UNKNOWN_TEXT,
  );

  // A payload without its field or member lists is a failed read too: UNKNOWN, never a crash.
  const malformed = fetched.data !== null && (!Array.isArray(fetched.data.fields) || !Array.isArray(fetched.data.members));
  let body;
  if (fetched.error || malformed) {
    body = (
      <Typography data-testid="dossier-member-diff-unknown" sx={{ px: "18px", pb: "14px", fontSize: "13px", color: ev.ink2 }}>
        {MEMBER_DIFF_UNKNOWN_TEXT}
      </Typography>
    );
  } else if (!fetched.data) {
    body = (
      <Typography sx={{ px: "18px", pb: "14px", fontSize: "12.5px", color: ev.ink3 }}>Loading member differences…</Typography>
    );
  } else {
    const diff = fetched.data;
    const shownFields = diff.fields.slice(0, MEMBER_DIFF_ROW_CAP);
    body = (
      <Box sx={{ px: "18px", pb: "14px" }} data-testid="dossier-member-diff">
        <ClusterMemberDiffView diff={{ ...diff, fields: shownFields }} labels={labels} />
        <Box component="div" sx={{ fontSize: "12px", color: ev.ink3, mt: "6px" }}>
          <MoreFooter shown={shownFields.length} total={diff.fields.length} noun="fields" testId="dossier-member-diff-more" />
        </Box>
        <Typography variant="caption" component="div" sx={{ color: ev.ink3, mt: "6px" }}>
          The read returns no observation time, so no evidence age is shown for these values.
        </Typography>
      </Box>
    );
  }

  return (
    <EstateCard sx={{ mt: "16px" }}>
      <Box sx={{ px: "18px", pt: "14px", pb: "8px" }}>
        <Typography sx={{ fontSize: "14px", fontWeight: 600, color: ev.ink }}>Member differences</Typography>
        <Typography sx={{ fontSize: "12px", color: ev.ink3 }}>
          What each cluster member stores, side by side. Differences between members are not drift on their own.
        </Typography>
      </Box>
      {body}
    </EstateCard>
  );
}
