# Inkwell: The Private Transcriber

Inkwell is my own private, fully offline dictation tool for taking personal
notes. I speak, it records, and it turns the recording into text on the phone itself, using
OpenAI's Whisper speech model through [whisper.cpp](https://github.com/ggml-org/whisper.cpp).
Nothing I say ever leaves the phone unless I choose to share or export it.

## Features

- **On-device transcription** with whisper.cpp and GGML Whisper models. Whisper Tiny is the
  default; Base and Small (quantised) give better accuracy, and any other GGML model can be imported.
- **Fully offline.** Recording and transcription never touch the network.
- **No recording time limit.** Audio is 16 kHz mono 16-bit PCM streamed straight to a WAV file on
  disk, so a two-hour session should uses no more memory than a two-minute one.
- **Records in the background.** Recording runs in a foreground service with an ongoing
  notification (Pause/Resume and Stop buttons), so it keeps going with the screen off or while
  using other apps. Pause/resume, elapsed time and a level meter in the app.
- **Crash-safe.** The WAV header is kept up to date while recording. If the app or phone dies
  mid-recording, the audio is repaired at the next launch, marked "Recovered", and can be
  transcribed as normal.
- **Low-storage warning** when free space drops under 200 MB, before and during recording. If
  storage really runs out, recording stops *and saves*, and a notification says why.
- **Long audio transcription.** The recording is read from disk in ~30-second chunks, each ending
  at the quietest moment so words aren't cut, and long pauses are skipped. The end of the text so
  far is given to Whisper as context so sentences join up across chunks. Text appears as each
  chunk finishes, and an interrupted transcription resumes where it stopped. Transcription runs
  in a foreground service with a wake lock and shows progress in the app and the notification.
- **Automatic punctuation and capitalisation** as Whisper produces them. Optional removal of
  `[BLANK_AUDIO]`, `[MUSIC]`, `(silence)` and similar tags.
- **Transcribe when I choose.** Tapping Stop just saves the recording. I tap **Transcribe** on it,
  in the list or in the note, when I want the text. The note shows which model will be used, with
  a button to change it first.
- **Says when the model couldn't cope.** If nothing usable comes out, or the speech doesn't seem to
  be in a language the model understands, the note says so and suggests choosing another model.
  For example, English-only models can't transcribe other languages. In that case the recording
  is always kept, even if audio is set to be deleted after transcription.
- **Library**: every note shows title, date, duration and the model used. Open a note to read,
  edit or rename it, re-transcribe it, or cancel/resume a transcription.
- **Deleting**, from the note screen or by long-pressing a note in the list: delete the recording
  only (keep the text), the transcript only (keep the audio, which can be transcribed again), or
  both. Select several notes to delete them together. Every delete asks for confirmation and says
  exactly what will be removed. Deleting is blocked while a recording or transcription is running.
- **Keep or delete audio after transcription** (setting; default keep).
- **Getting text out**: share as plain text or as a `.txt` attachment (email, Drive, Quick Share,
  Telegram, …), export a note as `.txt` or `.md` to any folder, **Export all** as one `.zip`,
  or copy to the clipboard.
- **Windows-safe file names.** A recording and its transcript share the timestamp of when
  recording started, in 24-hour local time: `11-11-2026-14-30-05.wav` and
  `11-11-2026-14-30-05.txt` (hyphens, never colons). The app shows it as `11-11-2026 14:30:05`.
  Exported and shared files use the same name. Two recordings in the same second get `-2`, `-3`, ….
- **Model manager**: download models with progress, cancel and resume; every download is checked
  against its published size and SHA-256 before use. Import my own GGML model, choose the active
  model, delete models, and see the storage each uses. Choose auto-detect or a fixed language for
  multilingual models.
- Material 3 UI that follows the system dark mode.

## Privacy

- Audio and transcripts are stored only in the app's private storage on the phone.
- Recording and transcription work with no network connection at all.
- The **only** network use is downloading a model from
  `huggingface.co/ggerganov/whisper.cpp`, and only when I tap **Download**. That's the only reason
  the app has the `INTERNET` permission.
