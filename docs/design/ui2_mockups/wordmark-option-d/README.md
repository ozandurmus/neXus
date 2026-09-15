# Option D — artwork for selection

Status: DRAFT — Product Owner selection pending. NXS-LOCAL-0206.

Open [preview.html](preview.html) locally. It places the reference beside the
reconstruction and shows all six assets at three sizes. No application wiring.

| File | Use |
| --- | --- |
| `wordmark-tagline.svg` | Navy wordmark with outlined A CLEARER TOMORROW |
| `wordmark.svg` | Navy wordmark without tagline |
| `app-mark.svg` | Two-tone crossing X on a navy rounded square |
| `wordmark-tagline-light-on-dark.svg` | White wordmark and tagline on navy |
| `wordmark-light-on-dark.svg` | White wordmark on navy |
| `app-mark-light-on-dark.svg` | Navy app tile with white perimeter for dark surfaces |

All SVGs are self-contained: no text elements, fonts, scripts, embedded raster
images or external references. Wordmark backgrounds are transparent in the navy
versions and explicit navy in the reversed versions.

## Provenance: sampled, measured, chosen

Reference: [`../logo-identity-explorations.png`](../logo-identity-explorations.png),
option D, bottom left. Coordinates below are source pixels, origin top left.

**Sampled:** original RGB channel values obtained with macOS
`NSBitmapImageRep.getPixel`, without display-color conversion. These are single
interior pixels, not a claimed original brand palette; the raster has texture
and color variation.

| Purpose | Source coordinate | Raw RGB | SVG value |
| --- | --- | --- | --- |
| Letter navy | (80, 700) | 15, 44, 73 | `#0F2C49` |
| Teal | (237, 645) | 7, 125, 147 | `#077D93` |
| Blue | (310, 645) | 19, 83, 226 | `#1353E2` |
| App tile / reverse background | (350, 870) | 14, 41, 68 | `#0E2944` |
| Reverse lettering / perimeter | (50, 800) | 254, 254, 254 | `#FEFEFE` |

**Measured visually in source pixels, approximate:** n occupies x=73–143,
e x=149–224, u x=330–399, s x=404–466; lowercase top is about y=663–667,
baseline y=735–739. X outer bounds are about x=215–341, y=635–737.
The reference tagline spans about x=130–406, with baseline around y=776.
The app tile is approximately 82 square with a 14 px corner radius.

**Chosen / reconstructed:** Bézier control points follow the observed letter
silhouettes; they are hand-drawn, not recovered source vectors. The X uses two
33 px horizontal-cap parallelograms extending to y=740, with blue drawn after
teal. This follows the explicit approved crossing requirement. The raster
visually uses a split center and different color segmentation; the continuous
blue-over-teal crossing is an intentional interpretation of the PO directive,
not a claim of a pixel-identical trace. Its bounding box overlaps the e/u
bounding boxes, retaining the tightly spaced single-word composition.

The tagline uses locally available Helvetica Regular, 17 units with 4.5 units
of added tracking, converted once through CoreText to path outlines. That font
and tracking are a visual approximation, not identification of the source font.
The outlined run measures 282.40 units wide. No font is required to display it.
Margins, flat colors in place of raster texture, the white reverse treatment,
and the reverse app tile's 2-unit white perimeter are design choices. App X
geometry is shared with the wordmark, scaled to 54 × 45 units.

## Validation

All six SVGs were rasterized with macOS AppKit and visually inspected at the
preview widths: 424 / 280 / 160 px for wordmarks and 164 / 82 / 32 px for app
marks. The two-color X remains recognizable at 32 px. The 160 px tagline is
intentionally shown to expose its small-size limit; use the tagline-free asset
when readable caption text is required at that scale. Browser automation was
unavailable; the static preview's HTML and local asset references were checked.

Run from the repository root to check the asset contract:

```sh
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
root = Path('docs/design/ui2_mockups/wordmark-option-d')
files = sorted(root.glob('*.svg'))
assert len(files) == 6
for file in files:
    svg = ET.parse(file).getroot()
    assert svg.get('viewBox') and svg.get('aria-label')
    for node in svg.iter():
        assert node.tag.rsplit('}', 1)[-1] in {'svg', 'g', 'path', 'rect'}
        assert not any('href' in key or 'url(' in value
                       for key, value in node.attrib.items())
preview = (root / 'preview.html').read_text()
for file in files:
    expected = 4 if file.name == 'wordmark-tagline.svg' else 3
    assert preview.count('src="' + file.name + '"') == expected
assert preview.count('width="32"') == 2
print('PASS: six outlined SVGs; three sizes each; both app marks at 32 px')
PY
python3 scripts/repository_privacy_check.py
```
