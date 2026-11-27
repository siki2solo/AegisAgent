# AegisAgent (Android Autonomous Accessibility Agent)

Privacy-first Android agent built with Jetpack Compose, AccessibilityService, Android Keystore AES-256-GCM encrypted logs, and on-device MediaPipe LLM inference.

## Active On-Device Model Configuration
- Model Profile: Gemma 3 1B Instruct (INT4 (.task))
- Target Path: `/data/local/tmp/llm/gemma-3-1b-it-int4.task`
- Minimum Android SDK: 28 (required by `com.google.mediapipe:tasks-genai`)

## Security Boundaries
1. Strict package allowlist enforced in `AgentAccessibilityService.kt`.
2. OS-level skipping of `isPassword` nodes, password input types, and `FLAG_SECURE` windows.
3. Explicit user confirmation gate before dispatching external actions.
4. Local-only activity logs encrypted via Android Keystore `AES/GCM/NoPadding`.

## Pushing the Local Gemma / MediaPipe Model via ADB
```bash
adb shell mkdir -p /data/local/tmp/llm
adb push gemma-3-1b-it-int4.task /data/local/tmp/llm/gemma-3-1b-it-int4.task
```

## Building APK via GitHub Actions
Push this extracted directory to a GitHub repository on `main`. `.github/workflows/build-apk.yml` compiles `gradle assembleRelease --no-daemon` and attaches a signed APK release automatically.
