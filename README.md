# AegisAgent (v1.3 Offline Assistant, Notebook Studio & Autonomous Agent)

Privacy-first Android workspace built with Jetpack Compose, SQLite FTS5, Android Keystore AES-256-GCM encrypted logs, native offline `TextToSpeech`, AccessibilityService, and on-device MediaPipe LLM inference.

## Active On-Device Model Configuration
- Model Profile: Gemma 3 1B Instruct (INT4 (.task))
- Target Path: `/data/local/tmp/llm/gemma-3-1b-it-int4.task`
- Minimum Android SDK: 28 (required by `com.google.mediapipe:tasks-genai`)

## What's Inside v1.3
1. **Gemini-Style Multi-Thread Chat (`LocalThreadStore`)**: Persists conversations locally in SQLite (`aegis_threads.db`), switches between `auto` (L1–L3) and `notebook` grounded modes, supports voice dictation and image attachments, and saves replies straight into Notebook sources.
2. **NotebookLM-Style Knowledge Studio (`NotebookStudioEngine.kt`)**: Check/uncheck local sources in `LocalRagStore`, ask source-grounded Q&A with inline citations, generate Briefing Docs and Study Guides, and listen to two-host Audio Overviews via offline Android `TextToSpeech`.
3. **Immutable Blacklist & Allowlist (`PolicyGatekeeper.kt`)**: Hardcoded `HardcodedBlacklist` blocks banking, UPI, and password manager packages regardless of user settings; `AgentAccessibilityService.kt` enforces strict package allowlisting.
4. **Unicode Injection Defense (`InputSanitizer`) & Rule-of-Two**: Strips invisible zero-width characters, bidi overrides, and Unicode tag blocks; freezes consequential actions (`SEND`, `PAY`, `DELETE`, `POST`, `SHARE`) on untrusted screens pending explicit HITL approval.
5. **Emergency Kill Switch & Encrypted Vault**: Double-press Volume Down in `AgentAccessibilityService` or tap `KILL` in `MainActivity` to halt execution and audio playback; activity logs are encrypted locally via Android Keystore `AES/GCM/NoPadding`.

## Pushing the Local Gemma / MediaPipe Model via ADB
```bash
adb shell mkdir -p /data/local/tmp/llm
adb push gemma-3-1b-it-int4.task /data/local/tmp/llm/gemma-3-1b-it-int4.task
```

## Building APK via GitHub Actions
Push this extracted directory to a GitHub repository on `main`. `.github/workflows/build-apk.yml` compiles `gradle assembleRelease --no-daemon` and attaches a signed APK release automatically.
