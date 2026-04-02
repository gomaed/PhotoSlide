# Changelog

## [1.1.2] — 2026-04-02

### Fixed
- Animations (loading spinner and crossfade) no longer stop on Xiaomi/MIUI devices — replaced `ValueAnimator` (Choreographer-dependent) with `Handler.postDelayed` on the render thread, which is immune to system-level vsync throttling
- Double-tap fade duration restored to fixed 200 ms regardless of the Fading Duration setting (interval advances still use the configured duration)
- Picture selection is now stable across screen rotations — each orientation remembers its own selection including all advances made via double-tap or interval
- Rotating the screen no longer resets the advance sequence to cell 0; the next cell to advance continues from where it left off
- Rotation no longer causes a brief flicker of placeholder colours — old bitmaps are kept alive until new ones are decoded and swapped in atomically

## [1.1.1] — 2026-04-02

### Fixed
- Grid no longer appears scrambled after screen rotation — `onSurfaceRedrawNeeded` now ensures the frame is redrawn once the surface is stable (affected tablets and some launchers)
- Photos with EXIF rotation tags now display in the correct orientation on all devices — switched from `BitmapFactory` to `ImageDecoder` which auto-applies EXIF orientation

## [1.1] — 2026-04-01

### Added
- **Fading Duration setting** — configure crossfade duration from Off (instant swap) to 1000 ms in 100 ms steps, merged into the Slide Interval card
- **Corner Radius setting** — round photo corners with fine-grained steps (2 px up to 16 px, then 4 px up to 32 px, then 8 px up to 128 px)
- **Grid Colour setting** — choose the background colour shown between photos (default: black)
- **Rescan button** — manually trigger a folder rescan from the Folders tab via a dedicated FAB, independent of whether the wallpaper is active
- **Placeholder grid** — four Material You tonal shades displayed when no images are loaded (also shown during scan)
- **Loading pill** — compact pill-style loading indicator (spinner + "Loading…" text) replaces the large spinner on the wallpaper
- **"No Pictures Selected" pill** — bold text with pill-shaped background and drop shadow; tapping it navigates directly to the Folders settings tab
- **Scan indicator in header** — small spinner and "Scanning…" label in the top-right of the settings header, visible on every tab during a scan
- **Material You header** — app bar and status bar area use `colorPrimaryContainer` from your device's dynamic colour palette
- **Double-tap advance** now enabled by default

### Changed
- Default grid spacing: 3 px
- Default corner radius: 8 px
- Default sort order: Random
- Default stagger ratio: 55/45
- FAB buttons in Folders tab are now Extended FABs with labels ("Rescan" / "Add New"), vertically aligned
- All UI labels use title-case capitalisation
- Folder item card margins match Settings card margins for visual consistency
- Settings header title ("PhotoSlide Settings") is now bold

### Fixed
- Folder scans now work even when the wallpaper is not active — `FolderSelectFragment` triggers scans directly via the shared `ImageScanner`
- Removed silent background rescan on wallpaper start that caused erratic image swapping after reboot
- Bottom navigation no longer breaks after tapping the "No Pictures Selected" pill (back stack issue fixed)

## [1.0] — 2026-03-26

### Added
- Initial release
- Photo grid live wallpaper with portrait and landscape layout support
- Folder selection with persistent URI permissions
- Stagger effect for alternating column offset
- Slide interval setting (5 s – 24 h, or Never)
- Double-tap to advance a photo immediately
- Sort by name, date, or random
- Grid spacing slider
- Set Wallpaper button in Settings
