#!/usr/bin/env python3
"""Shared, source-bound Maven verification. Python standard library only."""
import argparse
import contextlib
import datetime
import fcntl
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SHA = re.compile(r"[0-9a-f]{40}\Z")
ANSI = re.compile(r"\x1b\[[0-9;]*m")


def command(args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, stderr=subprocess.STDOUT, text=True).strip()


def source(path, expected, development=False):
    if not SHA.fullmatch(expected):
        raise ValueError("Expected a full 40-character source SHA")
    head = command(["git", "rev-parse", "HEAD"], path)
    clean = not command(["git", "status", "--porcelain", "--untracked-files=all"], path)
    if head != expected or (not clean and not development):
        raise ValueError(f"Source differs from expected clean commit: {path.name}")
    return {"sha": head, "clean": clean}


@contextlib.contextmanager
def lock(cache):
    cache.mkdir(parents=True, exist_ok=True)
    with (cache / ".mars-build.lock").open("a") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise ValueError("Maven cache is in use; do not refresh or build concurrently") from exc
        yield


@contextlib.contextmanager
def source_locks(paths):
    with contextlib.ExitStack() as stack:
        for path in sorted(paths):
            metadata = Path(command(['git', 'rev-parse', '--absolute-git-dir'], path))
            handle = stack.enter_context((metadata / '.mars-source-build.lock').open('a'))
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise ValueError('Source checkout is already being built or refreshed') from exc
        yield


def seed(cache, destination):
    """Copy only dependency data into a fresh repository; never share mutable files."""
    destination.mkdir(parents=True, exist_ok=False)
    for directory, subdirs, files in os.walk(cache, followlinks=False):
        if Path(directory) == cache:
            subdirs[:] = [name for name in subdirs if not name.startswith('.')]
        for name in subdirs + files:
            if (Path(directory) / name).is_symlink():
                raise ValueError("Cache symlinks are not supported")
    for item in cache.iterdir():
        if item.name.startswith("."):
            continue
        if item.is_symlink():
            raise ValueError("Cache symlinks are not supported")
        flags = ["-cR"] if sys.platform == "darwin" else ["--reflink=auto", "-R"]
        result = subprocess.run(["cp", *flags, str(item), str(destination)], capture_output=True)
        if result.returncode:
            raise ValueError("Unable to clone dependency cache")
    project = destination / "com/mars/cloud"
    if project.exists():
        shutil.rmtree(project)
    if project.exists():
        raise ValueError("Project artifacts survived cache isolation")


KEEP_RUNS = 1


def prune_runs(cache, keep=KEEP_RUNS):
    """Delete older run repositories under <cache>/.verification, keeping the newest `keep`.

    Called before a new run repository is created. Startup checks that follow a build resolve test classpaths
    in that build's run repository, so the newest existing one is kept: at most the previous and the current
    run repository exist. Archived framework builds and dependencies elsewhere in the cache are not touched.
    """
    runs = cache / ".verification"
    if runs.is_symlink():
        raise ValueError("Cache symlinks are not supported")
    if not runs.exists():
        return []
    directories = []
    for item in runs.iterdir():
        if item.is_symlink():
            raise ValueError("Cache symlinks are not supported")
        if item.is_dir():
            directories.append(item)
    directories.sort(key=lambda item: (item.stat().st_mtime_ns, item.name), reverse=True)
    removed = directories[keep:]
    for item in removed:
        shutil.rmtree(item)
    return [item.name for item in removed]


ARCHIVES = ".framework-builds"
PROJECT = Path("com/mars/cloud")
MANIFEST = "manifest.json"
IDENTITY = ("framework_sha", "toolchain", "policy_sha256")


