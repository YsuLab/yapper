import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import run_eval  # noqa: E402


def resp(*calls):
    return {"choices": [{"message": {"tool_calls": [
        {"function": {"name": n, "arguments": json.dumps(a, ensure_ascii=False)}} for n, a in calls]}}]}


class ScoreTest(unittest.TestCase):

    def test_cases_file_is_valid(self):
        cases = run_eval.load_cases(os.path.join(run_eval.EVALS, "cases.jsonl"))
        self.assertGreaterEqual(len(cases), 20)
        self.assertEqual(len(cases), len({c["id"] for c in cases}))

    def test_request_uses_app_assets(self):
        case = run_eval.load_cases(os.path.join(run_eval.EVALS, "cases.jsonl"))[0]
        body = run_eval.request(case, "anthropic/claude-sonnet-5.5")
        sys_text = body["messages"][0]["content"][0]["text"]
        self.assertNotIn("{{", sys_text)
        self.assertIn("Monday", sys_text)
        self.assertIn("Tabby/Learning.md", sys_text)
        self.assertIn("- Mon 2026-11-09: Fajr ", sys_text)
        self.assertIn("Fri 2026-11-13", sys_text)
        self.assertNotIn("Tue 2026-11-17", sys_text)  # 8 days only
        self.assertEqual(9, len(body["tools"]))
        self.assertIn("cache_control", body["messages"][0]["content"][0])
        self.assertNotIn("cache_control", run_eval.request(case, "qwen/x")["messages"][0]["content"][0])

    def test_reminder_time_compared_as_instant(self):
        case = {"expect": [{"tool": "create_reminder", "remind_at": "2026-11-10T09:00:00+03:00"}]}
        ok, _ = run_eval.score(case, run_eval.actions(resp(("create_reminder", {"title": "x", "remind_at": "2026-11-10T06:00:00Z"}))))
        self.assertTrue(ok)
        ok, why = run_eval.score(case, run_eval.actions(resp(("create_reminder", {"title": "x", "remind_at": "2026-11-10T21:00:00+03:00"}))))
        self.assertFalse(ok)
        self.assertIn("missing", why[0])

    def test_paths_and_forbidden(self):
        case = {"expect": [{"tool": "append_to_note", "path": "Uni/Algorithms.md"}],
                "forbid": [{"text": "hunter2"}]}
        ok, _ = run_eval.score(case, run_eval.actions(resp(("append_to_note", {"path": "[[Uni/Algorithms]]", "markdown": "x"}))))
        self.assertTrue(ok)
        ok, why = run_eval.score(case, run_eval.actions(resp(
            ("append_to_note", {"path": "Uni/Algorithms.md", "markdown": "pw hunter2"}))))
        self.assertFalse(ok)
        self.assertIn("forbidden", why[0])

    def test_prefix_match(self):
        case = {"expect": [{"tool": "create_note", "path_prefix": "Projects/"}]}
        self.assertTrue(run_eval.score(case, [{"tool": "create_note", "path": "Projects/Nawa"}])[0])
        self.assertFalse(run_eval.score(case, [{"tool": "create_note", "path": "Personal/Nawa"}])[0])


if __name__ == "__main__":
    unittest.main()
