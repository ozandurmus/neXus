import { useId } from "react";
import { m3 } from "../theme/m3Theme";

/**
 * The neXus mark on its own: the app mark's X, four nodes and hub (geometry of `assets/app-mark.svg`) without the navy
 * tile, in the same three theme colours as the wordmark. It is the logo where there is no room for the word: the folded
 * sidebar and the narrow strip (brand area 2026-11-13). The tile itself stays on the sign-in screen and the tab icon.
 */
export function NexusMark({ size = 40 }: { readonly size?: number }) {
  const maskId = `nx-mark-${useId().replace(/:/g, "")}`;
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="11 11 42 42" role="img" aria-label="neXus" data-brand="mark"
      width={size} height={size} style={{ display: "block", flex: "none" }}>
      <defs>
        <mask id={maskId} maskUnits="userSpaceOnUse" x="11" y="11" width="42" height="42">
          <rect x="11" y="11" width="42" height="42" fill="#fff" />
          <circle cx="32" cy="32" r="7" fill="#000" />
        </mask>
      </defs>
      <g mask={`url(#${maskId})`} strokeWidth="5.5" strokeLinecap="round" fill="none">
        <path d="M18 18L46 46" style={{ stroke: m3.brandTeal }} />
        <path d="M46 18L18 46" style={{ stroke: m3.brandBlue }} />
      </g>
      <g style={{ fill: m3.brandInk }}>
        <circle cx="18" cy="18" r="4.6" /><circle cx="46" cy="18" r="4.6" /><circle cx="18" cy="46" r="4.6" /><circle cx="46" cy="46" r="4.6" />
      </g>
      <circle cx="32" cy="32" r="7" fill="none" strokeWidth="3" style={{ stroke: m3.brandInk }} />
    </svg>
  );
}
