package com.example.voice

import java.net.URI

const val DEFAULT_VOICE_URL = "http://127.0.0.1:8790/"

/** Adapts only the APK response. The running room, its keys and durable ledgers are untouched. */
internal object VoiceRoomAdapter {
    private const val ROOM_EXPORT = "globalThis.voiceLab={start,stop,commit,status,configure,prepareReload};"

    fun sameOrigin(left: String, right: String): Boolean = runCatching {
        val a = URI(left)
        val b = URI(right)
        fun port(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        a.scheme in listOf("http", "https") && a.scheme == b.scheme &&
            a.host != null && a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)
    }.getOrDefault(false)

    fun adapt(source: String): String {
        check(source.split(ROOM_EXPORT).size == 2) {
            "Voice room adapter does not match the served app.js. Microphone remains stopped."
        }
        val patched = source.replace(ROOM_EXPORT,
            "globalThis.voiceLab={start,stop,commit,status,configure,prepareReload," +
                "discardPlaybackQueue:()=>{audioQueue.length=0;return status();}};")
        return BOOTSTRAP + "\n" + patched + "\nglobalThis.voiceConsoleAdapter.ready();\n"
    }

    // Installed before the room's first poll. Imports retain their original URL and implementation.
    val BOOTSTRAP = """
        (() => {
          const nativeFetch = globalThis.fetch.bind(globalThis);
          const nativeInterval = globalThis.setInterval.bind(globalThis);
          const ignoredAudio = new Set();
          let foreground = true, armed = false, ready = false, generation = 0;
          const room = () => globalThis.voiceLab;
          const report = () => {
            const status = room()?.status() || {};
            globalThis.VoiceConsoleBridge?.status(JSON.stringify({
              ...status, ready, foreground, source: location.href,
              running: Boolean(status.running), starting: Boolean(status.starting),
              micOpen: Boolean(status.micOpen)
            }));
          };
          const error = e => globalThis.VoiceConsoleBridge?.error(String(e?.message || e));
          globalThis.fetch = async (input, options) => {
            const response = await nativeFetch(input, options);
            const url = new URL(typeof input === 'string' ? input : input.url, location.href);
            if (url.origin !== location.origin || url.pathname !== '/api/state' || !response.ok) return response;
            const data = await response.clone().json();
            // Keep events, candidate diagnostics, provider errors, documents and account metadata intact.
            const listening = foreground && armed && room()?.status().running;
            for (const audio of data.audioQueue || []) if (!listening) ignoredAudio.add(audio.id);
            data.audioQueue = (data.audioQueue || []).filter(audio => !ignoredAudio.has(audio.id));
            const headers = new Headers(response.headers);
            headers.delete('content-length'); headers.delete('content-encoding');
            return new Response(JSON.stringify(data), {status: response.status, statusText: response.statusText, headers});
          };
          // Stop room refresh work while its screen is inactive; resuming timers never starts capture.
          globalThis.setInterval = (fn, delay, ...args) => nativeInterval(() => {
            if (foreground) fn(...args);
          }, delay);
          if (navigator.mediaDevices?.getUserMedia) {
            const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
            navigator.mediaDevices.getUserMedia = constraints => {
              if (!foreground || !armed) return Promise.reject(new Error('Choose Start microphone to begin capture.'));
              return original(constraints);
            };
          }
          globalThis.voiceConsoleAdapter = {
            ready() { ready = true; report(); },
            async start() {
              if (!foreground || !ready) throw new Error('Voice room is not ready in the foreground.');
              const attempt = ++generation;
              // Take an explicit fresh baseline before Start, including audio not seen by a prior poll.
              const response = await nativeFetch('/api/state', {cache: 'no-store'});
              const data = await response.json();
              if (!response.ok || data.ok === false) throw new Error('Voice state HTTP ' + response.status);
              for (const audio of data.audioQueue || []) ignoredAudio.add(audio.id);
              if (!foreground || attempt !== generation) return report();
              room().discardPlaybackQueue();
              armed = true;
              try { await room().start(); } finally { if (!room().status().running) armed = false; report(); }
            },
            async stop() {
              ++generation; armed = false;
              room()?.discardPlaybackQueue();
              await room()?.stop();
              report();
            },
            async suspend() { foreground = false; await this.stop(); },
            resume() { foreground = true; report(); },
            report
          };
          document.addEventListener('click', event => {
            const button = event.target.closest?.('button');
            if (button?.id !== 'start' && button?.id !== 'stop') return;
            event.preventDefault(); event.stopImmediatePropagation();
            if (button.id === 'start') globalThis.VoiceConsoleBridge?.start();
            else globalThis.VoiceConsoleBridge?.stop();
          }, true);
          document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'hidden') globalThis.voiceConsoleAdapter.suspend().catch(error);
            // Native lifecycle explicitly restores timers. Visibility never restores a microphone.
          });
          nativeInterval(() => { if (foreground) report(); }, 750);
        })();
    """.trimIndent()
}
