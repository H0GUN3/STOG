# STOG mobile design contract

## Direction

STOG uses a clean travel surface: pure-white canvas and surfaces, ink-colored
text, and yellow only for the primary action or active state. The map shell
uses a dark navigation surface while keeping map content visible behind
planning controls.

## Verification boundary

Device and emulator testing is a separate runtime surface. The runner records
the actual command output, device identity, screenshots, and observed visual,
permission, background-service, navigation, or multi-member behavior as
evidence. Host-side tests, lint, assembly, and source inspection remain
separate from device results. Every device-only criterion is recorded with
setup, action, expected result, and failure recovery; a source review alone
cannot claim a numeric visual score.

## Shared tokens

- Canvas: `StogCanvas` = `StogWhite`
- Surface: `StogSurface` = `StogWhite`
- Navigation surface: `StogDarkCanvas` (`#24221D`)
- Navigation inactive icon and label: `StogWhite`
- Navigation active icon and label: `StogYellow`
- Primary: `StogYellow`
- Active trip status text: `StogWarm`
- Ink and muted text: `StogInk`, `StogMuted`
- Border: `StogBorder`
- Base spacing: 8dp
- Screen content inset: 24dp
- Shared elevated surface top corners: 28dp
- Shared content cards: 16dp corners with a subtle `StogBorder`
- `StogMapBottomSheet` state heights: collapsed 96dp, half expanded 240dp,
  expanded 600dp on the configured Pixel 8 reference device. Expanded leaves
  the map-layer top action reachable.
- Map-layer edge treatment: 12dp platform shadow plus a 1dp `StogBorder`
  header line, visibly separating adjacent surfaces
- Map action controls: 6dp platform shadow using each control's existing shape;
  no custom Canvas shadow or layout change
- Sheet handle/control row: at least 48dp high
- Sheet title: `titleLarge`; place title: `titleMedium`; metadata/status: `bodyMedium`
- Primary and completed buttons keep identical geometry; completed uses readable
  disabled text and does not rely on color alone
- Character accents are supporting visual cues only: Red `#E53935`, Orange
  `#FB8C00`, Yellow `#FBC02D`, Green `#43A047`, Blue `#1E88E5`, Purple
  `#8E24AA`. Character names and roles remain visible beside every accent.

## Shared primitives

- `StogStatePanel`: the single judge-facing status primitive for section-scoped
  loading, honest empty, typed error, offline, expired-authentication, and
  permission-denied states. It uses the shared white surface, 16dp radius,
  `StogBorder`, 8dp rhythm, polite live-region semantics, and at most one
  recover/login/permission action with a 48dp target. Its machine state and
  action availability are exposed through production Compose semantics; screen
  tests do not pin display prose.
- `StogMapBottomSheet`: a persistent, map-overlaid Kakao-map-style sheet with
  a top-center drag handle and exactly three states: collapsed, half expanded,
  and expanded. It provides explicit state actions without covering the map
  context; its planning-status content identifies the selected trip and the
  current-session basket count in half expanded and expanded states.
- `Card`: content groups use one surface level; avoid cards nested inside cards.
- `Button`: yellow filled buttons are the single primary action in a region;
  disabled state is reserved for unavailable or already-completed actions.
- `StogAssetImage`: the shared bundled-image primitive owns asynchronous asset
  decoding, bounded image memory, content description, and stable layout while
  an asset loads. Character and survey-image surfaces reuse it.

## Map planning layout

- The Google map owns the map viewport. The top action bar owns an opaque
  `StogDarkCanvas` backing through the status-bar inset, and the bottom
  navigation clears the navigation bar with the same surface.
- `StogMapBottomSheet` owns planning content only. Place search never renders
  inside it and never changes its anchor or settled level.
- Discover never renders inside `StogMapBottomSheet`. The Discover navigation
  item opens a dedicated `StogCanvas` full-screen feed while preserving the
  shared five-item bottom navigation.
- The bottom sheet uses `StogSurface`. The top action bar and bottom navigation
  use opaque `StogDarkCanvas`; the navigation surface extends through the
  gesture-navigation area. Each component uses a uniform 12dp platform shadow
  and 1dp `StogBorder` line across its full top edge so the map, sheet, and
  navigation layers remain visibly distinct without changing their surface
  color. Map-shell system-bar icons use the light (white-icon) appearance.
- The bottom navigation is one row of five equal-width items. The centered AI
  item uses its image within the same layout and touch target as every other
  navigation item.
- The top map action bar keeps a search trigger followed by a notification
  icon. Its controls use 48dp affordances with 8dp gaps and never overlap the
  sheet. Search and notification surfaces keep their existing geometry and use
  the shared 6dp map-control shadow. The basket action is intentionally reserved
  for a different surface. The bar uses an 8dp outer edge inset; the supplied
  logo's transparent export margin receives an optical -12dp placement
  correction in a 120x40dp logo slot without changing action hit areas or bar
  height. Top action controls keep the dark shell surface with white glyphs and
  no circular background. The notification action shows an in-context
  empty-state message until notification content is available.
