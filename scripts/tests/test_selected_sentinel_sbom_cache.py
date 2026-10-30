import collections
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    'selected_sentinel_cache', Path(__file__).resolve().parents[1] / 'selected-sentinel-sbom.py'
)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SentinelCapturedTreeTokenCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'tree.stdout'
        self.metadata = {'packages': [self.package(name) for name in ('root', 'a', 'b', 'c')]}

    @staticmethod
    def package(name, identity=None):
        return {'id': identity or name + '@1.0.0', 'name': name, 'version': '1.0.0',
                'source': 'registry+https://github.com/rust-lang/crates.io-index'}

    @staticmethod
    def ref(identity):
        return 'urn:foxhole:cargo:' + hashlib.sha256(identity.encode()).hexdigest()

    def parse(self, raw, metadata=None):
        self.path.write_bytes(raw)
        return MODULE.captured_tree(self.path, metadata or self.metadata)

    def test_repeated_tokens_are_resolved_once_but_every_parent_edge_is_retained(self):
        raw = b'0|root v1.0.0|\n1|a v1.0.0|\n2|b v1.0.0|\n1|c v1.0.0|\n2|b v1.0.0|\n'
        with patch.object(MODULE.re, 'fullmatch', wraps=MODULE.re.fullmatch) as resolver:
            root, edges = self.parse(raw)
        self.assertEqual(4, resolver.call_count)
        self.assertEqual(self.ref('root@1.0.0'), root)
        self.assertEqual({self.ref('a@1.0.0'), self.ref('c@1.0.0')}, edges[root])
        self.assertEqual({self.ref('b@1.0.0')}, edges[self.ref('a@1.0.0')])
        self.assertEqual({self.ref('b@1.0.0')}, edges[self.ref('c@1.0.0')])

    def test_cached_token_still_rejects_a_depth_jump_in_a_different_stack(self):
        raw = b'0|root v1.0.0|\n1|a v1.0.0|\n2|b v1.0.0|\n3|c v1.0.0|\n1|a v1.0.0|\n3|c v1.0.0|\n'
        with self.assertRaisesRegex(ValueError, 'depth jump'):
            self.parse(raw)

    def test_cached_token_still_rejects_a_cycle_in_a_different_parent(self):
        raw = b'0|root v1.0.0|\n1|b v1.0.0|\n2|a v1.0.0|\n1|a v1.0.0|\n2|a v1.0.0|\n'
        with self.assertRaisesRegex(ValueError, 'package cycle'):
            self.parse(raw)

    def test_cached_root_token_cannot_create_a_second_root(self):
        with self.assertRaisesRegex(ValueError, 'multiple roots'):
            self.parse(b'0|root v1.0.0|\n1|a v1.0.0|\n1|a v1.0.0|\n0|root v1.0.0|\n')

    def test_control_unknown_ambiguous_and_invalid_utf8_after_valid_hits_are_refused(self):
        prefix = b'0|root v1.0.0|\n1|b v1.0.0|\n1|b v1.0.0|\n'
        cases = [(b'1|a v1.0.0\t|\n', ValueError, 'control'),
                 (b'1|unknown v1.0.0|\n', ValueError, 'unknown or ambiguous'),
                 (b'1|a v1.0.0\xff|\n', UnicodeDecodeError, None)]
        for suffix, error, message in cases:
            with self.subTest(suffix=suffix):
                if message:
                    with self.assertRaisesRegex(error, message):
                        self.parse(prefix + suffix)
                else:
                    with self.assertRaises(error):
                        self.parse(prefix + suffix)
        ambiguous = {'packages': self.metadata['packages'] + [self.package('a', 'other-a@1.0.0')]}
        with self.assertRaisesRegex(ValueError, 'unknown or ambiguous'):
            self.parse(prefix + b'1|a v1.0.0|\n', ambiguous)

    def test_line_count_size_and_newline_checks_run_even_after_hits(self):
        prefix = b'0|root v1.0.0|\n1|a v1.0.0|\n1|a v1.0.0|\n'
        with patch.object(MODULE, 'TREE_MAX_LINES', 3):
            with self.assertRaisesRegex(ValueError, 'oversized or incomplete'):
                self.parse(prefix + b'1|a v1.0.0|\n')
        for suffix in (b'1|a v1.0.0|', b'1|' + b'a' * 4094 + b'|\n'):
            with self.subTest(suffixLength=len(suffix)):
                with self.assertRaisesRegex(ValueError, 'oversized or incomplete'):
                    self.parse(prefix + suffix)

    def test_cache_is_local_to_each_metadata_invocation(self):
        raw = b'0|root v1.0.0|\n1|a v1.0.0|\n1|a v1.0.0|\n'
        first = self.parse(raw)[1]
        second_metadata = {'packages': [self.package('root'), self.package('a', 'different-a@1.0.0')]}
        with patch.object(MODULE.re, 'fullmatch', wraps=MODULE.re.fullmatch) as resolver:
            second = self.parse(raw, second_metadata)[1]
        self.assertEqual(2, resolver.call_count)
        self.assertNotEqual(first, second)
        self.assertEqual({self.ref('different-a@1.0.0')}, second[self.ref('root@1.0.0')])

    def test_lru_entry_eviction_keeps_recent_tokens_and_preserves_complete_graph(self):
        raw = b'0|root v1.0.0|\n1|a v1.0.0|\n1|b v1.0.0|\n1|a v1.0.0|\n1|c v1.0.0|\n1|a v1.0.0|\n1|b v1.0.0|\n'
        expected = self.parse(raw)
        with patch.object(MODULE, 'TREE_TOKEN_CACHE_MAX_ENTRIES', 2):
            with patch.object(MODULE.re, 'fullmatch', wraps=MODULE.re.fullmatch) as resolver:
                actual = self.parse(raw)
        self.assertEqual(expected, actual)
        self.assertEqual(5, resolver.call_count)

    def test_raw_byte_budget_evicts_without_dropping_or_accepting_tree_lines(self):
        observed = []
        class ObservedCache(collections.OrderedDict):
            def __setitem__(self, key, value):
                super().__setitem__(key, value)
                observed.append((len(self), sum(len(raw) for raw in self)))
        raw = b'0|root v1.0.0|\n1|a v1.0.0|\n1|b v1.0.0|\n1|a v1.0.0|\n'
        expected = self.parse(raw)
        budget = len(b'1|a v1.0.0|\n')
        with patch.object(MODULE, 'TREE_TOKEN_CACHE_MAX_BYTES', budget):
            with patch.object(MODULE, 'OrderedDict', ObservedCache):
                self.assertEqual(expected, self.parse(raw))
        self.assertTrue(observed)
        self.assertTrue(all(count <= 1 and size <= budget for count, size in observed))

    def test_disabled_cache_has_identical_semantics(self):
        raw = b'0|root v1.0.0|\n1|a v1.0.0|\n1|a v1.0.0|\n'
        expected = self.parse(raw)
        with patch.object(MODULE, 'TREE_TOKEN_CACHE_MAX_ENTRIES', 0):
            with patch.object(MODULE.re, 'fullmatch', wraps=MODULE.re.fullmatch) as resolver:
                self.assertEqual(expected, self.parse(raw))
        self.assertEqual(3, resolver.call_count)


if __name__ == '__main__':
    unittest.main()
