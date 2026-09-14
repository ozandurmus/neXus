import { useId } from "react";
import { NexusXStrokes } from "./NexusXStrokes";

const VIEWBOX_WIDTH = 220;
const VIEWBOX_HEIGHT = 72;

/**
 * The option-D wordmark: "ne" + the two-stroke capital X + "us" in the
 * inline geometry -- no added font, no raster.
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
      <path
        d="M4 58V30C4 24 8 20 15 20C23 20 29 25 29 33V58H21V35C21 31 19 29 16 29C13 29 12 31 12 35V58Z"
        fill={color}
      />
      <path
        d="M62 43H40C40 48 43 51 48 51C52 51 55 49 58 47V55C55 58 51 59 47 59C37 59 32 52 32 40C32 28 38 20 48 20C57 20 62 27 62 38ZM40 36H54C54 31 52 28 48 28C43 28 40 31 40 36Z"
        fill={color}
        fillRule="evenodd"
      />
      <g transform="translate(66,6) scale(0.9)">
        <NexusXStrokes />
      </g>
      <path
        d="M119 20H127V43C127 47 129 50 133 50C137 50 139 47 139 43V20H147V58H139V53C137 57 134 59 130 59C123 59 119 54 119 46Z"
        fill={color}
      />
      <path
        d="M177 23V31C173 29 169 28 166 28C163 28 161 29 161 31C161 33 163 34 167 35C174 37 178 41 178 47C178 55 172 59 164 59C160 59 156 58 153 56V48C157 51 161 52 164 52C168 52 170 50 170 48C170 46 168 45 164 44C157 42 153 38 153 32C153 24 159 20 166 20C170 20 174 21 177 23Z"
        fill={color}
      />
    </svg>
  );
}
