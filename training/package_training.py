"""Package only training code, rule snapshots and pinned simulator artifacts."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent
TOP_LEVEL = {"README.md", "INSTALL_WINDOWS.md", "TEST_REPORT.md", "THIRD_PARTY.md",
             "requirements.txt", "setup_windows.ps1", "build_simulator.py", "package_training.py"}
DIRECTORIES = {"yaoming_rl", "tests", "java", "java-tests", "engine-src", "simulator"}
ALLOWED = {".py", ".java", ".md", ".json", ".jar", ".txt", ".ps1"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        raise SystemExit(f"Refusing to overwrite an existing release: {output}")
    required = [ROOT / name for name in ("README.md", "INSTALL_WINDOWS.md", "TEST_REPORT.md",
                                        "simulator/simulator.jar", "simulator/manifest.json")]
    if not all(path.is_file() for path in required):
        raise SystemExit("Build and test the simulator and finish the documentation before packaging")
    files = []
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(ROOT)
        if "__pycache__" in relative.parts or ".pytest_cache" in relative.parts:
            continue
        if (len(relative.parts) == 1 and relative.name in TOP_LEVEL or relative.parts[0] in DIRECTORIES) and path.suffix in ALLOWED:
            files.append(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            archive.write(path, "yaoming-training/" + path.relative_to(ROOT).as_posix())
    with output.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    print(json.dumps({"archive": str(output), "bytes": output.stat().st_size, "files": len(files), "sha256": digest}, indent=2))


if __name__ == "__main__":
    main()
