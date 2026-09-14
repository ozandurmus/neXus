import { useId } from "react";
import { m3Theme } from "../theme/m3Theme";
import { NexusXStrokes } from "./NexusXStrokes";

const VIEWBOX_WIDTH = 220;
const VIEWBOX_HEIGHT = 72;

/**
 * The option-D wordmark: "ne" + the two-stroke capital X + "us" in the
 * theme's own bold geometric sans -- no added font, no raster
 * (docs/design/ui2_mockups/logo-identity-explorations.png, "D / Wordmark").
 * No tagline. `color` defaults to currentColor so the same mark works on a
 * light surface (dark navy inherited text colour) or a dark tile (white).
 */
export function NexusWordmark({
  height = 40,
  color = "currentColor",
}: {
  readonly height?: number;
  readonly color?: string;
}) {
  const titleId = useId();
  const width = (height * VIEWBOX_WIDTH) / VIEWBOX_HEIGHT;
  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${VIEWBOX_WIDTH} ${VIEWBOX_HEIGHT}`}
      role="img"
      aria-labelledby={titleId}
      xmlns="http://www.w3.org/2000/svg"
    >
      <title id={titleId}>neXus</title>
      <text
        x="0"
        y="58"
        fontFamily={m3Theme.typography.fontFamily}
        fontSize="54"
        fontWeight="800"
        letterSpacing="-1"
        fill={color}
      >
        ne
      </text>
      <g transform="translate(66,6) scale(0.9)">
        <NexusXStrokes />
      </g>
      <text
        x="132"
        y="58"
        fontFamily={m3Theme.typography.fontFamily}
        fontSize="54"
        fontWeight="800"
        letterSpacing="-1"
        fill={color}
      >
        us
      </text>
    </svg>
  );
}
