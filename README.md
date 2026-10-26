# Voice Inbox

Speak a short note and get it as a Markdown task in your Obsidian vault, with speech
recognition entirely on the phone (Android 8 or newer).

Tap the red **Record** icon and speak. Recording stops when you stop speaking; the
recognized text shows while you speak; fix it if needed and tap Save (or let the app
save it). The app appends one line to the Markdown file you picked:

```markdown
- [ ] Купить молоко и хлеб
```

Every capture is kept, with its audio, until you delete it: if saving fails or the app
is closed or killed at any point, the capture waits in **History** to be saved.

Status: version 0.4.0. Russian and English. See `AGENTS.md` for the plan.

## Install and first run

1. Install the APK from the [Releases](https://github.com/t7ko/android-voice-inbox/releases)
   page, or with [Obtainium](https://github.com/ImranR98/Obtainium) from
   `https://github.com/t7ko/android-voice-inbox`, which also installs updates.
2. Open Voice Inbox and tap **Download** on the speech model card: 46 MB for Russian,
   once, over the internet. After that the app works in airplane mode. The English model
   (41 MB) is in Settings.
3. Tap **Choose** and pick the Markdown file, for example the inbox note of your vault.
   The app keeps access to that one file; Obsidian does not need to be running.
4. Tap the microphone and allow recording.

## Recording

- The app installs two icons: **Voice Inbox** opens the app, **Record** (red) opens it
  already recording. A long press on the Voice Inbox icon offers Record too. On Xiaomi
  phones both icons appear on the home screen by themselves.
- Recording stops by itself 2 seconds after you stop speaking (Settings: 1 to 5 s, or
  off); short pauses between words do not stop it. If you say nothing, it stops after 10
  seconds. It never runs longer than 2 minutes (Settings: 30 s to 5 min). Stop works at
  any time.
- **Russian** and **English** chips under the record button pick the language. Each model
  knows only its language: English words in Russian speech come out as Russian-sounding
  words.
- **Save without tapping Save** (Settings, off by default) saves the text right after
  recording, but only when the recording ended normally and the recognizer is sure of
  the words. Otherwise the capture waits for you with the reason. Started from the Record
  icon, the app then goes back to the home screen by itself (Settings).
- Music and other sound pause while you record, and the screen stays on. A call ends the
  recording and keeps what was said; during a call, recording does not start.
- Tapping Record again while the last note is still being saved starts the next one
  right after it.

## How saving works

- Each Save adds exactly one line at the end of the file. A line break goes in front of
  it only if the file does not already end with one, so no blank lines appear, and the
  file's own line break style (LF or CRLF) is kept. Line breaks inside the text become
  spaces.
- The app never rewrites or truncates the file: it writes the new bytes at the end,
  flushes them to storage and reads them back. **Saved** shows only when they are there.
- If the file is gone, access was lost, or the storage cannot be written safely (cloud
  storage providers), the text stays on screen with the reason, and Save can be tried
  again, also later from History.
- No line is added twice. Before writing, the app notes exactly what goes where. If the
  write could not be confirmed, or the app was killed while saving, it looks in the file
  before trying again: if the line got there, the capture counts as saved; if it is
  clearly not there, it is written; if the same line is somewhere else in the file, you
  choose between **Save anyway** and **mark as saved**.

## History and recovery

- History lists all captures with their time, length, text and status (Saved, Not
  saved, Failed). **Open** takes one back to the editor to fix and save it; **Copy**,
  **Play** and **Delete** (after a confirmation) do what they say.
- **Record again** and **Later** in the editor keep the capture in History; the main
  screen shows how many are not saved.
- If the app is killed during a recording, the next start repairs the audio file and
  recognizes the text again from it; the capture then waits in History.
- The audio of saved captures is deleted 7 days after saving (Settings: 1, 7 or 30 days,
  or keep). Captures that are not saved keep their audio until you delete them.

## Privacy

Audio, text and the file stay on the phone. The app has the INTERNET permission for one
thing only: downloading the speech models from alphacephei.com, the Vosk project's site.
Each download is checked against a fixed SHA-256 checksum. Nothing else goes over the
network: no accounts, analytics or crash reports. To be sure, you can block the app's
network access in the phone's settings after the download.

Recordings are kept in the app's private storage, about 1 MB per 30 seconds.

## Speech recognition

Recognition uses [Vosk](https://alphacephei.com/vosk/) with the
`vosk-model-small-ru-0.22` and `vosk-model-small-en-us-0.15` models (Apache 2.0). They
are small models made for phones: quick, but they write everything in lowercase without
punctuation (the app capitalizes the first letter) and get some words wrong, so expect to
correct the text now and then.

## Known limitations

- A recording stops when you leave the app; there is no background recording. The
  audio and the text so far are kept on screen.
- If Obsidian has the same note open with unsaved edits, Obsidian may overwrite the
  added line when it saves.
- If you delete the added line in Obsidian while the app has not yet confirmed the save
  (it was killed in that instant), the next start may add it again.

## Development

See `AGENTS.md` for the design, the plan and how to test on the emulator. Unit tests:
`gradlew.bat testDebugUnitTest`; a debug APK: `gradlew.bat assembleDebug`. A signed
release: raise `versionCode` and `versionName` in `app/build.gradle.kts`, commit, then
`powershell -ExecutionPolicy Bypass -File scripts/release.ps1`.
