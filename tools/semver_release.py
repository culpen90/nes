#!/usr/bin/env python3
"""Read-only release planning for Pocket NES; publishing lives in the workflow.

Published GitHub releases and their immutable Git tags are the channel state.
Only merged PRs targeting main whose merge commit occurs in the unreleased
first-parent history can provide release directives. Contributor identity and
the human stable-readiness checklist deliberately do not affect the planner.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any


class ReleaseError(ValueError):
    """Repository state is unsafe or ambiguous to release automatically."""


@dataclass(frozen=True)
class Version:
    major: int
    minor: int
    patch: int
    beta: int | None = None

    @classmethod
    def parse(cls, tag: str) -> "Version":
        beta = re.fullmatch(r"v1\.0\.0-beta\.([1-9][0-9]*)", tag)
        if beta:
            return cls(1, 0, 0, int(beta.group(1)))
        stable = re.fullmatch(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", tag)
        if stable:
            return cls(*(int(part) for part in stable.groups()))
        raise ReleaseError(f"Unsupported release tag {tag!r}; expected v1.0.0-beta.N or vMAJOR.MINOR.PATCH.")

    @property
    def name(self) -> str:
        value = f"{self.major}.{self.minor}.{self.patch}"
        return value if self.beta is None else f"{value}-beta.{self.beta}"

    @property
    def tag(self) -> str:
        return f"v{self.name}"

    @property
    def order(self) -> tuple[int, int, int, int, int]:
        return (self.major, self.minor, self.patch, int(self.beta is None), self.beta or 0)

    def bump(self, kind: str) -> "Version":
        if kind == "major":
            return Version(self.major + 1, 0, 0)
        if kind == "minor":
            return Version(self.major, self.minor + 1, 0)
        return Version(self.major, self.minor, self.patch + 1)


MARKERS = {f"[release:{kind}]": kind for kind in ("stable", "major", "minor", "patch")}
BUMP_ORDER = {"patch": 0, "minor": 1, "major": 2}


def release_marker(body: str | None) -> str | None:
    """Directives are exact visible standalone lines, outside fences/comments."""
    markers = set()
    fence: str | None = None
    in_comment = False
    for line in (body or "").splitlines():
        if fence:
            closing = r" {0,3}" + re.escape(fence[0]) + "{" + str(len(fence)) + r",}[ \t]*"
            if re.fullmatch(closing, line):
                fence = None
            continue
        opening = re.fullmatch(r" {0,3}(`{3,}|~{3,})(.*)", line)
        if not in_comment and opening and not (opening.group(1)[0] == "`" and "`" in opening.group(2)):
            fence = opening.group(1)
            continue
        comment_line = in_comment
        cursor = 0
        while True:
            delimiter = "-->" if in_comment else "<!--"
            found = line.find(delimiter, cursor)
            if found < 0:
                break
            comment_line = True
            in_comment = not in_comment
            cursor = found + len(delimiter)
        if comment_line:
            continue
        if line in MARKERS:
            markers.add(MARKERS[line])
    if len(markers) > 1:
        raise ReleaseError(f"Conflicting release directives: {', '.join(sorted(markers))}.")
    return next(iter(markers), None)


def pr_bump(pr: dict[str, Any], marker: str | None) -> str:
    if marker in BUMP_ORDER:
        return marker
    title = pr.get("title") or ""
    body = pr.get("body") or ""
    if re.match(r"^[a-zA-Z][a-zA-Z0-9_-]*(?:\([^\n)]+\))?!:", title) or re.search(
        r"(?m)^BREAKING[ -]CHANGE:", body
    ):
        return "major"
    if re.match(r"^feat(?:\([^\n)]+\))?:", title):
        return "minor"
    return "patch"


def eligible_prs(prs: list[dict[str, Any]], commits: list[str]) -> list[dict[str, Any]]:
    position = {sha: index for index, sha in enumerate(commits)}
    result: dict[int, dict[str, Any]] = {}
    for pr in prs:
        if not pr.get("merged_at") or pr.get("base", {}).get("ref") != "main":
            continue
        if pr.get("merge_commit_sha") not in position:
            continue
        number = pr.get("number")
        if not isinstance(number, int) or number <= 0:
            raise ReleaseError("A merged PR has an invalid PR number.")
        if number in result and result[number] != pr:
            raise ReleaseError(f"Conflicting API snapshots for PR #{number}; rerun planning.")
        result[number] = pr
    return sorted(result.values(), key=lambda pr: (position[pr["merge_commit_sha"]], pr["number"]))


def candidate_version(previous: Version | None, prs: list[dict[str, Any]]) -> tuple[Version, list[int]]:
    markers = [(pr, release_marker(pr.get("body"))) for pr in prs]
    gates = [pr["number"] for pr, marker in markers if marker == "stable"]
    if previous is None or previous.beta is not None:
        if gates:
            if previous is None:
                raise ReleaseError("Publish the first beta v1.0.0-beta.1 before authorizing the first stable release.")
            return Version(1, 0, 0), gates
        return Version(1, 0, 0, 1 if previous is None else previous.beta + 1), []
    bump = max((pr_bump(pr, marker) for pr, marker in markers), key=BUMP_ORDER.get, default="patch")
    return previous.bump(bump), []


def make_plan(
    *,
    head_sha: str,
    first_parent_commits: list[str],
    releases: list[dict[str, Any]],
    tag_commits: dict[str, str],
    prs: list[dict[str, Any]],
    notes_file: str,
    commit_subjects: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Pure state transition, with commits ordered oldest first through head."""
    if not first_parent_commits or first_parent_commits[-1] != head_sha:
        raise ReleaseError("The selected head must be the end of its complete first-parent history.")
    if len(set(first_parent_commits)) != len(first_parent_commits):
        raise ReleaseError("First-parent history contains duplicate commits.")
    position = {sha: index for index, sha in enumerate(first_parent_commits)}
    published: list[tuple[int, Version, dict[str, Any]]] = []
    drafts: list[tuple[Version, dict[str, Any]]] = []
    seen_tags: set[str] = set()
    for release in releases:
        tag = release.get("tag_name")
        if not isinstance(tag, str):
            raise ReleaseError("A GitHub release is missing its tag name.")
        version = Version.parse(tag)
        if tag in seen_tags:
            raise ReleaseError(f"Multiple GitHub releases use {tag}.")
        seen_tags.add(tag)
        if bool(release.get("prerelease")) != (version.beta is not None):
            raise ReleaseError(f"Release {tag} has a prerelease flag inconsistent with its version.")
        if release.get("draft"):
            drafts.append((version, release))
            continue
        sha = tag_commits.get(tag)
        if sha not in position:
            raise ReleaseError(f"Published release tag {tag} is missing or outside the selected first-parent main history.")
        published.append((position[sha], version, release))
    published.sort(key=lambda item: (item[0], item[1].order))
    origin_tag = "v1.0.0-beta.1"
    if published and published[0][1].tag != origin_tag:
        raise ReleaseError("Published history must start with v1.0.0-beta.1 to establish Android versionCode 1.")
    previous: Version | None = None
    previous_position = -1
    stable_seen = False
    for index, version, _ in published:
        if previous is not None and (version.order <= previous.order or index <= previous_position):
            raise ReleaseError("Published releases must advance both the version and the first-parent source commit.")
        if version.beta is not None and stable_seen:
            raise ReleaseError("A beta release appears after the first stable release.")
        if version.beta is None and not stable_seen:
            if version != Version(1, 0, 0):
                raise ReleaseError("The first published stable release must be v1.0.0.")
            stable_seen = True
        previous, previous_position = version, index
    if len(drafts) > 1:
        raise ReleaseError("Multiple draft releases are pending; maintainers must resolve the ambiguous reservations.")

    target_sha = head_sha
    draft_id: int | None = None
    if drafts:
        draft_version, draft = drafts[0]
        target_text = draft.get("target_commitish") or ""
        tagged_sha = tag_commits.get(draft_version.tag)
        pinned_sha = target_text if re.fullmatch(r"[0-9a-f]{40}", target_text) else None
        if tagged_sha and pinned_sha and tagged_sha != pinned_sha:
            raise ReleaseError(f"Draft {draft_version.tag} disagrees with its immutable source tag.")
        target_sha = tagged_sha or pinned_sha
        if target_sha not in position or position[target_sha] <= previous_position:
            raise ReleaseError("The pending draft must pin an unreleased first-parent commit on main.")
        draft_id = draft.get("id")
        if not isinstance(draft_id, int) or draft_id <= 0:
            raise ReleaseError("The pending draft has no valid GitHub release id.")

    target_position = position[target_sha]
    unreleased = first_parent_commits[previous_position + 1:target_position + 1]
    base = {
        "head_sha": head_sha,
        "target_sha": target_sha,
        "previous_tag": previous.tag if previous else "",
        "origin_tag": origin_tag,
        "notes_file": notes_file,
        "existing_draft_release_id": draft_id,
    }
    if not unreleased:
        return {**base, "release": False, "reason": "The latest main commit is already published."}
    selected_prs = eligible_prs(prs, unreleased)
    version, gate_prs = candidate_version(previous, selected_prs)
    if drafts and drafts[0][0] != version:
        raise ReleaseError(f"Pending draft {drafts[0][0].tag} does not match the planned version {version.tag}.")
    if version.tag in tag_commits and tag_commits[version.tag] != target_sha:
        raise ReleaseError(f"Existing tag {version.tag} points to another commit; automatic retagging is forbidden.")
    origin_position = published[0][0] if published else target_position
    version_code = target_position - origin_position + 1
    if not 1 <= version_code <= 2_100_000_000:
        raise ReleaseError("The calculated Android versionCode is outside Android's supported range.")
    subjects = commit_subjects or {}
    linked = {pr["merge_commit_sha"] for pr in selected_prs}
    changes = [{"number": pr["number"], "title": pr.get("title") or "Untitled PR", "url": pr.get("html_url") or "", "sha": pr["merge_commit_sha"]} for pr in selected_prs]
    direct_commits = [{"sha": sha, "title": subjects.get(sha, "Direct main commit")} for sha in unreleased if sha not in linked]
    return {
        **base,
        "release": True,
        "version_name": version.name,
        "version_code": version_code,
        "tag": version.tag,
        "prerelease": version.beta is not None,
        "existing_tag": version.tag in tag_commits,
        "stable_gate_prs": gate_prs,
        "changes": changes,
        "direct_commits": direct_commits,
        "unreleased_commit_count": len(unreleased),
    }


