#!/usr/bin/env bash
#
# 对真实 xxl-job-admin 运行 job starter 的契约测试。
#
# 用法：tools/job-contract.sh <调度中心地址，如 http://127.0.0.1:28083> <注册主机> <前缀，如 s1-，可为空> <报告目录（源码树外、尚不存在）>
#
# 访问令牌与调度中心管理员口令只从环境变量读取，不出现在命令行：
#   MARS_JOB_ACCESS_TOKEN  调度中心的 xxl.job.accessToken
#   XXL_ADMIN_PASSWORD     调度中心管理员 admin 的口令
# 注册主机是调度中心能访问到本机执行器的主机名或 IP（调度中心在本机容器里时写容器访问宿主机用的主机名）。
#
# 远端 CI 没有调度中心，契约测试只由本脚本通过 Maven profile job-contract 在本机执行；
# Maven 本地仓由环境变量 MAVEN_ARGS 决定。测试经管理页面接口建一个带前缀的临时执行器组与任务，结束时删除它们。
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "用法：$0 <admin-address> <register-host> <prefix> <report-dir>" >&2
  exit 1
fi
ADMIN="$1"; REGISTER_HOST="$2"; PREFIX="$3"; REPORT_DIR="$4"
: "${MARS_JOB_ACCESS_TOKEN:?需要环境变量 MARS_JOB_ACCESS_TOKEN}"
: "${XXL_ADMIN_PASSWORD:?需要环境变量 XXL_ADMIN_PASSWORD}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MODULE="mars-cloud-job-spring-boot-starter"
case "$REPORT_DIR" in
  "$ROOT"/*) echo "报告目录必须在源码树外：$REPORT_DIR" >&2; exit 1 ;;
esac
[ -e "$REPORT_DIR" ] && { echo "报告目录已存在：$REPORT_DIR" >&2; exit 1; }
mkdir -p "$REPORT_DIR"

export MARS_JOB_ADMIN_ADDRESSES="$ADMIN"
export MARS_JOB_REGISTER_HOST="$REGISTER_HOST"
export MARS_MQ_PREFIX="$PREFIX"
LOG="$REPORT_DIR/maven.log"
{
  echo "framework: $(git -C "$ROOT" rev-parse HEAD)"
  echo "admin: $ADMIN"
  echo "register-host: $REGISTER_HOST"
  echo "prefix: $PREFIX"
  echo "started: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$REPORT_DIR/run.txt"

set +e
(cd "$ROOT" && mvn -B -ntp -Pjob-contract -pl "$MODULE" -am -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest='JobContractTest' verify) > "$LOG" 2>&1
STATUS=$?
set -e
mkdir -p "$REPORT_DIR/surefire-reports"
cp "$ROOT/$MODULE"/target/surefire-reports/*JobContractTest* "$REPORT_DIR/surefire-reports/" 2>/dev/null || true
echo "finished: $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$REPORT_DIR/run.txt"
echo "exit: $STATUS" >> "$REPORT_DIR/run.txt"
grep -E 'Tests run:.*JobContractTest|BUILD (SUCCESS|FAILURE)' "$LOG" | tail -3
exit "$STATUS"
