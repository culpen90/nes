"""Exercise the Android release comparison without Android runtime dependencies."""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/culpen/nes/ReleaseVersion.java"


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK is required")
class ReleaseVersionTests(unittest.TestCase):
    def test_canonical_parsing_and_numeric_semantic_order(self):
        harness = r"""
import com.culpen.nes.ReleaseVersion;

public final class ReleaseVersionHarness {
    private static ReleaseVersion parse(String value) {
        ReleaseVersion result = ReleaseVersion.parse(value);
        if (result == null) throw new AssertionError("Rejected valid version: " + value);
        return result;
    }

    private static void before(String left, String right) {
        if (parse(left).compareTo(parse(right)) >= 0) {
            throw new AssertionError(left + " must sort before " + right);
        }
        if (parse(right).compareTo(parse(left)) <= 0) {
            throw new AssertionError("Comparison must be symmetric");
        }
    }

    public static void main(String[] args) {
        before("1.0.0-beta.9", "1.0.0-beta.10");
        before("v1.0.0-beta.10", "1.0.0");
        before("1.0.0", "1.0.1-beta.1");
        before("1.9.100", "1.10.0");
        before("1.999999999999999999999.0", "2.0.0");
        before("1.0.0-beta.9223372036854775807", "1.0.0-beta.9223372036854775808");
        before("999999999999999999999999999999999999999.0.0",
               "1000000000000000000000000000000000000000.0.0");
        if (parse("v1.2.3-beta.4").compareTo(parse("1.2.3-beta.4")) != 0
                || !parse("v1.2.3").equals(parse("1.2.3"))
                || parse("v1.2.3").hashCode() != parse("1.2.3").hashCode()
                || !parse("v1.2.3-beta.4").versionName().equals("1.2.3-beta.4")
                || !parse("1.2.3-beta.4").isBeta() || parse("1.2.3").isBeta()) {
            throw new AssertionError("Canonical identity or release channel is incorrect");
        }
        String[] invalid = {null, "", "1.0", "1.0.0.0", "01.0.0", "1.00.0", "1.0.00",
                "1.0.0-beta.0", "1.0.0-beta.01", "1.0.0-beta", "1.0.0-rc.1",
                "1.0.0+build", "V1.0.0", "vv1.0.0", " 1.0.0", "1.0.0\n",
                "-1.0.0", "1.0.0-beta.-1", "1.0.0-beta.1.2", "١.0.0",
                "1".repeat(257) + ".0.0"};
        for (String value : invalid) {
            if (ReleaseVersion.parse(value) != null) {
                throw new AssertionError("Accepted invalid version: " + value);
            }
        }
    }
}
"""
        with tempfile.TemporaryDirectory(prefix="pocket-nes-version-test-") as directory:
            path = Path(directory)
            java = path / "ReleaseVersionHarness.java"
            java.write_text(harness, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", directory, str(SOURCE), str(java)],
                text=True, capture_output=True, check=False)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", directory, "ReleaseVersionHarness"],
                text=True, capture_output=True, check=False)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
