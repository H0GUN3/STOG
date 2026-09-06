# STOG Final Presentation Design System

## 0. Research Log

- Embedded reference: `docs/STOG_최종발표_PPT_구성안.docx` for narrative sequence and `docs/아키텍쳐 예시.png` for diagram density.
- Existing product assets: `docs/ds_rfs/stog_flow_design_mockup.png`, `docs/ds_rfs/여행성향.png`, and `docs/diagrams/stog-app-server-cloud-gcs-overview.png`.
- Chosen direction: warm editorial travel journal with a dark map-like technical layer; no generated imagery because existing project assets provide stronger product evidence.

## 1. Atmosphere & Identity

STOG should feel like a clear travel map on bright paper: airy, friendly, and easy to scan from the back of a presentation room. The signature is a single yellow route line that moves through light cards and becomes the data flow on the black architecture slide.

## 2. Color

| Role | Token | Value |
|---|---|---|
| Canvas | `cream` | `#F7F8F5` |
| Surface | `paper` | `#FFFFFF` |
| Ink | `ink` | `#13191D` |
| Muted | `muted` | `#64717B` |
| Route accent | `yellow` | `#F5B82E` |
| Map blue | `blue` | `#739FBB` |
| Nature olive | `olive` | `#A7B789` |
| Warm coral | `coral` | `#D88164` |
| Dark canvas | `night` | `#0F1418` |
| Dark surface | `nightSurface` | `#1B252D` |
| Light yellow | `paleYellow` | `#FFF1C7` |
| Light blue | `paleBlue` | `#E7F0F5` |
| Light coral | `paleCoral` | `#FBE7DF` |
| Light olive | `paleOlive` | `#EDF2E6` |

## 3. Typography

- Display: `Aptos Display`, 28–38pt, tight line spacing.
- Korean and UI: `Malgun Gothic`, 11–24pt.
- Metadata: `Cascadia Mono`, 8–10pt.
- Never place more than one short paragraph on a slide. Prefer a headline, three keywords, and one visual proof.

## 4. Spacing & Layout

- Canvas: 13.333 × 7.5 inches, 16:9.
- Outer margin: 0.55 inches.
- Base spacing: 0.12 inches; primary gaps: 0.24 / 0.48 / 0.72 inches.
- Use a 12-column mental grid. Keep diagrams inside the slide with at least 0.35 inches of breathing room.

## 5. Primitives

- `TitleBand`: overline, large title, one-line thesis.
- `EvidenceCard`: one number or keyword, one short descriptor, one pastel accent.
- `FlowNode`: icon-like label, short responsibility, one directional connector.
- `RouteLine`: yellow path used for narrative and system flow.
- `ArchitectureBox`: black container with nested cloud services and labeled arrows.

## 6. Motion & Interaction

Slides are static by design. Video is intentionally left as a later editing layer. The route line and numbered steps provide visual motion without animation.

## 7. Depth & Surface

Use bright canvas/surface contrast, thin borders, and restrained pastel accents. The architecture slide alone uses nested charcoal surfaces on black. Avoid decorative gradients and excessive shadows.

## 8. Accessibility Constraints & Accepted Debt

- Body text stays at or above 14pt where possible; diagram labels stay at or above 10pt.
- Contrast targets: dark ink on cream and ivory on night.
- Every diagram connection has a text label; color is never the only encoding.
- Accepted debt: final live-demo screenshots and video are inserted during presentation editing, not fabricated in this deck.
