"""Publish a staged Maven API to R2. Never deletes objects or logs credentials.

Run only in the serialized publication job. Multi-object uploads are not atomic:
artifact files are uploaded before metadata makes the version discoverable.
"""
import hashlib
import os
from pathlib import Path
import sys
import urllib.request
import xml.etree.ElementTree as ET


def verify_public(key, expected):
    request = urllib.request.Request(
        "https://repo.vegui.dev/" + key,
        headers={"User-Agent": "Acceleration-Maven-Publisher/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        if hashlib.sha256(response.read()).digest() != hashlib.sha256(expected).digest():
            raise ValueError("Public repository returned stale or corrupt content")


def merge_metadata(previous, incoming):
    root = ET.fromstring(incoming)
    if previous:
        old = ET.fromstring(previous)
        for field in ("groupId", "artifactId"):
            if old.findtext(field) != root.findtext(field):
                raise ValueError("Maven metadata identity mismatch")
        versions = {v.text for v in old.findall("versioning/versions/version")}
        versions.update(v.text for v in root.findall("versioning/versions/version"))
        container = root.find("versioning/versions")
        container.clear()
        ordered = sorted(versions, key=lambda v: tuple(int(n) for n in v.split("-")[0].split("."))
                         + (not v.endswith("-SNAPSHOT"),))
        for version in ordered:
            ET.SubElement(container, "version").text = version
        versioning = root.find("versioning")
        latest = versioning.find("latest")
        if latest is None:
            latest = ET.SubElement(versioning, "latest")
        latest.text = ordered[-1]
        stable = [v for v in ordered if not v.endswith("-SNAPSHOT")]
        release = versioning.find("release")
        if stable:
            if release is None:
                release = ET.SubElement(versioning, "release")
            release.text = stable[-1]
        elif release is not None:
            versioning.remove(release)
        updated = versioning.find("lastUpdated")
        old_updated = old.findtext("versioning/lastUpdated")
        if old_updated and updated is not None:
            updated.text = max(updated.text or "", old_updated)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def publish(directory):
    import boto3
    from botocore.exceptions import ClientError

    client = boto3.client("s3", endpoint_url=os.environ["R2_ENDPOINT"], region_name="auto",
                          aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
                          aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"])
    bucket = os.environ["R2_BUCKET"]
    base = directory / "dev/vegui/acceleration-api"
    if not base.is_dir():
        raise ValueError("Staged acceleration-api is missing")

    def existing(key):
        try:
            return client.get_object(Bucket=bucket, Key=key)["Body"].read()
        except ClientError as error:
            if error.response["Error"]["Code"] in ("NoSuchKey", "404"):
                return None
            raise

    def put(key, data, mutable):
        previous = existing(key)
        if previous == data:
            return
        if previous is not None and not mutable:
            raise ValueError("Refusing to overwrite a stable artifact: " + key)
        client.put_object(Bucket=bucket, Key=key, Body=data,
                          ContentType="application/xml" if key.endswith((".xml", ".pom"))
                          else "application/octet-stream",
                          CacheControl="no-store" if mutable else "public, max-age=31536000, immutable")

    files = sorted(path for path in base.rglob("*") if path.is_file())
    metadata = [path for path in files if path.name == "maven-metadata.xml"]
    artifacts = [path for path in files if "maven-metadata.xml" not in path.name]
    # Check every immutable destination before uploading any files.
    for path in artifacts:
        key = path.relative_to(directory).as_posix()
        previous = existing(key)
        if "-SNAPSHOT/" not in key and previous is not None and previous != path.read_bytes():
            raise ValueError("Stable version already exists with different content")
    for path in artifacts:
        key = path.relative_to(directory).as_posix()
        put(key, path.read_bytes(), "-SNAPSHOT/" in key)
    for path in sorted(metadata, key=lambda p: -len(p.parts)):
        key = path.relative_to(directory).as_posix()
        data = path.read_bytes()
        previous = existing(key)
        if path.parent == base:
            data = merge_metadata(previous, data)
        elif previous and (ET.fromstring(previous).findtext("versioning/lastUpdated", "")
                           > ET.fromstring(data).findtext("versioning/lastUpdated", "")):
            # An older queued workflow must not roll back snapshot discovery.
            continue
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            put(key + "." + algorithm, hashlib.new(algorithm, data).hexdigest().encode(), True)
        put(key, data, True)
    # Validate the public path, not just authenticated bucket access.
    for path in artifacts:
        if path.suffix in (".jar", ".pom"):
            key = path.relative_to(directory).as_posix()
            verify_public(key, path.read_bytes())


if __name__ == "__main__":
    publish(Path(sys.argv[1]))
