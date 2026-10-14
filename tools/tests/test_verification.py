import argparse
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest
from unittest import mock

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

    def test_message_that_starts_on_the_next_line_is_inspected(self):
        # Spring Boot writes some warnings from the next line on; the level line then ends at the logger name.
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'build.log'
            path.write_text('2026-09-22T18:18:46.484-07:00  WARN 42346 --- [probe] [           main] '
                            '[                                                 ] .s.a.UserDetailsServiceAutoConfiguration : \n'
                            '\n'
                            'Using generated security password: 0f0e1d2c\n'
                            '\n'
                            'This generated password is for development use only.\n'
                            '12:30:00 [main] ERROR example.Report -- \n'
                            'first line of the report\n')
            found = v.inspect_log(path, [{'pattern': r'ERROR Report: first line of the report', 'reason': 'negative test'}])
            self.assertEqual(found['unknown'], [{'line': 1, 'message': 'WARN UserDetailsServiceAutoConfiguration: '
                                                                         'Using generated security password: 0f0e1d2c'}])
            self.assertEqual([entry['line'] for entry in found['expected']], [6])

    def test_empty_message_does_not_borrow_the_next_record(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'build.log'
            path.write_text('12:30:00 [main] WARN example.Empty -- \n'
                            '12:30:01 [main] INFO example.Next -- next record\n'
                            '12:30:02 [main] ERROR example.Maven -- \n'
                            '[INFO] Tests run: 1, Failures: 0\n'
                            '12:30:03 [main] WARN example.Last --\n')
            found = v.inspect_log(path, [])
            self.assertEqual([entry['message'] for entry in found['unknown']],
                             ['WARN Empty: ', 'ERROR Maven: ', 'WARN Last: '])

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



TOOLCHAIN = 'Apache Maven 3.9.14 (test)\nJava version: 25.0.1, vendor: Amazon.com Inc.\n'
FRAMEWORK_SHA = 'a' * 40
SERVICE_SHA = 'b' * 40
REAL_RUN = subprocess.run


class FrameworkArchiveTest(unittest.TestCase):
    """Runs build() with Maven, Git and source checks replaced, so only the driver's own decisions are tested."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name).resolve()
        self.framework, self.service, self.cache = root / 'framework', root / 'service', root / 'cache'
        for checkout in (self.framework, self.service):
            checkout.mkdir()
            (checkout / 'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"/>')
        (self.framework / '.ci').mkdir()
        shutil.copy2(v.ROOT / '.ci/log-policy.json', self.framework / '.ci/log-policy.json')
        (self.cache / 'org/example').mkdir(parents=True)
        (self.cache / 'org/example/dep.jar').write_text('dependency')
        self.reports = root / 'reports'
        self.maven = []
        self.runs = 0
        for patch in (mock.patch.object(v, 'ROOT', self.framework),
                      mock.patch.object(v, 'source', lambda path, expected, development=False: {'sha': expected, 'clean': True}),
                      mock.patch.object(v, 'source_locks', lambda paths: contextlib.nullcontext()),
                      mock.patch.object(v.subprocess, 'check_output', lambda *args, **kwargs: TOOLCHAIN),
                      mock.patch.object(v.subprocess, 'run', self.fake_run),
                      mock.patch.dict(os.environ, {}, clear=False)):
            patch.start()
            self.addCleanup(patch.stop)
        os.environ.pop('MAVEN_ARGS', None)

    def fake_run(self, cmd, cwd=None, **kwargs):
        if cmd[0] == 'git':
            return subprocess.CompletedProcess(cmd, 0, stdout='', stderr='')
        if cmd[0] != 'mvn':
            return REAL_RUN(cmd, cwd=cwd, **kwargs)
        self.maven.append(Path(cwd).name)
        repository = Path(next(arg for arg in cmd if arg.startswith('-Dmaven.repo.local=')).split('=', 1)[1])
        if 'install' in cmd:
            artifact = repository / 'com/mars/cloud/mars-cloud-common/1.0.0-SNAPSHOT'
            artifact.mkdir(parents=True)
            (artifact / 'mars-cloud-common-1.0.0-SNAPSHOT.jar').write_text('framework jar')
        reports = Path(cwd) / 'target/surefire-reports'
        reports.mkdir(parents=True, exist_ok=True)
        (reports / 'TEST-X.xml').write_text('<testsuite name="X" tests="1"><testcase name="one"/></testsuite>')
        return subprocess.CompletedProcess(cmd, 0)

    def run_build(self, development=False, rebuild=False):
        self.runs += 1
        output = self.reports / str(self.runs)
        args = argparse.Namespace(framework=str(self.framework), framework_sha=FRAMEWORK_SHA, service=str(self.service),
                                  service_sha=SERVICE_SHA, framework_source='candidate', declared_framework='',
                                  purpose='compatibility', cache=str(self.cache), output=str(output),
                                  development=development, rebuild_framework=rebuild)
        self.maven.clear()
        with open(os.devnull, 'w') as quiet, contextlib.redirect_stdout(quiet), contextlib.redirect_stderr(quiet):
            v.build(args)
        return json.loads((output / 'report.json').read_text()), output

    def key(self):
        policy = hashlib.sha256((self.framework / '.ci/log-policy.json').read_bytes()).hexdigest()
        return v.archive_key(FRAMEWORK_SHA, TOOLCHAIN, policy)

    def archive(self):
        return self.cache / '.framework-builds' / self.key()

    def writable(self, path):
        os.chmod(path.parent, 0o755)
        if path.exists():
            os.chmod(path, 0o644)

    def test_key_covers_commit_toolchain_and_log_policy(self):
        key = v.archive_key('a' * 40, 'maven', 'policy')
        self.assertEqual(key, hashlib.sha256(('a' * 40 + '\nmaven\npolicy').encode()).hexdigest()[:24])
        self.assertEqual(len({key, v.archive_key('b' * 40, 'maven', 'policy'), v.archive_key('a' * 40, 'other', 'policy'),
                              v.archive_key('a' * 40, 'maven', 'other')}), 4)

    def test_first_build_archives_and_second_build_reuses(self):
        first, _ = self.run_build()
        self.assertTrue(first['success'], first.get('error'))
        self.assertEqual(self.maven, ['framework', 'service'])
        self.assertFalse(first['framework_reused'])
        archive = self.archive()
        manifest = (archive / 'manifest.json').read_bytes()
        self.assertEqual(first['framework_archive'], {'key': self.key(), 'manifest_sha256': hashlib.sha256(manifest).hexdigest()})
        self.assertEqual(json.loads((archive / 'build.json').read_text()), first['builds'][0])
        self.assertEqual(sorted(json.loads(manifest)['files']),
                         ['build.json', 'repository/com/mars/cloud/mars-cloud-common/1.0.0-SNAPSHOT/mars-cloud-common-1.0.0-SNAPSHOT.jar',
                          'tests/framework/TEST-X.xml'])
        for directory, subdirs, files in os.walk(archive):
            self.assertEqual(stat.S_IMODE(os.stat(directory).st_mode), 0o555)
            for name in files:
                self.assertEqual(stat.S_IMODE(os.stat(Path(directory) / name).st_mode), 0o444)

        second, output = self.run_build()
        self.assertTrue(second['success'], second.get('error'))
        self.assertEqual(self.maven, ['service'])
        self.assertTrue(second['framework_reused'])
        self.assertIsNone(second['framework_archive'])
        self.assertTrue(second['project_cache_empty_before_build'])
        self.assertEqual([build['name'] for build in second['builds']], ['framework', 'service'])
        self.assertEqual(second['builds'][0], {**first['builds'][0], 'reused': first['framework_archive']})
        self.assertTrue((output / 'tests/framework/framework/TEST-X.xml').is_file())
        jar = Path(second['repository']) / 'com/mars/cloud/mars-cloud-common/1.0.0-SNAPSHOT/mars-cloud-common-1.0.0-SNAPSHOT.jar'
        self.assertEqual(jar.read_text(), 'framework jar')
        self.assertTrue(os.access(jar, os.W_OK) and os.access(jar.parent, os.W_OK))
        self.assertEqual((archive / 'manifest.json').read_bytes(), manifest)

    def assert_reuse_rejected(self, detail):
        report, _ = self.run_build()
        self.assertFalse(report['success'])
        self.assertIn(f'Framework build archive {self.key()} failed verification: {detail}', report['error'])
        self.assertEqual(self.maven, [])
        self.assertEqual(report['builds'], [])

    def test_changed_jar_is_rejected(self):
        self.run_build()
        jar = self.archive() / 'repository/com/mars/cloud/mars-cloud-common/1.0.0-SNAPSHOT/mars-cloud-common-1.0.0-SNAPSHOT.jar'
        self.writable(jar)
        jar.write_text('replaced jar')
        self.assert_reuse_rejected('repository/com/mars/cloud/mars-cloud-common/1.0.0-SNAPSHOT/mars-cloud-common-1.0.0-SNAPSHOT.jar changed')

    def test_extra_file_is_rejected(self):
        self.run_build()
        extra = self.archive() / 'repository/com/mars/cloud/extra.jar'
        self.writable(extra)
        extra.write_text('extra')
        self.assert_reuse_rejected("extra files ['repository/com/mars/cloud/extra.jar'], missing files []")

    def test_missing_file_is_rejected(self):
        self.run_build()
        report = self.archive() / 'tests/framework/TEST-X.xml'
        self.writable(report)
        report.unlink()
        self.assert_reuse_rejected("extra files [], missing files ['tests/framework/TEST-X.xml']")

    def test_manifest_from_another_toolchain_is_rejected(self):
        self.run_build()
        manifest = self.archive() / 'manifest.json'
        self.writable(manifest)
        manifest.write_text(json.dumps({**json.loads(manifest.read_text()), 'toolchain': 'Apache Maven 3.9.13'}))
        self.assert_reuse_rejected('toolchain differs from this build')

    def test_development_build_neither_archives_nor_reuses(self):
        report, _ = self.run_build(development=True)
        self.assertTrue(report['success'], report.get('error'))
        self.assertFalse((self.cache / '.framework-builds').exists())
        self.assertIsNone(report['framework_archive'])
        self.run_build()
        report, _ = self.run_build(development=True)
        self.assertTrue(report['success'], report.get('error'))
        self.assertEqual(self.maven, ['framework', 'service'])
        self.assertFalse(report['framework_reused'])
        self.assertIsNone(report['framework_archive'])

    def test_rebuild_builds_the_framework_and_replaces_the_archive(self):
        first, _ = self.run_build()
        report, _ = self.run_build(rebuild=True)
        self.assertTrue(report['success'], report.get('error'))
        self.assertEqual(self.maven, ['framework', 'service'])
        self.assertFalse(report['framework_reused'])
        self.assertNotIn('reused', report['builds'][0])
        replaced = report['framework_archive']
        self.assertEqual(replaced['key'], first['framework_archive']['key'])
        self.assertNotEqual(replaced['manifest_sha256'], first['framework_archive']['manifest_sha256'])
        self.assertEqual(hashlib.sha256((self.archive() / 'manifest.json').read_bytes()).hexdigest(),
                         replaced['manifest_sha256'])
        self.assertEqual([path.name for path in self.archive().parent.iterdir()], [self.archive().name])

    def test_failed_framework_tests_are_not_archived(self):
        original = self.fake_run
        def failing(cmd, cwd=None, **kwargs):
            result = original(cmd, cwd=cwd, **kwargs)
            if cmd[0] == 'mvn':
                (Path(cwd) / 'target/surefire-reports/TEST-X.xml').write_text(
                    '<testsuite name="X" tests="1"><testcase name="one"><failure/></testcase></testsuite>')
            return result
        with mock.patch.object(v.subprocess, 'run', failing):
            report, _ = self.run_build()
        self.assertFalse(report['success'])
        self.assertFalse((self.cache / '.framework-builds').exists())

    def test_builds_keep_only_the_previous_run_repository(self):
        for _ in range(3):
            report, _ = self.run_build()
            self.assertTrue(report['success'], report.get('error'))
        runs = sorted(path.name for path in (self.cache / '.verification').iterdir())
        self.assertEqual(len(runs), 2)
        self.assertIn(Path(report['repository']).parent.name, runs)


class PruneRunsTest(unittest.TestCase):
    def test_keeps_newest_run_and_leaves_the_rest_of_the_cache(self):
        with tempfile.TemporaryDirectory() as temp:
            cache = Path(temp)
            runs = cache / '.verification'
            for name, seconds in (('old', 1), ('newest', 3), ('middle', 2)):
                (runs / name / 'repository').mkdir(parents=True)
                os.utime(runs / name, ns=(0, seconds * 10**9))
            (cache / '.framework-builds/key').mkdir(parents=True)
            (cache / 'org/example').mkdir(parents=True)
            (cache / 'org/example/dep.jar').write_text('dependency')
            self.assertEqual(sorted(v.prune_runs(cache)), ['middle', 'old'])
            self.assertEqual([path.name for path in runs.iterdir()], ['newest'])
            self.assertTrue((cache / '.framework-builds/key').is_dir())
            self.assertEqual((cache / 'org/example/dep.jar').read_text(), 'dependency')
            self.assertEqual(v.prune_runs(cache), [])

    def test_missing_directory_and_symlinks(self):
        with tempfile.TemporaryDirectory() as temp:
            cache = Path(temp)
            self.assertEqual(v.prune_runs(cache), [])
            (cache / '.verification').mkdir()
            (cache / 'elsewhere').mkdir()
            (cache / '.verification/link').symlink_to(cache / 'elsewhere', target_is_directory=True)
            with self.assertRaises(ValueError): v.prune_runs(cache)
            self.assertTrue((cache / 'elsewhere').is_dir())


class WriteArchiveTest(unittest.TestCase):
    def test_existing_archive_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            repository, tests = root / 'repository', root / 'tests'
            (repository / 'com/mars/cloud/a').mkdir(parents=True)
            (repository / 'com/mars/cloud/a/a.jar').write_text('one')
            tests.mkdir()
            (tests / 'TEST-X.xml').write_text('<testsuite/>')
            identity = {'framework_sha': 'a' * 40, 'toolchain': 'maven', 'policy_sha256': 'policy'}
            created = v.write_archive(root / 'cache', identity, repository, tests, {'name': 'framework'})
            self.assertEqual(created['key'], v.archive_key('a' * 40, 'maven', 'policy'))
            (repository / 'com/mars/cloud/a/a.jar').write_text('two')
            self.assertIsNone(v.write_archive(root / 'cache', identity, repository, tests, {'name': 'framework'}))
            archive = root / 'cache/.framework-builds' / created['key']
            self.assertEqual((archive / 'repository/com/mars/cloud/a/a.jar').read_text(), 'one')
            self.assertEqual([path.name for path in (root / 'cache/.framework-builds').iterdir()], [created['key']])
            self.assertEqual(v.load_archive(root / 'cache', identity)['reused'], created)

    def test_replace_supersedes_the_archive_and_clears_leftovers(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            repository, tests = root / 'repository', root / 'tests'
            (repository / 'com/mars/cloud/a').mkdir(parents=True)
            (repository / 'com/mars/cloud/a/a.jar').write_text('one')
            tests.mkdir()
            (tests / 'TEST-X.xml').write_text('<testsuite/>')
            identity = {'framework_sha': 'a' * 40, 'toolchain': 'maven', 'policy_sha256': 'policy'}
            created = v.write_archive(root / 'cache', identity, repository, tests, {'name': 'framework'})
            archives = root / 'cache/.framework-builds'
            leftover = archives / '.staging-interrupted'
            (leftover / 'repository').mkdir(parents=True)
            os.chmod(leftover / 'repository', 0o555)
            (repository / 'com/mars/cloud/a/a.jar').write_text('two')
            replaced = v.write_archive(root / 'cache', identity, repository, tests, {'name': 'framework'}, replace=True)
            self.assertEqual(replaced['key'], created['key'])
            self.assertNotEqual(replaced['manifest_sha256'], created['manifest_sha256'])
            self.assertEqual((archives / created['key'] / 'repository/com/mars/cloud/a/a.jar').read_text(), 'two')
            self.assertEqual([path.name for path in archives.iterdir()], [created['key']])
            self.assertEqual(v.load_archive(root / 'cache', identity)['reused'], replaced)


if __name__ == '__main__': unittest.main()
