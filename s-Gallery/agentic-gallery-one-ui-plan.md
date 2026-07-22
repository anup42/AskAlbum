# Agentic Gallery One UI redesign plan

## Outcome

Agentic Gallery should open and behave like a gallery first, with AI search integrated into normal gallery navigation. The primary screens should show the user's media, not implementation explanations. Model, indexing, and privacy details remain available under Settings without occupying the main experience.

This plan is based on the Samsung Gallery screens captured in this folder, the current Agentic Gallery screens captured beside them, and Samsung's published One UI guidance.

## Current problems found

| Current behavior | Why it does not feel like a gallery | Proposed replacement |
|---|---|---|
| A branded `AskPhotos` header appears on every screen | Consumes the high-value first viewport and makes every screen look like a demo | Screen-specific chrome with only relevant actions; no global brand masthead |
| Gallery starts with a large title, explanation, and import buttons | Media is pushed below the fold | Date/event header followed immediately by a dense photo grid; import moves to overflow or Menu |
| Media uses two-column cards with titles, locations, and repeated evidence text | A gallery should prioritize visual scanning and density | Four-column square thumbnails with 1-2 dp gutters and contextual badges only |
| `Why this answer?` is repeated on every tile | Evidence is useful only in a query result context | One evidence action on an answer card or detail sheet |
| Bottom navigation has Ask, Results, Gallery, Index, and Privacy | Results are transient, while Index and Privacy are settings; letter icons look unfinished | Photos, Albums, Ask, Menu with proper icons and labels |
| Ask is a large instructional landing page | Explains the system instead of helping the user search | Compact search surface, recent searches, category shortcuts, and progressive grid results |
| Indexing stages and producer versions occupy a primary tab | Developer diagnostics are exposed as product navigation | One compact On-device AI status row under Settings; advanced details behind a disclosure |
| Permanent green/lime brand palette | Competes with media and does not follow system appearance | Neutral media-first surfaces using Material color scheme and system accent |
| Enum-based screen switching | Cannot naturally represent nested search, results, viewer, and settings flows | A real navigation graph with nested destinations and back-stack restoration |

## Information architecture

Use four compact primary destinations:

1. **Photos** — chronological media timeline and default launch destination.
2. **Albums** — user albums and smart albums such as Recent, Favorites, Camera, Screenshots, Videos, and Documents.
3. **Ask** — AI-powered gallery search, extraction, comparison, and conversation.
4. **Menu** — a rounded action sheet or destination containing Videos, Favorites, Recent, Documents, People, Places, Trash, and Settings.

Navigation changes:

- Remove **Results** from persistent navigation. Results are opened from Ask and retain the query in the back stack.
- Move **Index Manager** and **Privacy** to Menu > Settings.
- Show **Onboarding** only until permissions and initial setup are complete.
- Do not add an empty Collections tab. Introduce it later only when stories, people, places, and document collections are implemented.
- Use recognizable vector icons and labels; remove letter placeholders.

## Screen specifications

### Photos

- Remove the global app header and all introductory copy.
- Preserve a quiet one-handed top region, with Search and More actions at the upper right.
- Group the timeline by capture date and optionally display event or place as secondary text.
- Default to four square columns on a compact phone, with 1-2 dp gutters and no card surface.
- Allow pinch gestures to change between denser and larger grids.
- Add only useful thumbnail overlays: video duration, favorite, burst/duplicate count, cloud/import problem, and selection state.
- Add fast month/year scrolling for large libraries.
- Place import, refresh, sort, and view options in More or Menu.
- Show an empty-state explanation only when there is no accessible media.

### Albums

- Use Add, Search, and More top actions.
- Render 2-3 column rounded cover tiles with album name and item count beneath the cover.
- Place frequently used smart albums in an Essential albums section.
- Support View all, sort, hide/show, and reordering without permanently explaining those functions.

### Ask

- Present Ask as gallery search rather than a product tutorial.
- Use a compact, reachable input with voice action and one line of placeholder text.
- Show recent queries and concise suggestion chips only when helpful.
- Offer visual categories such as People, Places, Documents, Receipts, Videos, and Events as shortcuts.
- Preserve the user's original language and support English, Hindi, and Hinglish without language instructions in the UI.
- Stream initial candidates into the same gallery grid used by Photos.
- Present the grounded answer as a compact expandable card or bottom sheet.
- Put citations, OCR regions, confidence, exactness, and coverage in a single **Why this answer?** sheet.
- For no matches, show a plain no-result state and editable filter chips; never fill the page with implementation guidance.

### Media viewer

- Use an immersive black or content-adaptive background.
- Place Back in a floating top-left control and secondary actions in a top-right pill.
- Add a filmstrip near the bottom for fast adjacent navigation.
- Limit the visible bottom actions to five: Favorite, Edit/Enhance, Ask, Share, Delete. Put remaining commands under More.
- **Ask about this** opens a query scoped to the active media item and its event.
- For video results, seek to the indexed evidence timestamp.

### Multi-select

- Replace the normal toolbar with Cancel, selection count, and Select all.
- Display selection circles directly on thumbnails.
- Show a contextual bottom toolbar with Create/Add, Share, Delete, and More.
- Keep destructive confirmation explicit and continue using MediaStore-safe operations.

### Menu and Settings

