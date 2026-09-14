# neXus wordmark renders broken on the login screen

status: planned · target: 

Product Owner report 2026-09-14: the login screen shows the wordmark as separated fragments (the letters read as 'ne' and 'us' with the accented X not joining them), so the product name does not read as one word. The mark is an inline SVG (ui2/frontend/src/brand/NexusWordmark.tsx) drawn from the option D study: letters set in the theme font stack plus two hand-drawn strokes for the capital X. The likely cause is that the letter text and the stroke geometry are positioned independently, so any font-metric difference between machines pulls them apart. A fix should make the whole wordmark one piece of geometry whose proportions cannot drift with the rendering font, and prove it at several sizes.
