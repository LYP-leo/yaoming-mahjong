"""Build a portable, offline JVM arena from the exact checked-in game rules.

The first repository build snapshots an explicit source allowlist. Later builds
use that snapshot unless --refresh-engine is given. A released ZIP contains the
snapshot, the compiled jar and verified dependency jars; users need no Maven.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
ENGINE_FILES = ["com/mahjong/domain/Tile.java"] + [
    f"com/mahjong/yaoming/{name}.java" for name in (
        "YmEngine", "YmRoom", "YmRules", "YmScoring", "YmTiles", "YmViews",
        "YmBots", "YmBotObservation", "YmShanten", "YmTrustee",
    )
]
DEPENDENCIES = [
    ("com/fasterxml/jackson/core/jackson-databind/2.18.3/jackson-databind-2.18.3.jar",
     "510bdda75a7a6186c5bf33b851239488a1450906ae5757121f2e1cc48a7e108f"),
    ("com/fasterxml/jackson/core/jackson-core/2.18.3/jackson-core-2.18.3.jar",
     "056bc4d3e5e53ce821450fa97b3f9e0f8dde125cf6da6884353bb1f09582e1d9"),
    ("com/fasterxml/jackson/core/jackson-annotations/2.18.3/jackson-annotations-2.18.3.jar",
     "8aa5740d80b5a5025508b41bbadbaa1fb3772267c628b2e30681a4f45f8b8931"),
    ("org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar",
     "7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832"),
]


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def java_tool(name: str, explicit_home: str | None) -> str:
    task_java_home = explicit_home or os.environ.get("JAVA_HOME")
    if task_java_home:
        candidate = Path(task_java_home) / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if candidate.is_file():
            return str(candidate)
        raise RuntimeError(f"Missing {name} under JAVA_HOME/JDK argument: {candidate}")
    found = shutil.which(name)
    if not found:
        raise RuntimeError(f"{name} not found. Install JDK 21 and set JAVA_HOME; Maven is not required.")
    return found


def run(command: list[str]) -> None:
    subprocess.run(command, check=True, cwd=ROOT)


def build(refresh: bool = False, test: bool = False, jdk: str | None = None) -> dict:
    snapshot = ROOT / "engine-src"
    source_root = ROOT.parent / "backend" / "src" / "main" / "java"
    if refresh or not snapshot.is_dir():
        if not all((source_root / name).is_file() for name in ENGINE_FILES):
            raise RuntimeError("Original backend sources not found. Use the bundled engine-src snapshot; do not use --refresh-engine outside the repository.")
        for name in ENGINE_FILES:
            destination = snapshot / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source_root / name, destination)
    missing = [name for name in ENGINE_FILES if not (snapshot / name).is_file()]
    if missing:
        raise RuntimeError(f"Incomplete engine snapshot: {missing}")
    adapters = sorted((ROOT / "java").rglob("*.java"))
    if not adapters:
        raise RuntimeError("Missing Java training adapter sources")

    output = ROOT / "simulator"
    libs = output / "lib"
    libs.mkdir(parents=True, exist_ok=True)
    for coordinate, expected_hash in DEPENDENCIES:
        target = libs / Path(coordinate).name
        if target.is_file() and sha256(target) == expected_hash:
            continue
        local_cache = Path.home() / ".m2" / "repository" / coordinate
        if local_cache.is_file() and sha256(local_cache) == expected_hash:
            shutil.copyfile(local_cache, target)
        else:
            url = "https://repo.maven.apache.org/maven2/" + coordinate
            print(f"Downloading pinned dependency: {url}")
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read(10 * 1024 * 1024 + 1)
            if hashlib.sha256(data).hexdigest() != expected_hash:
                raise RuntimeError(f"Dependency checksum mismatch: {coordinate}")
            target.write_bytes(data)
    classpath = os.pathsep.join(str(libs / Path(coordinate).name) for coordinate, _ in DEPENDENCIES)
    javac = java_tool("javac", jdk)
    java = java_tool("java", jdk)
    sources = [snapshot / name for name in ENGINE_FILES] + adapters
    source_hashes = {p.relative_to(ROOT).as_posix(): sha256(p) for p in sources}
    fingerprint = hashlib.sha256(json.dumps(source_hashes, sort_keys=True).encode("utf-8")).hexdigest()
    (ROOT / "build").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="javac-", dir=ROOT / "build") as temporary:
        classes = Path(temporary)
        run([javac, "--release", "21", "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes), *map(str, sources)])
        production_classes = sorted(classes.rglob("*.class"))
        if test:
            tests = sorted((ROOT / "java-tests").rglob("*.java"))
            if not tests:
                raise RuntimeError("--test requested but Java tests are missing")
            test_classpath = os.pathsep.join([str(classes), classpath])
            run([javac, "--release", "21", "-encoding", "UTF-8", "-cp", test_classpath, "-d", str(classes), *map(str, tests)])
            run([java, "-Xmx512m", "-Dfile.encoding=UTF-8", "-ea", "-cp", test_classpath,
                 "com.mahjong.yaoming.YmTrainingTest"])
        # A failed compile/test must not replace a previously usable simulator.
        jar_temporary = output / "simulator.jar.tmp"
        with zipfile.ZipFile(jar_temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nMain-Class: com.mahjong.yaoming.YmTrainingMain\n\n")
            for path in production_classes:
                archive.write(path, path.relative_to(classes).as_posix())
        jar_temporary.replace(output / "simulator.jar")
    artifact_hashes = {"simulator.jar": sha256(output / "simulator.jar")}
    artifact_hashes.update({"lib/" + Path(coordinate).name: expected for coordinate, expected in DEPENDENCIES})
    manifest = {"format": 1, "java_release": 21, "protocol": 1,
                "engine_hash": fingerprint, "sources": source_hashes, "artifacts": artifact_hashes,
                "rules": {"yaoming-3p": {"players": 3, "minimum_fan": 4, "cap_fan": 8},
                          "yaoming-4p": {"players": 4, "minimum_fan": 3, "cap_fan": 8}},
                "note": "Original engine/scoring sources; training-only no-op replay recorder; no network or persistence."}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"built": str(output), "engine_hash": fingerprint}, ensure_ascii=False))
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--refresh-engine", action="store_true", help="Explicitly replace the bundled rule source snapshot from ../backend")
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--jdk", help="JDK 21 installation directory, if not on PATH")
    args = parser.parse_args()
    build(args.refresh_engine, args.test, args.jdk)
