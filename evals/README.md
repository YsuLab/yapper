# Sorter eval

`cases.jsonl`: one capture per line (`transcript`, optional `screen_text`, `now`) with the actions that must
appear (`expect`) and must not (`forbid`). Matching: `tool`, exact `path` or `path_prefix`, `remind_at` (same
instant), `date`, or `text` anywhere in the call. Extra actions are allowed.

`vault-index.md` is the fake vault the cases route into. Rules = the app's starter `rules.md`.

```
OPENROUTER_API_KEY=... python3 tools/sorter-eval/run_eval.py --model anthropic/claude-sonnet-5.5
python3 tools/sorter-eval/run_eval.py --url https://gpu.tailnet.ts.net/v1 --model qwen3.6-35b-a3b
```

The 20 starter cases are synthetic. Replace them over time with real captures (copy the transcript from a capture
note, write what should have happened). A model must score ≥ 90 % to be the default.