- No analytics, crash reporting, ads, or third-party SDKs that send data anywhere.
- App data is excluded from Android cloud backup and device-to-device transfer.

Permissions: microphone (recording), notifications (recording/transcription progress),
foreground service for microphone and data sync (keep working in the background), wake lock
(keep the CPU awake during long transcriptions), and internet (model downloads only).

## Models

All models come from [ggerganov/whisper.cpp on Hugging Face](https://huggingface.co/ggerganov/whisper.cpp).
English-only (`.en`) models are a little more accurate for English, but they can't transcribe
anything else: other languages come out as nothing, as made-up English, or as the same sentence
over and over. Multilingual models handle about 100 languages and follow the Language setting
(auto-detect or fixed). If I speak anything other than English, even mixed with English, I use a
multilingual model.

| Model | File | Download | Languages | Guidance |
|---|---|---|---|---|
| Tiny (default) | `ggml-tiny.en.bin` | 78 MB | English-only | Fastest. Good for quick notes. |
| Tiny | `ggml-tiny.bin` | 78 MB | Multilingual | Fastest. |
| Base | `ggml-base.en.bin` | 148 MB | English-only | A good balance of speed and accuracy. |
| Base | `ggml-base.bin` | 148 MB | Multilingual | A good balance of speed and accuracy. |
| Small q5_1 | `ggml-small.en-q5_1.bin` | 190 MB | English-only | Best accuracy, slower. |
| Small q5_1 | `ggml-small-q5_1.bin` | 190 MB | Multilingual | Best accuracy, slower. |

Rough speed guide (it varies a lot between phones): **Tiny** is comfortably faster than real
time on a recent phone. **Base** takes roughly twice as long as Tiny, and **Small q5_1** roughly
three to four times as long as Base, which can be slower than real time on older phones. Try each
on a one-minute recording and pick the most accurate one that's fast enough. Thread count
defaults to the number of performance cores and can be changed in Settings.

### Importing my own model

1. Get a whisper.cpp **GGML** model file (`ggml-*.bin`), for example another file from the
   Hugging Face repo above (such as a quantised medium model), or one converted with the scripts
   in whisper.cpp's `models/` folder.
2. Put it anywhere the phone's file picker can reach (Downloads, Drive, a USB stick, …).
3. In Inkwell: **Models → Import model (.bin)** and pick the file.
4. Inkwell copies it into its own storage, checks that whisper.cpp can actually load it, and then
   adds it under **Your models**. Tap its radio button to make it active.

Bigger models need more RAM and are much slower; medium and large models may not be practical on
a phone.

## Building

### Option 1: download the APK from GitHub Actions (easiest)

Every push builds a release APK.

1. Push to GitHub (or run the workflow by hand: **Actions → Build APK → Run workflow**).
2. Open the finished run under **Actions** and download the artifact
   `Inkwell-<version>-<commit>` at the bottom of the page.
3. Unzip it to get the `.apk`.

Set up the signing secrets (below) first, so every build is signed with the same key and installs
as an update over the previous one.

### Option 2: build locally

Requirements: JDK 17, and the Android SDK with Platform 35, Build-Tools 35.0.0,
NDK `28.2.13676358` and CMake `3.31.6` (Android Studio's SDK Manager can install these, or use
`sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;28.2.13676358" "cmake;3.31.6"`).

```bash
git clone --recursive <this repo's URL> inkwell
cd inkwell
# if you cloned without --recursive:
git submodule update --init --recursive

./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
```

The first build compiles whisper.cpp and takes a few minutes. Native code is always compiled
with `-O3` in Release mode, even for debug APKs, because unoptimised whisper.cpp is about ten
times slower. Only `arm64-v8a` is built; ggml's CPU backend is built in several variants (ARMv8.0
up to ARMv9.2), and the app loads the fastest one the phone supports at runtime.

To build a signed release APK locally, set the same values the CI uses:

