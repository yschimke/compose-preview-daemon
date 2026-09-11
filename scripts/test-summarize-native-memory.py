#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('native_memory', Path(__file__).with_name('summarize-native-memory.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

NATIVE = '''Total: reserved=12KB, committed=8KB
- Java Heap (reserved=4KB, committed=4KB)
[0x00001000 - 0x00002000] reserved and committed 4KB for Java Heap from
[0x00002000 - 0x00003000] reserved 4KB for Code from
'''


def mapping(start, end, pss, path=''):
    return (f'{start:x}-{end:x} rw-p 00000000 00:00 0 {path}\n'
            f'Size: {(end-start)//1024} kB\nRss: {pss} kB\nPss: {pss} kB\n'
            f'Private_Clean: 0 kB\nPrivate_Dirty: {pss} kB\nAnonymous: {pss} kB\n')


class CorrelateTest(unittest.TestCase):
    def test_whole_vma_assignment_and_unmatched_memory_preserve_totals(self):
        result = module.correlate(NATIVE, mapping(0x1000, 0x2000, 3) + mapping(0x4000, 0x5000, 2) + mapping(0x6000, 0x7000, 1, '/tmp/libexample.so'))
        groups = result['mappingGroupsKiB']
        self.assertEqual(3, groups['NMT reserved: Java Heap']['Pss'])
        self.assertEqual(2, groups['outside NMT reservations: anonymous']['Pss'])
        self.assertEqual(1, groups['file: libexample.so']['Pss'])
        self.assertEqual(6, result['smapsTotalPssKiB'])
        self.assertEqual(6, sum(g['Pss'] for g in groups.values()))
        self.assertEqual(4, result['nmtCategories']['Java Heap']['committedKiB'])

    def test_crossing_reservations_are_not_apportioned(self):
        result = module.correlate(NATIVE, mapping(0x1000, 0x3000, 7))
        self.assertEqual(['mixed/partial NMT reservation'], list(result['mappingGroupsKiB']))
        self.assertEqual(['Code', 'Java Heap'], result['mixedMappings'][0]['categories'])
        self.assertEqual(7, result['mixedMappings'][0]['pssKiB'])

    def test_partial_overlap_is_not_called_heap(self):
        result = module.correlate(NATIVE, mapping(0x0000, 0x2000, 7))
        self.assertEqual(['mixed/partial NMT reservation'], list(result['mappingGroupsKiB']))

    def test_incomplete_sources_fail_instead_of_reporting_zero_memory(self):
        for native, smaps in [('', mapping(0x1000, 0x2000, 3)), (NATIVE, ''), (NATIVE, '1000-2000 rw-p 00000000 00:00 0\n')]:
            with self.assertRaises(ValueError):
                module.correlate(native, smaps)


if __name__ == '__main__':
    unittest.main()
