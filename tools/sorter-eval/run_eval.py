#!/usr/bin/env python3
"""Sorter eval: runs evals/cases.jsonl against any OpenAI-compatible chat endpoint and scores routing.

Uses the app's own prompt and tool files (app/src/main/assets/sorter/), so the eval tests exactly
what the phone sends. Stdlib only.

    OPENROUTER_API_KEY=... python3 tools/sorter-eval/run_eval.py --model anthropic/claude-sonnet-5.5
    python3 tools/sorter-eval/run_eval.py --url https://gpu.tailnet.ts.net/v1 --model qwen3.6-35b-a3b

A case passes when every expected action is present and nothing forbidden is. Extra actions are
fine (e.g. a reminder that also adds a note). Bar for M2 (and for a self-hosted model): >= 90 %.
"""
import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "sorter")
EVALS = os.path.join(ROOT, "evals")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def system_prompt(now, rules, index, daily_path, daily):
    t = read(os.path.join(ASSETS, "system.md"))
    return (t.replace("{{NOW}}", now.isoformat())
            .replace("{{ZONE}}", "Asia/Riyadh")
            .replace("{{WEEKDAY}}", now.strftime("%A"))
            .replace("{{RULES}}", rules.strip() or "(empty)")
            .replace("{{INDEX}}", index.strip() or "(empty)")
            .replace("{{DAILY_PATH}}", daily_path)
            .replace("{{DAILY}}", daily.strip() or "(empty)"))


def user_text(transcript, screen_text):
    # Mirrors SorterPrompt.userText in the app.
    t = transcript.strip() or "(no speech)"
    s = screen_text.strip() or "(none)"
    return ("## Transcript\n" + t + "\n\n"
            "## Screen text (what was on the phone screen when he started talking)\n" + s + "\n")


def request(case, model):
    now = dt.datetime.fromisoformat(case["now"])
    rules = case.get("rules") or read(os.path.join(ASSETS, "starter-rules.md"))
    index = case.get("index") or read(os.path.join(EVALS, "vault-index.md"))
    daily_path = "Daily/%s.md" % now.date().isoformat()
    sys_part = {"type": "text", "text": system_prompt(now, rules, index, daily_path, case.get("daily", ""))}
    if model.startswith("anthropic/"):
        sys_part["cache_control"] = {"type": "ephemeral"}
    return {
        "model": model,
        "messages": [
            {"role": "system", "content": [sys_part]},
            {"role": "user", "content": [{"type": "text", "text": user_text(case["transcript"], case.get("screen_text", ""))}]},
        ],
        "tools": json.loads(read(os.path.join(ASSETS, "tools.json"))),
        "tool_choice": "auto",
        "temperature": 0.2,
        "max_tokens": 8000,
    }


def call(url, key, body):
    req = urllib.request.Request(url.rstrip("/") + "/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", "X-Title": "Yapper eval"})
    if key:
        req.add_header("Authorization", "Bearer " + key)
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.loads(r.read().decode())


def actions(response):
    out = []
    msg = response["choices"][0]["message"]
    for c in msg.get("tool_calls") or []:
        fn = c.get("function") or {}
        args = fn.get("arguments") or "{}"
        try:
            args = json.loads(args) if isinstance(args, str) else args
        except json.JSONDecodeError:
            args = {}
        out.append({"tool": fn.get("name", ""), **args})
    return out


def norm_path(p):
    p = (p or "").strip().strip("/").replace("\\", "/")
    if p.startswith("[[") and p.endswith("]]"):
        p = p[2:-2]
    return p if p.lower().endswith(".md") else p + ".md"


def same_instant(a, b):
    try:
        return dt.datetime.fromisoformat(a.replace("Z", "+00:00")) == dt.datetime.fromisoformat(b.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return False


def matches(act, exp):
    if "tool" in exp and act.get("tool") != exp["tool"]:
        return False
    if "path" in exp and norm_path(act.get("path")) != exp["path"]:
        return False
    if "path_prefix" in exp and not norm_path(act.get("path")).startswith(exp["path_prefix"]):
        return False
    if "remind_at" in exp and not same_instant(act.get("remind_at", ""), exp["remind_at"]):
        return False
    if "date" in exp and act.get("date") != exp["date"]:
        return False
    if "text" in exp and exp["text"] not in json.dumps(act, ensure_ascii=False):
        return False
    return True


def score(case, acts):
    """Returns (passed, reasons)."""
    reasons = []
    for exp in case.get("expect", []):
        if not any(matches(a, exp) for a in acts):
            reasons.append("missing " + json.dumps(exp, ensure_ascii=False))
    for bad in case.get("forbid", []):
        if any(matches(a, bad) for a in acts):
            reasons.append("forbidden " + json.dumps(bad, ensure_ascii=False))
    return (not reasons), reasons


def load_cases(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=os.environ.get("SORTER_URL", "https://openrouter.ai/api/v1"))
    ap.add_argument("--model", default="anthropic/claude-sonnet-5.5")
    ap.add_argument("--cases", default=os.path.join(EVALS, "cases.jsonl"))
    ap.add_argument("--only", help="run one case id")
    args = ap.parse_args()
    key = os.environ.get("SORTER_API_KEY") or os.environ.get("OPENROUTER_API_KEY", "")
    cases = [c for c in load_cases(args.cases) if not args.only or c["id"] == args.only]
    passed = 0
    for c in cases:
        t = time.time()
        try:
            acts = actions(call(args.url, key, request(c, args.model)))
            ok, why = score(c, acts)
        except (urllib.error.URLError, KeyError, IndexError, ValueError) as e:
            ok, why, acts = False, ["error: %s" % e], []
        passed += ok
        print("%s %-22s %5.1fs %s" % ("PASS" if ok else "FAIL", c["id"], time.time() - t, "; ".join(why)))
        if not ok:
            for a in acts:
                print("       ", json.dumps(a, ensure_ascii=False)[:200])
    pct = 100.0 * passed / max(1, len(cases))
    print("\n%s: %d/%d = %.0f %% (bar: 90 %%)" % (args.model, passed, len(cases), pct))
    return 0 if pct >= 90 else 1


if __name__ == "__main__":
    sys.exit(main())
