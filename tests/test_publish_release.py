"""Publication state transitions, recovery and immutable public artifacts."""

import copy
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "publish_release", Path(__file__).resolve().parents[1] / "tools/publish-release.py")
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class FakeGitHub:
    def __init__(self, plan, release=None):
        self.plan = plan
        self.release = copy.deepcopy(release)
        self.tag_sha = plan["target_sha"] if release and not release["draft"] else None
        self.writes = []
        self.remote_bytes = {}

    def releases(self):
        return [copy.deepcopy(self.release)] if self.release else []

    def api(self, endpoint, *, method="GET", payload=None):
        if endpoint.startswith("git/ref/tags/"):
            return {"object": {"type": "commit", "sha": self.tag_sha}} if self.tag_sha else None
        if method != "GET":
            self.writes.append((method, endpoint, copy.deepcopy(payload)))
            if self.release is None:
                self.release = {"id": 42, "assets": [], "html_url": "https://example.test/release"}
            self.release.update(payload)
            if payload.get("draft") is False:
                self.tag_sha = self.plan["target_sha"]
        return copy.deepcopy(self.release)

    def upload(self, tag, paths):
        if not self.release["draft"]:
            raise AssertionError("An upload must never mutate public release assets")
        self.writes.append(("upload", tag))
        self.seed_assets(paths)

    def seed_assets(self, paths):
        self.release["assets"] = []
        for index, path in enumerate(paths):
            self.remote_bytes[index] = path.read_bytes()
            self.release["assets"].append({"id": index, "name": path.name,
                                           "size": path.stat().st_size, "state": "uploaded"})

    def download(self, asset_id, destination):
        destination.write_bytes(self.remote_bytes[asset_id])


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.signer = "b" * 64
        self.plan = {"release": True, "version_name": "1.0.0-beta.2", "version_code": 7,
                     "tag": "v1.0.0-beta.2", "target_sha": "a" * 40, "prerelease": True,
                     "existing_draft_release_id": None}
        self.write_artifacts()

    def write_artifacts(self):
        name = f"pocket-nes-{self.plan['version_name']}.apk"
        apk = self.root / name
        apk.write_bytes(b"verified production APK bytes")
        checksum = hashlib.sha256(apk.read_bytes()).hexdigest()
        self.metadata = {key: self.plan[key] for key in
                         ("version_name", "version_code", "target_sha", "prerelease")}
        self.metadata.update(apk=name, sha256=checksum, signer_sha256=self.signer)
        (self.root / "release.json").write_text(json.dumps(self.metadata), encoding="utf-8")
        (self.root / "SHA256SUMS").write_text(f"{checksum}  {name}\n", encoding="ascii")
        self.metadata, self.paths = publisher.verify_artifacts(self.plan, self.root, self.signer)

    def draft(self):
        return {"id": 42, "tag_name": self.plan["tag"], "target_commitish": self.plan["target_sha"],
                "draft": True, "prerelease": self.plan["prerelease"], "assets": [],
                "html_url": "https://example.test/release"}

    def run_publish(self, client):
        return publisher.publish(client, self.plan, "Release notes", self.metadata, self.paths)

    def test_beta_is_uploaded_and_verified_before_publication_and_never_latest(self):
        client = FakeGitHub(self.plan)
        release = self.run_publish(client)
        self.assertFalse(release["draft"])
        self.assertTrue(release["prerelease"])
        self.assertEqual(client.writes[-1][2]["make_latest"], "false")
        upload_index = next(i for i, write in enumerate(client.writes) if write[0] == "upload")
        publish_index = next(i for i, write in enumerate(client.writes)
                             if write[0] == "PATCH" and write[2].get("draft") is False)
        self.assertLess(upload_index, publish_index)
        self.assertEqual(client.tag_sha, self.plan["target_sha"])

    def test_stable_release_becomes_latest(self):
        self.plan.update(version_name="1.0.0", tag="v1.0.0", prerelease=False)
        self.write_artifacts()
        client = FakeGitHub(self.plan)
        release = self.run_publish(client)
        self.assertFalse(release["prerelease"])
        self.assertEqual(client.writes[-1][2]["make_latest"], "true")

    def test_incomplete_draft_is_recovered(self):
        self.plan["existing_draft_release_id"] = 42
        client = FakeGitHub(self.plan, self.draft())
        client.seed_assets(self.paths[:1])
        release = self.run_publish(client)
        self.assertFalse(release["draft"])
        self.assertEqual(len(release["assets"]), 3)
        self.assertFalse(any(write[0] == "POST" for write in client.writes))

    def test_retry_public_release_only_reads_and_verifies(self):
        public = self.draft()
        public["draft"] = False
        client = FakeGitHub(self.plan, public)
        client.seed_assets(self.paths)
        self.run_publish(client)
        self.assertEqual(client.writes, [])

    def test_public_asset_corruption_is_never_overwritten(self):
        public = self.draft()
        public["draft"] = False
        client = FakeGitHub(self.plan, public)
        client.seed_assets(self.paths)
        client.remote_bytes[0] = b"modified bytes of the same APK length"
        with self.assertRaisesRegex(publisher.PublishError, "differs"):
            self.run_publish(client)
        self.assertEqual(client.writes, [])

    def test_conflicting_tag_is_never_moved(self):
        client = FakeGitHub(self.plan)
        client.tag_sha = "c" * 40
        with self.assertRaisesRegex(publisher.PublishError, "different source"):
            self.run_publish(client)
        self.assertEqual(client.writes, [])

    def test_draft_source_mismatch_is_rejected(self):
        draft = self.draft()
        draft["target_commitish"] = "c" * 40
        client = FakeGitHub(self.plan, draft)
        with self.assertRaisesRegex(publisher.PublishError, "not pinned"):
            self.run_publish(client)
        self.assertEqual(client.writes, [])

    def test_existing_exact_tag_can_pin_a_draft_with_branch_target(self):
        draft = self.draft()
        draft["target_commitish"] = "main"
        client = FakeGitHub(self.plan, draft)
        client.tag_sha = self.plan["target_sha"]
        release = self.run_publish(client)
        self.assertFalse(release["draft"])
        self.assertEqual(release["target_commitish"], self.plan["target_sha"])

    def test_replaced_planned_draft_is_rejected(self):
        self.plan["existing_draft_release_id"] = 40
        client = FakeGitHub(self.plan, self.draft())
        with self.assertRaisesRegex(publisher.PublishError, "replaced"):
            self.run_publish(client)
        self.assertEqual(client.writes, [])

    def test_unexpected_draft_assets_are_not_published(self):
        draft = self.draft()
        draft["assets"] = [{"name": "unreviewed.apk"}]
        client = FakeGitHub(self.plan, draft)
        with self.assertRaisesRegex(publisher.PublishError, "unexpected assets"):
            self.run_publish(client)
        self.assertEqual(client.writes, [])

    def test_mismatched_production_version_is_rejected(self):
        self.metadata["version_code"] += 1
        (self.root / "release.json").write_text(json.dumps(self.metadata), encoding="utf-8")
        with self.assertRaisesRegex(publisher.PublishError, "version_code"):
            publisher.verify_artifacts(self.plan, self.root, self.signer)

    def test_wrong_certificate_is_rejected(self):
        with self.assertRaisesRegex(publisher.PublishError, "signing certificate"):
            publisher.verify_artifacts(self.plan, self.root, "c" * 64)

    def test_tampered_local_apk_is_rejected(self):
        self.paths[0].write_bytes(b"modified apk")
        with self.assertRaisesRegex(publisher.PublishError, "checksum"):
            publisher.verify_artifacts(self.plan, self.root, self.signer)

    def test_malformed_provenance_object_is_rejected(self):
        (self.root / "release.json").write_text('[]', encoding="utf-8")
        with self.assertRaisesRegex(publisher.PublishError, "JSON object"):
            publisher.verify_artifacts(self.plan, self.root, self.signer)

    def test_nonrelease_plan_is_rejected(self):
        self.plan["release"] = False
        with self.assertRaisesRegex(publisher.PublishError, "does not authorize"):
            publisher.verify_artifacts(self.plan, self.root, self.signer)


if __name__ == "__main__":
    unittest.main()
