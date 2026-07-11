# CI Results design QA

- Source visual truth: `/var/folders/rn/tbt648y54zl_f0m02zrb01sm0000gn/T/TemporaryItems/NSIRD_screencaptureui_PiHUvi/Screenshot 2026-07-11 at 14.20.06.png`
- Implementation screenshots: `/tmp/backend-ci-2048-glowfix-collapsed.png`, `/tmp/backend-ci-2048-glowfix-expanded.png`, `/tmp/backend-ci-2048-glowfix-scrolled.png`
- Focused evidence: `/tmp/backend-ci-local-arcana-unit-module.png`, `/tmp/backend-ci-local-server-live-e2e.png`, `/tmp/backend-ci-nested-scroll-final.png`
- Responsive evidence: `/tmp/backend-ci-responsive-430-final.png`, `/tmp/backend-ci-responsive-1200-final.png`, `/tmp/backend-ci-responsive-1280-final.png`, `/tmp/backend-ci-responsive-1320-final.png`
- Viewports: 2048 x 765 primary comparison; 1624 x 788 dense-state inspection; 430, 1200, 1280, and 1320 x 900 responsive inspection
- State: dark CI Results; q production summary and artifacts; collapsed overview, Arcana Unit hierarchy, server_py Live E2E selectors, and scrolled run history

## Full-view comparison evidence

The implementation preserves the source three-repository composition, background treatment, glass surfaces, status palette, typography hierarchy, and information density. The former always-open evidence blocks are intentionally replaced by compact repository-specific stacks and expandable rows. At 2048 and 1320 px, all three columns remain aligned without overlap or hidden primary actions. Narrower windows reuse the existing single-column composition so dense evidence does not collapse into unreadable cards.

## Focused-region comparison evidence

Focused inspection was required because module, contract, selector-pill, duration, and count labels are not readable in the full view. The Arcana capture proves layer -> subsystem/module -> contract/scope disclosure. The server_py capture proves the dedicated canary and model-selector family card, including observed effort pills. Expansion, nested expansion, collapse, and scroll were exercised through Chrome CDP.

## Findings

No actionable P0, P1, or P2 findings remain.

- Typography and copy: existing display/body families, weights, line heights, and semantic color hierarchy are preserved. Module names are title-cased; fallback provenance is the concise `JUnit scope · not ledger-mapped.`
- Spacing and layout: compact rows, full-card hover bounds, nested card padding, radii, borders, and scroll containment remain consistent with the source design. The 348-case Unit layer is bounded rather than extending the page.
- Colors and tokens: existing green, cyan, amber, muted, glass, border, and status tokens are reused; no replacement palette was introduced.
- Image quality: the existing winter landscape remains sharp, correctly cropped, and unobstructed. No source image or icon was replaced.
- Content: backend, server_py, and Arcana names, counts, durations, coverage, links, classifications, and evidence provenance remain explicit.
- Accessibility and behavior: disclosure rows retain practical full-width hit areas and visible state changes. On Wasm, nested evidence owns wheel input while hovered, then hands boundary deltas to the page; the accelerated outer bridge remains active elsewhere. No JS runtime/console errors were observed. The disposable preview intentionally lacked a websocket proxy, so its offline badge and connection refusal were expected; all HTTP summary/artifact data loaded from q.

## Comparison history

1. Initial P2: ordinary module identifiers remained lowercase and every fallback repeated a long provenance sentence.
   - Fix: centralized identifier title-casing and shortened the fallback to `JUnit scope · not ledger-mapped.`
   - Post-fix evidence: `/tmp/backend-ci-local-arcana-unit-module.png`.
2. Initial P1: web-only CSS status lights ignored Compose clipping, leaving cached/offscreen LazyColumn lights floating outside their cards after expansion and scrolling.
   - Fix: compare unclipped and clipped `boundsInWindow` and hide only DOM lights whose Compose slot is not fully visible. The commented Compose renderer remains untouched for the future engine migration.
   - Post-fix evidence: `/tmp/backend-ci-2048-glowfix-expanded.png` and `/tmp/backend-ci-2048-glowfix-scrolled.png`.
   - Structural check: 41 mounted overlays while scrolled; 7 visible, 34 hidden, and 0 visible outside the 2048 x 765 viewport. Scrolling back restored 16 visible, 19 hidden, and still 0 outside.
3. Initial P2: disclosure hover indication covered only the inner padded row instead of the complete bordered card header.
   - Fix: apply click and hover semantics to each full-width layer, umbrella, and group header, with padding inside the clickable region.
   - Post-fix evidence: user-confirmed desktop hot reload and `/tmp/backend-ci-nested-scroll-final.png`.
4. Initial P1: the Wasm capture-phase acceleration bridge consumed every wheel event before nested Compose scrollers could receive it.
   - Fix: a static CompositionLocal marks only native nested-wheel regions; the Wasm bridge yields while one is hovered, preserving native boundary handoff without adding recomposition state.
   - Post-fix evidence: `/tmp/backend-ci-nested-scroll-final.png` changed inner module identities while every outer status-light anchor remained pixel-identical; `/tmp/backend-ci-boundary-final.png` then proved page handoff. Final overlay audit: 47 mounted, 11 visible, 36 hidden, and 0 visible outside 1320 x 900.
5. Initial P2: the original 980dp breakpoint kept three dense repository cards side by side at 1000-1200px, truncating meaningful labels.
   - Fix: preserve the same weighted row and mobile column, switching at the measured 1280dp content threshold; no new layout machinery.
   - Post-fix evidence: `/tmp/backend-ci-responsive-1200-final.png` and `/tmp/backend-ci-responsive-1280-final.png` use the readable column; `/tmp/backend-ci-responsive-1320-final.png` restores the compact three-column cockpit; `/tmp/backend-ci-responsive-430-final.png` remains mobile-readable.

## Implementation checklist

- [x] Three-repository overview remains readable at the reference desktop viewport.
- [x] Layer, module/subsystem, contract/scope, and exact-case disclosures work.
- [x] Live E2E selector evidence has its dedicated presentation.
- [x] Nested large suites stay scroll-contained.
- [x] Nested Wasm wheel ownership and outer-page boundary handoff work.
- [x] Desktop, narrow-window, and mobile-width layouts preserve readable evidence.
- [x] CSS status lights remain performant and obey Compose visibility/clipping.
- [x] JVM, desktop, and production Wasm builds pass.

final result: passed