- Use a rounded One UI-style sheet with a concise grid: Videos, Favorites, Recent, Documents, People, Places, Trash, Settings.
- Settings uses compact preference rows rather than large promotional cards.
- Put model choice/download, index progress, OCR provider, face provider, storage, and rebuild controls under **On-device AI**.
- Put permissions, sensitive OCR, face opt-in, authentication, and deletion controls under **Privacy**.
- Put licenses and app version under **About**.
- Hide raw producer versions, vector dimensions, timings, and trace data under an explicitly enabled developer diagnostics screen.

## Text to remove from primary screens

Remove or relocate the following current text:

- `AskPhotos`
- `Private gallery intelligence`
- `ON-DEVICE`
- `Search local memories, refine the result, and inspect every supporting fact`
- `A private memory, already useful`
- `14 CC0 sample photos are indexed...`
- `Network only for model downloads`
- `Local library`
- `14 consented local items`
- repeated `Why this answer?` text inside media cards
- `Gallery memory`
- `Progressive, versioned...`
- model runtime and producer-version strings on normal screens

Privacy is still explained during onboarding and available in Settings. A small lock/status indicator may appear only when it communicates an actionable state.

## Visual system

- Use `MaterialTheme.colorScheme` and system accent colors. Favor neutral backgrounds (`#F5F6F8` light and near-black dark) so media remains dominant.
- Use Android system sans-serif. SamsungOne is proprietary and must not be bundled.
- Use original Material Symbols or permissively licensed vectors; do not extract Samsung icons.
- Use large rounded floating navigation and action containers, roughly 28-36 dp corner radius, with minimum 48 dp touch targets.
- Suggested type hierarchy: 28-32 sp semibold screen/section title where needed, 18-20 sp section heading, 14-16 sp body/labels.
- Use restrained fades, scale transitions, and bottom-sheet motion; respect reduced-motion settings.
- Support light/dark themes, large font sizes, edge-to-edge insets, and contrast requirements.

## Responsive behavior

- Compact phones: floating bottom navigation and four-column media grid.
- Medium widths and unfolded portrait: navigation rail and adaptive grid.
- Expanded widths: rail or drawer plus optional two-pane gallery/viewer or results/evidence layout.
- Use `WindowSizeClass`; do not infer layout only from device model.

## Recommended code restructuring

The current UI is concentrated in `MainActivity.kt`. Split it before the detailed redesign:

```text
ui/theme/AgenticGalleryTheme.kt
ui/navigation/GalleryDestination.kt
ui/navigation/GalleryNavHost.kt
feature/photos/PhotosScreen.kt
feature/albums/AlbumsScreen.kt
feature/ask/AskScreen.kt
feature/results/QueryResultsScreen.kt
feature/viewer/MediaViewerScreen.kt
feature/menu/GalleryMenuSheet.kt
feature/settings/SettingsScreen.kt
ui/components/MediaThumbnail.kt
ui/components/GalleryBottomBar.kt
ui/components/SelectionToolbar.kt
ui/components/EvidenceSheet.kt
```

Implementation notes:

- Introduce Navigation Compose for viewer, search/results, nested settings, and restored conversations.
- Keep the existing agent, retrieval, evidence, OCR, face, and model interfaces behind view models; this redesign should not couple model runtimes to Compose.
- Add repository queries for date/event grouping, album cover/count aggregation, favorite state, selection, search history, and media details.
- Reuse one `MediaThumbnail` and grid implementation across Photos and query results.
- Preserve safe MediaStore and SAF handling. UI similarity must not introduce broad storage access.

## Implementation sequence

### Slice 1 — Shell and theme

- Extract theme/navigation from `MainActivity.kt`.
- Remove the global brand header.
- Create Photos, Albums, Ask, and Menu navigation with proper vector icons.
- Move Index and Privacy behind Settings.
- Apply edge-to-edge, neutral colors, dark theme, and window-size handling.

### Slice 2 — Gallery timeline

- Add chronological grouping and the four-column media grid.
- Add video, favorite, duplicate/burst, and selection overlays.
- Add density change, fast scroll, empty/error states, and thumbnail loading states.

### Slice 3 — Albums, Menu, and Settings

- Add album aggregation and smart albums.
- Implement the rounded menu surface.
- Convert index/model/privacy pages into compact settings sections.

### Slice 4 — Ask and evidence

- Replace the instructional Ask landing page with compact search.
- Reuse gallery grid for progressive candidates and verified results.
- Add compact answer summary, filter chips, and evidence bottom sheet.

### Slice 5 — Viewer and selection

- Add immersive viewer, adjacent-item filmstrip, scoped Ask action, and video timestamp support.
- Add contextual multi-select UI and MediaStore-safe actions.

### Slice 6 — Quality gate

- Validate compact, unfolded, dark, high-contrast, and 1.3x-font layouts.
- Add screenshot tests for each primary surface and compare against the references in this folder.
- Verify TalkBack labels, keyboard focus, 48 dp targets, back behavior, state restoration, and selection semantics.

## Acceptance criteria

- The first Photos viewport is media-first and contains at least 16 visible thumbnails at default compact density when data is available.
- No explanatory paragraph appears on Photos, Albums, or the normal Ask results view.
- Persistent navigation has four destinations and no developer-only destinations.
- Results, viewer, settings, and evidence use proper nested navigation and Back behavior.
- Model versions, indexing internals, and provider diagnostics appear only under Settings/Developer diagnostics.
- Exactness and evidence remain available for every factual answer without being repeated on every media tile.
- Light, dark, 1.3x font, compact, and unfolded layouts pass screenshot review.
- TalkBack labels and minimum touch targets pass accessibility tests.
- No Samsung proprietary font, icon, animation, APK, or extracted binary asset is shipped.
