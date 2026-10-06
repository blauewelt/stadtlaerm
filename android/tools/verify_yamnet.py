#!/usr/bin/env python3
"""Verify the YAMNet model I/O and that the Kotlin classifier pre-processing matches a Python reference.

Usage (from the android/ directory; paths are resolved relative to this script):
    pip install ai-edge-litert scipy numpy
    python3 tools/verify_yamnet.py [--wav some_16k_or_48k.wav] [--workdir verify-out]

Steps:
 1. Load app/src/main/assets/yamnet.tflite, print input/output tensors, check that the label list
    embedded in the model (zip appended to the .tflite) equals app/src/main/assets/yamnet_labels.txt.
 2. Run the model on synthetic signals (silence, 1 kHz tone, white noise).
 3. Build a 48 kHz test signal from a speech clip at -60 dBFS (a realistic phone level), let the
    Kotlin engine produce its classifier window (Gradle test ClassifierPipelineExportTest with
    STADTLAERM_PREPROC_DIR set), and compare it sample by sample with a numpy implementation of the
    same FIR + decimation. Then run the model on the Kotlin window (raw and level-normalised) and on
    a scipy.signal.resample_poly reference and print the top-3 labels.
"""
import argparse
import io
import json
import os
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

import numpy as np
from scipy.io import wavfile
from scipy.signal import firwin, lfilter, resample_poly

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
MODEL = os.path.join(ASSETS, "yamnet.tflite")
N = 15600
DEFAULT_WAV_URL = "https://storage.googleapis.com/audioset/speech_whistling2.wav"


def load_interpreter():
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:  # pragma: no cover
        from tflite_runtime.interpreter import Interpreter
    it = Interpreter(model_path=MODEL)
    it.allocate_tensors()
    return it


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wav", help="speech/test clip (any rate, mono or stereo); default: download YAMNet sample")
    ap.add_argument("--workdir", default=None)
    ap.add_argument("--level-dbfs", type=float, default=-60.0)
    args = ap.parse_args()
    work = args.workdir or tempfile.mkdtemp(prefix="stadtlaerm-verify-")
    os.makedirs(work, exist_ok=True)

    # 1. Model I/O and labels -------------------------------------------------------------
    it = load_interpreter()
    inp = it.get_input_details()[0]
    out = it.get_output_details()[0]
    print(f"input : {inp['name']} shape={[int(v) for v in inp['shape']]} dtype={inp['dtype'].__name__}")
    print(f"output: {out['name']} shape={[int(v) for v in out['shape']]} dtype={out['dtype'].__name__}")
    assert list(inp["shape"]) in ([N], [1, N]), inp["shape"]
    assert list(out["shape"]) == [1, 521], out["shape"]
    embedded = zipfile.ZipFile(io.BytesIO(open(MODEL, "rb").read())).read("yamnet_label_list.txt").decode()
    embedded = embedded.rstrip("\n").split("\n")
    shipped = open(os.path.join(ASSETS, "yamnet_labels.txt"), encoding="utf-8").read().rstrip("\n").split("\n")
    assert embedded == shipped, "shipped label file differs from model metadata"
    assert len(shipped) == 521 and shipped[0] == "Speech"
    print(f"labels: {len(shipped)} (embedded == shipped), [0]={shipped[0]!r}, [320]={shipped[320]!r}")

    def run(x):
        x = np.asarray(x, dtype=np.float32).reshape(inp["shape"])
        it.set_tensor(inp["index"], x)
        it.invoke()
        return it.get_tensor(out["index"])[0]

    def top(s, k=3):
        idx = np.argsort(-s)[:k]
        return ", ".join(f"{shipped[i]}={s[i]:.2f}" for i in idx)

    # 2. Synthetic signals ------------------------------------------------------------------
    t = np.arange(N) / 16000.0
    rng = np.random.default_rng(0)
    print("silence          :", top(run(np.zeros(N))))
    print("1 kHz tone -6dBFS:", top(run(0.5 * np.sin(2 * np.pi * 1000 * t))))
    print("white noise -20  :", top(run(0.1 * rng.standard_normal(N))))

    # 3. Kotlin pipeline cross-check -------------------------------------------------------------
    wav = args.wav
    if wav is None:
        wav = os.path.join(work, "speech.wav")
        if not os.path.exists(wav):
            urllib.request.urlretrieve(DEFAULT_WAV_URL, wav)
    sr, x = wavfile.read(wav)
    x = x.astype(np.float64)
    if x.ndim > 1:
        x = x.mean(axis=1)
    x /= 32768.0 if np.abs(x).max() > 1.5 else 1.0
    if sr != 48000:
        from math import gcd
        g = gcd(sr, 48000)
        x = resample_poly(x, 48000 // g, sr // g)
    x = x[: 48000 * 3]
    x48 = x / np.sqrt(np.mean(x ** 2)) * 10 ** (args.level_dbfs / 20)
    x48 = x48.astype("<f4")
    x48.tofile(os.path.join(work, "input48k.f32"))

    env = dict(os.environ, STADTLAERM_PREPROC_DIR=work)
    cmd = [os.path.join(ROOT, "gradlew"), "-q", ":dsp:test", "--tests",
           "ch.stadtlaerm.dsp.ClassifierPipelineExportTest"]
    subprocess.run(cmd, cwd=ROOT, env=env, check=True)
    kraw = np.fromfile(os.path.join(work, "kotlin_window_raw.f32"), dtype="<f4")
    knorm = np.fromfile(os.path.join(work, "kotlin_window_norm.f32"), dtype="<f4")
    meta = json.load(open(os.path.join(work, "kotlin_meta.json")))
    assert len(kraw) == N and meta["length"] == N

    h = firwin(241, 7500, fs=48000, window=("kaiser", 7.0))
    y = lfilter(h, 1.0, x48.astype(np.float64))[2::3]
    end = meta["endSample"] // 3
    ref = y[end - N:end]
    diff = np.max(np.abs(ref - kraw))
    rel = diff / np.max(np.abs(ref))
    print(f"kotlin window vs numpy FIR+decimate: max|diff|={diff:.3g} (relative {rel:.2g})")
    assert rel < 1e-4, "Kotlin decimation does not match the reference"

    rp = resample_poly(x48.astype(np.float64), 1, 3)[end - N:end]
    gain = 10 ** (meta["gainDb"] / 20)
    rp_norm = np.clip(rp * gain, -1, 1)
    print(f"normalisation gain applied in Kotlin: {meta['gainDb']:.1f} dB "
          f"(window RMS {20*np.log10(np.sqrt(np.mean(kraw.astype(np.float64)**2))):.1f} dBFS)")
    s_raw, s_norm, s_rp = run(kraw), run(knorm), run(rp_norm)
    print("kotlin raw  (-60 dBFS):", top(s_raw))
    print("kotlin normalised     :", top(s_norm))
    print("scipy resample_poly+norm:", top(s_rp))
    print(f"max |score diff| kotlin-normalised vs scipy reference: {np.max(np.abs(s_norm - s_rp)):.3f}")
    print("OK")


if __name__ == "__main__":
    sys.exit(main())
