# Notes for AI coding agents: voice-inbox

An Android app for one user that turns a short spoken note (Russian, later English too)
into a Markdown task line appended to one file of an Obsidian vault:
record → offline recognition with Vosk → edit → Save → `- [ ] text` at the end of the
file. Reliability matters more than speed: no recording or text may be silently lost, and
success is shown only after the write was read back.

Machine-specific notes, where they exist, are in the git-ignored `AGENTS.local.md`. Keep
paths, file names, device details and anything else about one person's setup out of
tracked files: the repository is public.

## Plan

Built in milestones; each one ends with a build, tests and a report, and the next starts
only after the user approves.

1. Done (0.1.0): recording, offline Russian recognition with live text, manual Stop,
   picking the target file, appending with manual Save, explicit success or failure.
2. Done (0.2.0): a stored record per capture, History (open, copy, play, delete),
   retrying failed saves, recovery after a crash or kill, duplicate prevention (see
   "Exactly once"), deleting the audio of saved captures after a set time.
3. Convenience: a launcher entry or shortcut that starts recording at once; automatic
   stop after silence (Vosk's `Recognizer.setEndpointerDelays`, plus the level meter);
   configurable timeout and maximum length; optional automatic saving; English model.
4. Polish: interruptions (calls, audio focus), permission revocation, rapid repeated
   captures, startup and recognition latency.

## Decisions

- Recognition is Vosk (`com.alphacephei:vosk-android`, JNA plus `libvosk.so`) with
  `vosk-model-small-ru-0.22`. Android's `SpeechRecognizer` is not used: it may send audio
  to Google. Vosk's own `SpeechService` is not used either: it owns the `AudioRecord`, so
  the app could neither keep the audio nor measure its level.
- Record and recognize in one pass (`Recorder`): one thread reads the `AudioInput` and
  writes each buffer to `captures/<time>/audio.wav` in app storage, then queues it; a
  second thread loads the model (seconds on the first capture) and feeds the queue to a
  `Recognizer`. So recording starts at once, the audio is on disk from the first buffer
  (the WAV header gets its sizes at the end; until then the file length tells), and the
  final text is ready right after Stop. Vosk returns a finished piece of text whenever it
  detects a pause; `Transcript.join` strings them together.
- The model is downloaded by the app, once, from alphacephei.com (`SpeechModel`): the
  user did not want 50 MB in every APK update. This is the only use of the INTERNET
  permission. The download resumes after an interruption (HTTP Range), is checked
  against a pinned size and SHA-256, unpacked into a temporary folder (refusing paths
  outside it) and renamed into `files/models/<name>`, so a half-unpacked model is never
  used. The loaded `Model` stays in memory for the process; it is loaded right after
  install and when the app starts.
- Appending (`MarkdownAppend`, `TargetFile`): the file is opened `"rw"` through the
  Storage Access Framework, which never truncates, and the line is written with `pwrite`
  at the end offset, then `fsync`ed and read back. Not `"wa"`, which some document
  providers ignore, and not read-modify-write, where a crash between truncating and
  writing loses the whole file. A file descriptor that is not a regular file (cloud
  providers hand out pipes) is refused instead of guessed at. If the file's size changes
  between reading its end and writing, nothing is written.
- The line: whitespace and line breaks folded into single spaces, `- [ ] ` in front, a
  line break before it only if the file does not end with one, and one after it, so no
  blank lines appear. The file's own line break style is kept (CRLF if its last line
  break is CRLF). A file holding only a UTF-8 BOM counts as empty.
- `AppendResult` separates `NotWritten` (safe to retry) from `Unverified` (the write
  started but was not confirmed; retrying blindly may duplicate the line).
- The capture in progress lives in the process-wide `Capture` object (a `StateFlow`), so
  it survives rotation. A recording stops when the app leaves the screen, and its audio
  and text are kept. No background recording: the user does not want it, so there is no
  `microphone` foreground service (Android gives background apps only silence).
- The target file is persisted as a URI with a persistable read/write grant
  (`takePersistableUriPermission`); `TargetFile.hasAccess` checks the grant on every
  resume.
- Storage is plain files, no Room: SharedPreferences for settings; per capture a folder
  `captures/<id>/` (the id is the local start time, so folders sort by time) with
  `audio.wav` and `capture.json` (`CaptureRecord`, `CaptureStore`). A record is written to
  a temporary file, flushed and moved over the old one atomically, before the app acts on
  it. Statuses: RECORDING, TRANSCRIBING, READY (not saved), SAVING, SAVED, FAILED.
