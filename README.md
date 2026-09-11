# Ctrl+Alt+Del

> **Offline Emergency Mesh Communication & Voice Alerting System for Android**

Ctrl+Alt+Del is an open-source, device-to-device emergency communication Android application designed to operate in total blackout scenarios—where cellular towers, internet connectivity, and power infrastructure are unavailable. 

The application integrates on-device speech-to-text (STT), peer-to-peer Bluetooth Low Energy (BLE) mesh networking, and receiver-side text-to-speech (TTS) voice alerting into a unified, privacy-focused rescue tool.

---

## 1. Problem Statement

During natural disasters (earthquakes, floods, hurricanes) or civil emergencies, cellular base stations and Wi-Fi networks frequently suffer power outages or physical damage. Victims cannot reach emergency responders through traditional communication channels, and responders cannot locate survivors without active network coverage. 

**Ctrl+Alt+Del** solves this problem by providing an entirely self-contained, 100% offline emergency messaging pipeline that runs directly on everyday Android smartphones without internet access, external routers, or SIM cards.

---

## 2. Key Features

- **Push-to-Talk (PTT) Emergency Voice Capture**: Press-and-hold emergency interface with live audio waveform visualization and speech probability detection.
- **On-Device Speech-to-Text (STT)**: Transcribes the victim's spoken words into structured text offline using local acoustic models.
- **Peer-to-Peer BLE Mesh Communication**: Transfers text messages and emergency alerts directly between nearby devices over Bluetooth Low Energy (BLE) without internet or local Wi-Fi routers.
- **Multi-Hop Message Forwarding**: Messages propagate across intermediate devices with Time-To-Live (TTL) hop limits to extend communication range beyond direct radio distance.
- **Reliable Packet Transport**: Includes MTU chunking, reassembly, delivery acknowledgments (ACKs), and Room database persistence with automatic retransmission.
- **Receiver-Side Text-to-Speech (TTS) at Full Volume**: When an alert or message arrives on the receiver device, it automatically converts the received text into audible speech at maximum device volume, safely restoring original volume levels after playback.
- **Dual Communication Modes**:
  - **Emergency Workflow**: High-priority alert modal with automated voice playback.
  - **Nearby Mesh Chat**: Peer list discovery and manual text communication.
- **Zero Cloud / Zero Internet**: Operates in Airplane Mode with only Bluetooth enabled. The application manifest requests **zero** network (`INTERNET`) permissions.

---

## 3. Supported Languages

- **English (US)**: Full end-to-end support with bundled Vosk Kaldi acoustic model (`model-en-us`) and offline speech synthesis.
- **Tamil (தமிழ்)**: Transcription pipeline supports Tamil speech recognition via model import (`.zip`), preserving native UTF-8 script throughout the BLE transport layer and on-screen display.

---

## 4. System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     SENDER (Victim Device)                      │
│                                                                 │
│  Microphone Input (16 kHz Mono PCM)                             │
│         │                                                       │
│         ▼                                                       │
│  DeepFilterNet Noise Suppression                                │
│         │                                                       │
│         ▼                                                       │
│  Silero VAD (ONNX TinyML Voice Activity Detection)              │
│         │                                                       │
│         ▼                                                       │
│  Vosk Kaldi ASR Engine (Offline Speech-to-Text)                 │
│         │                                                       │
│         ▼                                                       │
│  EmergencyMessageFormatter (Structured Payload)                 │
│         │                                                       │
│         ▼                                                       │
│  BleCommunicationManager (GATT Client / Advertising)           │
└────────────────────────────────┬────────────────────────────────┘
                                 │
                   Direct Offline BLE Radio Link
                                 │
┌────────────────────────────────▼────────────────────────────────┐
│                    RECEIVER (Responder Device)                  │
│                                                                 │
│  BleGattServerManager (Packet Assembly & L2CAP Chunks)          │
│         │                                                       │
│         ▼                                                       │
│  Room Database Persistence & BLE Delivery ACK                   │
│         │                                                       │
│         ▼                                                       │
│  EmergencyAlertManager (Payload Parser & Duplicate LRU Filter)  │
│         │                                                       │
│         ▼                                                       │
│  High-Priority Visual Display & RealTextToSpeechEngine          │
│         │                                                       │
│         ▼                                                       │
│  Set Volume to 100% Maximum (STREAM_MUSIC)                      │
│         │                                                       │
│         ▼                                                       │
│  🔊 Phone Speaker Speaks Exact Transferred Text                 │
│         │                                                       │
│         ▼                                                       │
│  Restore Original User Volume Setting                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## 5. Subsystem Implementation Details