- Candidate rows use the shared card and button geometry; confirmed candidates
  remain visible with a disabled `담김` state.

## Dedicated place search

Exact visual references:

- `docs/ds_rfs/research1.jpg`: the map search control is an entry point, not
  expandable sheet content.
- `docs/ds_rfs/research2.jpg`: the entered state is a white full-screen search
  surface with an immediately focused field and visible software keyboard.

Contract:

- Tapping the map search control changes the app destination to the dedicated
  search screen without moving or replacing `StogMapBottomSheet`.
- The dedicated search screen keeps the shared `StogAppHeader` above the
  focused search field. It always exposes the STOG logo, search trigger, and
  notification action; the search trigger refocuses the existing field instead
  of opening a second search surface.
- The search screen uses a 24dp horizontal gutter and a rounded outlined search
  field containing a back action and `장소·주소 검색` input, with the shared
  6dp shadow and no microphone affordance.
- When the query is non-empty, the field shows a trailing clear action that
  removes the input without starting a new search.
- A horizontally scrolling category row follows the field, then a divider and
  the recent-search or result list. The home/work shortcuts, edit action,
  reserved banner, and search-mode chips are not part of this surface.
- The `AI추천` category uses the supplied
  `STOG_app_icon_foreground.png` in a 40dp square slot, tinted `StogYellow`;
  each category keeps a 48dp touch target.
- On initial entry, the body loads persisted recent searches or its honest empty
  state. After submission, STOG place cards replace that region and preserve
  bookmark, login, trip selection, and basket behavior.
- The field requests focus on entry. The screen body owns vertical scrolling;
  the map sheet and bottom navigation are not composed on this destination.
- The medium place-details state keeps the reference two-photo thumbnail row
  geometry and gives the sheet content the scroll owner when wrapped Korean
  titles or addresses exceed the available height; the row must remain
  reachable without changing the sheet anchor.

## Record capture and archive

- `PhotoCaptureScreen` is a dedicated edge-to-edge dark destination matching
  `docs/img/STOG_CAMERA.png`; `PhotoArchiveScreen` remains a dedicated
  light-canvas destination. Neither introduces a second navigation bar.
- The capture viewport adopts StyleGallery `overlay-stack`: the CameraX
  `PreviewView` fills the viewport, controls and truthful location feedback are
  overlaid in semantic focus order, and no child owns scrolling. Close and flash
  occupy top-start/top-end; gallery, shutter, and lens switch occupy
  bottom-start/bottom-center/bottom-end. Exactly these five controls exist, each
  with a 48dp minimum target; the shutter is a 72dp white disc with an ink ring.
- Capture uses existing dark tokens only: `StogDarkCanvas` for unavailable or
  obscured preview, `StogDarkSurface` for readable status material, `StogWhite`
  for controls/text, and `StogYellow` for location and primary Record emphasis.
  Small glyphs remain 24dp and all placement follows the 8dp scale.
- Confirmation uses a dark `cover`/scroll-body-shell composition: fixed back
  header, one vertically scrolling metadata body, and a fixed two-action footer.
  The 4:3 local derivative preview uses the large 24dp top radius; one white
  metadata surface below it uses 16dp lower corners and 1dp separators. Retake
  is secondary and Record is the sole yellow durable-commit action.
- `SetLogCaptureSurface` and `SetLogConfirmationSurface` are reusable state
  primitives. Capture states cover permission required, initializing, ready,
  capturing, provider/location failure, and bind failure. Confirmation states
  cover matched/no-match/pending place, known/unknown time, camera/gallery
  provenance, valid/invalid one-line note, private/public intent, saving,
  pending sync, saved, and failed. State is conveyed by labels and semantics,
  never color alone.
- Camera and location permission actions remain independent. Gallery remains
  available when camera permission is denied. Camera Record requires a
  policy-compliant foreground fix; a location-less gallery selection is
  explicitly archive-only and never receives current device location or time.
- Archive rows reuse the shared card anatomy: thumbnail area, title/metadata,
  then visibility and consent state. The "내 사진" filter is a labelled
  stateful control, not an icon-only affordance. Visibility and public consent
  remain separate controls; public consent names its version and pending
  moderation rather than implying publication.
- `SetLogReadbackMetadata` is the shared metadata treatment for archive rows,
  archive photo detail, archive map markers, and Cell photo cards. It uses the
  immutable place-name snapshot or an explicit no-match/unknown state, an
  optional note, one device-timezone capture-instant format or unknown state,
  and separate visibility, moderation, and current publication labels. Public
  intent, moderation pending/blocked, trip-not-public, and revoked states never
  use copy that implies current public visibility. Coordinate-less records stay
  in authorized archive/detail surfaces and never produce map or Cell markers.
