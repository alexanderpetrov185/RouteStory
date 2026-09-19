"""Generates app/src/main/res/raw/bgm.wav: a 16 s seamless ambient loop.

Run from the repository root:  python tools/make_bgm.py

Manual tool, not part of the Gradle build (no runtime dependency, no network, no third
party audio: everything is synthesized from sine partials, so there are no licensing
obligations).

Format is fixed by VideoExportUseCase.encodeBgmAac(): mono, 22050 Hz, 16-bit PCM and a
canonical 44 byte WAV header.

Loop continuity: every frequency is rounded to whole Hz and the loop is a whole number of
seconds, so each sine completes an integer number of cycles; all notes are written with
circular indices, so a decaying tail simply continues at the start of the loop.
"""

import math
import struct
import wave

SR = 22050
SECONDS = 16
N = SR * SECONDS
CHORD_SEC = 2.0

# Am - F - C - G, twice. Rounded to whole Hz on purpose (see docstring).
PROGRESSION = [
    (110, (262, 330, 440)),   # Am
    (87, (220, 262, 349)),    # F
    (131, (330, 392, 523)),   # C
    (98, (247, 294, 392)),    # G
] * 2

buf = [0.0] * N


def env(t, attack, sustain_end, release):
    if t < attack:
        return t / attack
    if t <= sustain_end:
        return 1.0
    return max(0.0, 1.0 - (t - sustain_end) / release)


def add_pad(freq, t0, amp, attack=0.30, release=0.45, partials=(1.0, 0.22, 0.08)):
    """Soft chord voice: slow attack, overlapping release, zero at both ends."""
    sustain_end = CHORD_SEC - 0.05
    total = int((sustain_end + release) * SR)
    start = int(t0 * SR)
    for i in range(total):
        t = i / SR
        e = env(t, attack, sustain_end, release)
        if e <= 0.0:
            continue
        idx = (start + i) % N
        for k, part in enumerate(partials):
            w = 2.0 * math.pi * freq * (k + 1) / SR
            buf[idx] += amp * part * e * math.sin(w * i)


def add_pluck(freq, t0, amp, decay, partials=(1.0, 0.30, 0.12)):
    """Gentle plucked tone: exponential decay, fully faded before the chord ends."""
    length = int(min(decay * 6.0, 1.6) * SR)
    start = int(t0 * SR)
    for i in range(length):
        e = amp * math.exp(-(i / SR) / decay)
        if e < 1e-4:
            break
        idx = (start + i) % N
        for k, part in enumerate(partials):
            w = 2.0 * math.pi * freq * (k + 1) / SR
            buf[idx] += e * part * math.sin(w * i)


for c, (root, tones) in enumerate(PROGRESSION):
    t0 = c * CHORD_SEC
    # Pad: the three upper chord tones.
    for tone in tones:
        add_pad(tone, t0, 0.115)
    # Bass: root on beats 1 and 3.
    add_pluck(root, t0, 0.20, 0.85, partials=(1.0, 0.18, 0.06))
    add_pluck(root, t0 + 1.0, 0.16, 0.75, partials=(1.0, 0.18, 0.06))
    # Light arpeggio on the off-beats (skips two eighths so it stays airy).
    for step in (1, 2, 4, 5, 6):
        tone = tones[step % len(tones)]
        add_pluck(tone, t0 + step * 0.25, 0.10, 0.45)

# One-pole low pass (~4.2 kHz): takes the edge off the partials.
cutoff = 4200.0
alpha = 1.0 - math.exp(-2.0 * math.pi * cutoff / SR)
y = 0.0
for i in range(N):
    y += alpha * (buf[i] - y)
    buf[i] = y

peak = max(abs(v) for v in buf) or 1.0
scale = 0.45 / peak  # about -7 dBFS, no clipping
samples = [int(max(-32767, min(32767, round(v * scale * 32767.0)))) for v in buf]

out = "app/src/main/res/raw/bgm.wav"
with wave.open(out, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(b"".join(struct.pack("<h", s) for s in samples))

delta = max(abs(samples[i] - samples[i - 1]) for i in range(1, N))
wrap = abs(samples[0] - samples[N - 1])
print("wrote %s frames=%d (%.1f s) peak=%d/32767" % (out, N, SECONDS, max(abs(s) for s in samples)))
print("inner max step=%d, loop wrap step=%d (loop is seamless when wrap <= inner)" % (delta, wrap))