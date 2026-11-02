import unittest
import xml.etree.ElementTree as ET
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError

from publish_maven import merge_metadata, verify_public
from release_version import resolve


def metadata(version):
    return (f"<metadata><groupId>dev.vegui</groupId><artifactId>acceleration-api</artifactId>"
            f"<versioning><latest>{version}</latest><versions><version>{version}</version>"
            "</versions></versioning></metadata>").encode()


class PublishingTest(unittest.TestCase):
    def test_public_verification_identifies_publisher(self):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b"artifact"
        with patch("publish_maven.urllib.request.urlopen", return_value=response) as open_url:
            verify_public("dev/vegui/acceleration-api/test.jar", b"artifact")
        request = open_url.call_args.args[0]
        self.assertEqual(request.full_url,
                         "https://repo.vegui.dev/dev/vegui/acceleration-api/test.jar")
        self.assertEqual(request.get_header("User-agent"), "Acceleration-Maven-Publisher/1.0")
        self.assertEqual(open_url.call_args.kwargs["timeout"], 60)

    def test_public_verification_still_rejects_corrupt_content(self):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b"corrupt"
        with patch("publish_maven.urllib.request.urlopen", return_value=response):
            with self.assertRaises(ValueError):
                verify_public("test.jar", b"artifact")

    def test_public_verification_does_not_bypass_access_errors(self):
        error = HTTPError("https://repo.vegui.dev/test.jar", 403, "Forbidden", {}, None)
        with patch("publish_maven.urllib.request.urlopen", side_effect=error):
            with self.assertRaises(HTTPError):
                verify_public("test.jar", b"artifact")

    def test_merge_preserves_stable_and_snapshot(self):
        root = ET.fromstring(merge_metadata(metadata("4.3.2"), metadata("4.3.3-SNAPSHOT")))
        self.assertEqual(root.findtext("versioning/release"), "4.3.2")
        self.assertEqual(root.findtext("versioning/latest"), "4.3.3-SNAPSHOT")
        self.assertEqual(len(root.findall("versioning/versions/version")), 2)

    def test_out_of_order_merge(self):
        root = ET.fromstring(merge_metadata(metadata("4.3.3"), metadata("4.3.2")))
        self.assertEqual(root.findtext("versioning/release"), "4.3.3")

    def test_version_rules(self):
        self.assertEqual(resolve("refs/heads/dev/4.0.0", "4.3.2-SNAPSHOT", "dev/4.0.0"),
                         "4.3.2-SNAPSHOT")
        with patch("release_version.subprocess.run") as git:
            self.assertEqual(resolve("refs/tags/v4.3.2", "4.3.2-SNAPSHOT", "dev/4.0.0"), "4.3.2")
            git.assert_called_once()
        for ref in ("refs/tags/v4.3.2-rc1", "refs/tags/v9.0.0", "refs/tags/v4.3"):
            with self.assertRaises(ValueError):
                resolve(ref, "4.3.2-SNAPSHOT", "dev/4.0.0")


if __name__ == "__main__":
    unittest.main()
