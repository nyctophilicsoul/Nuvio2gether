# Nuvio Aspect Ratio Engine

## Goals and invariants
1. Correct source geometry before applying a screen-fit policy.
2. Never silently stretch content. Full is the only mode that intentionally uses non-uniform scaling.
3. Keep resize-mode state in common Kotlin and map it to each engine in one adapter.
4. Keep controls, Compose OSD, and subtitle safe-area placement independent of the video rectangle.
5. Do not automatically crop embedded black bars: distinguishing encoded bars from intentional dark scenes requires content analysis and user confirmation.

## Geometry model
Let coded dimensions be Wc,Hc and sample aspect ratio be SAR = pixel width / pixel height. Display aspect ratio is DAR = (Wc / Hc) * SAR.
For corrected source dimensions Ws = Wc * SAR, Hs = Hc, and viewport Wv,Hv:
- sFit = min(Wv / Ws, Hv / Hs)
- sZoom = max(Wv / Ws, Hv / Hs)
- Rendered size is Wr = s * Ws, Hr = s * Hs
- Center offsets are x = (Wv - Wr) / 2, y = (Hv - Hr) / 2. Negative offsets are crop.
Best Fit uses corrected DAR and sFit. Fit uses coded square-pixel geometry and sFit. Full uses independent scales sx = Wv / Ws and sy = Hv / Hs. Zoom uses corrected DAR and sZoom. 16:9 replaces source DAR with 16/9 before fitting.

## Five modes
| Player / mode family | Modes / controls | DAR/SAR correction | Crop behavior | Subtitle behavior | Cost / overhead |\n|---|---|---|---|---|---|\n| VLC / LibVLC | Auto fit, scale factor, aspect override, crop ratio/window | Decoder/video-output metadata plus explicit aspect override | Separate crop ratio/window; crop and aspect override are not interchangeable | Video/subtitle output is engine-managed; crop may alter subtitle positioning depending on output/filter path | Usually video-output scaling; behavior depends on platform vout and hardware path |\n| Media3 / ExoPlayer | FIT, FIXED_WIDTH, FIXED_HEIGHT, FILL, ZOOM | VideoSize.pixelWidthHeightRatio / Format pixel ratio informs DAR | FIT letterboxes; ZOOM expands measured content frame; FILL stretches | SubtitleView is attached to PlayerView/content frame | SurfaceView path is efficient; TextureView/matrix transforms may require GPU composition |\n| mpv / libmpv | aspect override, unscaled, panscan, zoom, align | container/bitstream/ignore selection and explicit override | panscan / zoom / crop options | mpv OSD/libass generally follows mpv video output transform | GPU video output; scaler and hwdec choice affect performance |\n\n| Nuvio mode | Geometry source | Scale policy | Crop | Distortion |
|---|---|---|---|---|
| Best Fit (default) | Bitstream/container DAR and SAR | Uniform min scale | No | No |
| Fit | Coded width/height, square-pixel interpretation | Uniform min scale | No | No |
| Full | Source rectangle | Independent X/Y scale | No | Yes, intentional |
| Zoom | Corrected DAR | Uniform max scale | Center crop | No |
| 16:9 | Forced 16:9 display canvas | Uniform min scale | No | No |

There is no universally safe crop threshold. Crop fraction can be reported as a diagnostic, but must not block Zoom. For automated recommendations, a warning above 15% cropped source area can be explored as a UX heuristic, not a standards-derived or community-consensus limit.

## VLC / LibVLC notes\n\nVLC exposes distinct aspect-ratio override, scale, and crop controls. `libvlc_video_set_aspect_ratio()` changes the requested display aspect ratio; `libvlc_video_set_scale()` sets a scale factor (zero requests automatic fitting); LibVLC 4 adds crop-ratio/window APIs. Desktop UI presets such as 4:3, 16:9 and 2.35:1 are not all the same operation: selecting a display aspect ratio can reshape the image, while selecting Crop removes pixels to fit the chosen crop window. VLC's default/automatic fit should preserve the source geometry.\n\n## Engine mapping
### Media3 / ExoPlayer
- Best Fit: RESIZE_MODE_FIT; use Media3 corrected VideoSize.pixelWidthHeightRatio / Format.pixelWidthHeightRatio.
- Fit: RESIZE_MODE_FIT with coded width/height ratio supplied to the content frame, deliberately bypassing SAR correction.
- Full: RESIZE_MODE_FILL (non-uniform fill).
- Zoom: RESIZE_MODE_ZOOM.
- 16:9: content frame target ratio 16/9 and FIT. Re-apply after video-size callbacks because PlayerView may update its content frame when tracks change.
Prefer changing the existing content frame's measure ratio; do not replace the decoder surface or recreate ExoPlayer. TextureView transforms are a fallback only: they can increase GPU composition cost and risk diverging subtitle/overlay geometry. SubtitleView is a child of PlayerView's content frame, so validate bounds for Zoom and forced ratio.

