# Samsung Gallery reference set

This folder contains visual reference captures from the connected Samsung Galaxy and before/after captures of Agentic Gallery. It is for design comparison only.

Samsung Gallery package observed on the reference device:

- Package: `com.sec.android.gallery3d`
- Version: `15.8.00.84` (`1580000084`)
- Device: Samsung SM-F731U
- Android: API 36
- Main display used for capture: 1080 x 2640 at 480 dpi
- Capture date: 22 July 2026

Samsung's APK, fonts, icons, animations, and other proprietary binary assets are **not** copied into this repository. The screenshots are retained solely as implementation references. Agentic Gallery uses original vector assets and Android system typography.

## Samsung Gallery captures

| Surface | Screenshot | Accessibility hierarchy | What to inspect |
|---|---|---|---|
| Pictures | [PNG](captures/01-pictures/samsung-gallery-pictures.png) | Not retained | Four-column timeline grid, compact date/place headers, search and overflow, floating bottom navigation |
| Albums onboarding prompt | [PNG](captures/02-albums/02-albums.png) | [XML](captures/02-albums/02-albums.xml) | Optional album-tab customization prompt |
| Albums | [PNG](captures/02-albums-clean/02-albums-clean.png) | [XML](captures/02-albums-clean/02-albums-clean.xml) | Album covers, title/count hierarchy, essential-album grouping |
| Collections | [PNG](captures/03-collections/03-collections.png) | [XML](captures/03-collections/03-collections.xml) | Stories, screenshots, people/pets, and location collections |
| Menu | [PNG](captures/04-menu/04-menu.png) | [XML](captures/04-menu/04-menu.xml) | Rounded bottom sheet and compact action grid |
| Search | [PNG](captures/05-search-clean/05-search-clean.png) | [XML](captures/05-search-clean/05-search-clean.xml) | Search suggestions, category cards, reachable search field |
| Viewer with keyboard state | [PNG](captures/06-viewer-clean/06-viewer-clean.png) | [XML](captures/06-viewer-clean/06-viewer-clean.xml) | Floating viewer chrome and filmstrip |
| Viewer | [PNG](captures/06-viewer-normal/06-viewer-normal.png) | [XML](captures/06-viewer-normal/06-viewer-normal.xml) | Immersive media, top action pills, filmstrip, five bottom actions |
| Multi-select | [PNG](captures/07-selection-clean/07-selection-clean.png) | [XML](captures/07-selection-clean/07-selection-clean.xml) | Selection count, per-thumbnail selection, contextual bottom actions |

## Current Agentic Gallery captures

These are the baseline screens used for the comparison:

| Surface | Screenshot | Accessibility hierarchy |
|---|---|---|
| Gallery | [PNG](current-agentic/gallery-before/gallery-before.png) | [XML](current-agentic/gallery-before/gallery-before.xml) |
| Ask | [PNG](current-agentic/ask-before/ask-before.png) | [XML](current-agentic/ask-before/ask-before.xml) |
| Index manager | [PNG](current-agentic/settings-before/settings-before.png) | [XML](current-agentic/settings-before/settings-before.xml) |

## Agentic Gallery 0.0.4 implementation

These captures were taken from the installed app after the gallery-first redesign:

| Surface | Screenshot | Accessibility hierarchy |
|---|---|---|
| Photos | [PNG](current-agentic/v0.0.4/photos.png) | [XML](current-agentic/v0.0.4/photos.xml) |
| Albums | [PNG](current-agentic/v0.0.4/albums.png) | Not retained |
| Ask | [PNG](current-agentic/v0.0.4/ask.png) | Not retained |
| Menu | [PNG](current-agentic/v0.0.4/menu.png) | Not retained |

## Official references

- [Samsung Gallery feature guide](https://www.samsung.com/us/support/answer/ANS10002535/)
- [Samsung One UI design overview](https://developer.samsung.com/one-ui/index.html)
- [Samsung One UI bottom bar guidance](https://developer.samsung.com/one-ui/comp/bottom-bar.html)
- [Samsung large-screen and foldable guidance](https://developer.samsung.com/one-ui/largescreen-and-foldable/intro.html)
