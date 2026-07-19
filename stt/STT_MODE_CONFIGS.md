# STT Mode Config Presets

## ⭐ 1. Quiet Room (Studio-like, low noise)

```kotlin
RuntimeSttConfig(
    energyThreshold = 0.0012,      // Very sensitive — quiet environment
    preRollMs = 80,                // Tight, VAD triggers early
    stableChunkSizeMs = 300,       // Less stability needed
    debugLoggingEnabled = false,
    startStrategy = ManualStart(),
    stopStrategy = AutoSilenceStop(),
    autoSilenceMs = 900,           // Short pause ends utterance
    autoMaxDurationMs = 30000,
    sessionTimeoutMs = 0,
    warmupEnabled = false,
    warmupDurationMs = 0,
    highPassCutoffHz = 0,          // No filtering needed
    zcrEnabled = false
)
```

**Use when:**
- Office
- Bedroom
- Studio
- Controlled environment

---

## ⭐ 2. Noisy Room (Kitchen, workshop, fans, HVAC)

```kotlin
RuntimeSttConfig(
    energyThreshold = 0.0030,      // Less sensitive — suppress noise
    preRollMs = 150,               // VAD triggers late in noise
    stableChunkSizeMs = 600,       // More stability required
    debugLoggingEnabled = false,
    startStrategy = ManualStart(),
    stopStrategy = AutoSilenceStop(),
    autoSilenceMs = 1400,          // Longer pause needed
    autoMaxDurationMs = 30000,
    sessionTimeoutMs = 0,
    warmupEnabled = true,          // Helps stabilize noisy input
    warmupDurationMs = 150,
    highPassCutoffHz = 120,        // Remove low-frequency hum
    zcrEnabled = true              // Helps detect voiced speech in noise
)
```

**Use when:**
- Kitchen
- Workshop
- Car interior
- Fan/HVAC noise

---

## ⭐ 3. Mobile Microphone (Phone mic, variable distance)

```kotlin
RuntimeSttConfig(
    energyThreshold = 0.0020,      // Balanced sensitivity
    preRollMs = 120,               // Mobile VAD tends to trigger late
    stableChunkSizeMs = 500,
    debugLoggingEnabled = false,
    startStrategy = ManualStart(),
    stopStrategy = AutoSilenceStop(),
    autoSilenceMs = 1100,
    autoMaxDurationMs = 30000,
    sessionTimeoutMs = 0,
    warmupEnabled = true,
    warmupDurationMs = 100,
    highPassCutoffHz = 80,         // Reduce handling noise
    zcrEnabled = true
)
```

**Use when:**
- Phone mic
- Tablet mic
- Hand-held devices

---

## ⭐ 4. Desktop Microphone (USB mic, stable gain)

```kotlin
RuntimeSttConfig(
    energyThreshold = 0.0015,      // Desktop mics have stable gain
    preRollMs = 70,                // Very tight onset
    stableChunkSizeMs = 300,
    debugLoggingEnabled = false,
    startStrategy = ManualStart(),
    stopStrategy = AutoSilenceStop(),
    autoSilenceMs = 800,           // Very responsive UX
    autoMaxDurationMs = 30000,
    sessionTimeoutMs = 0,
    warmupEnabled = false,
    warmupDurationMs = 0,
    highPassCutoffHz = 0,
    zcrEnabled = false
)
```

**Use when:**
- Podcast mic
- USB condenser mic
- Desktop boom mic

---

## ⭐ 5. Conversational UX (Natural pauses, multi-turn dialogue)

```kotlin
RuntimeSttConfig(
    energyThreshold = 0.0018,
    preRollMs = 100,
    stableChunkSizeMs = 400,
    debugLoggingEnabled = false,
    startStrategy = ManualStart(),
    stopStrategy = AutoSilenceStop(),
    autoSilenceMs = 1600,          // Longer pause allowed
    autoMaxDurationMs = 60000,     // Longer utterances allowed
    sessionTimeoutMs = 2000,       // Allow multi-turn conversation
    warmupEnabled = false,
    warmupDurationMs = 0,
    highPassCutoffHz = 0,
    zcrEnabled = false
)
```

**Use when:**
- Chatbot
- Assistant
- Multi-turn conversation
- Natural speech

---

## ⭐ 6. Command-and-Control UX (Short, snappy commands)

```kotlin
RuntimeSttConfig(
    energyThreshold = 0.0025,      // Less sensitive to avoid false triggers
    preRollMs = 60,                // Very tight
    stableChunkSizeMs = 200,       // Fast detection
    debugLoggingEnabled = false,
    startStrategy = ManualStart(),
    stopStrategy = AutoSilenceStop(),
    autoSilenceMs = 600,           // Very short pause ends utterance
    autoMaxDurationMs = 5000,      // Commands are short
    sessionTimeoutMs = 0,
    warmupEnabled = false,
    warmupDurationMs = 0,
    highPassCutoffHz = 100,        // Remove rumble
    zcrEnabled = true              // Helps detect short voiced commands
)
```

**Use when:**
- Robot control
- Smart home commands
- Button-press UX
- Short phrases

---

## ⭐ Summary

| Preset | Environment |
| --- | --- |
| **Quiet Room** | Office, bedroom, studio, controlled environment |
| **Noisy Room** | Kitchen, workshop, car, fan/HVAC |
| **Mobile** | Phone mic, tablet mic, hand-held devices |
| **Desktop** | Podcast mic, USB condenser mic, desktop boom mic |
| **Conversational** | Chatbot, assistant, multi-turn dialogue |
| **Command-and-Control** | Robot control, smart home, short phrases |

Each preset is tuned for:
- VAD sensitivity
- Pre-roll
- Silence detection
- Warm-up
- Noise filtering
- UX timing
