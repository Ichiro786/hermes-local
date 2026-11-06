#!/usr/bin/env python3
"""Whisper model benchmark on this CPU (run inside the engine with Hermes' python, after the stt-whisper extra).

  voice-bench.py <english.wav> [models, default tiny,base,small]

For each model: load time, transcription time of the English sample and of a Hindi sample made with
edge-tts (when it can be made), the detected language and the text. One "BENCH ..." line per run.
"""
import asyncio
import os
import sys
import time

sys.path.insert(0, "/opt/hermes/hermes-agent")
import hermes_bootstrap  # noqa: F401,E402
from faster_whisper import WhisperModel  # noqa: E402

wav = sys.argv[1]
models = (sys.argv[2] if len(sys.argv) > 2 else "tiny,base,small").split(",")
samples = [("en", wav)]
try:
    import edge_tts

    async def _hi():
        await edge_tts.Communicate("नमस्ते हर्मीस, आज आगरा में मौसम कैसा है? मुझे कल सुबह की याद दिलाना।",
                                   "hi-IN-MadhurNeural").save("/tmp/bench-hi.mp3")
    asyncio.run(_hi())
    if os.path.getsize("/tmp/bench-hi.mp3") > 1000:
        samples.append(("hi", "/tmp/bench-hi.mp3"))
except Exception as exc:  # noqa: BLE001
    print("BENCH note: no Hindi sample:", exc, flush=True)

print("BENCH cpu:", os.cpu_count(), "threads", flush=True)
for name in models:
    t = time.monotonic()
    m = WhisperModel(name, device="cpu", compute_type="int8")
    load = time.monotonic() - t
    for lang, path in samples:
        t = time.monotonic()
        segs, info = m.transcribe(path, vad_filter=True)
        text = " ".join(s.text.strip() for s in segs)
        dt = time.monotonic() - t
        print(f"BENCH model={name} sample={lang} load={load:.1f}s audio={info.duration:.1f}s "
              f"transcribe={dt:.2f}s rtf={dt / max(info.duration, 0.1):.2f} detected={info.language}"
              f"({info.language_probability:.2f}) text={text[:120]!r}", flush=True)
    del m