- Record actions maintain at least 48dp touch targets, announce upload/failure
  changes as live status, preserve standard focus order, and never use color as
  the sole privacy or permission signal.

## Travel surfaces

- The visual direction is a modern premium map-based travel app: black and
  warm-white contrast, STOG yellow as the point color, elegant Korean type,
  soft rounded surfaces, and emotionally warm travel atmosphere. A plain list
  of generic cards is not an acceptable final surface.
- Home remains map-first. Its sheet owns the action for the current trip stage:
  empty suggests creating a trip, upcoming summarizes one trip, active leads
  with the next itinerary item, and completed links to the latest record.
- My Trips is a dedicated light-canvas destination, not map-sheet content. Its
  list groups active, upcoming, and completed trips before entering one
  workspace.
- Discover is a dedicated light-canvas feed destination. Its list owns vertical
  scrolling, uses the shared 24dp screen gutter, and keeps the Discover
  navigation item active.
- Discover matches `docs/img/발견.png`: the top navigation header is omitted,
  so the editorial cover image extends behind the system-bar area while its
  copy keeps the safe inset, then flows into a flat white SNS feed with a
  24dp gutter.
- Photo posts keep the visual order `author + location → 4:3 photo →
  like/comment reaction row → like count → caption and tags`. The author row
  owns the bookmark/save action, and no send/share action appears.
- The Discover save action is a real labelled touch target even when its visual
  glyph is an icon. Save and unsave remain Discover behavior; the user screen
  intentionally focuses on the current account's own photo archive.
- Comments remain outside the user profile surface until their product contract
  is reopened. Profile UI never presents a placeholder for a deferred action.
- The public-trail feed remains a separate product path, but it does not add
  management metadata to the photo-post rhythm.
- Basket candidates use a horizontal reel. Selection is explicit, and one
  yellow add-to-day button is the only primary action in that region. Drag and
  drop is not part of the MVP.
- Ordered itinerary cards show arrival time, expected visit duration, and the
  travel interval before the next card. Completed items move into one collapsed
  disclosure instead of disappearing.
- STOBEE always displays the selected trip name. Suggested changes are shown as
  proposals with a separate apply action; conversation alone never changes the
  itinerary.
- Fixture-only states are labelled `FE 미리보기` and never present save, visit,
  or AI proposal actions as server-completed work.

## User profile surface

- The profile owns one `LazyColumn`; no section creates an internal scroll
  container. Its semantic order is `header → identity hero → trip summary →
  character affinity → own photo archive`, using the page-grid gutter contract.
- The identity hero keeps the existing login, nickname edit, STOG fallback
  avatar, and direct signed avatar upload. The authenticated header exposes
  only logout; deferred settings and support actions are not rendered.
- The trip summary is a compact three-value readout for trips, distinct
  recorded Cells, and honey.
- Character affinity is derived from the server's `preference_scores` 6-axis
  map, not a new profile type or database field. The highest score appears in
  a large identity card, followed by a stable six-item affinity feed. Every
  item includes the character image, name, role, score, and text label; no
  color-only meaning is allowed.