### A. Offline Speech-to-Text (STT) Subsystem
- **Package**: `com.vibemusic.speechtotext`
- **Engine**: Vosk Kaldi offline speech recognition (`org.vosk:android:0.3.47`).
- **Voice Activity Detection (VAD)**: Silero VAD ONNX model (`silero_vad.onnx`) executed via ONNX Runtime Mobile (`com.microsoft.onnxruntime:onnxruntime-android:1.19.2`). Gates the speech recognizer to conserve CPU and battery during silence.
- **Noise Suppression**: Native and lightweight software noise suppression to mitigate environmental background noise in disaster zones.
- **Audio Capture**: 16 kHz, 16-bit mono PCM stream via Android `AudioRecord`.

### B. Offline Nearby Mesh Subsystem
- **Package**: `com.offline.ble.mesh`
- **Transport**: Bluetooth Low Energy (BLE) GATT server and GATT client.
- **Service & Characteristics**: Custom BLE service UUID with write, notify, and ACK characteristics.
- **Fragmentation**: `MessageChunker` splits payloads exceeding BLE MTU limits; `MessageAssembler` reconstructs incoming chunks.
- **Local Persistence**: SQLite database via AndroidX Room (`AppDatabase`, `MessageDao`, `MessageEntity`).
- **Background Operation**: `BleForegroundService` keeps discovery and mesh relaying active even when the app is minimized.

### C. Receiver-Side Text-to-Speech (TTS) Subsystem
- **Package**: `com.itantra.tts`
- **Engine**: [`RealTextToSpeechEngine`](app/src/main/java/com/itantra/tts/RealTextToSpeechEngine.kt) implementing [`TextToSpeechEngine`](app/src/main/java/com/itantra/tts/TextToSpeechEngine.kt).
- **Operation**:
  1. Receives the exact transferred text string.
  2. Saves the user's current media stream volume.
  3. Temporarily elevates media volume to **100% device maximum** (`getStreamMaxVolume`).
  4. Synthesizes and speaks the exact message via the on-device speech engine in a clear human voice (no sirens, buzzers, or frequency beeps).
  5. Monitors `UtteranceProgressListener.onDone` to ensure physical playback is completed before restoring original volume levels.

---

## 6. Two-Phone Communication Workflow

1. **Setup**: Install the application on two Android devices (Device A and Device B). Turn **OFF** Wi-Fi and Mobile Data. Turn **ON** Bluetooth on both devices.
2. **Device Discovery**: Launch the app. Devices automatically discover each other via BLE advertising and appear in the peer counter.
3. **Emergency Transmission (Device A)**:
   - On the **🚨 Emergency** tab, press and hold the red **EMERGENCY** button.
   - Speak: *"There is a fire near the main entrance."*
   - Release the button. Speech is transcribed locally, formatted, and transmitted via BLE.
4. **Emergency Reception (Device B)**:
   - Within seconds, Device B receives the packet.
   - Displays a high-priority emergency alert dialog with the exact text, sender node ID, and timestamp.
   - Converts the text to speech and speaks *"There is a fire near the main entrance"* out loud at full volume.
   - Sends a BLE ACK back to Device A; Device A marks the message status as **DELIVERED**.
5. **Chat Transmission**: Users can also switch to the **📡 Nearby Mesh** tab to send standard text messages, which are spoken and logged without triggering the emergency dialog.

---

## 7. Technology Stack & Frameworks

| Component | Technology |
| :--- | :--- |
| **Language** | Kotlin 2.2.10 (JVM target 11) |
| **UI Framework** | Jetpack Compose (BOM 2025.02.00) with Material 3 |
| **Build System** | Gradle with Kotlin DSL (`build.gradle.kts`), AGP 9.3.2 |
| **Speech Recognition** | Vosk Android 0.3.47 (Kaldi C++ native library) |
| **TinyML / VAD** | ONNX Runtime Mobile 1.19.2 + Silero VAD (`silero_vad.onnx`) |
| **Local Database** | AndroidX Room 2.7.2 with KSP code generation |
| **Concurrency** | Kotlin Coroutines & StateFlow / SharedFlow |
| **Nearby Networking** | Android Bluetooth LE (GATT Server, Client, Advertiser, Scanner) |
| **Speech Playback** | Android on-device TextToSpeech Engine with `AudioManager` |

---

## 8. Requirements

- **Minimum Android Version**: Android 8.0 (API Level 26)
- **Target / Compile SDK**: Android 16 (API Level 37)
- **Hardware Prerequisites**:
  - Microphone
  - Bluetooth 4.2+ with Bluetooth Low Energy (BLE) peripheral/central mode support

---

## 9. Project Structure