```bash
export INKWELL_KEYSTORE_FILE=/path/to/inkwell-release.jks
export INKWELL_KEYSTORE_PASSWORD='…'
export INKWELL_KEY_ALIAS=inkwell
export INKWELL_KEY_PASSWORD='…'
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

Without these, `assembleRelease` signs with the local debug key.

## Signing: create the keystore and add the GitHub secrets

Android only installs an update if it's signed with the same key as the installed app, so create
one release key and keep it safe.

1. Create the keystore (once). Use the same password for the store and the key when asked:

   ```bash
   keytool -genkeypair -v \
     -keystore inkwell-release.jks \
     -alias inkwell \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Base64-encode it:

   ```bash
   # Linux / WSL
   base64 -w 0 inkwell-release.jks > inkwell-release.jks.b64
   # macOS
   base64 -i inkwell-release.jks -o inkwell-release.jks.b64
   # Windows PowerShell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("inkwell-release.jks")) | Set-Content -NoNewline inkwell-release.jks.b64
   ```

3. Add the four repository secrets, either in **GitHub → Settings → Secrets and variables →
   Actions → New repository secret**, or with the GitHub CLI from the repo folder:

   ```bash
   gh secret set KEYSTORE_BASE64 < inkwell-release.jks.b64
   gh secret set KEYSTORE_PASSWORD      # paste the password when prompted
   gh secret set KEY_ALIAS --body inkwell
   gh secret set KEY_PASSWORD           # same password as above
   ```

4. Delete `inkwell-release.jks.b64`, and back up `inkwell-release.jks` and its password somewhere
   safe outside the repo. Keystores are git-ignored and must never be committed.

If the secrets are missing, CI still produces an installable APK signed with a throwaway debug
key and shows a warning. Each such build has a *different* key, so it can't be installed over a
previous one: you'd have to uninstall first, which deletes the notes in the app (export them
first). Losing the release keystore has the same effect, so keep the backup.

## Installing the APK (sideloading)

1. Copy the `.apk` to the phone (download it there directly, or use USB, Drive, Quick Share, …).
2. Open it from the Files app or the browser's downloads.
3. Android asks to allow that app to **install unknown apps**. Allow it (Settings → Apps →
   *the app you opened it from* → Install unknown apps), go back and tap **Install**.
4. Google Play Protect may warn about an unknown developer. Choose **More details → Install
   anyway**.
5. Open Inkwell, go to **Models**, download **Tiny** (78 MB) or import a model, and start recording.
   Allow the microphone and notification permissions when asked. After **Stop**, tap
   **Transcribe** on the recording.

To update, install the new APK over the old one. Notes are kept as long as both builds are
signed with the same key.

## Getting transcripts onto a PC

- **Share → Quick Share / Nearby Share** to a Windows PC with Quick Share installed: open a note,
  tap Share → *Share as .txt file*.
- **Email, Google Drive, Telegram, …**: same Share button, as text or as a `.txt` attachment.
- **Export to a folder**: note menu → *Export as .txt* / *Export as .md*, then pick a folder (for
  example Downloads, an SD card or a USB-OTG stick). Plug the phone into the PC with a USB cable
  (choose *File transfer*) and copy the files from that folder.
- **Everything at once**: Library menu → *Export all as .zip* (choose `.txt` or `.md`), then move
  the zip to the PC by any of the above.
- **Clipboard**: the copy button puts the whole transcript on the clipboard, for pasting into a
  synced notes app or document.

File names are the recording timestamp with hyphens (e.g. `11-11-2026-14-30-05.txt`), which
Windows, FAT32/exFAT USB sticks and SD cards all accept.

## Licenses

- Inkwell itself: MIT License, see [LICENSE](LICENSE). This covers the app's own code; the
  components below keep their own licenses.
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and ggml: MIT License. Included as a git
  submodule at `app/src/main/cpp/whisper.cpp` (pinned to release v1.9.5). The JNI bridge and CPU
  core detection are adapted from its `examples/whisper.android`.
- [Whisper](https://github.com/openai/whisper) model weights: MIT License, OpenAI.
- AndroidX, Jetpack Compose, Kotlin and kotlinx libraries: Apache License 2.0.
