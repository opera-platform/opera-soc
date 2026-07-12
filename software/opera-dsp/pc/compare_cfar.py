#!/usr/bin/env python3
"""Compares FPGA CFAR CSV output with bit-exact golden-model words."""

import argparse
import csv
import pathlib
import sys


_MAX_MISMATCH_PRINTS = 16
_REQUIRED_COLUMNS = frozenset(("frame", "bin", "raw"))
_MAX_WORD = (1 << 64) - 1


def _parse_word(text: str, base: int, description: str) -> int:
    """Parses one unsigned 64-bit word with a descriptive error."""
    try:
        word = int(text, base)
    except (TypeError, ValueError) as error:
        raise ValueError(f"invalid {description}: {text!r}") from error
    if not 0 <= word <= _MAX_WORD:
        raise ValueError(f"{description} is outside the 64-bit range: {text!r}")
    return word


def _read_expected(path: pathlib.Path) -> list[int]:
    """Reads hexadecimal golden words from a text file."""
    words = []
    with path.open("r", encoding="utf-8") as expected_file:
        for line_number, line in enumerate(expected_file, start=1):
            try:
                words.append(
                    _parse_word(line.strip(), 16, "expected hexadecimal word")
                )
            except ValueError as error:
                raise ValueError(
                    f"malformed expected word at line {line_number}: {error}"
                ) from error
    return words


def _read_actual(path: pathlib.Path) -> list[tuple[int, int, int]]:
    """Reads frame, bin, and raw word fields from the FPGA CSV file."""
    records = []
    with path.open("r", encoding="utf-8", newline="") as actual_file:
        reader = csv.DictReader(actual_file)
        if reader.fieldnames is None:
            raise ValueError("FPGA CSV is empty")

        missing = _REQUIRED_COLUMNS.difference(reader.fieldnames)
        if missing:
            columns = ", ".join(sorted(missing))
            raise ValueError(f"FPGA CSV is missing required columns: {columns}")

        for row in reader:
            try:
                frame = int(row["frame"], 10)
                bin_index = int(row["bin"], 10)
                word = _parse_word(row["raw"], 0, "FPGA raw word")
            except (TypeError, ValueError) as error:
                raise ValueError(
                    "malformed FPGA CSV record at line "
                    f"{reader.line_num}: {error}"
                ) from error
            if frame < 0 or bin_index < 0:
                raise ValueError(
                    "malformed FPGA CSV record at line "
                    f"{reader.line_num}: frame and bin must be non-negative"
                )
            records.append((frame, bin_index, word))
    return records


def _compare(expected: list[int], actual: list[tuple[int, int, int]]) -> int:
    """Reports all length errors and bounded word mismatches."""
    records = min(len(expected), len(actual))
    mismatches = 0
    for index in range(records):
        frame, bin_index, actual_word = actual[index]
        expected_word = expected[index]
        if expected_word == actual_word:
            continue
        if mismatches < _MAX_MISMATCH_PRINTS:
            print(
                f"[compare] mismatch record={index} frame={frame} "
                f"bin={bin_index} "
                f"expected=0x{expected_word:016x} actual=0x{actual_word:016x}",
                file=sys.stderr,
            )
        mismatches += 1

    if len(actual) < len(expected):
        print(
            f"[compare] FPGA CSV ended after {len(actual)} records",
            file=sys.stderr,
        )
        mismatches += 1
    elif len(actual) > len(expected):
        print(
            f"[compare] FPGA CSV has extra records after {len(expected)}",
            file=sys.stderr,
        )
        mismatches += 1

    if mismatches:
        print(
            f"[compare] FAIL records={records} mismatches={mismatches}",
            file=sys.stderr,
        )
        return 1

    print(f"[compare] PASS: {records} CFAR words match bit-exactly")
    return 0


def _parse_args() -> argparse.Namespace:
    """Parses command-line arguments."""
    parser = argparse.ArgumentParser(
        description="Compare FPGA CFAR output with bit-exact golden words."
    )
    parser.add_argument(
        "expected", type=pathlib.Path, help="golden expected.hex"
    )
    parser.add_argument("actual", type=pathlib.Path, help="FPGA output CSV")
    return parser.parse_args()


def main() -> int:
    """Loads the result files and returns the comparison status."""
    args = _parse_args()
    try:
        expected = _read_expected(args.expected)
        actual = _read_actual(args.actual)
    except (OSError, ValueError, csv.Error) as error:
        print(f"[compare] {error}", file=sys.stderr)
        return 1
    return _compare(expected, actual)


if __name__ == "__main__":
    sys.exit(main())
