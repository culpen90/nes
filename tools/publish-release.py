#!/usr/bin/env python3
"""Publish a verified plan and production APK; retry drafts without changing public assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Any


class PublishError(RuntimeError):
    pass


class GitHub:
    def __init__(self, repository: str):
        self.repository = repository
        self.prefix = f"repos/{repository}"

    def api(self, endpoint: str, *, method: str = "GET", payload: dict | None = None) -> Any:
        command = ["gh", "api", "--method", method, f"{self.prefix}/{endpoint}",
                   "-H", "Accept: application/vnd.github+json",
                   "-H", "X-GitHub-Api-Version: 2022-11-28"]
        if payload is not None:
            command += ["--input", "-"]
        result = subprocess.run(command, input=json.dumps(payload) if payload is not None else None,
                                text=True, capture_output=True, check=False)
        if result.returncode:
            if method == "GET" and "HTTP 404" in result.stderr:
                return None
            raise PublishError(f"GitHub {method} {endpoint} failed: {result.stderr.strip()}")
        return json.loads(result.stdout) if result.stdout.strip() else None

    def releases(self) -> list[dict]:
        result = subprocess.run(
            ["gh", "api", f"{self.prefix}/releases?per_page=100", "--paginate", "--slurp"],
            text=True, capture_output=True, check=False)
        if result.returncode:
            raise PublishError(f"Could not read releases: {result.stderr.strip()}")
        return [release for page in json.loads(result.stdout) for release in page]

    def upload(self, tag: str, paths: list[Path]) -> None:
        result = subprocess.run(
            ["gh", "release", "upload", tag, "--repo", self.repository, "--clobber",
             *map(str, paths)], text=True, capture_output=True, check=False)
        if result.returncode:
            raise PublishError(f"Draft asset upload failed: {result.stderr.strip()}")

    def download(self, asset_id: int, destination: Path) -> None:
        with destination.open("wb") as stream:
            result = subprocess.run(
                ["gh", "api", f"{self.prefix}/releases/assets/{asset_id}",
                 "-H", "Accept: application/octet-stream"],
                stdout=stream, stderr=subprocess.PIPE, check=False)
        if result.returncode:
            raise PublishError(f"Could not verify remote asset {asset_id}.")


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_artifacts(plan: dict, artifact_dir: Path, expected_signer: str) -> tuple[dict, list[Path]]:
    if not isinstance(plan, dict):
        raise PublishError("The release plan must be a JSON object.")
    if plan.get("release") is not True:
        raise PublishError("The plan does not authorize a release.")
    version = plan.get("version_name", "")
    if not isinstance(version, str) or not re.fullmatch(
            r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-beta\.[1-9]\d*)?", version):
        raise PublishError("Invalid release version.")
    if plan.get("tag") != f"v{version}":
        raise PublishError("Tag and version disagree.")
    if type(plan.get("version_code")) is not int or not 1 <= plan["version_code"] <= 2_100_000_000:
        raise PublishError("Invalid Android version code.")
    if not isinstance(plan.get("target_sha"), str) or not re.fullmatch(r"[0-9a-f]{40}", plan["target_sha"]):
        raise PublishError("The plan must identify an exact source SHA.")
    if type(plan.get("prerelease")) is not bool or plan["prerelease"] != ("-beta." in version):
        raise PublishError("Version and release channel disagree.")
    expected_signer = expected_signer.lower().replace(":", "")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_signer):
        raise PublishError("The official signing certificate pin is required.")
    apk_name = f"pocket-nes-{version}.apk"
    paths = [artifact_dir / name for name in (apk_name, "SHA256SUMS", "release.json")]
    if any(path.is_symlink() or not path.is_file() or path.stat().st_size == 0 for path in paths):
        raise PublishError("All three verified production artifacts are required.")
    metadata = json.loads(paths[2].read_text(encoding="utf-8"))
    if not isinstance(metadata, dict):
        raise PublishError("Production artifact metadata must be a JSON object.")
    for key in ("version_name", "version_code", "target_sha", "prerelease"):
        if metadata.get(key) != plan[key] or type(metadata.get(key)) is not type(plan[key]):
            raise PublishError(f"Production artifact metadata disagrees with plan: {key}.")
    if metadata.get("apk") != apk_name or metadata.get("signer_sha256") != expected_signer:
        raise PublishError("Production artifact identity or signing certificate is incorrect.")
    apk_digest = digest(paths[0])
    if metadata.get("sha256") != apk_digest:
        raise PublishError("Production APK checksum does not match its provenance.")
    if paths[1].read_text(encoding="ascii") != f"{apk_digest}  {apk_name}\n":
        raise PublishError("SHA256SUMS does not identify the planned production APK exactly.")
    return metadata, paths


def verify_tag(client: GitHub, plan: dict, *, required: bool = False) -> bool:
    reference = client.api(f"git/ref/tags/{plan['tag']}")
    if reference is None:
        if required:
            raise PublishError("Published release tag is missing.")
        return False
    obj = reference["object"]
    seen: set[str] = set()
    while obj.get("type") == "tag":
        if obj["sha"] in seen:
            raise PublishError("Release tag contains a reference cycle.")
        seen.add(obj["sha"])
        tag = client.api(f"git/tags/{obj['sha']}")
        if tag is None:
            raise PublishError("Release tag cannot be resolved.")
        obj = tag["object"]
    if obj.get("type") != "commit" or obj.get("sha") != plan["target_sha"]:
        raise PublishError("Release tag points to a different source commit; refusing to move it.")
    return True


def verify_release_identity(release: dict, plan: dict, *, tag_pinned: bool = False) -> None:
    if release.get("tag_name") != plan["tag"] or release.get("prerelease") is not plan["prerelease"]:
        raise PublishError("Existing release version or channel disagrees with the plan.")
    # Older public releases sometimes use a branch name as target_commitish.
    # Their immutable tag is verified separately. Drafts must pin the source by
    # an exact target SHA or an existing tag that resolves to that exact SHA.
    if release.get("draft") and release.get("target_commitish") != plan["target_sha"] and not tag_pinned:
        raise PublishError("Existing draft is not pinned to the planned source SHA.")


def verify_remote_assets(client: GitHub, release: dict, paths: list[Path]) -> None:
    assets = release.get("assets", [])
    names = [asset["name"] for asset in assets]
    expected = {path.name: path for path in paths}
    if len(names) != len(expected) or set(names) != set(expected):
        raise PublishError("Remote release must contain exactly the three planned production artifacts.")
    with tempfile.TemporaryDirectory(prefix="pocket-nes-release-verify-") as directory:
        for asset in assets:
            local = expected[asset["name"]]
            if asset.get("state") != "uploaded" or asset.get("size") != local.stat().st_size:
                raise PublishError(f"Remote asset is incomplete: {local.name}.")
            remote = Path(directory) / local.name
            client.download(asset["id"], remote)
            if digest(remote) != digest(local):
                raise PublishError(f"Remote asset differs from the verified build: {local.name}.")


def publish(client: GitHub, plan: dict, notes: str, metadata: dict, paths: list[Path]) -> dict:
    tag_pinned = verify_tag(client, plan)
    matches = [release for release in client.releases() if release.get("tag_name") == plan["tag"]]
    if len(matches) > 1:
        raise PublishError("Multiple releases claim the same tag; maintainer repair is required.")
    release = matches[0] if matches else None
    planned_draft = plan.get("existing_draft_release_id")
    if planned_draft and (release is None or release.get("id") != int(planned_draft)):
        raise PublishError("The planned draft disappeared or was replaced; replan before publishing.")
    if release:
        verify_release_identity(release, plan, tag_pinned=tag_pinned)
        if not release.get("draft"):
            verify_tag(client, plan, required=True)
            verify_remote_assets(client, release, paths)
            return release  # Idempotent retry: no public release or asset mutation.
    body = notes.rstrip() + "\n\n<!-- pocket-nes-release " + json.dumps(metadata, sort_keys=True) + " -->\n"
    payload = {"tag_name": plan["tag"], "target_commitish": plan["target_sha"],
               "name": f"Pocket NES {plan['version_name']}", "body": body,
               "draft": True, "prerelease": plan["prerelease"], "make_latest": "false"}
    if release is None:
        release = client.api("releases", method="POST", payload=payload)
    else:
        unexpected = {asset["name"] for asset in release.get("assets", [])} - {path.name for path in paths}
        if unexpected:
            raise PublishError("Existing draft contains unexpected assets; maintainer repair is required.")
        release = client.api(f"releases/{release['id']}", method="PATCH", payload=payload)
    release_id = release["id"]
    release = client.api(f"releases/{release_id}")
    verify_release_identity(release, plan)
    if not release.get("draft"):
        raise PublishError("Draft was published during preparation; refusing to overwrite public assets.")
    verify_tag(client, plan)
    client.upload(plan["tag"], paths)
    release = client.api(f"releases/{release_id}")
    verify_release_identity(release, plan)
    if not release.get("draft"):
        raise PublishError("Draft was published during upload; review the public release before retrying.")
    verify_remote_assets(client, release, paths)
    verify_tag(client, plan)
    release = client.api(f"releases/{release_id}", method="PATCH", payload={
        "draft": False, "prerelease": plan["prerelease"],
        "make_latest": "false" if plan["prerelease"] else "true"})
    if release.get("draft") or release.get("prerelease") is not plan["prerelease"]:
        raise PublishError("GitHub did not publish the requested channel.")
    verify_tag(client, plan, required=True)
    verify_remote_assets(client, release, paths)
    return release


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--plan-file", required=True, type=Path)
    parser.add_argument("--notes-file", required=True, type=Path)
    parser.add_argument("--artifact-dir", required=True, type=Path)
    parser.add_argument("--expected-signer", required=True)
    args = parser.parse_args()
    try:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
            raise PublishError("Invalid GitHub repository.")
        plan = json.loads(args.plan_file.read_text(encoding="utf-8"))
        metadata, paths = verify_artifacts(plan, args.artifact_dir, args.expected_signer)
        release = publish(GitHub(args.repo), plan, args.notes_file.read_text(encoding="utf-8"), metadata, paths)
        print(f"Published {plan['tag']} at {plan['target_sha']}: {release['html_url']}")
    except (PublishError, OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Release publication stopped: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
