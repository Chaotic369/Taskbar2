# Quick Launcher (native Android)

Native Kotlin port of the HTML prototype: edge trigger -> slide-to-select popup -> release to launch.

## Open & run
1. Android Studio (Ladybug 2024.2+ / JDK 17) -> *Open* this folder -> let Gradle sync (it fetches Gradle 8.9 from gradle-wrapper.properties).
2. Run on a device (Android 9+/API 28+).
3. In the app: *Overview -> System Permissions*
   - **Accessibility Service** (recommended): carries the overlay itself (no "draw over apps" needed) and enables Lock screen, Recents, Notifications, Screenshot, Power menu, Home, Back.
   - or **Display over other apps**: runs the overlay from a foreground service instead.
   - Modify system settings (brightness slider, auto-rotate), Do Not Disturb access (DND / silent), Phone (Direct Dial).

## Architecture
| File | Role |
|---|---|
| `Renderer.kt` | `PopupRenderer`: draws the whole grid on a Canvas (no per-icon Views). Shared by overlay, preview and tiles. |
| `Views.kt` | `TriggerView`, `LayerView` (full-screen overlay: card, ring, edit handles), `PopupCardView` (scroll / tap / drag-reorder). |
| `Overlay.kt` | `OverlayManager` (windows, gesture, launching, slider panel), accessibility service, foreground service, boot receiver. |
| `Sys.kt` | Live toggle state + all system actions. |
| `Tree.kt` | `Fs` (lists/opens folders for SAF trees *and* raw paths) and `TreeView` (hover-to-expand file tree). |
| `Launcher.kt` | App/call/SMS/email/web/intent/folder/file launching, async icon cache. |
| `Store.kt`, `Models.kt` | State (same JSON as the HTML export), settings controls. |
| `Ui.kt`, `Editor.kt`, `Preview.kt` | Compose settings, shortcut editor, crop dialog, live preview. |

## Notes
* Backups are JSON-compatible with the HTML prototype export.
* **Folder tree:** hover a folder shortcut for ~0.3 s (or tap it once the popup is open) and the popup turns into an indented tree. Hover a folder row to expand it, slide to a file and release to open it. Header: `‹` back to the grid, `↗` open the current folder in your file manager. Touch mode: tap to expand/collapse, drag to scroll.
* **Internal storage:** *Shortcuts → + Internal storage*, or the *Quick add* chips in the folder editor. Raw paths need *Overview → System Permissions → Files & Wallpaper Access* (All-files access on Android 11+). Folders chosen with *Browse* need no extra permission.
* The same permission lets the settings preview show your home wallpaper.
* Folders/files use the Storage Access Framework (no All-Files permission). Raw `/storage/emulated/0/...` paths also work for folders.
* "Direct Shortcut" actions: listed automatically only if this app is the default home app; otherwise use *Choose:* entries (apps' own shortcut pickers).
* Hotspot / Wi-Fi / Bluetooth / Airplane open the system panel/settings (Android doesn't allow third-party apps to toggle them).

## New design (v2 UI)
* Black popup with a red `#b3202b` accent (edit handles, hover, primary buttons) and amber `#ffbf00` for settings (toggles, sliders, active tabs).
* Settings tabs: Overview, Shortcuts, Grid, Icons, Popup, Trigger, Folder, Selection Ring. *Shortcuts* shows the popup grid in edit mode (tap to edit, x to delete, long-press and drag to reorder).
* Long-press an icon in the popup for the Edit / Info / Remove menu.
* **Corner fast slider**: with 2+ folder shortcuts a "Folders" strip appears beside the popup; slide along it and release to jump into a folder tree (Folder tab to toggle).
* **Edit mode trigger handles**: arrow tabs at the trigger ends stretch its length, the side tab changes its width, drag the bar to move it.
* New settings: trigger opacity, hide trigger indicator, hover dwell delay, disable in landscape.
