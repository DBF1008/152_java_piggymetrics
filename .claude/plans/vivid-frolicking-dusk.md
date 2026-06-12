# 构建发布链路重构：可复用配置 + 发布清单校验

## 问题

`.travis.yml` 中 `after_success` 段有 10 段几乎一模一样的 `docker build → tag → push` 指令（9 个 Java 服务 + 1 个 mongodb）。每加/改/删一个服务都需要在三处手动同步（pom.xml modules、travis 发布块、docker-compose image），极易漏改。此外：

- `docker push $IMAGE` 不带 tag，会把本地所有 tag 全推上去，行为不明确
- 本地没有手段校验"发布清单"和"实际存在的模块/Dockerfile"是否一致

## 方案概览

引入三样东西，把"发布哪些镜像"这件事收敛到**一个文件**：

```
release.config              ← 唯一清单（image_name + build_context）
scripts/build-and-push.sh   ← 读清单，循环 build/tag/push
scripts/validate-release.sh ← 交叉校验：清单 vs pom.xml vs 文件系统
```

`.travis.yml` 瘦身为只调用这两个脚本。

---

## 详细设计

### 1. `release.config` — 镜像发布清单（唯一数据源）

纯文本，每行一条，`#` 开头为注释，格式：`<image_suffix>|<build_context_dir>`

```
# PiggyMetrics 镜像发布清单
# 格式: <image_suffix>|<build_context_dir>
# image_suffix 会拼接为 ${DOCKER_REGISTRY}/piggymetrics-<suffix>
config|config
registry|registry
gateway|gateway
auth-service|auth-service
account-service|account-service
statistics-service|statistics-service
notification-service|notification-service
monitoring|monitoring
turbine-stream-service|turbine-stream-service
mongodb|mongodb
```

**为什么不用 YAML/JSON？** bash 解析简单、零依赖，Travis trusty 环境不一定有 yq/jq。

### 2. `scripts/build-and-push.sh` — 构建 & 推送脚本

读取 `release.config`，对每条记录执行：
```bash
docker build -t ${REGISTRY}/${PREFIX}-${suffix}:${COMMIT} ${context}
docker tag ... :${TAG}          # TAG = "latest" if master, else branch name
docker push ... :${COMMIT}
docker push ... :${TAG}         # 显式 push 每个 tag，不再裸推
```

关键参数通过环境变量传入：
- `DOCKER_REGISTRY` — 默认 `sqshq`
- `IMAGE_PREFIX` — 默认 `piggymetrics`
- `COMMIT` — 短 SHA
- `TAG` — 分支标签

脚本开头 `set -euo pipefail`，任何一步失败立即退出。

### 3. `scripts/validate-release.sh` — 发布清单校验脚本

做三项交叉检查，任一失败则 exit 1：

| 检查项 | 逻辑 |
|--------|------|
| **Dockerfile 存在性** | release.config 中每个 build_context 目录下必须有 Dockerfile |
| **Maven 模块完整性** | pom.xml 中 `<modules>` 列出的每个模块都必须出现在 release.config 中 |
| **docker-compose 镜像覆盖** | docker-compose.yml 中所有 `image: sqshq/piggymetrics-*` 都必须在 release.config 中有对应条目 |

输出格式：每项检查打印 PASS/FAIL 及详情，最后打印汇总。

### 4. `.travis.yml` 精简

```yaml
after_success:
  - bash <(curl -s https://codecov.io/bash)
  - docker login -u $DOCKER_USER -p $DOCKER_PASS
  - export TAG=$(if [ "$TRAVIS_BRANCH" == "master" ]; then echo "latest"; else echo $TRAVIS_BRANCH; fi)
  - bash scripts/validate-release.sh
  - bash scripts/build-and-push.sh
```

原来 60+ 行的重复块缩减为 5 行。

---

## 文件变更清单

| 文件 | 操作 |
|------|------|
| `release.config` | **新建** — 10 条镜像记录 |
| `scripts/build-and-push.sh` | **新建** — 构建推送循环脚本 |
| `scripts/validate-release.sh` | **新建** — 三项交叉校验脚本 |
| `.travis.yml` | **修改** — after_success 改为调用脚本 |

---

## 验证方式

1. **本地执行校验脚本**：`bash scripts/validate-release.sh`，确认三项全 PASS
2. **模拟构建**（dry-run 模式）：`DRY_RUN=1 bash scripts/build-and-push.sh`，确认输出的 docker 命令正确
3. **故意破坏测试**：从 release.config 删掉一行，运行校验脚本，确认报 FAIL
4. **Travis 环境**：push 后观察 CI 日志，确认 validate → build → push 顺序执行，10 个镜像全部构建推送成功
