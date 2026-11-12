import { useId } from "react";
import { m3 } from "../theme/m3Theme";

/**
 * The neXus wordmark, drawn in place (geometry of `assets/wordmark.svg`, option D): lettering, nodes and hub in one ink,
 * the X in teal (left stroke) and blue (right stroke). The three colours are theme tokens, the light and the
 * light-on-dark artwork's own values, so the mark sits straight on the surface in both modes with no box behind it
 * (brand area 2026-11-13). The sign-in screen keeps its own always-dark artwork files.
 */
export function NexusWordmark({ height = 40 }: { readonly height?: number }) {
  // The hub is cut out of the X by a mask; the wordmark can be on a page more than once, so the mask id is per instance.
  const maskId = `nx-wordmark-${useId().replace(/:/g, "")}`;
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="-2 -2 200 44" role="img" aria-label="neXus" data-brand="wordmark"
      height={height} width={Math.round((height * 200 / 44) * 100) / 100} style={{ display: "block", flex: "none" }}>
      <defs>
        <mask id={maskId} maskUnits="userSpaceOnUse" x="80" y="-2" width="48" height="44">
          <rect x="80" y="-2" width="48" height="44" fill="#fff" />
          <circle cx="104" cy="20" r="5.6" fill="#000" />
        </mask>
      </defs>
      <g fill="none" strokeWidth="7" strokeLinecap="round" strokeLinejoin="round" style={{ stroke: m3.brandInk }}>
        <path d="M3.5 36.5V16A12 12 0 0 1 27.5 16V36.5" />
        <path transform="translate(38 0)" d="M3.5 20H36.5A16.5 16.5 0 1 0 31.67 31.67" />
        <path transform="translate(130 0)" d="M3.5 3.5V24A12 12 0 0 0 27.5 24V3.5" />
        <path transform="translate(168 0)" d="M24 8.5C21.5 5 18 3.5 14 3.5C8.5 3.5 4.5 6.5 4.5 11C4.5 15.5 8 17.5 14 19.5C20 21.5 24.5 23.5 24.5 28.5C24.5 33.5 20 36.5 14 36.5C9.5 36.5 5.5 35 3.5 31.5" />
      </g>
      <g mask={`url(#${maskId})`} strokeWidth="6.6" strokeLinecap="round" fill="none">
        <path d="M90 4L118 36" style={{ stroke: m3.brandTeal }} />
        <path d="M118 4L90 36" style={{ stroke: m3.brandBlue }} />
      </g>
      <g style={{ fill: m3.brandInk }}>
        <circle cx="90" cy="4" r="4.3" /><circle cx="118" cy="4" r="4.3" /><circle cx="90" cy="36" r="4.3" /><circle cx="118" cy="36" r="4.3" />
      </g>
      <circle cx="104" cy="20" r="5.6" fill="none" strokeWidth="2.8" style={{ stroke: m3.brandInk }} />
    </svg>
  );
}
