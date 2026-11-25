import importlib.util
import json
from pathlib import Path
from unittest.mock import patch

import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('flowseal_sync', ROOT / 'scripts/sync_upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def test_moved_locked_tag_rejected_before_archive_or_writes(tmp_path):
    lock = tmp_path / 'lock.json'
    lock.write_text(json.dumps({'tag': 'v1.2.3', 'commit': 'a' * 40}))
    with patch.object(sync, 'LOCK', lock), patch.object(sync, 'fetch', return_value=json.dumps({'sha': 'b' * 40}).encode()) as fetch:
        with pytest.raises(ValueError, match='(?i)tag.*(moved|changed)'):
            sync.sync('v1.2.3')
    assert fetch.call_count == 1
    assert json.loads(lock.read_text())['commit'] == 'a' * 40


@pytest.mark.parametrize(('latest', 'draft', 'prerelease', 'expected'), [
    ('v1.12.0', False, False, 'v1.12.0'),
    ('v1.11.4', False, False, None),
    ('1.11.4.0', False, False, None),
    ('v1.9.9', False, False, None),
    ('v1.12.0', True, False, None),
    ('v1.12.0', False, True, None),
])
def test_plan_only_selects_a_newer_published_stable_release(latest, draft, prerelease, expected):
    lock = {'tag': 'v1.11.4', 'commit': 'a' * 40}
    release = {'tag_name': latest, 'draft': draft, 'prerelease': prerelease}
    assert sync.plan_update(lock, lock['commit'], release) == expected


def test_plan_rejects_moved_pin_even_if_a_newer_tag_exists():
    with pytest.raises(ValueError, match='Locked Flowseal tag.*moved'):
        sync.plan_update({'tag': 'v1.11.4', 'commit': 'a' * 40}, 'b' * 40,
                         {'tag_name': 'v1.12.0', 'draft': False, 'prerelease': False})
