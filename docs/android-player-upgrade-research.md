# Android player upgrade research

## Scope

This test branch targets Android playback reliability for high-bitrate remuxes, HDR10/HDR10+, and Dolby Vision. The priority is to improve synchronization and give enough runtime evidence to tell a demuxer, decoder, display-capability, or network-buffering problem apart. It does not promise that every Android device can decode every Dolby Vision profile: that depends on the stream profile/layers, Android MediaCodec implementation, device firmware, display path, and the player's bitstream handling.

## Current playback architecture

1. `PlayerSettingsRepository` loads persisted playback preferences, including the Android engine, decoder preference, DV7-to-HEVC mapping, tunneling and custom buffering options.
2. `PlatformPlayerSurface` starts with ExoPlayer in `Auto` mode. If ExoPlayer reports a playback error, the existing Auto path can switch to libmpv. Explicit ExoPlayer or libmpv choices remain user-controlled.
3. `ExoPlayerSurface` constructs the request-header/data-source chain, infers a container/MIME type from stream metadata, response headers and URL, creates extractors and a track selector, and wires the configured renderer factory and load control.
4. When a separate audio URL is supplied, Nuvio creates separate audio and video media sources and merges them. Those sources may expose different start timestamps, so timestamp alignment matters for lip-sync.
5. Player diagnostics log preparation, buffering/ready/error events and first-frame timing. Video track metadata now includes codec MIME, codec string, dimensions, frame rate, bitrate and the Media3 colour metadata fields.

## Changes made on this branch

### Align timestamps for split audio/video sources

The separate-audio path now creates `MergingMediaSource(true, videoSource, audioSource)`, enabling Media3's period timestamp offset adjustment. This addresses the class of sync problems caused by independently timestamped audio and video sources. It does not fix mismatched content edits, clock drift, bad timestamps inside a single file, or decoder/render timing problems. The sources must still have merge-compatible timelines.

### Log video tracks instead of silently filtering them out

The track logger previously calculated a `VIDEO` label and then discarded every video group. It now logs video formats (including every format in an adaptive video group) and their codec and colour metadata. This does not change decoder selection or image processing; it makes actual on-device support and negotiation easier to diagnose.

## Findings from other open-source players

- **CloudStream** uses Media3/ExoPlayer and a fairly mature track/rendering/data-source stack. Its build also declares an FFmpeg decoding extension (NextLib). Useful ideas to study are explicit renderer/decode diagnostics, track selection, and container-specific source handling. An FFmpeg extension is not a blanket guarantee of hardware-quality HDR output, and software decoding of 4K remuxes can be too expensive on phones.
  - Player implementation: https://github.com/recloudstream/cloudstream/blob/master/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt
  - Build dependencies: https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle.kts

- **Stremio Mobile (community Android client)** keeps a player abstraction with selectable ExoPlayer/Media3 and MPV backends. That separation is useful for Nuvio: different devices and formats can require different playback engines without coupling the UI to one player.
  - Repository and architecture: https://github.com/stremio-native/stremio-android

- **Kodi** has explicit HEVC Dolby Vision RPU handling in its bitstream converter behind `HAVE_LIBDOVI` and invokes RPU-processing code. This is materially different from merely identifying a file as Dolby Vision or mapping a MIME type. Any similar support in Nuvio should be implemented as a tested demux/bitstream path or an appropriate maintained native library—not as a UI toggle that claims to convert profiles.
  - Bitstream converter: https://github.com/xbmc/xbmc/blob/master/xbmc/utils/BitstreamConverter.cpp

- **AndroidX Media3** exposes timestamp adjustment for merged media sources and selects codecs based on available platform decoders and format/display capabilities. Its APIs are the right first layer for ordinary A/V synchronization and playback instrumentation, while profile-specific Dolby Vision processing may require additional bitstream support.
  - MergingMediaSource: https://developer.android.com/reference/androidx/media3/exoplayer/source/MergingMediaSource
  - MediaCodecVideoRenderer: https://github.com/androidx/media/blob/androidx-media3-1.9.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java

- **dovi_tool** documents real Profile 7/8 RPU transformations. Such transformations must be designed with the source layers in mind: dropping the Dolby Vision enhancement layer, especially FEL, is not lossless. Profile conversion is not a safe substitute for device capability detection.
  - Repository: https://github.com/quietvoid/dovi_tool

## Important Dolby Vision and HDR constraints

