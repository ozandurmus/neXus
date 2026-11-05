# neXus sign-in and logo proposals (opus)

Status: DRAFT (design exploration, static mock-ups; no product code changed).

Four self-contained HTML files. Each opens by double-click, uses no external resource (system font stacks,
inline SVG, inline script), and has the same review bar in the top-right corner: **Light/Dark**, **Error state**,
**Motion** (disabled when the OS asks for reduced motion, which also stops all animation), and **Brand sheet**
(the logo on light and dark, plus app-icon sizes). Submitting the form shows a short busy state and then the one
generic error, "Sign-in failed. Check your details.", clears the password and returns focus to it. In a normal
browser `?theme=dark|light` and `?error=1` open a given state directly. Every page has the Local account / LDAP
selector as a real radio group, labelled fields, a show/hide password control, a Caps Lock hint, visible focus
rings, an "authorised users only" line and the footer "neXus · on-premises · version 2.6". Every animated scene
is labelled as an illustration, not live data, so it is never mistaken for estate state.

## Proposal 1 · Constellation (`proposal-1.html`)

**Concept:** the estate as a living topology. **Mood:** deep navy, technical, calm; dark by default. **Layout:**
split screen, animated hero on the left with the headline "Every firewall in the estate, seen and verified." and
the five planes SEE · VERIFY · TRACE · RECOVER · OPERATE; a solid sign-in panel on the right. **Animation:** a
small inline canvas draws a seeded network of gateways, HA pairs (twin dots) and managers (rings); pulses travel
along the links and hop on, and a node ripples when a pulse arrives. Capped at 30 fps, paused in a hidden tab,
and nodes behind the headline are thinned so text never competes with motion. **Logo (evolves the X):** the
current teal and blue X becomes a hub, four nodes joined through one central ring; the wordmark is geometric
monoline where the n and the u mirror each other around the hub. **Why it fits:** a nexus is a connection point,
and seeing the whole estate is the product's first plane.

## Proposal 2 · Throughput (`proposal-2.html`)

**Concept:** policy as a gate. **Mood:** light, clean, engineering-drawing on a dotted grid; uses the product's
own colour tokens (m3Theme light and dark), so it sits closest to today's UI. **Layout:** headline top-left
("From the inventory to the rule that decided it.") with the principle written as an arrowed flow; a white card
on the right with two radio tiles for Local account / LDAP. **Animation:** SVG lanes converge into one policy
column and fan out again; packets glide through, a few stop at the column and show a small stop mark; rule cells
flash as they evaluate. Runs on SVG/SMIL, so it scales to any screen without script; paused on a composed frame
when motion is off. **Logo (evolves the X):** the same two strokes, but one passes over the other with a cut,
like a road interchange; soft-cornered "instrument panel" lettering; the tile is primary blue in both modes.
**Why it fits:** TRACE and policy hygiene are what make neXus more than an inventory, and this page explains
that in one look.

## Proposal 3 · Lattice (`proposal-3.html`)

**Concept:** verification as a field of cells. **Mood:** dark teal-ink, precise, a little cinematic; a light
variant on pale mineral. **Layout:** centred and symmetric: wordmark, compact card with corner registration
ticks, then the five planes, which light one after another. **Animation:** a perspective grid floor advances
toward the viewer while cells near the horizon light up as they are "checked"; the horizon glows slowly. Pure
CSS with transform and opacity only (GPU-composited, no canvas, no script loop); the lit cells are phase-locked
to the grid so the loop never jumps. The auth selector is a sliding switch on a radio group. **Logo (evolves the
X, inverted):** a square cut into four triangles, teal top and bottom, blue left and right, so the X is the
negative space between them. The wordmark is lattice lettering (horizontals and verticals only) with the mark
itself standing in for the X. **Why it fits:** VERIFY and compliance are the product's daily work; the mark is
solid shapes, so it survives 16 px and one-colour print in reports.

## Proposal 4 · Meridian (`proposal-4.html`)

**Concept:** quiet premium. **Mood:** paper, ink and one brass accent, the restraint of a private bank; a warm
charcoal dark variant. **Layout:** the form on the left as an editorial column (serif "Sign in", underline tabs
for Local account / LDAP, square-edged fields, a full-width ink button); on the right, the art and the statement
"Every firewall, accounted for." **Animation:** a family of hairline circles all touching at one point; two
brass markers travel the main pair of arcs and meet at that point every nine seconds, where a faint ring opens;
a soft light drifts behind. Slow by design (SVG/SMIL plus one CSS transform). **Logo (a new X):** two arcs back
to back, )( , meeting at a brass dot; the wordmark sets "ne" and "us" in a book serif around it. The serif comes
from the system stack in this mock-up; for release the letters are outlined to paths so the logo never depends on
installed fonts. **Why it fits:** two sides meeting at one point is HA, and also intent against runtime; neXus
is where they are reconciled. The least "animated" option, for a PO who wants lively but not busy.

## Notes for choosing

- Most motion to least: 1 Constellation, 3 Lattice, 2 Throughput, 4 Meridian.
- Closest to the current product palette: 2. Biggest departure: 4.
- Every logo keeps a two-part X idea except 4, which replaces it.
- None of the pages shows a hostname, address or customer name; the error text never says whether the account
  exists.
