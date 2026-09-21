import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('verify', Path(__file__).resolve().parents[1] / 'verify.py')
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


class VerificationTest(unittest.TestCase):
    def test_stale_project_is_removed_but_third_party_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); cache = root / 'cache'; cache.mkdir()
            (cache / 'com/mars/cloud/retired/1').mkdir(parents=True)
            (cache / 'com/mars/cloud/retired/1/old.jar').write_text('poison')
            (cache / 'org/example').mkdir(parents=True)
            (cache / 'org/example/dep.jar').write_text('dependency')
            v.seed(cache, root / 'fresh')
            self.assertFalse((root / 'fresh/com/mars/cloud').exists())
            self.assertEqual((root / 'fresh/org/example/dep.jar').read_text(), 'dependency')
            self.assertTrue((cache / 'com/mars/cloud/retired/1/old.jar').exists())

    def test_lock_rejects_second_writer(self):
        with tempfile.TemporaryDirectory() as temp:
            with v.lock(Path(temp)):
                with self.assertRaises(ValueError):
                    with v.lock(Path(temp)):
                        self.fail('second writer acquired lock')

    def test_logs_do_not_ignore_errors_or_unknown_warnings(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'build.log'
            path.write_text('12:30:00 [main] WARN example.Contract -- expected invalid input\n'
                            '[WARNING] Unknown compiler warning\n'
                            'OpenJDK warning: unexpected JVM warning\n'
                            '12:30:01 [main] ERROR example.Dns -- native library missing\n')
            found = v.inspect_log(path, [{'pattern': r'WARN Contract: expected invalid input', 'reason': 'negative test'}])
            self.assertEqual(len(found['expected']), 1)
            self.assertEqual(len(found['unknown']), 3)

    def test_missing_failed_and_skipped_test_reports_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"/>')
            (root / 'src/test').mkdir(parents=True)
            (root / 'src/test/Test.java').write_text('class Test {}')
            with self.assertRaises(ValueError): v.test_reports(root, root / 'output')
            reports = root / 'target/surefire-reports'; reports.mkdir(parents=True)
            for counts in ('tests="0"', 'tests="1" failures="1"', 'tests="1" errors="1"', 'tests="1" skipped="1"'):
                (reports / 'TEST-X.xml').write_text(f'<testsuite name="X" {counts}/>')
                with self.assertRaises(ValueError): v.test_reports(root, root / 'output')
            (reports / 'TEST-X.xml').write_text('<testsuite name="X" tests="2"><testcase name="one"/><testcase name="two"/></testsuite>')
            self.assertEqual(v.test_reports(root, root / 'output')['tests'], 2)

    def test_exact_sha_and_clean_source_required(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def git(*args): return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()
            git('init', '-q'); git('config', 'user.name', 'Test'); git('config', 'user.email', 'test@example.invalid')
            (root / 'file').write_text('one'); git('add', 'file'); git('commit', '-qm', 'one'); sha = git('rev-parse', 'HEAD')
            self.assertTrue(v.source(root, sha)['clean'])
            with v.source_locks([root]):
                with self.assertRaises(ValueError):
                    with v.source_locks([root]):
                        self.fail('source directory was built twice')
            with self.assertRaises(ValueError): v.source(root, '0' * 40)
            (root / 'file').write_text('two')
            with self.assertRaises(ValueError): v.source(root, sha)
            self.assertFalse(v.source(root, sha, True)['clean'])

    def test_nested_suite_counts_actual_cases(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"/>')
            reports = root / 'target/surefire-reports'; reports.mkdir(parents=True)
            (reports / 'TEST-X.xml').write_text('<testsuite name="X" tests="0"><testcase name="nested" classname="Display name"/></testsuite>')
            self.assertEqual(v.test_reports(root, root / 'output')['tests'], 1)

    def test_info_containing_error_is_not_a_diagnostic(self):
        self.assertIsNone(v.diagnostic('[INFO] Tests run: 2, Time elapsed: 0.019 s -- in example.error.ContractTest'))

    def test_nested_symlink_cannot_escape_repository(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); cache = root / 'cache'; (cache / 'org').mkdir(parents=True)
            external = root / 'external'; external.mkdir()
            (cache / 'org/example').symlink_to(external, target_is_directory=True)
            with self.assertRaises(ValueError): v.seed(cache, root / 'fresh')

    def test_hidden_nested_symlink_is_also_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); cache = root / 'cache'; (cache / 'org/.metadata').mkdir(parents=True)
            external = root / 'external'; external.mkdir()
            (cache / 'org/.metadata/link').symlink_to(external, target_is_directory=True)
            with self.assertRaises(ValueError): v.seed(cache, root / 'fresh')

    def test_runner_display_flag_is_allowed_but_build_overrides_are_rejected(self):
        for args in ('-ntp', '--no-transfer-progress', '-ntp -Dmaven.repo.local=cache'):
            self.assertNotIn('MAVEN_ARGS', v.maven_environment({'MAVEN_ARGS': args}))
        for args in ('-DskipTests', '-ntp -Dmaven.test.skip=true', '-DargLine=override', '-pl module'):
            with self.assertRaises(ValueError): v.maven_environment({'MAVEN_ARGS': args})

    def test_linux_connection_refusal_rule_is_limited_to_fixture(self):
        policy = json.loads((v.ROOT / '.ci/log-policy.json').read_text())['service']
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'log'
            message = '12:30:00 [main] WARN example.EnvelopeErrorWebExceptionHandler -- 网关错误 502 code=63003 GET /refused/anything: finishConnect(..) failed with error(-111): Connection refused: /127.0.0.1:43369'
            path.write_text(message + '\n' + message.replace('/refused/anything', '/actual-service/orders'))
            result = v.inspect_log(path, policy)
            self.assertEqual(len(result['expected']), 1)
            self.assertEqual(len(result['unknown']), 1)

    def test_policy_excludes_native_dns_error(self):
        policy = json.loads((v.ROOT / '.ci/log-policy.json').read_text())
        self.assertFalse(any('DnsServerAddressStreamProviders' in x['pattern'] for x in policy['service']))


if __name__ == '__main__': unittest.main()
