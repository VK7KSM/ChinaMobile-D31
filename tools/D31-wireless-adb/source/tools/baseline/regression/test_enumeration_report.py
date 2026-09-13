"""核验完整命令行产生的合成报告及旧报告渲染兼容性。"""
import importlib.util
import json
from pathlib import Path
import sys
import unittest

module_path, report_root = sys.argv[1:3]
del sys.argv[1:3]
spec = importlib.util.spec_from_file_location('candidate_compare', module_path)
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)
root = Path(report_root)


class ReportTests(unittest.TestCase):
    def setUp(self):
        self.report = json.loads((root / 'report-private.json').read_text(encoding='utf-8'))

    def test_full_cli_keeps_configuration_gap(self):
        self.assertEqual(0, self.report['categories']['CONFIGURATION_SEMANTICS']['total'])
        self.assertEqual(1, self.report['categories']['INVENTORY']['readFailed'])
        self.assertIn('CONFIGURATION_SEMANTICS_NOT_COLLECTED', self.report['gaps'])
        self.assertFalse(self.report['repairPlanGenerated'])
        self.assertEqual('coverage-1.0.1', self.report['engineVersion'])

    def test_chinese_summary_shows_inventory_and_gap(self):
        text = (root / 'report-private.md').read_text(encoding='utf-8')
        self.assertIn('| 目录枚举 | 1 | 0 | 0 | 1 |', text)
        self.assertIn('未取得可计入配置语义类别的字段证据', text)

    def test_old_report_without_inventory_still_renders(self):
        self.report['categories'].pop('INVENTORY')
        self.assertIn('配置语义', subject.render(self.report))

    def test_semantic_fields_do_not_claim_complete_configuration(self):
        self.report['gaps'].remove('CONFIGURATION_SEMANTICS_NOT_COLLECTED')
        self.assertIn('未证明配置完整或修复完成', subject.render(self.report))

    def test_wrapped_input_is_bound_without_copying_sources(self):
        self.assertEqual('/manifest', self.report['inputs']['observation']['manifestPointer'])
        text = json.dumps(self.report)
        self.assertNotIn('fixture-target', text)
        self.assertNotIn('fixture-baseline', text)


if __name__ == '__main__':
    unittest.main()