- Recovery (`Capture.init`, once per process, skipping captures started by this
  process): RECORDING or TRANSCRIBING, and folders without a readable record, get their
  WAV header repaired from the file length (`Wav.repair`) and their audio recognized
  again (`Transcriber.transcribe`), then READY with a note; SAVING gets its file checked
  (below). Captures that are not saved are never deleted automatically.
- History lists every capture, newest first: Open (into the editor, for READY and
  FAILED), Copy, Play (`AudioPlayer`, one at a time), Delete (with a confirmation). The
  capture screen shows "N captures not saved" when there are any.
- In the editor, "Record again" and "Later" keep the capture in History as not saved;
  only Delete, after a confirmation, removes it. Edited text is stored when the app leaves
  the screen, when another capture starts and before saving.
- Settings: the audio of saved captures is deleted 1, 7 (default) or 30 days after
  saving, or kept; the record stays. Checked at every start of the process and when the
  setting changes.
- Debug builds take `files/test-input.wav` instead of the microphone while that file
  exists, played in real time (`WavFileInput`), because the emulator's microphone records
  silence. Release builds ignore it.
- APK size: Vosk's native library is 10 MB per ABI, so release builds carry only
  `arm64-v8a`; debug builds add `x86_64` for the emulator.
- English only, strings in code.

## Exactly once

No hidden markers go into the vault. Instead (`TaskSaver`, `MarkdownAppend.check`):

1. Right before the bytes are written, the record is stored as SAVING with a
   `PendingWrite`: the file, its size then (the offset) and the exact characters. If that
   cannot be stored, nothing is written.
2. Written, flushed and read back: SAVED. Failed before writing: FAILED without a pending
   write, safe to retry. Written but not confirmed (`Unverified`): FAILED with the
   pending write kept.
