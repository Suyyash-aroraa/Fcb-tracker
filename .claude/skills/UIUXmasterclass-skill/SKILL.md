---
name: UIUXmasterclass-skill
description: "Design and build gorgeous, distinctive, non-templated frontends, and audit existing UI for quality and 'AI-generated' tells. Merges the taste-skill anti-slop discipline (design read, three dials, banned defaults, pre-flight check) with the ui-ux-pro-max design-intelligence engine (searchable styles, palettes, font pairings, UX guidelines, chart and stack rules). Use for any new page, app, dashboard, landing page, redesign, component, or when the user asks for UI that looks premium, stands out, or doesn't look vibecoded. Also use for UI reviews, accessibility passes and dark-mode work."
---

# UIUXmasterclass

One workflow, two sources of truth:

- **Taste** (from [taste-skill](https://github.com/leonxlnx/taste-skill), MIT): *what to refuse*. Reads the brief, sets dials, bans LLM default aesthetics. Summarised in `references/anti-slop.md`.
- **Intelligence** (from [ui-ux-pro-max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill), MIT): *what to reach for*. A local search engine over styles, palettes, font pairings, UX rules, chart types and stack guidance. Run via `scripts/search.py`, with rules in `references/quick-reference.md` and `references/pro-rules.md`.
- **Data-dense mode** (this skill): the bridge for dashboards, stats apps and trackers, which taste-skill leaves out of scope. See `references/data-dense.md`.

When the two disagree, apply this order: **user brief and brand > accessibility > anti-slop bans > search-engine suggestion**. The search engine suggests; taste decides.

---

## Step 0. Design Read (before any code)

State one line: **"Reading this as: <page kind> for <audience>, with a <vibe> language, leaning toward <system or aesthetic family>."**

Signals to read: page kind, vibe words, linked references, audience, existing brand assets (logo, colours, type), quiet constraints (accessibility-first, regulated, kids). Brand assets are starting material, not optional input. If the read genuinely forks, ask **one** question. Otherwise proceed.

## Step 1. Set the three dials

| Dial | 1-3 | 4-7 | 8-10 |
|---|---|---|---|
| `DESIGN_VARIANCE` | symmetric, centred | offsets, mixed ratios | asymmetric, fractional grids |
| `MOTION_INTENSITY` | hover/active only | CSS transitions, load-in cascades | scroll-driven choreography |
| `VISUAL_DENSITY` | gallery-airy | daily-app spacing | cockpit: hairlines not cards, mono numbers |

Presets: landing 7/6/4, portfolio 8/7/3, editorial 6/4/3, public-sector 3/2/5, **dashboard / stats tracker 6/4/7**, redesign-preserve = match existing.
Asymmetric layouts (variance >= 4) **must** collapse to one column below 768px.

## Step 2. Generate the design system with the engine

Always call the script by its path inside this skill directory:

```bash
S=".claude/skills/UIUXmasterclass-skill/scripts/search.py"
python3 $S "<product> <industry> <keywords>" --design-system -p "<Project>" \
  --variance <V> --motion <M> --density <D>
```

Then supplement with one focused query per concern (2-5 words, one domain):

```bash
python3 $S "real-time dashboard" --domain chart
python3 $S "tabs keyboard focus" --domain ux
python3 $S "sports condensed display" --domain typography
python3 $S "<concern>" --stack html-tailwind   # or react, nextjs, svelte, vue...
```

Domains: `product style color typography google-fonts chart ux landing icons gsap react web`. If a search returns nothing twice, say so and fall back to the rules here. Never present an empty search as a result. Persist with `--persist --output-dir <project-root>` only if the user wants a `design-system/` folder.

**Filter the output through taste before using it:**
- Brand colours override the suggested palette. Keep the brand recognisable in both themes.
- Swap LLM-default fonts (Inter as a reflex, Fraunces, Instrument Serif) for the engine's second choice or a taste pairing: Geist + Geist Mono, Satoshi + JetBrains Mono, Cabinet Grotesk + Inter Tight. Sport/data products: a condensed display face (Big Shoulders Display, Barlow Condensed, Oswald) with a clean grotesk body and a mono or tabular-nums face for figures.
- Suggested "pattern" sections are a menu, not a script. Only build sections the content can fill.

## Step 3. Lock the tokens

Write CSS custom properties (or Tailwind theme) **once**, at the root:

- Semantic tokens: `--bg --surface --surface-2 --line --text --text-2 --text-3 --accent --on-accent --win --draw --loss --focus` (rename to the domain).
- Both themes defined from the start: `prefers-color-scheme` by default, plus a manual toggle via `[data-theme]`. No pure `#000` / `#fff`.
- **One accent**, saturation under 80%. Brand identity colours may appear as fields/stripes, but interactive emphasis uses the single accent everywhere.
- **One radius scale** (sharp, soft 12-16px, or pill), documented and obeyed.
- A type scale with `font-variant-numeric: tabular-nums` on every number that updates or aligns.
- A z-index scale (base, sticky, overlay, toast). No `z-index: 9999`.
- Motion tokens: `--ease-out: cubic-bezier(0.16,1,0.3,1)`, durations 150/250/400ms. Only `transform` and `opacity` animate.

## Step 4. Build

Hard rules (details and rationale in `references/anti-slop.md`):

1. **Hero/header fits the first viewport**: headline at most 2 lines, subtext at most 20 words, at most 4 text elements.
2. **No AI tells**: no purple-blue glow gradients, no three equal feature cards, no eyebrow above every section (max 1 per 3 sections), no section numbering ("01 / Features"), no decorative status dots, no scroll cues, no fake div screenshots, no gradient text on big headers, no neon glows, no custom cursors.
3. **Zero em-dashes (—) and en-dash separators (–)** in visible copy. Use a period, comma, colon or hyphen.
4. **Icons from one real set** (Phosphor, Tabler, Heroicons, Radix), one stroke weight. No emoji as UI. No hand-drawn SVG icons.
5. **Real assets**: real logos, photos and crests from the data source or brand. Never placeholder "Jane Doe" people, fake-perfect numbers or invented brand names.
6. **Every state is designed**: skeletons shaped like the final layout (not spinners), composed empty states that say how to populate, inline contextual errors, `:active` press feedback (`scale(.98)` or a 1px translate).
7. **Cards only when elevation means hierarchy.** Otherwise group with space, hairlines or tone shifts. Vary layout families: the same section layout at most once per page.
8. **Theme lock**: sections never flip theme mid-page.
9. **Accessibility is not optional**: contrast 4.5:1 for body text and 3:1 for large text and UI marks, a visible `:focus-visible` ring, keyboard reachable tabs and menus (roving tabindex / arrow keys), touch targets at least 44px, `aria-live` for updating scores, colour never the only signal (W/D/L carry a letter too), `prefers-reduced-motion` collapses every animation.
10. **Performance**: reserve space for images (`width`/`height` or `aspect-ratio`), `loading="lazy"` below the fold, preconnect fonts with `display=swap`, grain/noise only on a fixed `pointer-events:none` layer, no `scroll` event listeners (use IntersectionObserver or CSS scroll-driven animation).

For dashboards, stats and data apps, also apply `references/data-dense.md`.

## Step 5. Copy audit

Re-read every visible string. Rewrite anything cute-but-wrong, filler verbs ("elevate", "seamless", "unleash"), micro-meta sentences, or copy that reads like an LLM trying to sound thoughtful. Plain functional labels beat poetic ones. One register per page.

## Step 6. Pre-flight (mechanical; fail means fix and re-check)

- [ ] Design read stated; dials set; engine queried (or fallback declared)
- [ ] Tokens defined once; both themes render; toggle works; no raw hex in components
- [ ] `grep -n "—\|–"` over templates/copy returns nothing user-visible
- [ ] Eyebrow count <= ceil(sections / 3); no section numbers; no decorative dots
- [ ] One accent; one radius scale; one icon family
- [ ] Loading, empty and error states exist for every data region
- [ ] Keyboard pass: tab through everything, focus ring visible, no traps
- [ ] 375px, 768px, 1024px and 1440px checked; no horizontal page scroll
- [ ] `prefers-reduced-motion: reduce` tested
- [ ] Contrast checked in **both** themes (text 4.5:1, UI marks 3:1)
- [ ] Images have dimensions + alt; decorative icons `aria-hidden="true"`
- [ ] Copy audit done; numbers are real data or labelled as such
- [ ] If a browser is available: screenshot both themes at mobile and desktop and look at them before declaring done

## Redesigns

Detect the mode first: greenfield, **preserve**, or **overhaul**. For preserve, audit brand tokens, IA, SEO baseline and existing accessibility wins before touching anything. Apply levers in order: typography, spacing, colour recalibration, motion, then recomposition. Never silently change URLs, nav labels, form field names, logos or legal copy.

## Files

| Path | Use |
|---|---|
| `scripts/search.py` | Design-intelligence search (`--design-system`, `--domain`, `--stack`) |
| `data/` | CSV/JSON knowledge base used by the script |
| `references/anti-slop.md` | Taste rules in full: bans, layout discipline, copy rules |
| `references/data-dense.md` | Dashboard / stats / tracker rules |
| `references/quick-reference.md` | All 119 UX guidelines by category |
| `references/pro-rules.md` | Polish rules + canonical pre-delivery checklist (app UI) |
| `licenses/` | Upstream MIT licenses and pinned commits |