def archive_key(framework_sha, toolchain, policy_sha256):
    """Archive directory name: any change of commit, tool versions or log policy selects another archive."""
    return hashlib.sha256("\n".join((framework_sha, toolchain, policy_sha256)).encode()).hexdigest()[:24]


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def tree_files(root):
    """Relative paths of every regular file under root; symlinks and other file types are rejected."""
    found = []
    for directory, subdirs, files in os.walk(root, followlinks=False):
        current = Path(directory)
        for name in subdirs + files:
            path = current / name
            if path.is_symlink():
                raise ValueError(f"Symlink in framework build archive: {path.relative_to(root)}")
        for name in files:
            path = current / name
            if not path.is_file():
                raise ValueError(f"Unsupported file in framework build archive: {path.relative_to(root)}")
            found.append(path.relative_to(root).as_posix())
    return sorted(found)


def copy_tree(source_root, destination):
    """Copy file contents only, so the copy stays writable whatever the source modes are."""
    for directory, subdirs, files in os.walk(source_root, followlinks=False):
        current = Path(directory)
        target = destination / current.relative_to(source_root)
        target.mkdir(parents=True, exist_ok=True)
        for name in subdirs + files:
            if (current / name).is_symlink():
                raise ValueError("Symlinks are not supported in project artifacts")
        for name in files:
            shutil.copyfile(current / name, target / name)