3. A SAVING record found at start-up (the app died during the write) and any retry of a
   record with a pending write first check the file:
   - its size equals the offset: the file is as it was before, the line is not there;
     safe to write (this also keeps an earlier capture with the same text from being
     mistaken for this one);
   - the bytes are at the offset: SAVED, nothing written;
   - otherwise, if the same line is anywhere in the file, the user decides ("Save
     anyway" or "It is in the file: mark as saved"); if it is nowhere, safe to write;
   - the file cannot be read: the user decides too.

Remaining limits: if exactly as many bytes as the line were removed between the write and
the check (for example the user deleted that very line in Obsidian), the file has its old
size again, reads as "not there", and the line is written again. A file over 32 MB is not searched; the
user decides. If Obsidian later overwrites the file with an older copy, the line is lost
there, but the capture stays SAVED in History with its text.

## Layout

- `app/src/main/java/t7helpers/voiceinbox/`: `Capture` (the capture on screen, saving,
  recovery), `Captures` (`CaptureRecord`, `CaptureStore`), `Recorder`, `Transcriber` and
  `Transcript` (recording and recognition threads), `AudioInput` (`MicInput`,
  `WavFileInput`), `Wav` (header, writer, repair, level), `SpeechModel` and `ModelZip`
  (download, unpack, load), `Markdown` (task line, append algorithm, `TaskSaver`),
  `TargetFile` (SAF access, pread/pwrite), `Settings`.
- `.../ui/`: Compose screens `CaptureScreen`, `HistoryScreen` and `SettingsScreen`,
  shared cards in `Components`, `AudioPlayer`; navigation is a plain enum in
  `MainActivity`, which also holds the file picker and the microphone permission request.
- `app/src/test/`: JVM unit tests against real files (`RafAccess`): the append algorithm
  (empty file, with and without a trailing line break, CRLF, BOM, repeated captures,
  failing and non-sticking writes, a file growing meanwhile), `TaskSaver` (what is stored
  before writing, retries after confirmed and failed unconfirmed writes, a same-text
  earlier capture, moved and removed lines), the record format and store, WAV writing,
  reading and repair, the transcript, and model archive unpacking.

## Build and releases

Versions are in `gradle/libs.versions.toml`. The scripts are for Windows PowerShell.

- `scripts/build.ps1` runs the unit tests and writes `dist/voice-inbox-<version>.apk`, a
  release build signed with the key named in the git-ignored `local.properties`
  (`release.storeFile`, `release.storePasswordFile`, `release.keyAlias`); without it the
  script stops. `gradlew.bat assembleDebug` is enough for the emulator.
- `scripts/release.ps1` tags `v<versionName>`, pushes, and publishes the APK as a GitHub
  release at `t7ko/android-voice-inbox` for Obtainium, with the token file named by
  `github.tokenFile` in `local.properties`. Never print a token or password, or put one
  on a command line.
- Debug and release builds are signed with different keys: switching between them on a
  device needs `adb uninstall t7helpers.voiceinbox`, which deletes the downloaded model
  and the captures.

## Testing on the emulator

- `scripts/emulator-ui.py` lists texts on screen and taps them (`texts`, `tap TEXT`,
  `tapsub TEXT`, `shot NAME`). Right after starting the app, its first dump can be stale;
  run `texts` once more.
- Speech: make a 16 kHz mono 16-bit WAV of Russian speech, for example from a Spoken
  Wikipedia recording on Wikimedia Commons:
  `ffmpeg -i in.ogg -t 12 -ac 1 -ar 16000 -c:a pcm_s16le ru-12s.wav`. Then
  `adb push ru-12s.wav /data/local/tmp/` and
  `adb shell "run-as t7helpers.voiceinbox cp /data/local/tmp/ru-12s.wav files/test-input.wav"`.
  Remove it (`run-as ... rm files/test-input.wav`) to use the microphone again.
- Files: `adb shell "run-as t7helpers.voiceinbox ls -lR files"`; the target file from the
  picker under `/sdcard/Documents/...`. Piping `adb shell cat` output on Windows shows
  CRLF line breaks that are not in the file; use `adb shell od -c` without a pipe.
- A crash: `adb shell "run-as t7helpers.voiceinbox kill -9 <pid>"` (`pidof
  t7helpers.voiceinbox`) during a recording. A save is too quick to interrupt; instead,
  with the app stopped, write the `capture.json` a crash would leave (status `SAVING`
  with a `pending` write) and set up the target file to match. Write it with a JSON
  library: shell quoting easily turns an escaped line break into a real one.

## Tested

On 2026-10-25, version 0.1.0 (debug build) on an Android 14 emulator (Pixel 6 profile,
google_apis image):

- The model card downloads 46 MB, checks it, unpacks it (89 MB) and disappears; the zip
  is deleted. In airplane mode the download shows "No connection" with Try again. Killed
  after 1.1 MB, a new download continued from 1,113,859 bytes and passed the checksum.
- The system picker (device storage → Documents/Vault/Inbox.md) sets the target with a
  persisted grant; the name shows on both screens.
- With the 12 s test WAV: "Loading the speech model…" on the first capture before the
  model was preloaded, then live text, "Speech detected", the timer, and the editor with
  «Русский язык а русский язык это один из восточнославянских языков национальный язык
  русского народа является одним из наиболее». Right after a new download the model was
  ready at once ("Listening…").
- Save appended the line to a file ending without a line break (a `\n` before the line,
  one after it); three more saves added three lines with no blank lines; all LF.
- In airplane mode recording, recognition and saving work.
- Rotating during a recording keeps it; Stop after 7.1 s ends it with a correct WAV
  header and the text so far.
- With the target file deleted, Save shows "Not saved. The file is missing…", creates no
  file and keeps the text; after restoring the file, Save works.
- The emulator microphone (silence): "Listening…", Stop, "Nothing was recognized"; Save
  with empty text says so; a typed "Buy milk" saves as `- [ ] Buy milk`.
- Denying the microphone shows the message and "Open App info", which opens App info.

On 2026-10-25, version 0.2.0 (debug build), installed over 0.1.0 with its data:

- The capture 0.1.0 had left (a folder with audio and no record) was recovered at start:
  recognized again and listed as "Not saved" with a note; Open and Save added it to the
  file. "N captures not saved" shows on the capture screen.
- Killed with `kill -9` 7.7 s into a 12 s recording: the record said RECORDING and the WAV
  header 0 bytes. At the next start, the header was repaired (246,400 bytes) and the text
  recognized again from the audio within 9 s.
- Records as a crash during saving leaves them: SAVING with its bytes in the file became
  SAVED without a second write; SAVING with the file unchanged became FAILED ("The app
  stopped while saving...") and Open, Save wrote it once. A FAILED record with an
  unconfirmed write whose line was elsewhere in the file asked the user: "Save anyway"
  wrote it, "It is in the file: mark as saved" did not.
- History: Play plays the WAV and turns back to Play at its end; Copy, pasted into the
  editor, gives the text; Delete asks first and removes the folder, the line stays in
  the file.
- Edited text survives leaving the app and a force stop. "Record again" and "Later" keep
  the capture as not saved.
- A capture saved 10 days ago lost its audio at start (7 days by default) and kept its
  record; an unsaved one kept its audio.

Not tried yet: a real phone and real speech through a microphone, a revoked SAF grant,
a provider other than local storage, recovery without the speech model, Android 8-13,
release builds.
