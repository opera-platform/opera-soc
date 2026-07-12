#!/usr/bin/env python3
"""Plots CUT, threshold, and detected peaks from OPERA DSP CFAR output."""

import argparse
import csv
import pathlib
import sys
from collections.abc import Sequence


_REQUIRED_COLUMNS = frozenset(("frame", "bin", "cut", "threshold", "peak"))


def _nonnegative_integer(value: str) -> int:
    """Parses a non-negative integer command-line argument."""
    try:
        number = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            f"expected an integer, got {value!r}"
        ) from error
    if number < 0:
        raise argparse.ArgumentTypeError("frame must be non-negative")
    return number


def _read_frames(
    path: pathlib.Path,
) -> dict[int, list[tuple[int, float, float, bool]]]:
    """Reads CFAR records and groups them by frame number."""
    frames: dict[int, list[tuple[int, float, float, bool]]] = {}
    with path.open("r", encoding="utf-8", newline="") as input_file:
        reader = csv.DictReader(input_file)
        if reader.fieldnames is None:
            raise ValueError("CFAR output is empty")

        missing = _REQUIRED_COLUMNS.difference(reader.fieldnames)
        if missing:
            columns = ", ".join(sorted(missing))
            raise ValueError(f"CFAR CSV is missing required columns: {columns}")

        for row in reader:
            try:
                frame = int(row["frame"], 10)
                bin_index = int(row["bin"], 10)
                cut = float(row["cut"])
                threshold = float(row["threshold"])
                peak_value = int(row["peak"], 10)
            except (TypeError, ValueError) as error:
                raise ValueError(
                    f"malformed CFAR record at line {reader.line_num}: {error}"
                ) from error

            if frame < 0 or bin_index < 0:
                raise ValueError(
                    f"malformed CFAR record at line {reader.line_num}: "
                    "frame and bin must be non-negative"
                )
            if peak_value not in (0, 1):
                raise ValueError(
                    f"malformed CFAR record at line {reader.line_num}: "
                    "peak must be zero or one"
                )
            frames.setdefault(frame, []).append(
                (bin_index, cut, threshold, bool(peak_value))
            )

    if not frames:
        raise ValueError("CFAR output has no records")

    for records in frames.values():
        records.sort(key=lambda record: record[0])
    return frames


def _select_frames(
    frames: dict[int, list[tuple[int, float, float, bool]]],
    requested: Sequence[int],
) -> list[int]:
    """Selects requested frames or the first available frame by default."""
    selected = list(dict.fromkeys(requested)) if requested else [min(frames)]
    missing = [frame for frame in selected if frame not in frames]
    if missing:
        available = ", ".join(str(frame) for frame in sorted(frames))
        absent = ", ".join(str(frame) for frame in missing)
        raise ValueError(
            f"requested frame(s) {absent} not found; available frames: {available}"
        )
    return selected


def _plot_frames(
    frames: dict[int, list[tuple[int, float, float, bool]]],
    selected: Sequence[int],
    output: pathlib.Path,
    show: bool,
) -> None:
    """Plots selected CFAR frames and writes the resulting image."""
    try:
        import matplotlib.pyplot as plt  # pylint: disable=import-outside-toplevel
    except ImportError as error:
        raise RuntimeError(
            "matplotlib is required; install it in the opera-soc environment"
        ) from error

    figure, axes = plt.subplots(
        len(selected),
        1,
        figsize=(12, max(4, 3.5 * len(selected))),
        squeeze=False,
        sharex=True,
    )

    for axis, frame in zip(axes[:, 0], selected):
        records = frames[frame]
        bins = [record[0] for record in records]
        cuts = [record[1] for record in records]
        thresholds = [record[2] for record in records]
        peak_bins = [record[0] for record in records if record[3]]
        peak_cuts = [record[1] for record in records if record[3]]

        axis.plot(bins, cuts, label="CUT", color="tab:blue", linewidth=1.2)
        axis.plot(
            bins,
            thresholds,
            label="Threshold",
            color="tab:orange",
            linewidth=1.2,
        )
        axis.scatter(
            peak_bins,
            peak_cuts,
            label="Detection",
            color="tab:red",
            marker="x",
            s=40,
            zorder=3,
        )
        axis.set_title(f"CFAR frame {frame}: {len(peak_bins)} detections")
        axis.set_ylabel("CFAR value")
        axis.grid(True, alpha=0.25)
        axis.legend(loc="best")

    axes[-1, 0].set_xlabel("FFT bin")
    figure.tight_layout()
    output.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output, dpi=160)
    print(f"[plot] wrote {output}")

    if show:
        plt.show()
    plt.close(figure)


def _parse_args() -> argparse.Namespace:
    """Parses command-line arguments."""
    parser = argparse.ArgumentParser(
        description="Plot CUT, threshold, and detections from CFAR output CSV."
    )
    parser.add_argument(
        "input",
        type=pathlib.Path,
        help="CFAR CSV produced by opera_dsp_pc",
    )
    parser.add_argument(
        "--frame",
        action="append",
        default=[],
        type=_nonnegative_integer,
        help="frame to plot; repeat for multiple frames (default: first frame)",
    )
    parser.add_argument(
        "--output",
        type=pathlib.Path,
        help="output image (default: input name with .png suffix)",
    )
    parser.add_argument(
        "--show",
        action="store_true",
        help="also open the plot in an interactive window",
    )
    return parser.parse_args()


def main() -> int:
    """Loads the CFAR output and creates the requested plot."""
    args = _parse_args()
    output = args.output or args.input.with_suffix(".png")
    try:
        frames = _read_frames(args.input)
        selected = _select_frames(frames, args.frame)
        _plot_frames(frames, selected, output, args.show)
    except (OSError, RuntimeError, ValueError, csv.Error) as error:
        print(f"[plot] {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
