"""Convert OpenGameArt CC0 'Camera' / photo.ogg to mono 22050 Hz WAV.
Source: https://opengameart.org/content/camera (CC0).
No synthesis: the original shutter waveform is preserved.
"""
import subprocess
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "tools" / "shutter_cc0_src.ogg"
OUT = ROOT / "app" / "src" / "main" / "res" / "raw" / "shutter.wav"
TMP = ROOT / "tools" / "_shutter_cc0.wav"

def main():
    subprocess.run(["ffmpeg", "-y", "-i", str(SRC), "-ac", "1", "-ar", "22050", "-c:a", "pcm_s16le", str(TMP)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with wave.open(str(TMP), "rb") as src:
        n = src.getnframes(); rate = src.getframerate(); data = src.readframes(n)
    with wave.open(str(OUT), "wb") as out:
        out.setnchannels(1); out.setsampwidth(2); out.setframerate(22050); out.writeframes(data)
    TMP.unlink(missing_ok=True)
    print(f"wrote {OUT} frames={n} rate={rate} duration={n/rate:.3f}s (CC0 source)")

if __name__ == "__main__": main()


