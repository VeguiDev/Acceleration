"""Resolve CI version without changing gradle.properties or trusting tag contents."""
import os
import re
import subprocess
from pathlib import Path


def resolve(ref, base, default):
    if ref.startswith("refs/tags/"):
        tag = ref.removeprefix("refs/tags/")
        if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
            raise ValueError("Stable tags must use vX.Y.Z")
        version = tag[1:]
        if version != base.removesuffix("-SNAPSHOT"):
            raise ValueError("Tag does not match gradle.properties")
        subprocess.run(["git", "merge-base", "--is-ancestor", "HEAD",
                        "origin/" + default], check=True)
        return version
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT", base):
        raise ValueError("Development version must end in -SNAPSHOT")
    return base


if __name__ == "__main__":
    properties = dict(line.split("=", 1) for line in
                      Path("gradle.properties").read_text().splitlines() if "=" in line)
    print("version=" + resolve(os.environ["GITHUB_REF"], properties["version"],
                               os.environ["DEFAULT_BRANCH"]))
