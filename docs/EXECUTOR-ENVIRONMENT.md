# Connected executor identity

The APK remains an Android client. Shell, filesystem, working directory and user identity belong to the connected executor.

## Selection

`initialize.environmentInfo` must report `shell.path`, `userHomeDir`, `cwd` and `platformOs`. Missing metadata is a visible error. Commands use the exact reported shell (`-c`); interactive terminals use that shell (`-l`). There is no server-side executable rewrite or silent Android shell fallback. Processes inherit the executor environment through explicit `env: {}`; PTYs additionally request `TERM=xterm-256color`. The APK does not inject Android HOME, PREFIX or PATH into Linux.

The installed native executor advertises `/bin/sh`. A direct protocol execution verified that this exact path works and runs as the Termux UID; its name must not be judged by the APK's filesystem.

## Workspace settings

The existing Android Termux profile is identified by the reported Android OS and exact Termux home. It retains `~/.codex/android-client/workspace.json` and the established `~/.codex` default.

Other executors start in their advertised cwd. Their settings live in `~/.local/state/codex-goal/workspaces/<scope>/workspace.json`, where scope is the first 24 lowercase SHA256 hex characters of UTF-8 `platformOs + "\n" + canonicalHomeUri + "\n" + canonicalAdvertisedCwdUri`. This is an additive app-owned configuration file, separate from project source. Different roots receive independent settings. Existing accidental `~/.codex/android-client` files on Linux are not imported or deleted automatically.

Settings use format version 1, with an absolute local file URI in `workspaceUri`; the bundled `assets/workspace.schema.json` documents the schema. URI decoding and encoding preserves spaces, Unicode and reserved filename characters. Writes retain readback checks and existing external-writer conflict checks. The filesystem protocol has no atomic compare-and-swap, so those checks do not eliminate races with external writers.

A loaded workspace is associated with its executor session. Pending file edits from another session block automatic loading until copied or closed. Old workspace paths are not automatically reused for a new session.

## Connection and health

Each exact endpoint has its own latest resumable session. Switching remote then native resumes the native record instead of choosing the globally newest remote record. A service ACTION_START endpoint is saved to settings, so the UI and transport identify the same endpoint.

The documented compatibility route `/v1/exec-server` uses `/v1/health`; the preview selector query is retained byte for byte. Other deployments retain their existing `<path>/readyz` health convention. No arbitrary health routes are probed or silently guessed. A new server with a different convention requires an explicit adapter.

## Verification boundaries

Unit tests cover profile choice, URI escaping, settings scopes, selector preservation, actual WebSocket empty-environment serialization and per-endpoint session resumption. On-device acceptance must separately execute commands, PTY roundtrips and filesystem operations against both actual executors. CI success or a successful handshake alone is not installed runtime acceptance.
