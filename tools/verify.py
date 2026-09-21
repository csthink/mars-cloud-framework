#!/usr/bin/env python3
"""Shared, source-bound Maven verification. Python standard library only."""
import argparse
import contextlib
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
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


def diagnostic(line):
    line = ANSI.sub("", line).strip()
    # Match severity fields, not words such as '.error.' in test class names.
    match = re.search(r"(?:^|\s)(WARN|ERROR)\s+(?:\d+\s+---\s+)?(?:\[[^\]]*\]\s*)*([\w.$]+)\s+(?:--|:)\s+(.*)", line)
    if match:
        return f"{match[1]} {match[2].split('.')[-1]}: {match[3]}"
    if re.match(r"\[(WARNING|ERROR)\]", line) or re.match(r"(?:OpenJDK\b.*\bwarning:|WARNING:|warning:)", line):
        return line
    return None


def inspect_log(path, policy):
    known, unknown = [], []
    rules = [(re.compile(rule["pattern"]), rule["reason"]) for rule in policy]
    for number, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
        message = diagnostic(line)
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


def build(args):
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    report = {"schema": 1, "success": False, "formal": not args.development,
              "purpose": args.purpose, "framework_source": args.framework_source,
              "declared_framework": args.declared_framework, "sources": {}, "builds": [],
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
        inherited = os.environ.get("MAVEN_ARGS", "")
        if inherited and not re.fullmatch(r"-Dmaven.repo.local=\S+", inherited):
            raise ValueError("MAVEN_ARGS may only select the development repository")
        env = os.environ.copy()
        for key in ("MAVEN_ARGS", "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
            env.pop(key, None)
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
        with lock(cache), source_locks([path for _, path, _ in inputs]):
            run_root = cache / ".verification" / uuid.uuid4().hex
            repository = run_root / "repository"
            seed(cache, repository)
            report["repository"] = str(repository)
            report["project_cache_empty_before_build"] = not (repository / "com/mars/cloud").exists()
            for name, path, _ in inputs:
                config = path / ".mvn/maven.config"
                if config.exists() and re.search(r"skip|test=|argLine|maven.repo.local|failure.ignore", config.read_text(), re.I):
                    raise ValueError("Maven configuration overrides verification")
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
    args = parser.parse_args()
    if not args.service or not args.service_sha:
        parser.error("Both repository inputs are required")
    return build(args)


if __name__ == "__main__":
    sys.exit(main())
