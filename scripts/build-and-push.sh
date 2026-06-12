#!/usr/bin/env bash
# build-and-push.sh — 读取 release.config，循环 build / tag / push
# 依赖环境变量：
#   COMMIT        — 短 SHA（必须）
#   TAG           — 分支标签，如 latest / feature-xxx（必须）
#   DOCKER_REGISTRY — 镜像仓库前缀，默认 sqshq
#   IMAGE_PREFIX    — 镜像名前缀，默认 piggymetrics
#   DRY_RUN       — 设为 1 时只打印命令不执行

set -euo pipefail

# ────────────────────────────────────────────────────────────────
# 参数校验
# ────────────────────────────────────────────────────────────────
if [[ -z "${COMMIT:-}" ]]; then
  echo "ERROR: COMMIT 环境变量未设置" >&2
  exit 1
fi

if [[ -z "${TAG:-}" ]]; then
  echo "ERROR: TAG 环境变量未设置" >&2
  exit 1
fi

REGISTRY="${DOCKER_REGISTRY:-sqshq}"
PREFIX="${IMAGE_PREFIX:-piggymetrics}"
CONFIG_FILE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/release.config"
DRY_RUN="${DRY_RUN:-0}"

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "ERROR: 找不到发布清单文件: $CONFIG_FILE" >&2
  exit 1
fi

# ────────────────────────────────────────────────────────────────
# 辅助函数
# ────────────────────────────────────────────────────────────────
run() {
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "[DRY-RUN] $*"
  else
    echo ">>> $*"
    "$@"
  fi
}

# ────────────────────────────────────────────────────────────────
# 主循环
# ────────────────────────────────────────────────────────────────
echo "========================================"
echo " PiggyMetrics 镜像构建 & 发布"
echo " Registry : ${REGISTRY}"
echo " Commit   : ${COMMIT}"
echo " Tag      : ${TAG}"
echo " Dry-run  : ${DRY_RUN}"
echo "========================================"

TOTAL=0
FAILED=0

while IFS='|' read -r suffix context || [[ -n "$suffix" ]]; do
  # 跳过空行和注释
  [[ -z "$suffix" || "$suffix" =~ ^[[:space:]]*# ]] && continue
  # 去除首尾空白
  suffix="$(echo "$suffix" | xargs)"
  context="$(echo "$context" | xargs)"

  IMAGE="${REGISTRY}/${PREFIX}-${suffix}"
  TOTAL=$((TOTAL + 1))

  echo ""
  echo "─── [${TOTAL}] ${IMAGE} (context: ${context}) ───"

  if ! run docker build -t "${IMAGE}:${COMMIT}" "./${context}"; then
    echo "ERROR: 构建失败 — ${IMAGE}" >&2
    FAILED=$((FAILED + 1))
    continue
  fi

  run docker tag "${IMAGE}:${COMMIT}" "${IMAGE}:${TAG}"
  run docker push "${IMAGE}:${COMMIT}"
  run docker push "${IMAGE}:${TAG}"

done < "$CONFIG_FILE"

# ────────────────────────────────────────────────────────────────
# 汇总
# ────────────────────────────────────────────────────────────────
echo ""
echo "========================================"
echo " 构建发布完成: ${TOTAL} 个镜像, 失败 ${FAILED} 个"
echo "========================================"

if [[ "$FAILED" -gt 0 ]]; then
  exit 1
fi
