import tempfile
import unittest
from pathlib import Path

import score

NOTE = """---
yapper: capture
yap_id: 20261109-112305-ab12
---
%%yap:20261109-112305-ab12%%
![[20261109-112305-ab12.ogg]]

## Transcript · OpenAI gpt-4o-transcribe · 2.3 s
%%yap:20261109-112305-ab12%%
ذكّرني بُكرة الساعة ١٠ عن الـ meeting.

## Transcript · Groq whisper-large-v3 · 0.6 s
%%yap:20261109-112305-ab12%%
ذكرني بكره الساعه عشرة عن الميتنق

## Transcript · ElevenLabs scribe_v2 · failed
%%yap:20261109-112305-ab12%%
_HTTP 401_

## Reference
ذكرني بكرة الساعة 10 عن ال meeting
"""


class ScoreTest(unittest.TestCase):
    def test_normalize_arabic(self):
        self.assertEqual(score.normalize("أَهلاً، إلى الـ Meeting؟ ٣"), "اهلا الي ال meeting 3")
        self.assertEqual(score.normalize("%%yap:x%% ة ى"), "ه ي")

    def test_rates(self):
        self.assertEqual(score.wer("a b c", "a b c"), 0.0)
        self.assertAlmostEqual(score.wer("a b c d", "a x c d"), 0.25)
        self.assertEqual(score.cer("", ""), 0.0)
        self.assertEqual(score.cer("", "x"), 1.0)

    def test_parse_note(self):
        ref, ts = score.parse_note(NOTE)
        self.assertEqual(ref, "ذكرني بكرة الساعة 10 عن ال meeting")
        self.assertEqual([t[0] for t in ts],
                         ["OpenAI gpt-4o-transcribe", "Groq whisper-large-v3", "ElevenLabs scribe_v2"])
        self.assertEqual(ts[0][1], 2.3)
        self.assertTrue(ts[2][3])

    def test_score_table_ranks_best_first(self):
        with tempfile.TemporaryDirectory() as d:
            Path(d, "Capture 1.md").write_text(NOTE, encoding="utf-8")
            Path(d, "Other note.md").write_text("# not a capture", encoding="utf-8")
            out = score.score(Path(d))
        lines = out.splitlines()
        self.assertEqual(lines[0], "Clips with a reference: 1")
        self.assertTrue(lines[4].startswith("| OpenAI gpt-4o-transcribe | 1 | 0.0% | 0.0% | 2.3 s | 0/1 |"))
        self.assertIn("| ElevenLabs scribe_v2 | 0 | – | – | – | 1/1 |", out)


if __name__ == "__main__":
    unittest.main()
