# Migration Lab：本地结构迁移故障恢复演练台

这是一个零外部网络依赖的 Spring Boot + H2 应用，用来在本地沙箱中演练结构迁移、分批回填、双写窗口、故障退出、重启判定、继续或补偿回滚。

它不是表单型 CRUD：每次运行都创建隔离 schema，迁移过程写入哈希链 WAL，重启时同时使用日志证据和物理结构探测判断状态。无法证明安全时状态进入 `BLOCKED`，必须由用户选择继续或回滚，系统不会凭步骤名称盲目重跑。

## 构建、测试和启动

安装/打包：

```bash
./mvnw -q -DskipTests package
```

测试并启动：

```bash
./mvnw -q test && ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5209
```

打开页面：

```text
http://127.0.0.1:5209
```

默认数据库在 `./data/migration-lab*`。要换一个完全干净的库，可停止应用后删除 `data/`，或设置：

```bash
MIGRATION_LAB_DB='jdbc:h2:file:/tmp/migration-lab;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE' ./mvnw -q spring-boot:run
```

## 内置迁移场景

默认定义是 `customer-email-normalization`：

1. 创建新表 `customers_v2`，增加生成列 `email_normalized = lower(trim(email))`。
2. 安装 H2 行级触发器，建立旧表到新表的双写窗口。
3. 按稳定主键边界分批回填历史行，每批 25 行，共 6 批。
4. 逐行比较旧读路径和新读路径，记录行数、摘要和首个差异位置。
5. 原子切换读路径：删除触发器、`customers -> customers_old`、`customers_v2 -> customers`。
6. 终局校验结构、数据不变量和 WAL 终局状态，全部满足才标记 `COMPLETED`。

隔离 schema 中有独立副本；默认种子数据为 137 行。可以在双写窗口通过页面按钮或 API 插入新行，验证新行不丢失、不重复。

## 页面操作

1. 打开页面后选择故障点，例如 `backfill_customers.batch_after`。
2. 选择退出方式：
   - `simulate`：抛出进程退出异常，进程不终止，适合快速跑完整套点。
   - `halt`：执行 `Runtime.getRuntime().halt(86)`，模拟真实进程退出。
3. 点击“创建副本”，再点击“启动并推进”。
4. 如果使用 `halt`，重启同一个应用和同一个本地数据库。
5. 启动恢复会自动扫描未终局运行；也可以点“模拟重启判定”。
6. 根据诊断点击“安全继续”或“补偿回滚”。
7. 在“批次”“校验摘要”“恢复日志”区域查看每批行数、主键边界、checksum、旧/新读路径摘要和 WAL 哈希链。
8. 可复制当前证据 JSON，之后导入任意同规则版本实例重新证明。

## 用 API 复现一次真实故障恢复

先启动应用：

```bash
./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5209
```

另开终端：

```bash
# 取定义指纹
curl -s http://127.0.0.1:5209/api/definitions/customer-email-normalization
```

将返回 JSON 中的 `fingerprint` 复制到环境变量：

```bash
FP='替换成 fingerprint'

# 创建隔离副本
RUN=$(curl -s -X POST http://127.0.0.1:5209/api/runs \
  -H 'Content-Type: application/json' \
  -d "{\"fingerprint\":\"$FP\",\"exitMode\":\"halt\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

# 在第一批物理提交后、批次日志确认前真实退出；HTTP EOF 是预期现象
curl -s -X POST http://127.0.0.1:5209/api/runs/$RUN/start \
  -H 'Content-Type: application/json' \
  -d '{"faultPoint":"backfill_customers.batch_after","exitMode":"halt"}'
```

重启：

```bash
./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--server.port=5209
```

查看恢复判定：

```bash
curl -s http://127.0.0.1:5209/api/runs/$RUN
```

预期：状态为 `READY`，当前步骤为 `backfill_customers`，诊断说明“恢复游标取目标表最早缺失主键的前一个稳定边界”。第一批的稳定边界是 `25`，因此 `cursorId=25`。

继续：

