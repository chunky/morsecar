# MorseCar implementation plan

## Architecture

```
┌──────────────┐   MediaController    ┌──────────────────────────────────────┐
│  Phone UI    │ ───────────────────▶ │ PlaybackService (MediaLibraryService)│
│  (Compose)   │                      │  ├─ MorsePlayer (SimpleBasePlayer)    │
└──────────────┘                      │  │   └─ MorseAudioEngine (AudioTrack) │
┌──────────────┐   MediaBrowser       │  ├─ Library (SAF folders → books)     │
│ Android Auto │ ───────────────────▶ │  └─ browse tree, custom commands     │
└──────────────┘                      └──────────────────────────────────────┘
        both read/write ──▶ DataStore (timing settings, folders, per-book positions)
```

Modules:

- **`:morse`**: plain Kotlin/JVM, with no Android dependencies. It holds the Morse
  alphabet, text normalization, timing math, the text → element timeline, and PCM
  synthesis. Almost all of the logic that's easy to get subtly wrong lives here
  and is unit-tested on the JVM.
- **`:app`**: the Android side: audio output, the media session, the library,
  persistence, and the UI.

### Key design decisions

1. **Positions are character offsets, not times.** Changing a speed slider changes
   how long everything takes, so a saved time would point to the wrong place.
   Each book's saved position is an offset into its raw text. "Time" (for the
   progress bar, for seeking, and for ±15/30s) is computed from the current
   timing settings. Recomputing durations over a whole book is a cheap O(n) pass
   over per-character durations, so it can rerun whenever a slider moves.

2. **Audio is synthesized live and streamed to an `AudioTrack`.** No files are
   pre-rendered. A worker thread pulls elements from the timeline, renders a
   sine tone at the user-chosen frequency with 5 ms raised-cosine edges so it
   doesn't click, and writes it in small chunks. Slider changes take effect at
   the next element, and the buffer is kept short (~100–200 ms) so they feel
   immediate.

3. **The player is a Media3 `SimpleBasePlayer` wrapping that engine.** This gets
   us the media notification, lock-screen controls, Bluetooth and steering-wheel
   buttons, and Android Auto for free. `SimpleBasePlayer` doesn't handle audio
   focus (ExoPlayer does), so we do it ourselves. **Pause, rather than duck, on
   any transient focus loss:** Morse is hard to copy at reduced volume under a
   navigation prompt.

4. **Settings flow through DataStore, not session commands.** The UI writes the
   slider values (three speeds plus tone frequency) and the service observes them. Android Auto can't show sliders
   anyway, so Auto changes speed through preset custom actions that write the
   same store.

5. **The highlight follows what's audible, not what's been rendered.** The engine
   records `(sampleIndex → charOffset)` markers as it writes audio. The current
   position comes from `AudioTrack.getPlaybackHeadPosition()` mapped through
   those markers, so the highlighted word lines up with the sound instead of
   running ~200 ms ahead.

### Timing math (for reference)

- One unit at W WPM = `1200 / W` ms (PARIS = 50 units).
- Dit = 1, dah = 3, intra-character gap = 1, all in **character** units.
- Gap between letters = 3 **letter-spacing** units; gap between words = 7
  **word-spacing** units; gap between paragraphs = 10 **word-spacing** units
  (a slightly longer break than a word gap; one named constant, easy to tune).
- Farnsworth preset (character speed *c*, effective speed *s*), using the ARRL
  formula: `t_a = (60c − 37.2s) / (s·c)` seconds of spacing per word, so the
  spacing unit is `t_a / 19`, equivalent to `1.2 / (t_a/19)` WPM on both
  spacing sliders. For c=20, s=10 that's about 5.5 WPM spacing. Test: PARIS at
  20/10 comes out to exactly 6.0 s.

### Presets (initial list)

| Button            | Character | Letter spacing | Word spacing |
|-------------------|-----------|----------------|--------------|
| Standard 20       | 20        | 20             | 20           |
| Standard 15       | 15        | 15             | 15           |
| Farnsworth 20/10  | 20        | ≈5.5           | ≈5.5         |
| Farnsworth 18/12  | 18        | computed       | computed     |

These are just a starting set, easy to change later. Presets change only the
three speeds, not the tone frequency.

### Tone frequency

A fourth slider, 400–1000 Hz, defaulting to 600 Hz. A new frequency applies
from the next tone, so pitch never changes in the middle of a dit or dah.

