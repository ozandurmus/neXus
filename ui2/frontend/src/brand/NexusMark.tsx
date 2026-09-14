import { useId } from "react";
import { NEXUS_TILE_DARK } from "./colors";
import { NexusXStrokes } from "./NexusXStrokes";

/**
 * The option-D icon variant: the two-stroke X alone on a dark rounded tile
 * (docs/design/ui2_mockups/logo-identity-explorations.png, "D / Wordmark",
 * bottom-right square). Pure vector -- scales from 20px to 200px with no
 * raster asset.
 */
export function NexusMark({ size = 40 }: { readonly size?: number }) {
  const titleId = useId();
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 64 64"
      role="img"
      aria-labelledby={titleId}
      xmlns="http://www.w3.org/2000/svg"
    >
      <title id={titleId}>neXus</title>
      <rect width="64" height="64" rx="16" fill={NEXUS_TILE_DARK} />
      <NexusXStrokes />
    </svg>
  );
}
