# Anti-slop rules (condensed from taste-skill)

Source: https://github.com/leonxlnx/taste-skill (MIT, see `../licenses/`). Condensed and reorganised; every rule is contextual, and an explicit brief can override it.

## Defaults to reach past

LLM defaults that make interfaces look generated: AI-purple/blue gradients, a centred hero over a dark mesh, three equal feature cards, glassmorphism on everything, infinite micro-animations, Inter + slate-900. Pick deliberately from the design read instead.

## Typography

- Inter is not the default. Prefer Geist, Satoshi, Cabinet Grotesk, Outfit, or a brand face. Inter is fine for neutral, Linear-style or public-sector briefs.
- Serif is not the "creative = serif" reflex. Use it only for genuinely editorial, luxury or heritage briefs, or when the brand names one. Fraunces and Instrument Serif are banned as defaults.
- Emphasis inside a headline uses italic or bold of the **same** family, never a random second family.
- Italic display type with descenders (g j p q y) needs line-height >= 1.1 plus bottom reserve.
- Don't make H1s scream through raw size. Build hierarchy with weight, colour and space.
- Body: 16px minimum, line-height ~1.5, measure <= 65ch.

## Colour

- Max one accent, saturation < 80%. Neutral base (zinc/slate/stone, or a brand-tinted neutral) plus one high-contrast accent.
- Colour consistency lock: the accent chosen for the page is used on the whole page. No teal badge in the footer of a rose page.
- One palette temperature: don't mix warm and cool greys.
- Premium-consumer ban: cream/bone backgrounds + brass/clay/oxblood accents + espresso text is the LLM default for "premium". Use it only when the brand is genuinely that.
- No pure #000 or #fff. No neon outer glows. No oversaturated accents. No gradient text on large headers.

## Layout

- Anti-centre bias when variance > 4: split, left-aligned or asymmetric heroes.
- Hero fits the first viewport: headline <= 2 lines, subtext <= 20 words, <= 4 text elements, top padding <= ~6rem.
- Navigation stays on one line on desktop, 64-72px tall (80px max).
- Section layout families don't repeat. At most 2 consecutive image/text zigzags.
- Bento grids have exactly as many cells as content, and at least 2-3 cells with real visual variation.
- The split header (big headline left, tiny paragraph right) is banned as a default. Stack them instead.
- Cards only when elevation communicates hierarchy. Otherwise use space, hairlines or tone.
- Shape consistency lock: one radius system, documented, obeyed.
- Every multi-column layout declares its < 768px collapse explicitly.
- Theme lock: sections don't flip light/dark mid-page.

## Content

- Short sections: headline <= 8 words, body <= 25 words, plus one visual or one CTA.
- More than 5 items needs a better component than a long `divide-y` list: grouped columns, a card grid, tabs, scroll-snap pills or a carousel.
- Don't put `border-b` under every row of a long table. Group rows, or use hairlines sparingly.
- No fake-precise numbers unless they are real data or labelled as mock.
- No "John Doe" names, egg avatars or "Acme / Nexus" brands.
- No filler verbs: elevate, seamless, unleash, next-gen, revolutionise.
- Quotes: 3 lines max, real attribution (name + role).

## Banned "designed-looking" tells

- Version labels in the hero (V2.0, BETA) unless it is really a launch.
- Section-number eyebrows ("01 / Features", "002 · Work"), `01 / 4` pagination on tiles.
- More than one eyebrow per 3 sections.
- Middle-dot chains ("a · b · c · d"): max one per line.
- Decorative coloured status dots. OK only for real live status, sparingly.
- Em-dash `—` anywhere visible, and en-dash `–` as a separator. Zero, not "sparingly".
- `<br>`-split italic headline gimmicks, rotated vertical text, decorative crosshair grid lines.
- Div-built fake product screenshots, fake terminals, fake version footers.
- Pills or labels overlaid on photos, pretentious fake photo credits.
- Decorative mono-caps strips ("DESIGN. BUILD. SHIP.") at the hero bottom.
- Locale/time/weather strips, scroll cues ("Scroll to explore").
- "Quietly trusted by", "Field notes", "On the bench" and other performative-craftsman labels.
- Generic step labels ("Step 1 / Phase 01"). The action is the label.
- Progress bars with big filled grey tracks on marketing pages.

## States and interaction

- Skeletons shaped like the final layout; composed empty states; inline errors; toasts only for transient events.
- `:active` feedback via `scale(.98)` or a 1px translate.
- Button text fits on one line; one label per intent across the page; button and form contrast checked.
- Labels above inputs, errors below, never placeholder-as-label.

## Motion and performance

- Animate only transform and opacity. `will-change` sparingly.
- Never `window.addEventListener('scroll')`. Use IntersectionObserver, CSS `animation-timeline: view()`, or GSAP ScrollTrigger.
- Motion above level 3 must honour `prefers-reduced-motion`; loops, parallax and physics collapse to static.
- Grain/noise only on fixed, `pointer-events:none` layers.
- Reserve space for media (CLS < 0.1); LCP < 2.5s; INP < 200ms.
- A documented z-index scale, no arbitrary z-50 spam.

## Icons and assets

- A real icon library (Phosphor, Tabler, Heroicons, Radix), consistent stroke. No emoji icons, no hand-rolled SVG icons.
- Real images: brand/data-source assets first, then generated images, then `picsum.photos/seed/<descriptive>/<w>/<h>`. Otherwise leave labelled TODO slots and tell the user.
- Logo walls are logos only, with no category captions, legible in both themes.
