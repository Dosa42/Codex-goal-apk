# Files in Codex Goal

The **Files** tab is beside **Console**. It browses the real filesystem of the connected exec-server. For the existing native Termux connection, Home is `/data/data/com.termux/files/home`; Downloads is `/storage/emulated/0/Download`.

## Use it

- Tap **Home**, **Downloads**, **Up**, or enter an absolute folder path and choose **Open path**. Hidden entries are included.
- Tap a folder or text file name to open it. Use **New file** and **New folder** to create entries in the displayed folder.
- Edit a UTF-8 text file and choose **Save**. The app reads the saved bytes back. **Copy text** copies the editor contents to Android's clipboard.
- Select an entry using its selection control. Choose **Copy** or **Cut**, open the destination folder, then **Paste here**.
- Select an entry for **Rename** or **Delete**. Folder copying and deletion include nested contents. Delete confirms the exact selected target.
- **Use as working folder** changes the existing executor workspace setting. Browsing alone does not change the command working folder or create a settings file. Missing settings use the executor defaults until you explicitly save a working folder.

## How files reach Termux

The Android UI uses the existing WebSocket executor connection and its `fs/*` protocol. Files do not need to be exported into Android shared storage before editing. Android's app UID is not used to impersonate Termux; the running Termux executor performs the operations with its own filesystem access. Selecting another executor changes which filesystem is displayed.

Native `fs/copy` takes `sourcePath`, `destinationPath`, and `recursive`. Native rename/move methods are unavailable in the inspected executor, so Cut and Rename copy, verify the result, then remove the source. They are not atomic moves. An interruption can leave both entries or a partial destination; durable per-operation receipts identify the source and destination. Inspection records what exists without replaying a mutation or declaring an uncertain result successful. After inspecting, an explicit acknowledgement records your decision to allow further writes; the original receipt remains.

The app checks for destination collisions and changed file contents before saving. The protocol does not expose exclusive creation or a compare-and-swap write; another filesystem writer can still race with those checks. Clipboard and editor writes are tied to the executor connection/session. There is no added workspace-only path boundary or arbitrary file-count/size ceiling. The text editor requires UTF-8; copying files does not decode them as text. Whole-file reading and verification remain subject to device memory and the executor's actual capabilities. Unsaved editor drafts and the Files clipboard are not restored after the app process dies; saved file contents and operation receipts persist.

## Groq voice accounts

The APK's Voice view loads the existing voice room at `http://127.0.0.1:8790/`. That service owns the shared three-account Groq TTS pool and its account picker; the APK does not maintain a second pool. The three stored credentials are distinct. A fresh Node request verified the third account's model-list authentication with HTTP200; that alone does not verify TTS terms, speech generation, or current speech quota.

The selected account remains **Original Groq**. Account switching is explicit; automatic retries and failover remain off. STT still uses the original bootstrap key. Opening Files, Console, or Voice does not automatically start the microphone. Installation and execution evidence for this update is recorded in the versioned delivery report under `Download/Codex-Documents/01-Goal-Console`.

## Executor reconnection

Reconnect retries the saved executor-session ID, including a row previously marked lost. Temporary already-attached/busy replies keep that ID and use the existing reconnect backoff. An actual unknown or expired session stays visibly unavailable. Only **New Session** creates a replacement executor session; previous process records are retained and are not replayed or migrated. This executor session is separate from the persistent CEO Codex goal.