## Milestones (in order)

Each milestone ends with something that runs. The order is chosen so the riskiest
integration, Android Auto, gets an early smoke test, and so the phone UI doesn't
get rewritten when the service architecture arrives.

### M1: Morse core (`:morse`, test-driven) ✅
- Alphabet: A–Z, 0–9, and common punctuation, loaded from the data file
  `morse/src/main/resources/.../alphabet.json` (not hardcoded). Prosigns are
  deferred; nothing in plain-text books needs them yet.
- `TextNormalizer`: uppercase; map curly quotes, dashes, ellipses, etc. to
  encodable characters; silently drop anything still unencodable; collapse
  whitespace. One or more blank lines become a paragraph break. A single
  newline counts as a space, because plain-text books (e.g. Project Gutenberg)
  hard-wrap lines. Keep a mapping back to raw-text offsets.
- `MorseTiming` + Farnsworth helper + presets (above).
- `MorseTimeline`: text + timing → element stream (including paragraph gaps); char offset ↔ time;
  "advance/rewind N seconds from offset X". Stores speed-independent unit
  counts at checkpoints every 256 chars, so slider changes are free and whole
  books stay small in memory.
- **Done when:** unit tests cover PARIS timings, Farnsworth, normalization
  edge cases, and seek math.

### M2: Tone synthesis (`:morse`) ✅
- PCM renderer: elements → 16-bit mono samples (frequency is a parameter
  per render call, plus sample rate and envelope).
- A small JVM-side dev tool that writes a `.wav`, so you can listen to the
  output without a phone: `./gradlew :morse:renderWav --args="--help"`.
- **Done when:** sample counts match the timeline exactly, and the WAV sounds clean.

Status: M1–M2 are done and tested on the computer. M3–M9 are **written and
build cleanly (lint-clean, release build OK) but haven't run on a device yet**.
The "Done when" checks for those still need a phone; see *Device testing
checklist* below.

### M3: Audio engine on device ✅ (code), ⏳ (device)
- `playback/MorseAudioEngine.kt`: a streaming `AudioTrack` (~150 ms buffer) fed
  by a render thread. Writes are non-blocking, so pause and seek never wait on
  a blocked write. Tracks the audible position (decision 5), pads the end with
  silence so a short final stretch still plays, and rebuilds the track if the
  output device goes away.
- Skipped the temporary debug screen and went straight to the real UI (M5).
- **Done when:** it plays on a phone, sliders change speed mid-stream, and there
  are no clicks or gaps.

### M4: Presets and settings persistence ✅ (code), ⏳ (device)
- `settings/SettingsRepository.kt` (DataStore); sliders snap to 0.5 WPM and
  10 Hz steps; preset chips. Default is Farnsworth 20/10 at 600 Hz.
- **Done when:** settings survive an app restart, and the presets set the sliders.

### M5: Media session and background playback ✅ (code), ⏳ (device)
- `playback/MorsePlayer.kt` (`SimpleBasePlayer`) and `playback/PlaybackService.kt`
  (`MediaLibraryService`).
- Foreground notification, audio focus (pauses on any loss, resumes after
  transient ones), pause on headphone/BT disconnect.
- Buttons (`playback/PlaybackCommands.kt`): ±15 s beside play/pause, ±30 s as
  secondary buttons, and a "next speed preset" button in the overflow.
  Previous/next (steering wheel) skip ∓30 s.
- The phone UI uses a `MediaController`, like Auto does.
- The last book is reloaded (paused) when the service starts, and Bluetooth or
  Auto "play" can resume it even when MorseCar isn't running.
- **Done when:** playback continues with the screen off and app backgrounded,
  and the DHU smoke test passes.

### M6: Library and resume ✅ (code), ⏳ (device)
- Folder picker (`ACTION_OPEN_DOCUMENT_TREE`, persisted permission), multiple
  folders, subfolders.
- Uses `DocumentsContract` queries directly instead of `DocumentFile`, which
  does one query per file attribute and is slow on big folders.
- `text/BookText.kt`: UTF-8/UTF-16 byte-order marks, UTF-8, falling back to
  Windows-1252; rejects binary files and files over 16 MB.
- Per-book position (raw offset) saved on pause, seek, stop, book change, and
  every 10 s while playing; resume snaps to the start of the word.
