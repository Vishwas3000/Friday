#!/usr/bin/env python3
"""Independent stdlib audit of prepared v2 rows against official raw TRAIN shards."""
import argparse
import ast
import collections
import hashlib
import json
import re
import tempfile
from pathlib import Path

def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)

def checksum(raw):
    return hashlib.sha256(raw).hexdigest()

def file_hash(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()

def system_prompt(adapter, expected_sha256):
    raw = adapter.read_bytes()
    assert checksum(raw) == expected_sha256, 'Adapter SHA256 mismatch'
    assignments = [node.value for node in ast.parse(raw).body
                   if isinstance(node, ast.Assign)
                   and any(isinstance(target, ast.Name) and target.id == 'SYSTEM' for target in node.targets)]
    assert len(assignments) == 1, 'Expected one literal SYSTEM assignment'
    value = ast.literal_eval(assignments[0])
    assert isinstance(value, str) and value, 'SYSTEM must be nonempty literal text'
    return value

def audit(sources, prepared):
    if not __debug__:
        raise ValueError('Run the assert-based independent audit without Python -O')
    manifest = json.loads((prepared / 'manifest.json').read_text())
    assert manifest['status'] == 'complete' and manifest['protocol'] == 'dialogue_state_v2'
    source_manifest = sources / 'manifest.json'
    assert file_hash(source_manifest) == manifest['source_manifest']['sha256'], 'Source manifest SHA256 mismatch'
    frozen_system = system_prompt(Path(__file__).with_name('dialogue_data.py'), manifest['adapter_sha256'])
    fetched = json.loads(source_manifest.read_text())
    assert fetched['status'] == 'complete'
    source_info = {(e['dataset'], e['source_path']): e for e in fetched['files'].values()}
    selected = collections.defaultdict(dict)
    groups = {'train': set(), 'validation': set()}
    inputs = {'train': set(), 'validation': set()}
    identities, counts = set(), collections.Counter()
    maximum = 0
    for split in ('train', 'validation'):
        path = prepared / (split + '.jsonl')
        assert file_hash(path) == manifest['files'][split]['sha256']
        with path.open(encoding='utf-8') as stream:
            for line in stream:
                row = json.loads(line)
                p, target = row['provenance'], json.loads(row['response'])
                assert set(row) == {'id', 'split', 'kind', 'messages', 'response', 'provenance'}
                assert row['kind'] == 'dialogue_state_v2' and row['split'] == split and p['source_split'] == 'train'
                assert row['id'] not in identities
                identities.add(row['id'])
                assert [m['role'] for m in row['messages']] == ['system', 'user']
                assert row['messages'][0]['content'] == frozen_system, 'SYSTEM prompt mismatch'
                length = sum(len(m['content']) for m in row['messages'])
                maximum = max(maximum, length)
                assert length <= manifest['parameters']['max_input_chars']
                user = json.loads(row['messages'][1]['content'])
                assert set(user) == {'selected_service_schema', 'previous_state', 'other_service_states', 'previous_system_utterance', 'current_user_utterance'}
                schema = user['selected_service_schema']
                slots = {s['name']: s for s in schema['slots']}
                intents = {i['name'] for i in schema['intents']} | {'NONE'}
                assert set(target) == {'service', 'active_intent', 'slot_values', 'requested_slots'}
                assert target['service'] == p['service'] == schema['service_name']
                assert target['active_intent'] in intents
                assert set(target['slot_values']) <= slots.keys() and set(target['requested_slots']) <= slots.keys()
                for name, values in target['slot_values'].items():
                    if slots[name].get('is_categorical'):
                        assert set(values) <= set(slots[name].get('possible_values', [])) | {'dontcare'}, row['id']
                ih, th = checksum(canonical(row['messages']).encode()), checksum(canonical(target).encode())
                assert ih == p['input_fingerprint'] and th == p['target_fingerprint']
                assert checksum((ih + '\n' + th).encode()) == p['fingerprint']
                assert ih not in inputs[split]
                inputs[split].add(ih)
                groups[split].add((p['dataset'], p['dialogue_id']))
                threshold = int(manifest['parameters']['validation_fraction'] * (1 << 256))
                expected_split = 'validation' if int(checksum((p['dataset'] + '\n' + p['dialogue_id']).encode()), 16) < threshold else 'train'
                assert split == expected_split
                entry = source_info[(p['dataset'], p['file'])]
                assert p['file_sha256'] == entry['sha256'] and p['commit'] == entry['revision']
                assert re.search(r'(^|/)train/dialogues_[0-9]{3}\.json$', p['file']) and entry['split'] == 'train'
                key = (p['dialogue_id'], p['turn'], p['service'])
                assert key not in selected[(p['dataset'], p['file'])]
                selected[(p['dataset'], p['file'])][key] = (checksum(canonical(user).encode()), th)
                counts[split] += 1
        assert counts[split] == manifest['files'][split]['rows']
    assert not groups['train'] & groups['validation'] and not inputs['train'] & inputs['validation']
    checked = 0
    for (dataset, file), requested in selected.items():
        schema_file = 'train/schema.json' if dataset == 'sgd' else 'data/MultiWOZ_2.2/schema.json'
        schema_path = sources / dataset / schema_file
        assert file_hash(schema_path) == source_info[(dataset, schema_file)]['sha256']
        schemas = {s['service_name']: s for s in json.loads(schema_path.read_text())}
        raw = (sources / dataset / file).read_bytes()
        assert checksum(raw) == source_info[(dataset, file)]['sha256']
        for dialogue in json.loads(raw.decode('utf-8')):
            known, previous_system = {}, ''
            for index, turn in enumerate(dialogue['turns']):
                if turn['speaker'] == 'SYSTEM':
                    previous_system = turn['utterance']
                    continue
                assert turn['speaker'] == 'USER'
                before, updates = dict(known), {}
                for frame in turn['frames']:
                    if 'state' not in frame:
                        continue
                    service = frame['service']
                    state = {'service': service, **frame['state']}
                    updates[service] = state
                    key = (dialogue['dialogue_id'], index, service)
                    if key not in requested:
                        continue
                    expected = {'selected_service_schema': schemas[service], 'previous_state': before.get(service),
                                'other_service_states': {s: v for s, v in before.items() if s != service},
                                'previous_system_utterance': previous_system, 'current_user_utterance': turn['utterance']}
                    ih, th = requested[key]
                    assert ih == checksum(canonical(expected).encode()), key
                    assert th == checksum(canonical(state).encode()), key
                    checked += 1
                known.update(updates)
    assert checked == sum(counts.values()) == manifest['prepared_rows']
    report = {'protocol': 'dialogue_state_v2', 'rows_verified': checked, 'rows_by_split': dict(counts),
              'declared_target_schema_violations': 0, 'categorical_enum_violations': 0,
              'target_vs_current_human_annotation_mismatches': 0,
              'input_vs_prior_states_and_raw_utterances_mismatches': 0,
              'conversation_overlap': 0, 'input_fingerprint_overlap': 0, 'all_sources_official_train': True,
              'max_input_characters': maximum, 'selected_dialogues': {s: len(v) for s, v in groups.items()},
              'manifest_sha256': file_hash(prepared / 'manifest.json'),
              'source_manifest_sha256': file_hash(source_manifest),
              'adapter_sha256': manifest['adapter_sha256'], 'system_prompt_sha256': checksum(frozen_system.encode()),
              'audit_script_sha256': file_hash(Path(__file__)),
              'scope': 'Independent stdlib reconstruction from raw TRAIN sources; no adapter import, GPU or model execution.'}
    (prepared / 'independent-check.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))

def self_check():
    assert canonical({'b': ['é'], 'a': 1}) == canonical({'a': 1, 'b': ['é']})
    assert checksum(b'hello') == '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824'
    try:
        canonical({'invalid': float('nan')})
    except ValueError:
        pass
    else:
        raise AssertionError('Nonfinite JSON was accepted')
    with tempfile.TemporaryDirectory(prefix='friday-audit-check-') as directory:
        path = Path(directory) / 'source.json'
        path.write_bytes(b'{"source":"synthetic audit fixture"}')
        assert file_hash(path) == checksum(path.read_bytes())
        original = file_hash(path)
        path.write_bytes(path.read_bytes() + b' ')
        assert file_hash(path) != original
        adapter = Path(directory) / 'adapter.py'
        adapter.write_text("SYSTEM = ('literal ' 'prompt')\nraise RuntimeError('must never execute')\n")
        assert system_prompt(adapter, file_hash(adapter)) == 'literal prompt'
        try:
            system_prompt(adapter, original)
        except AssertionError:
            pass
        else:
            raise AssertionError('Changed adapter hash was accepted')
    print('Independent audit self-check passed: canonical JSON, nonfinite rejection, streaming source integrity and literal prompt binding')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-check', action='store_true')
    parser.add_argument('--sources', type=Path)
    parser.add_argument('--prepared', type=Path)
    args = parser.parse_args()
    if args.self_check:
        self_check()
    elif args.sources is None or args.prepared is None:
        parser.error('--sources and --prepared are required for an audit')
    else:
        audit(args.sources, args.prepared)
