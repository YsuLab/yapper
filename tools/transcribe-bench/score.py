#!/usr/bin/env python3
"""Score transcribers on the owner's real captures (M1 benchmark).

Each Inbox note written by Yapper in benchmark mode holds one `## Transcript · <provider> · <s> s`
section per transcriber. To score a clip, add a `## Reference` section to the note with the exact
words that were said (copy the best transcript and fix it). Then:

    python3 tools/transcribe-bench/score.py <path-to-vault>/Inbox

Prints a markdown table: per provider CER and WER (lower is better) after Arabic normalization,
median latency, and failures. Pure standard library.
"""
from __future__ import annotations

import re
import statistics
import sys
import unicodedata
from pathlib import Path

SECTION = re.compile(r"^## (.+)$", re.M)
TRANSCRIPT = re.compile(r"^Transcript · (.+?) · (?:(\d+(?:\.\d+)?) s|(failed))$")
MARKER = re.compile(r"%%yap:[^%]*%%")
DIACRITICS = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭ]")
PUNCT = re.compile(r"[^\w\s]", re.UNICODE)
TRANS = str.maketrans({
    "أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا",
    "ى": "ي", "ة": "ه", "ـ": "",
    **{chr(0x0660 + i): str(i) for i in range(10)},  # Arabic-Indic digits
    **{chr(0x06F0 + i): str(i) for i in range(10)},  # Eastern Arabic-Indic digits
    "،": " ", "؛": " ", "؟": " ",
})


def normalize(text: str) -> str:
    t = unicodedata.normalize("NFKC", MARKER.sub(" ", text))
    t = DIACRITICS.sub("", t).translate(TRANS).lower()
    t = PUNCT.sub(" ", t).replace("_", " ")
    return " ".join(t.split())


def edit_distance(a, b) -> int:
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def error_rate(ref, hyp) -> float:
    if not ref:
        return 0.0 if not hyp else 1.0
    return edit_distance(ref, hyp) / len(ref)


def wer(ref: str, hyp: str) -> float:
    return error_rate(normalize(ref).split(), normalize(hyp).split())


def cer(ref: str, hyp: str) -> float:
    return error_rate(normalize(ref).replace(" ", ""), normalize(hyp).replace(" ", ""))


def parse_note(md: str):
    """Returns (reference or None, [(provider, latency_s or None, text, failed)])."""
    parts = SECTION.split(md)
    reference = None
    transcripts = []
    for i in range(1, len(parts) - 1, 2):
        title, body = parts[i].strip(), MARKER.sub("", parts[i + 1]).strip()
        if title.lower() == "reference":
            reference = body
            continue
        m = TRANSCRIPT.match(title)
        if m:
            failed = m.group(3) is not None
            latency = None if failed else float(m.group(2))
            transcripts.append((m.group(1), latency, "" if failed else body, failed))
    return reference, transcripts


def score(folder: Path) -> str:
    rows: dict[str, dict] = {}
    clips = 0
    for note in sorted(folder.glob("*.md")):
        md = note.read_text(encoding="utf-8")
        if "yapper: capture" not in md:
            continue
        reference, transcripts = parse_note(md)
        if reference is not None:
            clips += 1
        for provider, latency, text, failed in transcripts:
            r = rows.setdefault(provider, {"cer": [], "wer": [], "lat": [], "fail": 0, "runs": 0})
            r["runs"] += 1
            if failed:
                r["fail"] += 1
                continue
            r["lat"].append(latency)
            if reference is not None:
                r["cer"].append(cer(reference, text))
                r["wer"].append(wer(reference, text))
    lines = [
        f"Clips with a reference: {clips}",
        "",
        "| Provider | Scored | CER | WER | Median latency | Failures |",
        "|---|---|---|---|---|---|",
    ]
    def key(item):
        c = item[1]["cer"]
        return statistics.mean(c) if c else 9.0
    for provider, r in sorted(rows.items(), key=key):
        c = f"{statistics.mean(r['cer']):.1%}" if r["cer"] else "–"
        w = f"{statistics.mean(r['wer']):.1%}" if r["wer"] else "–"
        lat = f"{statistics.median(r['lat']):.1f} s" if r["lat"] else "–"
        lines.append(f"| {provider} | {len(r['cer'])} | {c} | {w} | {lat} | {r['fail']}/{r['runs']} |")
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    print(score(Path(argv[1])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