```
Ctrl-Alt-Del/
├── app/
│   ├── build.gradle.kts              # App-level dependencies and plugins
│   ├── proguard-rules.pro            # R8/ProGuard configuration
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   # Permissions, service, activity definitions
│       │   ├── assets/               # Bundled offline models
│       │   │   ├── model-en-us/      # Vosk Kaldi English acoustic model
│       │   │   └── silero_vad.onnx   # Silero VAD ONNX model
│       │   ├── java/
│       │   │   ├── com/disasteralert/
│       │   │   │   ├── emergency/    # EmergencyAlertManager, Formatter, Models
│       │   │   │   └── ui/           # MainActivity, ViewModel, Compose screens
│       │   │   ├── com/itantra/tts/  # TextToSpeechEngine interface & implementation
│       │   │   ├── com/offline/ble/mesh/ # BLE GATT server/client, Room DB, protocol
│       │   │   └── com/vibemusic/speechtotext/ # Vosk ASR, Silero VAD, Audio pipeline
│       │   └── res/                  # Icons, colors, strings, themes, XML layouts
│       └── test/                     # Unit test suites
├── gradle/
│   ├── libs.versions.toml            # Dependency version catalog
│   └── wrapper/                      # Gradle wrapper files
├── build.gradle.kts                  # Root build script
├── gradle.properties                 # JVM arguments & AndroidX flags
├── gradlew / gradlew.bat             # Gradle wrapper scripts
├── settings.gradle.kts               # Repository and module declarations
└── .gitignore                        # Excludes build caches, local.properties, and *.apk
```

---

## 10. How to Build and Run

### In Android Studio
1. Open **Android Studio** (Ladybug / Meerkat or newer recommended).
2. Select **File > Open...** and choose the repository root folder.
3. Allow Gradle to sync dependencies.
4. Connect an Android device via USB with USB Debugging enabled.
5. Click the green **Run ▶** button (`Shift + F10`).

### Via Command Line (Gradle)
```bash
# Build the debug APK
./gradlew assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk

# Run unit tests
./gradlew testDebugUnitTest
```

---

## 11. Testing & Verification

The project includes an automated unit test suite (`app/src/test/`):
- **`RealTextToSpeechEngineTest`**: Tests priority handling, preemption of active speech, and duplicate suppression.
- **`EmergencyMessageFormatterTest`**: Validates structured parsing for emergency/normal payloads and Tamil UTF-8 character preservation.
- **`PacketSerializerTest`**: Validates binary and string serialization for BLE transmission.
- **`MessageChunkerTest`**: Verifies payload segmentation into MTU chunks and complete reassembly.
- **`PipelineComponentsTest`**: Tests audio configuration and buffer mechanics.

All 24 unit tests pass cleanly:
```bash
./gradlew testDebugUnitTest
# Result: BUILD SUCCESSFUL (24/24 tests passed)
```

---

## 12. Offline Operation & Privacy

- **No Internet Permission**: `android.permission.INTERNET` is **not** present in `AndroidManifest.xml`. The operating system prevents the application from making any network calls.
- **Local Data Storage**: All message history, peer IDs, and transcripts are stored exclusively in an on-device SQLite database via AndroidX Room.
- **No Telemetry or Tracking**: No third-party tracking, crash reporting SDKs, or cloud analytics are integrated.

---

## 13. Limitations & Known Issues

- **Physical BLE Range**: Direct Bluetooth Low Energy range is typically 10–30 meters depending on physical obstacles (concrete walls, terrain). Messages beyond single-hop distance require intermediate peer nodes to act as relays.
- **Tamil Acoustic Model**: The English acoustic model (`model-en-us`) is pre-bundled in assets (~40 MB). The Tamil speech recognition model is not pre-bundled due to APK size constraints and must be loaded via the in-app model manager from device storage.
- **Device Sleep Optimization**: Some Android manufacturers aggressively kill background Bluetooth scanning. The foreground service notification should remain enabled to maintain continuous mesh discovery.

---

## 14. Planned Future Work

- [ ] Multi-channel audio encoding for low-bandwidth walkie-talkie voice streaming.
- [ ] Integration of compact, on-device neural TTS models for multiple regional languages.
- [ ] Automatic cryptographic packet signing for peer node authenticity.
- [ ] Wi-Fi Aware (NAN) transport layer for higher bandwidth data transfer where hardware supports it.

---

## 15. License & Disclaimers

- **Project Status**: This software is an experimental prototype developed for emergency disaster communication research and demonstrations.
- **Emergency Disclaimer**: This application is not a certified replacement for official government emergency services (such as 911, 112, or local rescue authorities) where standard communication networks are operational.
- **Third-Party Open-Source Components**:
  - **Vosk / Kaldi**: Apache License 2.0
  - **ONNX Runtime**: MIT License
  - **Silero VAD**: MIT License
  - **AndroidX & Jetpack Libraries**: Apache License 2.0
