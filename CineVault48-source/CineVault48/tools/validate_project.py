#!/usr/bin/env python3
"""Perform offline structural checks that do not require the Android SDK."""

import json
import os
import sqlite3
import sys
import tempfile
import xml.etree.ElementTree as ET


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def require_files() -> None:
    """Fail when an essential source, resource or documentation file is missing."""
    required = [
        "settings.gradle.kts",
        "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/assets/config.json",
        "app/src/main/java/com/vidal/cinevault/core/ScreenshotManager.kt",
        "app/src/main/java/com/vidal/cinevault/data/CineVaultDatabase.kt",
        "app/src/main/java/com/vidal/cinevault/ui/MainActivity.kt",
        "docs/DATABASE.sql",
        "docs/ARCHITECTURE.md",
        "docs/DEPLOYMENT.md",
    ]
    missing = [path for path in required if not os.path.isfile(os.path.join(ROOT, path))]
    if missing:
        raise AssertionError(f"Missing required files: {missing}")


def validate_json() -> None:
    """Validate configuration keys and safe lifecycle ordering."""
    path = os.path.join(ROOT, "app/src/main/assets/config.json")
    with open(path, encoding="utf-8") as handle:
        config = json.load(handle)
    assert config["retention_hours"] > config["warning_hours_before_expiry"] > 0
    assert config["trash_grace_hours"] > 0
    assert config["check_interval_hours"] >= 1
    assert config["thumbnail_size_px"] >= 256


def validate_xml() -> None:
    """Parse every Android XML resource and manifest."""
    paths = []
    for base, _, names in os.walk(os.path.join(ROOT, "app/src/main")):
        for name in names:
            if name.endswith(".xml"):
                paths.append(os.path.join(base, name))
    for path in paths:
        ET.parse(path)
    assert paths, "No Android XML files found"


def validate_schema() -> None:
    """Execute the documented SQLite schema and verify required tables and indexes."""
    schema_path = os.path.join(ROOT, "docs/DATABASE.sql")
    with open(schema_path, encoding="utf-8") as handle:
        schema = handle.read()
    with tempfile.NamedTemporaryFile(suffix=".db") as database_file:
        connection = sqlite3.connect(database_file.name)
        connection.executescript(schema)
        tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert {"imagenes", "proyectos", "logs"}.issubset(tables)
        image_columns = {row[1] for row in connection.execute("PRAGMA table_info(imagenes)")}
        assert {"status", "expires_at", "warning_at", "delete_after", "type"}.issubset(image_columns)
        connection.close()


def validate_sources() -> None:
    """Check for required API names and unfinished markers in Kotlin sources."""
    source_root = os.path.join(ROOT, "app/src")
    combined = ""
    for base, _, names in os.walk(source_root):
        for name in names:
            if name.endswith(".kt"):
                with open(os.path.join(base, name), encoding="utf-8") as handle:
                    combined += handle.read() + "\n"
    required_symbols = [
        "class ScreenshotManager",
        "fun addImage",
        "fun add_image",
        "fun markPermanent",
        "fun mark_permanent",
        "fun checkExpired",
        "fun check_expired",
        "fun deleteExpired",
        "fun delete_expired",
        "fun search",
        "class MaintenanceWorker",
        "class MainActivity",
    ]
    for symbol in required_symbols:
        assert symbol in combined, f"Missing symbol: {symbol}"
    assert "TODO(" not in combined
    assert "FIXME" not in combined


def main() -> int:
    """Run every structural validation and report a single concise outcome."""
    checks = [require_files, validate_json, validate_xml, validate_schema, validate_sources]
    try:
        for check in checks:
            check()
    except Exception as error:
        print(f"VALIDATION FAILED: {error}", file=sys.stderr)
        return 1
    print(f"VALIDATION PASSED: {len(checks)} checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