- **Done when:** you can pick a real book, kill the app, reopen, and resume at
  the same spot.

### M7: Scrolling text with highlighted word (phone only) ✅ (code), ⏳ (device)
- The service shares the book text and audible position in-process
  (`playback/NowPlaying.kt`) instead of through the session: a whole book is too
  big for Binder, and the position changes ~10×/s.
- `ui/BookTextView.kt`: paragraphs (capped at 600 chars) in a `LazyColumn`; the
  current word's line is kept a third of the way down. Scrolling by hand pauses
  following until you tap "Follow along".
- **Done when:** the highlight stays in sync at slow and fast speeds and across
  skips and slider changes.

### M8: Full Android Auto experience ✅ (code), ⏳ (device)
- Browse tree: *Recent* and *Folders* tabs → folders → books, with "N% done".
- Speed presets via the overflow button (cycles through them; the current one
  is shown on the button and under the title).
- Voice: "play <title> on MorseCar" searches recent books, then the folders;
  "play MorseCar" resumes. Also supports Auto's search screen.
- **Done when:** a full commute workflow (pick book, resume, skip, change
  preset) works in the DHU and in a real car.

### M9: Hardening ✅ (code), ⏳ (device)
- Clear errors for missing files, revoked folder access, binary/empty/huge
  files. Positions are saved from a scope that outlives the service.
- Release build: R8 shrinking (1.8 MB APK), signed with your own key via
  `keystore.properties` (see README).
- Still to verify on a device: process death mid-playback, phone calls, BT
  reconnect, and very large books.

## Device testing checklist

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

**Phone**
1. Add a folder, open a book: Morse plays and the highlighted word follows.
2. Drag each slider while playing: the change is heard within a character or
   two, with no clicks or dropouts. Try each preset chip.
3. ±15/±30 buttons, and dragging the position bar.
4. Screen off and app in background: playback continues; notification and
   lock-screen controls work, including the skip buttons.
5. Unplug headphones / disconnect Bluetooth: playback pauses.
6. A navigation prompt or phone call pauses playback, and it resumes afterwards.
7. Kill the app (swipe away), reopen: same book, same spot (within ~10 s).
8. Scroll the text by hand, then tap "Follow along".
9. A big book (several MB), a non-text file renamed `.txt`, an empty file.

**Android Auto (DHU, then a real car)**
10. MorseCar is listed; Recent and Folders tabs browse correctly.
11. Play from the browse tree; ±15 (main), ±30 and speed preset (secondary or
    overflow) buttons appear and work.
12. Steering-wheel next/previous skip ±30 s. *Unverified:* whether Auto also
    shows its own previous/next buttons next to ours.
13. "Hey Google, play <book title> on MorseCar".
14. Start the car with MorseCar not running and press play: the last book resumes.

## Decisions made

- **Package name / applicationId:** `org.icculus.chunky.morsecar`.
- **Unencodable characters:** silently dropped (for now).
- **Paragraph breaks:** a slightly longer gap than a word gap (see timing math).
- **Tone frequency:** user-set with a slider.
- **Sample books:** every `.txt` in the top-level `samples/` folder ships in
  the APK and appears as a "Samples" folder (phone and Auto).
- **Project Gutenberg books:** a new (or finished) book starts after the
  `*** START OF ...` line, skipping the license header, and the title comes from
  its `Title:` line. The text itself isn't changed.
- **Distribution:** self-installed (sideloaded) for now, with no Play Store.
  Sideloaded apps only show up in Android Auto (both the DHU and a real car)
  after you enable Android Auto's developer mode (tap *Version* ten times in
  its settings) and then turn on *Unknown sources* in its developer settings.

## Tooling notes

- Build: `./gradlew :app:assembleDebug`; tests: `./gradlew :morse:test :app:testDebugUnitTest`;
  lint: `./gradlew :app:lintDebug`; release: `./gradlew :app:assembleRelease`.
- Listen to the engine on the desktop:
  `./gradlew :morse:renderWav --args="--farnsworth 20/10 --tone 700 --out /tmp/x.wav CQ CQ DE K1ABC"`
  (or `--in book.txt`).
- The DHU ships in the SDK package `extras;google;auto`. Installing it needs the
  modern `cmdline-tools` sdkmanager (the legacy `tools/bin/sdkmanager` in this
  SDK doesn't run on Java 21), or Android Studio's SDK Manager.
