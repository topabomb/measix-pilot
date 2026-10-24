from pathlib import Path

import pytest

from models.entry import TranslationEntry
from services.dead_entry_finder import DeadEntryFinder, ReferenceScanError
from services.xml_parser import StringsXmlParser


def write(root, path, content):
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    return target


def test_cross_module_java_kotlin_and_android_xml_references(tmp_path):
    write(tmp_path, "app/src/main/java/Main.kt", "val id = other.R.string.shared")
    write(tmp_path, "library/src/main/java/Labels.java", "int id = R.string.java_label;")
    for directory in ("layout-land", "menu", "navigation", "xml"):
        write(tmp_path, f"library/src/main/res/{directory}/view.xml",
              f'<root label="@string/{directory.replace("-", "_")}"/>')
    write(tmp_path, "app/src/main/AndroidManifest.xml", '<manifest label="@string/manifest"/>')
    write(tmp_path, "library/src/main/res/values/strings.xml",
          '<resources><string name="alias">@string/alias_target</string></resources>')
    keys = DeadEntryFinder(tmp_path).find_referenced_keys(["app/src/main/**/*.kt"])
    assert keys == {"shared", "java_label", "layout_land", "menu", "navigation", "xml", "manifest", "alias_target"}


def test_generated_output_is_pruned_even_with_explicit_patterns(tmp_path):
    for directory in ("build", "generated", ".git", ".venv", "node_modules"):
        write(tmp_path, f"library/{directory}/R.kt", "R.string.stale")
        write(tmp_path, f"library/{directory}/res/layout/broken.xml", "<broken")
    write(tmp_path, "library/src/main/java/Use.kt", "R . string . alive")
    assert DeadEntryFinder(tmp_path).find_referenced_keys(["**/*"]) == {"alive"}


def test_dynamic_keep_list_and_reserved_names_do_not_become_deletion_candidates(tmp_path):
    entries = [TranslationEntry(key) for key in ("dynamic_label", "app_name", "unused")]
    assert DeadEntryFinder(tmp_path, keep_keys=["dynamic_label"]).mark_dead_entries(entries, []) == 1
    assert [entry.key for entry in entries if entry.is_dead] == ["unused"]


@pytest.mark.parametrize("failure", [PermissionError("denied"), UnicodeError("invalid UTF-8")])
def test_failed_file_read_clears_old_suggestions_and_keeps_cause(tmp_path, monkeypatch, failure):
    path = write(tmp_path, "library/src/main/java/Unreadable.kt", "R.string.in_use")
    original = Path.read_text
    def fail(selected, *args, **kwargs):
        if selected == path:
            raise failure
        return original(selected, *args, **kwargs)
    monkeypatch.setattr(Path, "read_text", fail)
    entries = [TranslationEntry("in_use", is_dead=True), TranslationEntry("unreferenced", is_dead=True)]
    with pytest.raises(ReferenceScanError) as caught:
        DeadEntryFinder(tmp_path).mark_dead_entries(entries, [])
    assert str(path) in str(caught.value)
    assert caught.value.__cause__ is failure
    assert not any(entry.is_dead for entry in entries)


def test_directory_enumeration_failure_cannot_publish_partial_scan(tmp_path, monkeypatch):
    failure = PermissionError(13, "denied", str(tmp_path / "library"))
    def walk(root, onerror):
        onerror(failure)
        yield
    monkeypatch.setattr("services.dead_entry_finder.os.walk", walk)
    entries = [TranslationEntry("unknown", is_dead=True)]
    with pytest.raises(ReferenceScanError, match="library"):
        DeadEntryFinder(tmp_path).mark_dead_entries(entries, [])
    assert not entries[0].is_dead


def test_malformed_source_xml_aborts_suggestions_without_modifying_resources(tmp_path):
    path = write(tmp_path, "library/src/main/res/layout/broken.xml", '<root label="@string/used"')
    original = path.read_bytes()
    with pytest.raises(ReferenceScanError) as caught:
        DeadEntryFinder(tmp_path).find_referenced_keys([])
    assert str(path) in str(caught.value)
    assert path.read_bytes() == original


def test_delete_candidate_still_uses_original_atomic_xml_owner(tmp_path, monkeypatch):
    path = write(tmp_path, "app/src/main/res/values/strings.xml",
                 '<resources><string name="unused">Old</string></resources>')
    entries = [TranslationEntry("unused")]
    assert DeadEntryFinder(tmp_path).mark_dead_entries(entries, []) == 1
    original = path.read_bytes()
    def fail(*args):
        raise OSError("atomic replace failed")
    monkeypatch.setattr("services.xml_parser.os.replace", fail)
    with pytest.raises(OSError, match="atomic replace failed"):
        StringsXmlParser.delete_entry(path, entries[0].key)
    assert path.read_bytes() == original
    assert list(path.parent.iterdir()) == [path]