- The affinity feed adapts StyleGallery `feed`
  (https://raw.githubusercontent.com/changeroa/StyleGallery/main/patterns/stacking/feed.md);
  the surrounding profile `LazyColumn` remains its only scroll owner. Primary
  character images are 112dp on the profile and 168dp on the result surface;
  affinity rows use 64dp images.
- The archive adapts StyleGallery `dense-grid`
  (https://raw.githubusercontent.com/changeroa/StyleGallery/main/patterns/grid-repetition/dense-grid.md)
  to exactly three equal square columns on phone widths. Rows keep source
  order, fill incomplete rows with inert space, and each owned tile exposes
  its visibility state as text and semantics. Loading, error, and empty states
  stay local to the archive section.

## Preference survey and result surface

- The survey owns one `LazyColumn`. Each question uses a fluid 4:3 media frame
  above the prompt and options; the question image is decorative support with
  an accessible description derived from the prompt. This adapts StyleGallery
  `frame`
  (https://raw.githubusercontent.com/changeroa/StyleGallery/main/patterns/media-fit/frame.md)
  with the survey `LazyColumn` as the only scroll owner.
- After the canonical survey is saved successfully, the result surface shows
  the representative character and the six-axis affinity feed before normal
  post-login routing. The result surface owns no nested scroll container and
  has one explicit continuation action.

## FE finish rules

### Tokens and alignment

- Use an 8dp spacing grid. Screen gutters are 24dp; section, card, and text
  spacing comes from the same grid rather than one-off values.
- Corner radius has three levels only: small 10dp, medium 16dp, large 24dp.
- Separate most surfaces with color and a 1dp border. Elevation is reserved for
  floating map controls and sheets.
- Do not force unrelated cards to equal heights. Align image ratios, title
  baselines, and footer positions within the same card family.
- Horizontal reels leave 16-30dp of the next card visible. Section titles and
  trailing actions align by text baseline.
- Small icons remain 20-24dp inside at least 48dp touch targets. Card and
  bookmark actions use non-overlapping hit regions.
- Place names use at most two lines with ellipsis. Image boxes keep a fixed
  ratio before loading and show a STOG tourism, food, or festival placeholder
  instead of a generic gray box.

### Data states

- Loading is section-scoped so delayed nickname, trip, and recommendation data
  does not shift the whole screen. Each section defines empty, loading, error,
  and loaded states.
- A section error stays local and does not replace unrelated content. When
  recommendations are absent, hide that section or show useful alternative
  content rather than an empty card.

### Map and sheet mechanics

- Sheet movement does not recalculate the map camera position. Camera padding
  uses the unobscured map region so a selected marker or Cell stays visible
  when the sheet expands.
- Sheet drag and inner-list scroll have explicit nested-scroll ownership.
  Reaching the end of a list must not unexpectedly collapse the sheet.
- The handle is a subtle real drag affordance. Expanded sheets respect the top
  safe inset, and clipping order must not create a dark seam at rounded corners.
- Edge-to-edge inset ownership is singular: root, top bar, map, sheet, and
  bottom navigation each have one declared owner. Never stack
  `statusBarsPadding()` or `navigationBarsPadding()` on parent and child.
- Gesture and three-button navigation modes must both keep the bottom UI
  visible. Sheet and map heights derive from available constraints, never one
  Pixel-device `height(XXX.dp)` value.

### Korean type and travel cards

- Typography has five roles only: screen/sheet title, section title, card
  title, body, caption. Do not add hard-coded Korean line breaks; wrapping
  follows available width.
- One surface uses one date style. Do not mix `8.24 - 8.26`, `2026.08.24`, and
  `8월 24일` in the same context.
- Trip status always includes text such as `예정`, `여행 중`, or `완료`; color
  is supplementary. Member previews stop after three faces and use `+N`.
- A trip card prioritizes trip name, date, one status, and one supporting fact.
  Do not turn it into an administrator summary.

### Itinerary motion and STOG identity

- Adding a place highlights its insertion point with a brief entrance motion
  and scrolls only when needed. Reordering visibly moves the card.
- Travel time and distance use a lower-emphasis connector between place cards.
  Long day selectors scroll horizontally, and the selected day has a clear
  background, underline, or pill state.
- Cell emphasis increases in this order: base, recommended, visited, selected,
  photo-bearing. It must not obscure map labels.
- A Cell photo uses a minimum crop scale, center crop, and optional dim layer.
  Show one representative photo plus a small count, never a photo stack.

### 내 여행 reference surface

- `docs/img/내 여행 페이지.png` is the visual contract: a white edge-to-edge
  surface, `내 여행` title, four category tabs, a dashed `새 여행 만들기`
  entry card above the active section, grouped trip sections, and shared dark
  five-item navigation.
- The category tabs filter `전체`, `진행 중`, `예정`, and `지난 여행`.
  The all-trips view shows section counts in that order, with the active
  section first.
- Trip cards use one horizontal row with a 96dp image slot, title/status,
  date/duration, participant, and place-count support lines, and a trailing
  navigation affordance. Cards never expose date editing controls.
- The add card opens a separate `새 여행` destination that owns title/date
  editing. Group-trip management sends an invitation link through the Android
  share sheet; the `LazyColumn` body owns scrolling while
  the parent `Scaffold` keeps bottom navigation fixed. This adopts StyleGallery
  `feed` and `scroll-body-shell` as the spatial contracts.
- Until the trip-list API is finalized, the Android feed uses deterministic
  local mock cards; existing creation, detail, and management callbacks remain
  the integration boundary.
- Trip detail places the mapped representative image above the information
  card. Its overflow action opens a modal `여행 관리` sheet whose destructive
  row is role-specific: owners see `여행 삭제`, while invited members see
  `여행 나가기`; both never appear together.
- The owner sheet contains invite sharing, participant management, and trip
  information editing. The member sheet contains invite sharing and
  participant viewing. Destructive actions always require
  confirmation. Owner deletion is blocked while active non-owner members
  remain; participant removal or departure must happen first.

Functionality and visual completion are separate gates. After each screen,
review spacing, alignment, typography hierarchy, touch targets, data states,
system insets, long Korean text, small screens, gesture conflicts, and component
consistency. Prefer shared tokens over arbitrary dp or sp values.

