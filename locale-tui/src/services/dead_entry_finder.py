"""Conservative cross-module references; incomplete scans never suggest deletion."""

import fnmatch
import os
import re
from pathlib import Path
from typing import TYPE_CHECKING

from lxml import etree

if TYPE_CHECKING:
    from models.entry import TranslationEntry


class ReferenceScanError(OSError):
    """A source could not be inspected, so absence of references is unproven."""


class DeadEntryFinder:
    PATTERNS = (r"\bR\s*\.\s*string\s*\.\s*(\w+)", r"@string/(\w+)")
    RESERVED_KEYS = {"app_name"}
    EXCLUDED_DIRECTORIES = {
        "build", "generated", ".git", ".gradle", ".kotlin", ".idea", ".venv",
        "venv", "node_modules", "__pycache__", ".pytest_cache",
    }

    def __init__(self, project_root: Path, keep_keys=()):
        self.project_root = project_root
        # Dynamic name lookups cannot be inferred from a regex. Retained keys belong
        # to the resource module, even when their consumers are in another module.
        self.keep_keys = self.RESERVED_KEYS | set(keep_keys)
        self.compiled_patterns = [re.compile(pattern) for pattern in self.PATTERNS]

    def find_referenced_keys(self, source_patterns: list[str]) -> set[str]:
        referenced = set()
        patterns = [pattern.replace("\\", "/") for pattern in source_patterns]
        for pattern in patterns:
            if Path(pattern).is_absolute() or ".." in Path(pattern).parts:
                raise ReferenceScanError(f"Source pattern must stay within {self.project_root}: {pattern}")

        def fail(error):
            raise ReferenceScanError(f"Reference scan failed at {error.filename}: {error}") from error

        # glob silently skips unreadable directories; walk must have an error callback.
        for directory, directories, files in os.walk(self.project_root, onerror=fail):
            directories[:] = sorted(name for name in directories if name.lower() not in self.EXCLUDED_DIRECTORIES)
            for name in directories:
                path = Path(directory) / name
                if path.is_symlink() or path.is_junction():
                    raise ReferenceScanError(f"Reference scan cannot follow linked source directory: {path}")
            for name in sorted(files):
                path = Path(directory) / name
                relative = path.relative_to(self.project_root)
                source = path.suffix.lower() in {".kt", ".java", ".kts"}
                resource = path.suffix.lower() == ".xml" and ("res" in relative.parts or name == "AndroidManifest.xml")
                extra = any(fnmatch.fnmatchcase(relative.as_posix(), pattern) for pattern in patterns)
                if source or resource or extra:
                    referenced.update(self._extract_keys_from_file(path))
        return referenced

    def _extract_keys_from_file(self, file_path: Path) -> set[str]:
        try:
            content = file_path.read_text(encoding="utf-8")
            if file_path.suffix.lower() == ".xml":
                tree = etree.fromstring(content.encode("utf-8"), etree.XMLParser(resolve_entities=False, no_network=True))
                if tree.getroottree().docinfo.doctype:
                    raise ValueError("DOCTYPE is not supported")
            return {key for pattern in self.compiled_patterns for key in pattern.findall(content)}
        except (OSError, UnicodeError, etree.Error, ValueError) as error:
            raise ReferenceScanError(f"Reference scan failed at {file_path}: {type(error).__name__}: {error}") from error

    def mark_dead_entries(self, entries: list["TranslationEntry"], source_patterns: list[str]) -> int:
        # A failed refresh must not retain deletion advice from older source contents.
        for entry in entries:
            entry.is_dead = False
        referenced = self.find_referenced_keys(source_patterns) | self.keep_keys
        for entry in entries:
            entry.is_dead = entry.key not in referenced
        return sum(entry.is_dead for entry in entries)
