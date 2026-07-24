
---

## High-level architecture

| Component | Role |
| --- | --- |
| **WakeWordSessionManager** | Orchestrates listening, matching, and mode transitions |
| **WakeWordEngine** | Runs PCM → MFCC → similarity → threshold |
| **MFCC** | Converts PCM frames into feature vectors |
| **TemplateStore** | Holds the reference wake‑word MFCC template(s) |
| **Foreground Service** | Keeps continuous low‑power listening alive |

---

## 1. Cato directive (template-matching WUW)

**Directive ID:** `ASR-WUW-TEMPLATE-MATCHING-INIT`  
**Priority:** High  

### Objective
Implement a lightweight wake‑word engine using MFCC + template matching (no TFLite/ONNX), integrated with the existing ASR subsystem.

### Actions

**1. Create `:wuw` module**

Structure:

```text
:wuw/
  WakeWordSessionManager.kt
  WakeWordEngine.kt
  MfccExtractor.kt
  TemplateStore.kt
  WakeWordService.kt
```

---

**2. Implement MFCC extraction**

- Input: PCM `ShortArray` (16 kHz, mono)
- Output: `FloatArray[]` (frames × coefficients)

Pipeline:

- pre‑emphasis  
- frame blocking (e.g. 25 ms, 10 ms stride)  
- windowing (Hamming)  
- FFT  
- mel filterbank  
- log energy  
- DCT → MFCC coefficients  

---

**3. Implement TemplateStore**

- API:
  - `saveTemplate(mfccFrames: List<FloatArray>)`
  - `loadTemplate(): List<FloatArray>`
- Storage: simple file or SharedPreferences + binary blob.

Offline flow:

1. Record wake‑word once (or a few times).
2. Compute MFCC.
3. Save as template.

---

**4. Implement WakeWordEngine (template matching)**

- Input: live PCM frames.
- Steps:
  1. Convert PCM → MFCC (same pipeline as template).
  2. Compute similarity between live MFCC sequence and template MFCC sequence using:
     - DTW (preferred), or
     - cosine similarity over aligned frames.
  3. If similarity ≥ threshold → fire `onWakeWordDetected()`.

- API:
  - `processPcm(pcm: ShortArray)`
  - `setThreshold(value: Float)`
  - `setListener(listener: WakeWordListener)`

---

**5. Implement WakeWordSessionManager**

Responsibilities:

- start/stop foreground service  
- manage AudioRecord  
- feed PCM into `WakeWordEngine`  
- on detection → call `VoskSessionManager.startCommandMode()`  
- after command → resume listening  

API:

```kotlin
startWakeWordMode()
stopWakeWordMode()
setThreshold(value: Float)
```

---

**6. Tuning & validation**

- Collect multiple wake‑word samples → average template or multiple templates.
- Test in:
  - quiet room  
  - TV noise  
  - different distances  
- Adjust:
  - MFCC frame size/stride  
  - DTW window constraints  
  - similarity threshold.

---

If you want next, I can sketch:

- MFCC extractor structure  
- DTW similarity function shape  
- `WakeWordEngine` class outline in Kotlin.