def write_archive(cache, identity, repository, tests, evidence):
    """Store a framework build that passed every check; an existing archive for the same key is kept as is."""
    key = archive_key(*(identity[name] for name in IDENTITY))
    archives = cache / ARCHIVES
    if archives.is_symlink():
        raise ValueError("Cache symlinks are not supported")
    final = archives / key
    if final.exists() or final.is_symlink():
        return None
    archives.mkdir(parents=True, exist_ok=True)
    staging = archives / f".staging-{key}-{uuid.uuid4().hex}"
    try:
        copy_tree(repository / PROJECT, staging / "repository" / PROJECT)
        copy_tree(tests, staging / "tests")
        (staging / "build.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
        files = {name: file_sha256(staging / name) for name in tree_files(staging)}
        manifest = json.dumps({**{name: identity[name] for name in IDENTITY}, "files": files},
                              ensure_ascii=False, indent=2) + "\n"
        (staging / MANIFEST).write_text(manifest)
        os.replace(staging, final)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    for directory, subdirs, files in os.walk(final, topdown=False):
        for name in files:
            os.chmod(Path(directory) / name, 0o444)
        os.chmod(directory, 0o555)
    return {"key": key, "manifest_sha256": hashlib.sha256(manifest.encode()).hexdigest()}


def load_archive(cache, identity):
    """Return the verified archive for this build input, or None when there is none.

    A damaged or mismatched archive fails the run instead of being rebuilt, because a changed archive needs inspection.
    """
    key = archive_key(*(identity[name] for name in IDENTITY))
    archives = cache / ARCHIVES
    archive = archives / key
    if archives.is_symlink() or archive.is_symlink():
        raise ValueError("Cache symlinks are not supported")
    if not archive.exists():
        return None
    def reject(detail):
        raise ValueError(f"Framework build archive {key} failed verification: {detail}; "
                         f"inspect {archive} and remove it before building again")
    try:
        data = (archive / MANIFEST).read_bytes()
        manifest = json.loads(data)
    except (OSError, ValueError):
        reject("unreadable manifest")
    if not isinstance(manifest, dict) or set(manifest) != {*IDENTITY, "files"} or not isinstance(manifest["files"], dict):
        reject("unexpected manifest fields")
    for name in IDENTITY:
        if manifest[name] != identity[name]:
            reject(f"{name} differs from this build")
    present = [name for name in tree_files(archive) if name != MANIFEST]
    if sorted(manifest["files"]) != present:
        extra = sorted(set(present) - set(manifest["files"]))
        missing = sorted(set(manifest["files"]) - set(present))
        reject(f"extra files {extra}, missing files {missing}")
    if "build.json" not in present or not any(name.startswith(f"repository/{PROJECT.as_posix()}/") for name in present) \
            or not any(name.startswith("tests/") for name in present):
        reject("incomplete archive")
    for name, expected in manifest["files"].items():
        if file_sha256(archive / name) != expected:
            reject(f"{name} changed")
    try:
        evidence = json.loads((archive / "build.json").read_text())
    except (OSError, ValueError):
        reject("unreadable build.json")
    if not isinstance(evidence, dict) or evidence.get("name") != "framework":
        reject("build.json is not a framework build")
    return {"path": archive, "evidence": evidence,
            "reused": {"key": key, "manifest_sha256": hashlib.sha256(data).hexdigest()}}


# Match severity fields, not words such as '.error.' in test class names. The message is optional:
# Spring Boot writes some messages from the next line on, leaving the level line to end at the logger.
LOG_RECORD = r"(?:^|\s)({levels})\s+(?:\d+\s+---\s+)?(?:\[[^\]]*\]\s*)*([\w.$]+)\s+(?:--|:)(?:\s+(.*))?$"
SEVERE_RECORD = re.compile(LOG_RECORD.format(levels="WARN|ERROR"))
ANY_RECORD = re.compile(LOG_RECORD.format(levels="TRACE|DEBUG|INFO|WARN|ERROR"))
MAVEN_RECORD = re.compile(r"\[(?:DEBUG|INFO|WARNING|ERROR)\]")


def diagnostic(line, following=()):
    """following: the lines after this one, read only when the message starts on the next line."""
    line = ANSI.sub("", line).strip()
    match = SEVERE_RECORD.search(line)
    if match:
        return f"{match[1]} {match[2].split('.')[-1]}: {match[3] or continuation(following)}"
    if re.match(r"\[(WARNING|ERROR)\]", line) or re.match(r"(?:OpenJDK\b.*\bwarning:|WARNING:|warning:)", line):
        return line
    return None


def continuation(following):
    """First non-blank following line, unless it starts another log record."""
    for line in following:
        line = ANSI.sub("", line).strip()
        if line:
            is_record = ANY_RECORD.search(line) or MAVEN_RECORD.match(line) or diagnostic(line) is not None
            return "" if is_record else line
    return ""


def inspect_log(path, policy):
    known, unknown = [], []
    rules = [(re.compile(rule["pattern"]), rule["reason"]) for rule in policy]
    lines = path.read_text(errors="replace").splitlines()
    for number, line in enumerate(lines, 1):
        message = diagnostic(line, itertools.islice(lines, number, None))
        if message is None:
            continue
        reasons = [reason for pattern, reason in rules if pattern.fullmatch(message)]
        entry = {"line": number, "message": message}
        if reasons:
            known.append({**entry, "reason": reasons[0]})
        else:
            unknown.append(entry)
    return {"expected": known, "unknown": unknown}


def test_reports(root, output):
    """Every reactor module with test sources must produce successful Surefire XML."""
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    modules = []
    def visit(directory):
        tree = ET.parse(directory / "pom.xml")
        modules.append(directory)
        for module in tree.findall("m:modules/m:module", namespace):
            visit(directory / module.text.strip())
    visit(root)
    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    suites = []
    for module in modules:
        sources = list((module / "src/test").rglob("*.java")) if (module / "src/test").exists() else []
        reports = list((module / "target/surefire-reports").glob("TEST-*.xml"))
        if sources and not reports:
            raise ValueError(f"Missing test reports: {module.name}")
        case_classes = set()
        for report in reports:
            case_classes.add(ET.parse(report).getroot().attrib['name'])
            for case in ET.parse(report).getroot().findall('testcase'):
                case_classes.add(case.attrib.get('classname', ''))
        for test_source in sources:
            content = test_source.read_text()
            if re.search(r'@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b', content):
                package = re.search(r'package\s+([\w.]+)\s*;', content)
                class_name = (package[1] + '.' if package else '') + test_source.stem
                if not any(name == class_name or name.startswith(class_name + '$') for name in case_classes):
                    raise ValueError(f'Missing test class: {class_name}')
        module_tests = 0
        for report in reports:
            suite = ET.parse(report).getroot()
            cases = suite.findall('testcase')
            counts = {'tests': len(cases), 'failures': sum(len(c.findall('failure')) for c in cases),
                      'errors': sum(len(c.findall('error')) for c in cases),
                      'skipped': sum(len(c.findall('skipped')) for c in cases)}
            if not cases or any(counts[k] or int(suite.attrib.get(k, '0')) for k in ('failures', 'errors', 'skipped')):
                raise ValueError(f"Incomplete or failed test suite: {report.name}")
            # Surefire can report zero tests on a @Nested container while retaining its testcases.
            if int(suite.attrib.get('tests', '-1')) not in (0, len(cases)):
                raise ValueError(f"Inconsistent test count: {report.name}")
            module_tests += counts['tests']
            suites.append(suite.attrib["name"])
            for key, value in counts.items():
                total[key] += value
            dest = output / "tests" / root.name / module.name
            dest.mkdir(parents=True, exist_ok=True)
            shutil.copy2(report, dest / report.name)
        if sources and not module_tests:
            raise ValueError(f"No tests executed: {module.name}")
    if not total["tests"]:
        raise ValueError("No tests executed")
    return {**total, "suites": suites}


def maven_environment(environment):
    # Hosted runners can set the display-only -ntp flag. No inherited build overrides.
    for argument in shlex.split(environment.get('MAVEN_ARGS', '')):
        if argument not in ('-ntp', '--no-transfer-progress') and not re.fullmatch(r'-Dmaven.repo.local=\S+', argument):
            raise ValueError('MAVEN_ARGS contains an unsupported build override')
    result = environment.copy()
    for key in ('MAVEN_ARGS', 'MAVEN_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        result.pop(key, None)
    return result


def build(args):
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {"schema": 1, "success": False, "formal": not args.development,
              "purpose": args.purpose, "framework_source": args.framework_source,
              "declared_framework": args.declared_framework, "sources": {}, "builds": [],
              "framework_reused": False, "framework_archive": None,
              "run": {k: os.environ.get(k, "") for k in ("GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_SHA", "GITHUB_EVENT_NAME", "GITHUB_REF", "SOURCE_HEAD_SHA")},
              "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    try:
        framework = Path(args.framework).resolve()
        service = Path(args.service).resolve() if args.service else None
        if framework != ROOT:
            raise ValueError("Use the driver from the exact framework being built")
        inputs = [("framework", framework, args.framework_sha)]
        if service:
            inputs.append(("service", service, args.service_sha))
        for name, path, expected in inputs:
            if output == path or path in output.parents:
                raise ValueError("Report directory must be outside source trees (Maven clean removes target)")
            report["sources"][name] = source(path, expected, args.development)
        if args.declared_framework and not SHA.fullmatch(args.declared_framework):
            raise ValueError("Invalid dependency revision")
        # CLI has no arbitrary Maven arguments; inherited overrides cannot silently skip tests.
        env = maven_environment(os.environ)
        version = subprocess.check_output(["mvn", "--version"], env=env, cwd=framework, text=True, stderr=subprocess.STDOUT)
        report["toolchain"] = ANSI.sub("", version)
        if not re.search(r"Apache Maven 3\.9\.14(?:\s|$)", version) or not re.search(r"Java version: 25[.,]", version) or "Amazon.com Inc." not in version:
            raise ValueError("Requires Apache Maven 3.9.14 and Amazon Corretto JDK 25")
        cache = Path(args.cache).expanduser().resolve()
        for _, path, _ in inputs:
            slot = subprocess.run(["git", "config", "--worktree", "--get", "mars.slot"], cwd=path, capture_output=True, text=True).stdout.strip()
            if slot and cache != Path.home() / ".m2" / f"slot-{slot}":
                raise ValueError("Worktree builds must use their own slot cache")
        policy = json.loads((ROOT / ".ci/log-policy.json").read_text())
        report["policy_sha256"] = hashlib.sha256((ROOT / ".ci/log-policy.json").read_bytes()).hexdigest()
        identity = {"framework_sha": report["sources"]["framework"]["sha"], "toolchain": report["toolchain"],
                    "policy_sha256": report["policy_sha256"]}
        # Only formal builds produce or consume archives; development reports are never evidence.
        formal = not args.development
        with lock(cache), source_locks([path for _, path, _ in inputs]):
            prune_runs(cache)
            reuse = load_archive(cache, identity) if formal and not args.rebuild_framework else None
            run_root = cache / ".verification" / uuid.uuid4().hex
            repository = run_root / "repository"
            seed(cache, repository)
            report["repository"] = str(repository)
            report["project_cache_empty_before_build"] = not (repository / PROJECT).exists()
            for name, path, _ in inputs:
                config = path / ".mvn/maven.config"
                if config.exists() and re.search(r"skip|test=|argLine|maven.repo.local|failure.ignore", config.read_text(), re.I):
                    raise ValueError("Maven configuration overrides verification")
                if name == "framework" and reuse:
                    copy_tree(reuse["path"] / "repository" / PROJECT, repository / PROJECT)
                    copy_tree(reuse["path"] / "tests", output / "tests" / path.name)
                    print(f"Reusing framework build {reuse['reused']['key']}", flush=True)
                    report["builds"].append({**reuse["evidence"], "reused": reuse["reused"]})
                    report["framework_reused"] = True
                    continue
                cmd = ["mvn", "-B", "-ntp", f"-Dmaven.repo.local={repository}",
                       "-DskipTests=false", "-Dmaven.test.skip=false", "-DskipITs=false", "-Dmaven.test.failure.ignore=false",
                       "clean", "install" if name == "framework" else "verify"]
                log = output / f"{name}.log"
                print(f"Building {name}; log: {log}", flush=True)
                with log.open("w") as stream:
                    status = subprocess.run(cmd, cwd=path, env=env, stdout=stream, stderr=subprocess.STDOUT).returncode
                evidence = {"name": name, "command": cmd, "exit_code": status, "log": log.name,
                            "diagnostics": inspect_log(log, policy[name])}
                report["builds"].append(evidence)
                if status:
                    raise ValueError(f"{name}: Maven exited {status}")
                evidence["tests"] = test_reports(path, output)
                if evidence["diagnostics"]["unknown"]:
                    raise ValueError(f"{name}: unknown diagnostic(s); inspect report.json")
                if name == "framework" and formal:
                    if source(path, args.framework_sha) != report["sources"][name]:
                        raise ValueError("Sources changed during verification")
                    report["framework_archive"] = write_archive(cache, identity, repository,
                                                                output / "tests" / path.name, evidence)
            for directory, subdirs, files in os.walk(repository):
                current = Path(directory)
                relative = current.relative_to(repository)
                if relative == Path('com/mars'):
                    subdirs[:] = [name for name in subdirs if name != 'cloud']
                for filename in files:
                    target = cache / relative / filename
                    if not target.exists():
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copy2(current / filename, target)
            for name, path, expected in inputs:
                if source(path, expected, args.development) != report["sources"][name]:
                    raise ValueError("Sources changed during verification")
        report["success"] = True
    except (ValueError, OSError, subprocess.SubprocessError, ET.ParseError) as exc:
        report["error"] = str(exc)
        print(str(exc), file=sys.stderr)
    finally:
        report["finished_at"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return 0 if report["success"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--framework", default=str(ROOT))
    parser.add_argument("--framework-sha", required=True)
    parser.add_argument("--service")
    parser.add_argument("--service-sha")
    parser.add_argument("--framework-source", choices=("candidate", "main"), required=True)
    parser.add_argument("--declared-framework", default="")
    parser.add_argument("--purpose", choices=("compatibility", "service"), required=True)
    parser.add_argument("--cache", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--development", action="store_true")
    parser.add_argument("--rebuild-framework", action="store_true",
                        help="build and test the framework even when an archived build of the same input exists")
    args = parser.parse_args()
    if not args.service or not args.service_sha:
        parser.error("Both repository inputs are required")
    return build(args)


if __name__ == "__main__":
    sys.exit(main())