- Dolby Vision Profile 7 commonly uses a base layer plus an enhancement layer and RPU metadata. Whether the full representation can be displayed depends on the device decoder path and stream/container handling. Some devices only use a compatible base layer; others may reject a profile/layer combination.
- Profile 8 is not one universal bitstream arrangement. A compatible Profile 8 stream can still be rendered as HDR10/base-layer video if the player/device does not apply Dolby Vision metadata. Profile labels alone do not prove that dynamic metadata reached the display.
- HDR10/HDR10+ and Dolby Vision are output-pipeline capabilities, not just file extensions. Correct codec parsing, usable colour metadata, decoder support, and an HDR-capable display/output path are all required. Forcing the playback app to claim a profile is supported cannot make unsupported hardware support it.
- The existing DV7-to-HEVC option is a compatibility/decoder mapping option. It should not be described as a lossless Profile 7-to-Profile 8 conversion or full RPU/enhancement-layer processing.
- Tunneling and very large read-ahead buffers are not universally beneficial. Tunneling can depend on device codec/display/audio capabilities; large buffers can increase memory pressure and delay playback start without fixing a slow or unstable source. Keep these as opt-in controls until device testing shows a clear benefit.

## On-device validation matrix

Run these tests on at least one HDR/Dolby Vision-capable device and one Android device without Dolby Vision output support. Record device model, Android version, display capability, player engine, file size/bitrate, stream profile, and the `NuvioPlayerDiag` logs.

| Case | What to verify |
| --- | --- |
| SDR AVC and HEVC, MP4/MKV | Baseline playback, seeking, audio selection, subtitles and resume remain unchanged. |
| HDR10 HEVC remux, high bitrate | HDR output is reported by the device/display path, colours are not washed out, no repeated dropped-frame bursts or rebuffer loops. |
| HDR10+ HEVC remux | Verify actual output on supported hardware; do not infer HDR10+ from filename/metadata alone. |
| Dolby Vision Profile 8 stream | Confirm track codec and colour transfer metadata, then visually verify DV/HDR output on supported hardware and sane base-layer fallback elsewhere. |
| Dolby Vision Profile 7 MEL and FEL MKV remuxes | Separately test whether the stream opens, whether RPU/EL data is retained/used by the playback chain, and whether fallback is correct. Do not combine MEL/FEL results into one claim. |
| Split video URL + audio URL | Use content with a known initial timestamp offset; verify lip-sync at start, after seeks, and after pause/resume. |
| TrueHD / DTS-HD / E-AC-3 audio tracks | Verify audio track visibility and actual device/route support; avoid treating unsupported passthrough as a video-decoder issue. |
| 4K high-bitrate playback and repeated seeking | Compare ExoPlayer versus libmpv, monitor dropped frames/rebuffering and memory pressure, and check for sustained A/V drift. |
| Auto engine fallback | Introduce a stream that reliably causes ExoPlayer to fail; confirm libmpv starts, position handling is sensible, and errors are visible rather than swallowed. |
| Low-memory device | Check playback start and long sessions with conservative buffer settings; ensure there is no OOM or excessive cache growth. |

## Follow-up work before claiming full Dolby Vision support

1. Add a Media3/Android device diagnostics screen or structured log export that records selected/unsupported video track details, decoder initialization/failure, first-frame latency, dropped frames, buffering time and the active backend. Redact signed URLs and request headers.
2. Reproduce Profile 7 remux handling with small licensed test clips, inspect the extractor/decoder path, then choose a maintained libdovi-compatible native integration or a known supported Media3 path. Add tests for RPU preservation, MEL/FEL policy, container timestamps and seek/resume before enabling conversions by default.
3. Improve Auto engine selection using evidence from decoder capability/initialization and track support; avoid routing solely by file-name tokens. Verify the libmpv fallback preserves the intended start position and subtitles where possible.
4. Pin and evaluate Media3/decoder-extension updates independently. This project includes custom prebuilt AARs, so changing a Gradle dependency alone may not update the actual player/decoder implementation.
5. Run the Android unit/host tests, debug APK build and real-device playback matrix. A successful compile is necessary but cannot establish correct HDR tone mapping, Dolby Vision metadata handling, or lip-sync.

## Verification status

The source changes have been committed on `player-upgrade-test`. No Gradle build, CI run, or physical-device playback test was executed in the editing environment, so playback improvements beyond the documented timestamp-alignment behavior remain unverified until the validation matrix is run.
