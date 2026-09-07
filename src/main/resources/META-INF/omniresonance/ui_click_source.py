# SPDX-License-Identifier: LGPL-3.0-or-later
"""Recreate the approved V4.2 UI-click PCM. Offline authoring source, never executed by the mod."""

import argparse
import array
import math
from pathlib import Path
import sys
import wave


SAMPLE_RATE = 48_000
DURATION = 0.090
PEAK = 10 ** (-11 / 20)


def resonance(t):
    if t <= 0:
        return 0.0
    rise = 1 - math.exp(-t / 0.0025)
    return rise * (
        0.13 * math.sin(2 * math.pi * 2310 * t) * math.exp(-t / 0.019)
        + 0.025 * math.sin(2 * math.pi * 3465 * t) * math.exp(-t / 0.015)
    )


def synthesize():
    samples = []
    count = round(SAMPLE_RATE * DURATION)
    for index in range(count):
        t = index / SAMPLE_RATE
        onset = math.sin(math.pi / 2 * min(1.0, t / 0.00065)) ** 2
        release = math.sin(math.pi / 2 * min(1.0, (count - 1 - index) / (SAMPLE_RATE * 0.010))) ** 2
        electronic = 0.48 * math.sin(2 * math.pi * 1200 * t) * math.exp(-t / 0.008)
        electronic += 0.045 * math.sin(2 * math.pi * 3150 * t) * math.exp(-t / 0.003)
        articulation = 0.30 * math.sin(2 * math.pi * 1320 * t) * math.exp(-t / 0.0022)
        articulation += 0.075 * math.sin(2 * math.pi * 2640 * t) * math.exp(-t / 0.00085)
        articulation += 0.035 * math.sin(2 * math.pi * 330 * t) * math.exp(-t / 0.002)
        halo = resonance(t) + 0.14 * resonance(t - 0.007) + 0.09 * resonance(t - 0.013)
        samples.append((electronic + articulation + halo) * onset * release)
    gain = PEAK / max(abs(value) for value in samples)
    return [value * gain for value in samples]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Destination mono 48 kHz PCM WAV")
    args = parser.parse_args()
    samples = synthesize()
    assert samples[0] == 0 and samples[-1] == 0
    assert all(math.isfinite(value) and abs(value) < 1 for value in samples)
    pcm = array.array("h", (round(value * 32767) for value in samples))
    if sys.byteorder != "little":
        pcm.byteswap()
    with wave.open(str(args.output), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(pcm.tobytes())


if __name__ == "__main__":
    main()
