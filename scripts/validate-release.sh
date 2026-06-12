#!/usr/bin/env bash
# validate-release.sh — 交叉校验发布清单与实际项目结构
# 检查项：
#   1. release.config 中每个 build_context 目录下必须有 Dockerfile
#   2. pom.xml <modules> 列出的每个模块都必须出现在 release.config 中
#   3. docker-compose.yml 中所有 sqshq/piggymetrics-* 镜像都必须在 release.config 中有对应条目
#
# 退出码: 0 = 全部通过, 1 = 存在失败项
# 兼容 bash 3.2+（macOS 默认）

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_FILE="${PROJECT_ROOT}/release.config"
POM_FILE="${PROJECT_ROOT}/pom.xml"
COMPOSE_FILE="${PROJECT_ROOT}/docker-compose.yml"

# 用临时文件跟踪失败数（解决管道子 shell 变量不可见问题）
FAIL_FILE="$(mktemp)"
PASS_FILE="$(mktemp)"
trap 'rm -f "$FAIL_FILE" "$PASS_FILE"' EXIT

# ────────────────────────────────────────────────────────────────
# 辅助函数
# ────────────────────────────────────────────────────────────────
pass() {
  echo "  ✅ PASS: $1"
  echo "." >> "$PASS_FILE"
}

fail() {
  echo "  ❌ FAIL: $1"
  echo "." >> "$FAIL_FILE"
}

# 在列表中查找匹配项
contains_line() {
  local needle="$1"
  local haystack="$2"
  echo "$haystack" | while IFS= read -r line; do
    if [[ "$line" == "$needle" ]]; then
      return 0
    fi
  done
}

# ────────────────────────────────────────────────────────────────
# 前置检查
# ────────────────────────────────────────────────────────────────
echo "========================================"
echo " PiggyMetrics 发布清单校验"
echo "========================================"
echo ""

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "ERROR: 找不到发布清单文件: $CONFIG_FILE" >&2
  exit 1
fi

# 读取 release.config（跳过注释和空行）
CONFIG_ENTRIES="$(grep -v '^[[:space:]]*#' "$CONFIG_FILE" | grep -v '^[[:space:]]*$' || true)"
CONFIG_COUNT="$(echo "$CONFIG_ENTRIES" | wc -l | tr -d ' ')"
CONFIG_SUFFIX_LIST="$(echo "$CONFIG_ENTRIES" | cut -d'|' -f1 | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"

echo "发布清单共 ${CONFIG_COUNT} 条记录"
echo ""

# ────────────────────────────────────────────────────────────────
# 检查 1: Dockerfile 存在性
# ────────────────────────────────────────────────────────────────
echo "── 检查 1: Dockerfile 存在性 ──"

echo "$CONFIG_ENTRIES" | while IFS='|' read -r suffix context || [[ -n "$suffix" ]]; do
  suffix="$(echo "$suffix" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  context="$(echo "$context" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  [[ -z "$suffix" ]] && continue

  dockerfile="${PROJECT_ROOT}/${context}/Dockerfile"
  if [[ -f "$dockerfile" ]]; then
    pass "${suffix}: ${context}/Dockerfile 存在"
  else
    fail "${suffix}: 找不到 ${context}/Dockerfile"
  fi
done
echo ""

# ────────────────────────────────────────────────────────────────
# 检查 2: Maven 模块完整性
# ────────────────────────────────────────────────────────────────
echo "── 检查 2: Maven 模块完整性 ──"

if [[ ! -f "$POM_FILE" ]]; then
  fail "pom.xml 不存在: $POM_FILE"
else
  # 从 pom.xml 提取 <module>xxx</module>（兼容 macOS grep）
  POM_MODULES="$(sed -n 's/.*<module>\([^<]*\)<\/module>.*/\1/p' "$POM_FILE")"

  if [[ -z "$POM_MODULES" ]]; then
    fail "pom.xml 中未找到任何 <module> 声明"
  else
    # 正向: pom.xml 中的每个模块都必须在 release.config 中
    echo "$POM_MODULES" | while IFS= read -r mod || [[ -n "$mod" ]]; do
      [[ -z "$mod" ]] && continue
      if contains_line "$mod" "$CONFIG_SUFFIX_LIST"; then
        pass "Maven 模块 ${mod} 已在发布清单中"
      else
        fail "Maven 模块 ${mod} 未出现在 release.config 中"
      fi
    done

    # 反向: release.config 中的 Java 服务必须在 pom.xml 里（mongodb 除外）
    echo "$CONFIG_SUFFIX_LIST" | while IFS= read -r suffix || [[ -n "$suffix" ]]; do
      [[ -z "$suffix" || "$suffix" == "mongodb" ]] && continue
      if contains_line "$suffix" "$POM_MODULES"; then
        pass "发布清单 ${suffix} 对应 Maven 模块"
      else
        fail "发布清单中的 ${suffix} 在 pom.xml <modules> 中未找到"
      fi
    done
  fi
fi
echo ""

# ────────────────────────────────────────────────────────────────
# 检查 3: docker-compose 镜像覆盖
# ────────────────────────────────────────────────────────────────
echo "── 检查 3: docker-compose 镜像覆盖 ──"

if [[ ! -f "$COMPOSE_FILE" ]]; then
  fail "docker-compose.yml 不存在: $COMPOSE_FILE"
else
  # 提取 docker-compose.yml 中所有 sqshq/piggymetrics-* 镜像后缀（兼容 macOS）
  COMPOSE_IMAGES="$(sed -n 's/.*image:[[:space:]]*sqshq\/piggymetrics-\([^[:space:]]*\).*/\1/p' "$COMPOSE_FILE" | sort -u)"

  if [[ -z "$COMPOSE_IMAGES" ]]; then
    fail "docker-compose.yml 中未找到任何 sqshq/piggymetrics-* 镜像"
  else
    echo "$COMPOSE_IMAGES" | while IFS= read -r img || [[ -n "$img" ]]; do
      [[ -z "$img" ]] && continue
      if contains_line "$img" "$CONFIG_SUFFIX_LIST"; then
        pass "compose 镜像 piggymetrics-${img} 已在发布清单中"
      else
        fail "compose 镜像 piggymetrics-${img} 未出现在 release.config 中"
      fi
    done
  fi
fi
echo ""

# ────────────────────────────────────────────────────────────────
# 汇总
# ────────────────────────────────────────────────────────────────
PASS_TOTAL="$(wc -l < "$PASS_FILE" | tr -d ' ')"
FAIL_TOTAL="$(wc -l < "$FAIL_FILE" | tr -d ' ')"

echo "========================================"
echo " 校验结果: ✅ ${PASS_TOTAL} 通过, ❌ ${FAIL_TOTAL} 失败"
echo "========================================"

if [[ "$FAIL_TOTAL" -gt 0 ]]; then
  echo ""
  echo "请修复上述失败项后再执行构建发布。" >&2
  exit 1
fi
