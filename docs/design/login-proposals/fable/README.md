# neXus sign-in proposals · Fable

Status: `DRAFT` (design exploration, static mockups; no product code touched).

Four self-contained HTML files, each a full sign-in page at 1440×900 that also lays out at 1280 and 1920. Every file carries the authentication selector (Local account / LDAP), username and password, the Sign in button, the generic error state ("Sign-in failed. Check your details.", shown by the `Error state` preview button or by submitting the mock form), the footer line `neXus · on-premises · version 2.6`, and a `Logo sheet` dialog with the new mark and wordmark on light and on dark. No external resources; system font stacks only; `prefers-reduced-motion` disables all motion and leaves a designed static frame. Focus rings, labels, `role="alert"` and keyboard order are in place in all four.

## proposal-1.html · Constellation

The estate as a living topology. A canvas draws about sixty drifting nodes in two loose clusters, links them to their nearest neighbours, and sends pulses along the links; a node glows briefly when a pulse arrives. Mood: dark, calm, alive, close to the product's own dark theme. The sign-in panel is a frosted glass card on the right; the left carries the statement "Every firewall. One picture." and the five verbs as pills. Animation is a ~30 fps canvas loop that pauses when the tab is hidden and renders one static frame under reduced motion. Logo: **Node X**, an evolution of the current mark: the two strokes keep their teal and blue but become links, the four ends and the crossing become nodes, and the hub is a hollow ring. It fits neXus because the product's first verb is SEE, and the thing it sees is exactly this: devices and the links between them.

## proposal-2.html · Throughput

Policy as a readable gate. On a light, editorial page, packets travel left to right across eight lanes; at a dashed vertical gate most pass and turn teal (verified), a few stop and fade (refused). Pure CSS keyframes, no JavaScript motion; position and colour share one timeline so the colour change lands exactly at the gate. Mood: daylight, confident, newspaper-clean, with a bold headline "Policy you can read. Traffic you can trace." and a white card with a teal-to-blue hairline on top. Under reduced motion the packets rest in a static, readable distribution. Logo: **Gate X**, the current X kept as an X but with the strokes given meaning: solid blue is the rule, dashed teal is the traffic that meets it; the navy tile stays, and the wordmark sets the mark as the X of "nexus". It fits neXus because policy hygiene, path analysis and compliance are all the same gesture: a claim passes a rule and leaves evidence.

## proposal-3.html · Lattice

A security mesh. A full-bleed hexagonal lattice is built once by a tiny script and then breathes in slow concentric waves from a focal point behind the panel, with a few "verified" cells holding a steady teal light. Mood: graphite, ice, industrial, hardware-adjacent; square corners, mono uppercase labels, an ice-blue primary button with a sheen on hover, and the five verbs cycling emphasis in the lower-left. All motion is CSS with a per-cell delay, so CPU cost is one compositor layer. Logo: **Hex X**, a new mark rather than an evolution: the X cut inside a hexagonal cell with the centre left open, a deliberate gap where the strokes would meet, because neXus does not assume the crossing, it verifies it. Wordmark: NEXUS in wide-tracked capitals. It fits a bank's firewall estate because it reads as engineered and controlled, and the single-weight geometry survives a favicon, a stamp or an engraved badge.

## proposal-4.html · Quiet

Premium restraint. Warm paper, a serif voice from the system (Iowan Old Style / Palatino / Georgia), a thin rule splitting the page, and no card at all: the form sits on the page like print, with underline fields and a black pill button. Motion is almost nothing: two soft lights breathe over 24–30 s in the corners, the mark draws itself once on load, and the five numbered verbs pass emphasis from one to the next every three seconds. A faint SVG grain gives the paper texture without any image file. Logo: **Scribe X**, a new mark that keeps only the letter: an X written with a broad nib, one hairline stroke and one heavy stroke, single ink, so weight contrast does the work the two colours used to do and the mark never competes with status colours inside the product. It fits neXus because the product's own law is "only what was observed, and when"; this page says that with confidence and silence rather than with effects.

## Files

- `docs/design/login-proposals/fable/proposal-1.html`
- `docs/design/login-proposals/fable/proposal-2.html`
- `docs/design/login-proposals/fable/proposal-3.html`
- `docs/design/login-proposals/fable/proposal-4.html`
- `docs/design/login-proposals/fable/README.md`
