#!/usr/bin/env python3
"""Generates a deterministic OPERA DSP demo input signal."""

import argparse
import math
import pathlib
import sys


_NUM_POINTS = 256
_DEFAULT_SAMPLES = 4096
_MAX_SAMPLES = 4096
_TONE_BINS = (37, 123, 211)


def _sample_count(value: str) -> int:
    """Parses and validates the requested number of samples."""
    try:
        samples = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            f"sample count is not an integer: {value!r}"
        ) from error

    if samples <= 0 or samples > _MAX_SAMPLES:
        raise argparse.ArgumentTypeError(
            f"sample count must be between 1 and {_MAX_SAMPLES}"
        )
    if samples % _NUM_POINTS:
        raise argparse.ArgumentTypeError(
            f"sample count must be a multiple of {_NUM_POINTS}"
        )
    return samples


def _next_noise(state: int) -> tuple[int, float]:
    """Advances the 32-bit LCG and returns its scaled noise sample."""
    state = (state * 1664525 + 1013904223) & 0xFFFFFFFF
    unit = ((state >> 8) & 0xFFFF) / 65535.0
    return state, (unit - 0.5) * 0.02


def _write_signal(path: pathlib.Path, samples: int) -> None:
    """Writes the configured real-valued tone and noise samples."""
    noise_state = 1
    with path.open("w", encoding="utf-8", newline="\n") as output:
        output.write("# real imag\n")
        for index in range(samples):
            point = index % _NUM_POINTS
            phases = [
                2.0 * math.pi * tone_bin * point / _NUM_POINTS
                for tone_bin in _TONE_BINS
            ]
            real = (
                0.45 * math.sin(phases[0])
                + 0.25 * math.sin(phases[1])
                + 0.12 * math.cos(phases[2])
            )
            noise_state, noise = _next_noise(noise_state)
            output.write(f"{real + noise:.9f} {0.0:.9f}\n")


def _parse_args() -> argparse.Namespace:
    """Parses command-line arguments."""
    parser = argparse.ArgumentParser(
        description="Generate a deterministic OPERA DSP demo input signal."
    )
    parser.add_argument(
        "output",
        nargs="?",
        type=pathlib.Path,
        default=pathlib.Path("tx_file.txt"),
        help="output text file (default: tx_file.txt)",
    )
    parser.add_argument(
        "sample_count",
        nargs="?",
        type=_sample_count,
        default=_DEFAULT_SAMPLES,
        help=f"positive multiple of {_NUM_POINTS} up to {_MAX_SAMPLES}",
    )
    return parser.parse_args()


def main() -> int:
    """Runs the signal generator."""
    args = _parse_args()
    try:
        _write_signal(args.output, args.sample_count)
    except OSError as error:
        print(f"{args.output}: {error}", file=sys.stderr)
        return 1

    print(f"[generate] wrote {args.sample_count} samples to {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