### mpv / libmpv
Apply properties after file load and whenever the mode changes. Reset every property that the previous mode may have changed.
- Best Fit: keepaspect=yes, video-aspect-method=container, video-aspect-override=no, video-unscaled=no, video-zoom=0, panscan=0.
- Fit: keepaspect=yes, video-aspect-method=ignore, video-aspect-override=no, video-unscaled=downscale-big, video-zoom=0, panscan=0.
- Full: keepaspect=no, video-unscaled=no, panscan=0.
- Zoom: keepaspect=yes, video-aspect-method=container, video-aspect-override=no, video-unscaled=no, video-zoom=0, panscan=1.
- 16:9: keepaspect=yes, video-aspect-override=16:9, video-unscaled=no, video-zoom=0, panscan=0.
libmpv C API uses mpv_set_property_string / mpv_set_property and mpv_command with the same property names. mpv video-unscaled disables panscan, so it must only be enabled for Fit. panscan is crop-to-fill, not a generic zoom multiplier.

## Screen adaptation
| Viewport | Best Fit | Fit | Full | Zoom | 16:9 |
|---|---|---|---|---|---|
| 19.5:9 / 20:9 phone | Preserve DAR; safe-area aware | Coded geometry fit | Stretch to usable viewport | Crop sides or top/bottom | 16:9 canvas centered |
| 4:3 / 3:2 / 16:10 tablet | Preserve DAR | Preserve coded ratio | Stretch | Crop excess | 16:9 canvas |
| 16:9 TV (720p/1080p/4K) | Usually fills for 16:9 content | Same unless anamorphic | Fills | Minimal/no crop for 16:9 | Fills |
| 21:9 monitor | Preserve cinematic DAR | Coded ratio | Stretch wide | Crop top/bottom for 16:9 | 16:9 canvas with side bars |
| 32:9 monitor | Side bars for ordinary content | Coded ratio | Extreme horizontal stretch | Severe vertical crop for 16:9 | 16:9 canvas centered |
The viewport is the actual player content bounds, not physical display dimensions. On phones it excludes cutouts/system bars only when the player does not intentionally draw behind them. Foldables must recompute on hinge/posture and window-size changes.

## Subtitle and OSD isolation
- Compose controls and OSD remain in a sibling overlay layer above the video surface, laid out in viewport coordinates.
- External text subtitles should use a viewport/safe-area subtitle host; calculate bottom offset from the visible video rectangle only when the subtitle policy explicitly requests inside-picture placement.
- Bitmap and in-band subtitles rendered by the decoder may be tied to the video surface. Do not promise viewport pinning unless the engine exposes a separate subtitle renderer.
- Android Media3 SubtitleView is managed by PlayerView; test FIT, ZOOM, Full and 16:9 with WebVTT, SRT/SSA sidecars, and bitmap PGS/VobSub. Libass overlay geometry follows the visible video rect for picture-relative subtitles and the full viewport for safe-area subtitles.
- iOS mpv/libass subtitles use mpv's OSD path and can follow mpv's video transform. For strict isolation, route external text cues to a separate native/Compose overlay; keep styled ASS rendering in mpv when fidelity is more important than viewport pinning.
- Changing modes must not recreate player, surface, decoder, or subtitle track. Apply properties in-place and debounce layout-only recalculation until a valid viewport is available.

## Performance and failure handling
- Prefer SurfaceView/hardware overlays where compatible. TextureView and arbitrary Compose graphics transforms may force GPU composition.
- Avoid surface recreation for mode changes; update measurement or mpv properties in-place.
- Recompute on video-size, pixel-ratio, rotation, viewport, safe-area, and fold-posture changes.
- Clamp invalid SAR to 1.0 and reject non-positive dimensions. Unknown aspect metadata falls back to coded dimensions.
- Do not automatically crop low-resolution 4:3 content. Best Fit preserves it; Zoom remains user choice.
- 2.35:1 bars encoded into a 16:9 raster are not metadata. A 16:9 override cannot remove them; use explicit crop or a future black-bar detector with confidence and user consent.

## Community signal (qualitative, not a statistical survey)
Reddit threads across VLC, mpv, Stremio and ultrawide communities show recurring complaints about accidental stretching, wrong DAR/SAR interpretation, black bars baked into files, and zoom modes that cut off image content or subtitles. They also show demand for one-touch crop/zoom, especially on ultrawide displays. No defensible crop-percentage consensus emerges; defaults should preserve the whole frame and make crop opt-in.