#!/usr/bin/env bash
#
# 对真实 RocketMQ 运行 rocketmq starter 的契约测试。
#
# 用法：tools/rocketmq-contract.sh <name-server host:port> <前缀，如 s1-，可为空> <报告目录（源码树外、尚不存在）>
#
# 远端 CI 没有 RocketMQ，契约测试只由本脚本通过 Maven profile rocketmq-contract 在本机执行；
# Maven 本地仓由环境变量 MAVEN_ARGS 决定（例如 -Dmaven.repo.local=...）。脚本不修改源码，不清理外部状态，
# 测试自己创建并删除带前缀的临时主题与消费组。
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "用法：$0 <name-server host:port> <prefix> <report-dir>" >&2
  exit 1
fi
NAME_SERVER="$1"; PREFIX="$2"; REPORT_DIR="$3"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MODULE="mars-cloud-rocketmq-spring-boot-starter"
case "$REPORT_DIR" in
  "$ROOT"/*) echo "报告目录必须在源码树外：$REPORT_DIR" >&2; exit 1 ;;
esac
[ -e "$REPORT_DIR" ] && { echo "报告目录已存在：$REPORT_DIR" >&2; exit 1; }
mkdir -p "$REPORT_DIR"

export ROCKETMQ_NAME_SERVER="$NAME_SERVER"
export MARS_MQ_PREFIX="$PREFIX"
LOG="$REPORT_DIR/maven.log"
{
  echo "framework: $(git -C "$ROOT" rev-parse HEAD)"
  echo "name-server: $NAME_SERVER"
  echo "prefix: $PREFIX"
  echo "started: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$REPORT_DIR/run.txt"

set +e
(cd "$ROOT" && mvn -B -ntp -Procketmq-contract -pl "$MODULE" -am -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtest='RocketMqContractTest' verify) > "$LOG" 2>&1
STATUS=$?
set -e
mkdir -p "$REPORT_DIR/surefire-reports"
cp "$ROOT/$MODULE"/target/surefire-reports/*RocketMqContractTest* "$REPORT_DIR/surefire-reports/" 2>/dev/null || true
echo "finished: $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$REPORT_DIR/run.txt"
echo "exit: $STATUS" >> "$REPORT_DIR/run.txt"
grep -E 'Tests run:.*RocketMqContractTest|BUILD (SUCCESS|FAILURE)' "$LOG" | tail -3
exit "$STATUS"