def markdown_text(value: str) -> str:
    value = " ".join(value.splitlines())
    return re.sub(r"([\\`*_{}\[\]<>])", r"\\\1", value)


def release_notes(plan: dict[str, Any]) -> str:
    if not plan["release"]:
        return ""
    channel = "Beta release" if plan["prerelease"] else "Stable release"
    lines = [f"{channel} **{plan['version_name']}**.", "", f"Android versionCode: **{plan['version_code']}**.", f"Source commit: `{plan['target_sha']}`.", ""]
    if plan["stable_gate_prs"]:
        numbers = ", ".join(f"#{number}" for number in plan["stable_gate_prs"])
        lines += [f"Maintainers authorized the first stable release by merging {numbers} with `[release:stable]`.", ""]
    if plan["previous_tag"]:
        lines += [f"Changes since `{plan['previous_tag']}`:", ""]
    else:
        lines += ["Changes in the first beta:", ""]
    for change in plan["changes"]:
        url = change["url"]
        reference = f"[#{change['number']}]({url})" if re.fullmatch(r"https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/pull/[0-9]+", url) else f"#{change['number']}"
        lines.append(f"- {markdown_text(change['title'])} ({reference})")
    for commit in plan["direct_commits"]:
        lines.append(f"- {markdown_text(commit['title'])} (`{commit['sha'][:12]}`)")
    return "\n".join(lines) + "\n"


