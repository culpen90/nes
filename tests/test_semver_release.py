#!/usr/bin/env python3
"""Release state-machine regressions; no GitHub account or network required."""

import importlib.util
from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "tools" / "semver_release.py"
SPEC = importlib.util.spec_from_file_location("semver_release", SCRIPT)
release = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release
SPEC.loader.exec_module(release)


def sha(number):
    return f"{number:040x}"


def published(tag, number=1, **overrides):
    return {"id": number, "tag_name": tag, "draft": False, "prerelease": "-beta." in tag, "target_commitish": "main", **overrides}


def pr(number, commit, title="fix: repair input", body="", **overrides):
    return {"number": number, "merge_commit_sha": commit, "title": title, "body": body, "merged_at": "2026-10-08T00:00:00Z", "base": {"ref": "main"}, "html_url": f"https://github.com/culpen90/nes/pull/{number}", "user": {"login": "outside-contributor"}, **overrides}


class PlannerTests(unittest.TestCase):
    def plan(self, *, head=5, commits=None, releases=None, tags=None, prs=None):
        return release.make_plan(
            head_sha=sha(head),
            first_parent_commits=commits if commits is not None else [sha(i) for i in range(1, head + 1)],
            releases=releases if releases is not None else [published("v1.0.0-beta.1")],
            tag_commits=tags if tags is not None else {"v1.0.0-beta.1": sha(2)},
            prs=prs or [],
            notes_file="release-notes.md",
            commit_subjects={sha(4): "docs: clarify saves"},
        )

    def stable_history(self):
        return [published("v1.0.0-beta.1"), published("v1.0.0", 2)], {"v1.0.0-beta.1": sha(2), "v1.0.0": sha(3)}

    def test_beta_stays_beta_even_for_breaking_changes(self):
        plan = self.plan(prs=[pr(8, sha(5), "feat(core)!: replace save format", "BREAKING CHANGE: saves migrate")])
        self.assertEqual(plan["tag"], "v1.0.0-beta.2")
        self.assertTrue(plan["prerelease"])
        self.assertEqual(plan["version_code"], 4)

    def test_later_beta_increments_latest_beta_number(self):
        plan = self.plan(releases=[published("v1.0.0-beta.1"), published("v1.0.0-beta.8", 2)], tags={"v1.0.0-beta.1": sha(2), "v1.0.0-beta.8": sha(4)})
        self.assertEqual(plan["tag"], "v1.0.0-beta.9")

    def test_any_contributor_can_authorize_first_stable_with_only_marker(self):
        gate = pr(42, sha(4), "docs: readiness evidence", "[release:stable]")
        plan = self.plan(prs=[gate, pr(43, sha(5), "feat!: large change")])
        self.assertEqual(plan["tag"], "v1.0.0")
        self.assertFalse(plan["prerelease"])
        self.assertEqual(plan["stable_gate_prs"], [42])
        self.assertEqual(plan["version_code"], 4)

    def test_skipped_push_backlog_keeps_an_earlier_gate(self):
        plan = self.plan(head=8, prs=[pr(9, sha(3), body="[release:stable]")])
        self.assertEqual(plan["tag"], "v1.0.0")
        self.assertEqual(plan["unreleased_commit_count"], 6)
        self.assertEqual(plan["stable_gate_prs"], [9])

    def test_stable_default_patch_feature_minor_and_breaking_major(self):
        releases, tags = self.stable_history()
        for title, body, expected in [
            ("docs: update guide", "", "v1.0.1"),
            ("feat: add controller support", "", "v1.1.0"),
            ("feat(input): add controller support", "", "v1.1.0"),
            ("fix!: replace save layout", "", "v2.0.0"),
            ("fix(core)!: replace saves", "", "v2.0.0"),
            ("fix: repair save import", "BREAKING CHANGE: old format removed", "v2.0.0"),
        ]:
            with self.subTest(title=title, body=body):
                plan = self.plan(releases=releases, tags=tags, prs=[pr(9, sha(5), title, body)])
                self.assertEqual(plan["tag"], expected)
                self.assertFalse(plan["prerelease"])

    def test_highest_bump_across_backlog_wins(self):
        releases, tags = self.stable_history()
        plan = self.plan(releases=releases, tags=tags, prs=[pr(4, sha(4), "feat: add rewind"), pr(5, sha(5), "fix!: move saves")])
        self.assertEqual(plan["tag"], "v2.0.0")

    def test_explicit_bump_overrides_its_own_pr_title(self):
        releases, tags = self.stable_history()
        for marker, expected in [("patch", "v1.0.1"), ("minor", "v1.1.0"), ("major", "v2.0.0")]:
            with self.subTest(marker=marker):
                plan = self.plan(releases=releases, tags=tags, prs=[pr(9, sha(5), "feat!: replace core", f"[release:{marker}]")])
                self.assertEqual(plan["tag"], expected)

    def test_explicit_semver_bump_cannot_skip_beta_channel(self):
        plan = self.plan(prs=[pr(5, sha(5), body="[release:major]")])
        self.assertEqual(plan["tag"], "v1.0.0-beta.2")

    def test_stable_marker_after_transition_is_inert(self):
        releases, tags = self.stable_history()
        plan = self.plan(releases=releases, tags=tags, prs=[pr(9, sha(5), "feat: add rewind", "[release:stable]")])
        self.assertEqual(plan["tag"], "v1.1.0")
        self.assertEqual(plan["stable_gate_prs"], [])

    def test_direct_commits_also_release_stable_patch(self):
        releases, tags = self.stable_history()
        plan = self.plan(releases=releases, tags=tags)
        self.assertEqual(plan["tag"], "v1.0.1")
        self.assertEqual(plan["direct_commits"][0]["title"], "docs: clarify saves")

    def test_empty_backlog_is_idempotent(self):
        releases, tags = self.stable_history()
        tags["v1.0.0"] = sha(5)
        plan = self.plan(releases=releases, tags=tags, prs=[pr(9, sha(5), body="[release:stable]")])
        self.assertFalse(plan["release"])
        self.assertNotIn("tag", plan)
        self.assertEqual(plan["target_sha"], sha(5))

    def test_exact_standalone_marker_syntax(self):
        for body in [" [release:stable]", "[release:stable] ", "Use [release:stable]", "> [release:stable]", "[RELEASE:STABLE]", "[release:Stable]", "[release:stable] suffix"]:
            with self.subTest(body=body):
                self.assertEqual(self.plan(prs=[pr(9, sha(5), body=body)])["tag"], "v1.0.0-beta.2")
        self.assertEqual(self.plan(prs=[pr(9, sha(5), body="Evidence\r\n[release:stable]\r\n")])["tag"], "v1.0.0")

    def test_fenced_marker_examples_do_not_authorize_a_release(self):
        for body in [
            "```text\n[release:stable]\n```",
            "   ~~~markdown\n[release:stable]\n  ~~~",
            "````\n[release:stable]\n```\n[release:stable]\n````",
            "~~~\n[release:stable]\n```\n[release:stable]\n~~~",
            "```\n[release:stable]\n``` not a closing fence\n[release:stable]",
            "```text\n[release:stable]",
            "~~~\r\n[release:stable]\r\n",
        ]:
            with self.subTest(body=body):
                self.assertEqual(self.plan(prs=[pr(9, sha(5), body=body)])["tag"], "v1.0.0-beta.2")
        for body in ["```\n[release:major]\n```\n[release:stable]", "~~~\r\n[release:major]\r\n~~~~\r\n[release:stable]\r\n"]:
            with self.subTest(body=body):
                self.assertEqual(self.plan(prs=[pr(9, sha(5), body=body)])["tag"], "v1.0.0")

    def test_hidden_html_comment_marker_instructions_do_not_authorize(self):
        for body in [
            "<!--\n[release:stable]\n-->",
            "<!-- template instructions\n[release:stable]",
            "<!--\n--> <!-- another comment\n[release:stable]\n-->",
        ]:
            with self.subTest(body=body):
                self.assertEqual(self.plan(prs=[pr(9, sha(5), body=body)])["tag"], "v1.0.0-beta.2")
        for body in [
            "<!--\n[release:major]\n-->\n[release:stable]",
            "```\n<!--\n```\n[release:stable]",
            "~~~ <!-- info text\n[release:major]\n~~~\n[release:stable]",
            "<!--\n```\n-->\n[release:stable]",
        ]:
            with self.subTest(body=body):
                self.assertEqual(self.plan(prs=[pr(9, sha(5), body=body)])["tag"], "v1.0.0")

    def test_stable_bumps_reset_lower_order_fields(self):
        releases, tags = self.stable_history()
        releases.append(published("v1.7.9", 3))
        tags["v1.7.9"] = sha(4)
        for title, expected in [("fix: repair audio", "v1.7.10"), ("feat: add filters", "v1.8.0"), ("feat!: migrate saves", "v2.0.0")]:
            with self.subTest(title=title):
                self.assertEqual(self.plan(releases=releases, tags=tags, prs=[pr(9, sha(5), title)])["tag"], expected)

    def test_conflicting_directives_refuse_release_in_both_channels(self):
        for history in [None, self.stable_history()]:
            for body in ["[release:stable]\n[release:major]", "[release:patch]\n[release:minor]"]:
                with self.subTest(history=history, body=body):
                    kwargs = {"releases": history[0], "tags": history[1]} if history else {}
                    with self.assertRaisesRegex(release.ReleaseError, "Conflicting release"):
                        self.plan(prs=[pr(9, sha(5), body=body)], **kwargs)
        self.assertEqual(self.plan(prs=[pr(9, sha(5), body="[release:stable]\n[release:stable]")])["tag"], "v1.0.0")

    def test_unmerged_other_base_side_branch_and_released_prs_cannot_gate(self):
        for outsider in [
            pr(1, sha(5), body="[release:stable]", merged_at=None),
            pr(1, sha(5), body="[release:stable]", base={"ref": "develop"}),
            pr(1, sha(99), body="[release:stable]"),
            pr(1, sha(2), body="[release:stable]"),
        ]:
            with self.subTest(outsider=outsider):
                self.assertEqual(self.plan(prs=[outsider])["tag"], "v1.0.0-beta.2")

    def test_unrelated_pr_directives_are_not_even_parsed(self):
        unrelated = pr(3, sha(99), body="[release:stable]\n[release:patch]")
        self.assertEqual(self.plan(prs=[unrelated])["tag"], "v1.0.0-beta.2")

    def test_source_release_must_belong_to_first_parent_main(self):
        with self.assertRaisesRegex(release.ReleaseError, "outside.*first-parent"):
            self.plan(tags={"v1.0.0-beta.1": sha(99)})
        with self.assertRaisesRegex(release.ReleaseError, "outside.*first-parent"):
            self.plan(commits=[sha(1), sha(3), sha(4), sha(5)])

    def test_history_requires_initial_beta_and_first_stable_exactly_one_zero_zero(self):
        cases = [
            ([published("v1.0.0-beta.2")], {"v1.0.0-beta.2": sha(2)}, "start with"),
            ([published("v1.0.0")], {"v1.0.0": sha(2)}, "start with"),
            ([published("v1.0.0-beta.1"), published("v1.1.0", 2)], {"v1.0.0-beta.1": sha(2), "v1.1.0": sha(3)}, "first published stable"),
            ([published("v1.0.0-beta.1"), published("v0.9.0", 2)], {"v1.0.0-beta.1": sha(2), "v0.9.0": sha(3)}, "advance"),
        ]
        for releases, tags, message in cases:
            with self.subTest(releases=releases):
                with self.assertRaisesRegex(release.ReleaseError, message):
                    self.plan(releases=releases, tags=tags)

    def test_reversed_version_history_or_beta_after_stable_is_rejected(self):
        releases, tags = self.stable_history()
        releases.append(published("v1.0.0-beta.2", 3))
        tags["v1.0.0-beta.2"] = sha(4)
        with self.assertRaisesRegex(release.ReleaseError, "advance|beta release"):
            self.plan(releases=releases, tags=tags)
        with self.assertRaisesRegex(release.ReleaseError, "advance"):
            self.plan(releases=[published("v1.0.0-beta.1"), published("v1.0.0-beta.3", 2), published("v1.0.0-beta.2", 3)], tags={"v1.0.0-beta.1": sha(2), "v1.0.0-beta.3": sha(3), "v1.0.0-beta.2": sha(4)})

    def test_nonstandard_release_tags_and_channel_flags_are_rejected(self):
        for tag in ["nightly", "v1.0.0-rc.1", "1.0.0-beta.2", "v1.0.0-beta.02", "v01.0.0"]:
            with self.subTest(tag=tag):
                with self.assertRaisesRegex(release.ReleaseError, "Unsupported release tag"):
                    self.plan(releases=[published(tag)])
        with self.assertRaisesRegex(release.ReleaseError, "prerelease flag"):
            self.plan(releases=[published("v1.0.0-beta.1", prerelease=False)])

    def test_version_code_counts_only_first_parent_commits_from_original_beta(self):
        commits = [sha(1), sha(2), sha(8), sha(20), sha(30)]
        plan = self.plan(head=30, commits=commits, prs=[pr(8, sha(20), body="[release:stable]")])
        self.assertEqual(plan["version_code"], 4)
        self.assertEqual(plan["tag"], "v1.0.0")

    def test_tag_collision_refuses_retagging(self):
        with self.assertRaisesRegex(release.ReleaseError, "retagging is forbidden"):
            self.plan(tags={"v1.0.0-beta.1": sha(2), "v1.0.0-beta.2": sha(4)})
        plan = self.plan(tags={"v1.0.0-beta.1": sha(2), "v1.0.0-beta.2": sha(5)})
        self.assertTrue(plan["existing_tag"])

    def test_pending_draft_resumes_pinned_older_target_before_backlog(self):
        draft = published("v1.0.0-beta.2", 18, draft=True, target_commitish=sha(4))
        plan = self.plan(releases=[published("v1.0.0-beta.1"), draft], prs=[pr(19, sha(5), body="[release:stable]")])
        self.assertEqual(plan["target_sha"], sha(4))
        self.assertEqual(plan["head_sha"], sha(5))
        self.assertEqual(plan["tag"], "v1.0.0-beta.2")
        self.assertEqual(plan["existing_draft_release_id"], 18)
        self.assertEqual(plan["version_code"], 3)
        followup = self.plan(releases=[published("v1.0.0-beta.1"), published("v1.0.0-beta.2", 18)], tags={"v1.0.0-beta.1": sha(2), "v1.0.0-beta.2": sha(4)}, prs=[pr(19, sha(5), body="[release:stable]")])
        self.assertEqual(followup["tag"], "v1.0.0")
        self.assertEqual(followup["version_code"], 4)

    def test_draft_tag_is_the_pin_when_rest_target_was_a_branch(self):
        draft = published("v1.0.0-beta.2", 18, draft=True)
        plan = self.plan(releases=[published("v1.0.0-beta.1"), draft], tags={"v1.0.0-beta.1": sha(2), "v1.0.0-beta.2": sha(4)})
        self.assertEqual(plan["target_sha"], sha(4))

    def test_unsafe_draft_reservations_are_rejected(self):
        drafts = [
            (published("v1.0.0-beta.2", 18, draft=True), {}, "pending draft must pin"),
            (published("v1.0.0-beta.2", 18, draft=True, target_commitish=sha(99)), {}, "pending draft must pin"),
            (published("v1.0.0-beta.2", 18, draft=True, target_commitish=sha(2)), {}, "pending draft must pin"),
            (published("v1.0.0-beta.3", 18, draft=True, target_commitish=sha(4)), {}, "does not match"),
            (published("v1.0.0-beta.2", 18, draft=True, target_commitish=sha(4)), {"v1.0.0-beta.2": sha(5)}, "disagrees"),
        ]
        for draft, extra_tags, message in drafts:
            with self.subTest(draft=draft):
                with self.assertRaisesRegex(release.ReleaseError, message):
                    self.plan(releases=[published("v1.0.0-beta.1"), draft], tags={"v1.0.0-beta.1": sha(2), **extra_tags})
        with self.assertRaisesRegex(release.ReleaseError, "Multiple draft releases"):
            self.plan(releases=[published("v1.0.0-beta.1"), published("v1.0.0-beta.2", 18, draft=True, target_commitish=sha(4)), published("v1.0.0-beta.3", 19, draft=True, target_commitish=sha(5))])

    def test_first_beta_bootstrap_has_version_code_one(self):
        plan = self.plan(releases=[], tags={})
        self.assertEqual(plan["tag"], "v1.0.0-beta.1")
        self.assertEqual(plan["version_code"], 1)
        with self.assertRaisesRegex(release.ReleaseError, "Publish the first beta"):
            self.plan(releases=[], tags={}, prs=[pr(9, sha(5), body="[release:stable]")])

    def test_notes_contain_reviewable_source_version_and_prs(self):
        plan = self.plan(prs=[pr(9, sha(5), "fix: escape [text]", "[release:stable]")])
        notes = release.release_notes(plan)
        self.assertIn("Android versionCode: **4**", notes)
        self.assertIn(sha(5), notes)
        self.assertIn("[release:stable]", notes)
        self.assertIn("https://github.com/culpen90/nes/pull/9", notes)
        self.assertIn(r"escape \[text\]", notes)

    def test_github_output_skip_and_recovery_contract(self):
        plan = self.plan()
        output = release.github_outputs(plan)
        self.assertIn("release=true\n", output)
        self.assertIn("prerelease=true\n", output)
        self.assertIn("version_code=4\n", output)
        self.assertIn(f"head_sha={sha(5)}\n", output)
        self.assertIn("existing_draft_release_id=\n", output)
        self.assertIn("release=false\n", release.github_outputs(self.plan(head=2)))
        with self.assertRaisesRegex(release.ReleaseError, "Unsafe multiline"):
            release.github_outputs({"notes_file": "notes\nrelease=true"})


if __name__ == "__main__":
    unittest.main()
