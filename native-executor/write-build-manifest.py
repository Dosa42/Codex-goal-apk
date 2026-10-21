import hashlib
import json
import os
from pathlib import Path
import sys

root = Path(sys.argv[1])
manifest = {
    "sourceBaseCommit": "c382cb99717bbaedd0e79c61835ddfc966423424",
    "patchedSourceCommit": os.environ["STABLE_GIT_COMMIT"],
    "patchedSourceTree": os.environ["EXECUTOR_SOURCE_TREE"],
    "deliveryCommit": os.environ["GITHUB_SHA"],
    "runId": os.environ["GITHUB_RUN_ID"],
    "runAttempt": os.environ["GITHUB_RUN_ATTEMPT"],
    "target": "aarch64-linux-android",
    "rust": "1.95.0",
    "ndk": os.environ["ANDROID_NDK_VERSION"],
    "retentionDefault": "unlimited; explicit session/close or executor shutdown ends session",
    "state": "built; not installed or runtime accepted",
    "files": {
        path.name: {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bytes": path.stat().st_size}
        for path in sorted(root.iterdir()) if path.is_file()
    },
}
(root / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