def command(args: list[str]) -> str:
    try:
        return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as error:
        detail = error.stderr.strip() if isinstance(error, subprocess.CalledProcessError) else str(error)
        raise ReleaseError(f"Command failed ({args[0]}): {detail}") from error


def github_pages(repo: str, endpoint: str) -> list[dict[str, Any]]:
    result = json.loads(command(["gh", "api", "--paginate", "--slurp", f"repos/{repo}/{endpoint}"]))
    if not isinstance(result, list) or any(not isinstance(page, list) for page in result):
        raise ReleaseError("Unexpected paginated GitHub API response.")
    items = [item for page in result for item in page]
    if any(not isinstance(item, dict) for item in items):
        raise ReleaseError("Unexpected object in the GitHub API response.")
    return items


def github_outputs(plan: dict[str, Any]) -> str:
    fields = ("release", "version_name", "version_code", "tag", "target_sha", "head_sha", "prerelease", "notes_file", "existing_draft_release_id", "existing_tag", "previous_tag", "origin_tag")
    lines = []
    for key in fields:
        value = plan.get(key)
        if isinstance(value, bool):
            value = str(value).lower()
        elif value is None:
            value = ""
        value = str(value)
        if "\n" in value or "\r" in value:
            raise ReleaseError(f"Unsafe multiline GitHub output field: {key}.")
        lines.append(f"{key}={value}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True, help="GitHub OWNER/REPO")
    parser.add_argument("--head", default="HEAD", help="Latest fetched canonical main head (default: HEAD)")
    parser.add_argument("--plan-file", required=True)
    parser.add_argument("--notes-file", required=True)
    parser.add_argument("--github-output", help="Append scalar outputs to this file, normally $GITHUB_OUTPUT")
    args = parser.parse_args()
    try:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
            raise ReleaseError("--repo must have the form OWNER/REPO.")
        head_sha = command(["git", "rev-parse", "--verify", f"{args.head}^{{commit}}"])
        commits = command(["git", "rev-list", "--first-parent", "--reverse", head_sha]).splitlines()
        tags = command(["git", "tag", "--list"]).splitlines()
        tag_commits = {}
        for tag in tags:
            try:
                Version.parse(tag)
            except ReleaseError:
                continue  # Unrelated source tags do not define the app release channel.
            tag_commits[tag] = command(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"])
        releases = github_pages(args.repo, "releases?per_page=100")
        prs = github_pages(args.repo, "pulls?state=closed&base=main&per_page=100")
        subject_lines = command(["git", "log", "--first-parent", "--format=%H%x00%s", head_sha]).splitlines()
        subjects = dict(line.split("\x00", 1) for line in subject_lines)
        plan = make_plan(head_sha=head_sha, first_parent_commits=commits, releases=releases, tag_commits=tag_commits, prs=prs, notes_file=args.notes_file, commit_subjects=subjects)
        output = github_outputs(plan)
        Path(args.plan_file).parent.mkdir(parents=True, exist_ok=True)
        Path(args.notes_file).parent.mkdir(parents=True, exist_ok=True)
        Path(args.notes_file).write_text(release_notes(plan), encoding="utf-8")
        Path(args.plan_file).write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
        if args.github_output:
            with open(args.github_output, "a", encoding="utf-8") as stream:
                stream.write(output)
        print(json.dumps({key: plan.get(key) for key in ("release", "tag", "version_code", "target_sha", "head_sha", "existing_draft_release_id")}))
    except (ReleaseError, json.JSONDecodeError) as error:
        print(f"Release planning failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
