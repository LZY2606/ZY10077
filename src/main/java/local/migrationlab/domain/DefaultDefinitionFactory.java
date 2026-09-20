package local.migrationlab.domain;

import org.springframework.stereotype.Component;

@Component
public class DefaultDefinitionFactory {
    public String create() {
        return """
                {
                  "id": "customer-email-normalization",
                  "title": "客户邮箱规范化结构迁移",
                  "schema": {
                    "sourceTable": "customers",
                    "targetTable": "customers_v2",
                    "primaryKey": "id",
                    "batchSize": 25
                  },
                  "snapshot": {
                    "customers": {
                      "columns": [
                        {"name": "id", "type": "BIGINT", "primaryKey": true},
                        {"name": "email", "type": "VARCHAR(320)", "nullable": false},
                        {"name": "created_at", "type": "TIMESTAMP WITH TIME ZONE", "nullable": false}
                      ],
                      "expectedRows": 137
                    }
                  },
                  "seedRows": 137,
                  "preconditions": [
                    {"type": "tableExists", "table": "customers"},
                    {"type": "tableMissing", "table": "customers_v2"},
                    {"type": "triggerMissing", "name": "trg_customers_dualwrite"}
                  ],
                  "steps": [
                    {
                      "id": "create_customers_v2",
                      "type": "create_target",
                      "title": "创建带规范化邮箱列的新表",
                      "preconditions": [{"type": "tableMissing", "table": "customers_v2"}],
                      "failurePoints": [
                        {"id": "create_customers_v2.before", "phase": "before", "expected": "未创建；从未开始，可继续"},
                        {"id": "create_customers_v2.after", "phase": "after", "expected": "表已提交；跳过创建，进入双写窗口"}
                      ]
                    },
                    {
                      "id": "enable_dual_write",
                      "type": "enable_dual_write",
                      "title": "建立旧表到新表的双写触发器",
                      "preconditions": [
                        {"type": "tableExists", "table": "customers_v2"},
                        {"type": "triggerMissing", "name": "trg_customers_dualwrite"}
                      ],
                      "failurePoints": [
                        {"id": "enable_dual_write.before", "phase": "before", "expected": "触发器不存在；继续安装"},
                        {"id": "enable_dual_write.after", "phase": "after", "expected": "触发器已安装；跳过重复安装"}
                      ]
                    },
                    {
                      "id": "backfill_customers",
                      "type": "backfill",
                      "title": "按稳定主键边界分批回填历史行",
                      "batchSize": 25,
                      "preconditions": [
                        {"type": "tableExists", "table": "customers"},
                        {"type": "tableExists", "table": "customers_v2"},
                        {"type": "triggerExists", "name": "trg_customers_dualwrite"}
                      ],
                      "failurePoints": [
                        {"id": "backfill_customers.before", "phase": "before", "expected": "游标未推进；安全继续"},
                        {"id": "backfill_customers.batch_after", "phase": "batch_after", "expected": "批次行和游标已提交；从下一主键边界继续"},
                        {"id": "backfill_customers.after", "phase": "after", "expected": "历史行全部提交，新行由双写覆盖"}
                      ]
                    },
                    {
                      "id": "verify_dual_write",
                      "type": "verify",
                      "title": "校验旧读路径与新读路径",
                      "failurePoints": [
                        {"id": "verify_dual_write.before", "phase": "before", "expected": "无验证派生物；可重新计算"},
                        {"id": "verify_dual_write.after", "phase": "after", "expected": "验证已提交；可检查摘要并进入切换"}
                      ]
                    },
                    {
                      "id": "cutover_read_path",
                      "type": "cutover",
                      "title": "原子切换旧读路径名称并停止双写",
                      "failurePoints": [
                        {"id": "cutover_read_path.before", "phase": "before", "expected": "仍处于双写窗口；可继续切换"},
                        {"id": "cutover_read_path.after", "phase": "after", "expected": "旧名已指向新结构，触发器已删除"}
                      ]
                    },
                    {
                      "id": "final_invariants",
                      "type": "final_verify",
                      "title": "终局结构、数据与日志判定",
                      "failurePoints": [
                        {"id": "final_invariants.before", "phase": "before", "expected": "切换完成但终局证明缺失；重新验证"},
                        {"id": "final_invariants.after", "phase": "after", "expected": "结构、不变量与日志终局全部满足"}
                      ]
                    }
                  ],
                  "invariants": [
                    {"type": "tableExists", "table": "customers"},
                    {
                      "type": "sameReadPath",
                      "oldPath": "select id, email, created_at from customers order by id",
                      "newPath": "select id, email, created_at from customers_v2 order by id"
                    }
                  ],
                  "readPaths": {
                    "old": "select id, email, created_at from customers order by id",
                    "new": "select id, email, created_at from customers_v2 order by id",
                    "comparison": "exactRows"
                  }
                }
                """;
    }
}