```bash
curl -s -X POST http://127.0.0.1:5209/api/runs/$RUN/resume
```

预期最终状态是 `COMPLETED`。也可以在故障后选择回滚：

```bash
curl -s -X POST http://127.0.0.1:5209/api/runs/$RUN/rollback
```

预期最终状态是 `ROLLED_BACK`，WAL 中有逆序补偿事件和 `ROLLBACK_COMMITTED`。

## 状态和恢复原则

- `NEVER_STARTED`：只看到运行创建或未发现 PREPARED/物理效果；故障点 before 可重新开始。
- `RUNNING`：步骤或批次正在进行；重启后必须重新判定。
- `READY`：日志与物理证据证明可以安全继续。
- `AWAITING_DECISION` / `PAUSED`：等待人工选择继续或回滚。
- `BLOCKED`：物理状态和日志不能证明属于安全前态或后态，系统拒绝猜测。
- `COMPLETED`：结构、数据不变量、WAL 终局三者全部满足；不是命令返回零就算完成。
- `ROLLED_BACK`：可补偿步骤已按逆序回滚。

恢复判定的核心规则：

- DDL 使用物理事务提交；重启后查表、触发器等物理状态。
- 批次回填使用 `where id > :cursor order by id fetch first :n rows only`。
- 插入使用 `where not exists`，因此恢复重放幂等。
- 重启后不直接使用 `max(customers_v2.id)`，因为双写窗口中可能已有更大的新行；恢复游标取“源表存在但目标表缺失的最小主键”的前一个稳定边界。
- 触发器、表名组合或日志链不能证明时，进入 `BLOCKED`。

## 定义版本、旧指纹和冲突

定义内容会规范化后计算 SHA-256 指纹。每次运行永久绑定创建时的指纹；之后定义修改不会影响已有运行。

修改定义必须通过分支接口提交父指纹。两个浏览器基于同一个旧版本提交时：

- 第一个提交生成新版本。
- 第二个仍带旧父指纹会收到 `409 CONFLICT`。
- 后到一方需要重新载入最新版本，手工重新合并自己的修改，再基于最新父指纹提交。

## 证据包和派生物

证据导出包含：

- 规则版本 `migration-lab.rules.v1`
- 定义指纹
- 运行头和隔离 schema
- 全量 WAL 事件与前序哈希链
- 每批行数、末位主键和 checksum
- 旧/新读路径校验摘要
- 终局结构和数据证明

导入证据时原始 JSON 只追加到 `evidence_bundle`，不做原地改写。分析结果是派生物，包含规则版本、来源指纹和 `PROVEN/REJECTED` 状态。

## 离线测试

测试不依赖外部服务：

```bash
./mvnw -q test
```

覆盖内容：

- 完整迁移、6 批回填、旧/新读路径一致、终局结构和哈希链。
- 第一批物理提交后崩溃，恢复期间插入新行，恢复后不丢失、不重复。
- 切换后逆序补偿回滚。
- 两个浏览器基于旧版本提交时的分支冲突。
- 证据导入、原始证据保留、规则版本和来源指纹验证。
- 所有 13 个声明故障点分别验证继续和回滚，共 26 个演练决策。
- 真实 `Runtime.halt(86)` 后重启进程，重新打开 H2 文件库并恢复。

## 代码布局

- `src/main/resources/schema-control.sql`：控制库、WAL、批次、校验、证据表。
- `domain/DefinitionStore.java`：内容寻址定义、版本分支和冲突。
- `domain/RunStore.java`：运行头、哈希链 WAL、批次和校验记录。
- `engine/PhysicalMigrator.java`：隔离 schema、DDL、触发器、回填、切换和物理探测。
- `engine/MigrationEngine.java`：步骤状态机、重启判定、继续和补偿回滚。
- `engine/PreconditionChecker.java`：前置条件、跨表不变量和读路径 checksum。
- `domain/RehearsalService.java`：自动演练所有声明故障点。
- `domain/EvidenceService.java`：证据导出、不可变接收和重新证明。
- `src/main/resources/static/`：无 CDN、无外部网络依赖的操作页面。
