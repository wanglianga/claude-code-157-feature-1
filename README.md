# 医院陪护证申请核验进出记录退证服务 (escort-pass-service)

住院患者陪护全生命周期服务：家属 **申请** → 护士站 **审批** → 签发电子陪护证并 **同步门禁/病区扫码点/安保巡查** →
进出 **扫码核验与异常记录** → 陪护人 **替换/冻结/违规处理** → **退证归档**。
申请、审批、进出、替换、违规、退证全部归档在**同一条陪护记录（escort_case）**上，
每一次替换、冻结、退证都会通知病区"患者当下由谁照护"。

技术栈：Kotlin + Spring Boot 3.2 + Spring Data JPA + Flyway + PostgreSQL 15 + Kafka + Maven。

## 1. 业务能力

| 环节 | 说明 | 接口 |
|---|---|---|
| 申请 | 住院号、病区/床位、陪护人身份与联系方式、健康承诺、核酸/传染病筛查、陪护期限、夜间陪护需求 | `POST /api/applications` |
| 审批 | 护士站按病区探视限制、床位陪护上限、夜间陪护专项审批、筛查有效期审批；通过即发证 | `POST /api/applications/{no}/decision` |
| 发证同步 | 电子证含二维码 token，状态变化同步 GATE / WARD_SCAN / SECURITY_PATROL 三类下游，`permit_sync` 留同步状态 | 自动 |
| 进出核验 | 记录扫码时间、门禁位置、方向；检测超允许时段、未登记换人、多证共用、病区封闭、外出限制 | `POST /api/access/scan` |
| 替换 | 陪护人临时生病、亲属争抢陪护资格、排班调整；旧证 REPLACED、新证签发，同记录留痕 | `POST /api/replacements` |
| 病区管理 | 封闭管理（批量冻结/解封）、患者转科、护士限制外出、押金争议登记 | `POST /api/wards/...` |
| 违规 | 安保发现冒用（自动吊销证件）、夜间异常、多证共用、过期未退等；可确认/驳回处理 | `POST /api/violations` |
| 退证 | 正常退证/过期自动作废（定时任务）/违规吊销；门禁即时失效 | `POST /api/permits/{no}/return` |
| 归档 | 单条陪护记录聚合申请、证件、进出、替换、违规、完整事件流 | `GET /api/cases/{caseNo}/archive` |
| 看板 | 病区在院陪护人数、今日出入、夜间异常、违规频次、过期未退、退证平均耗时 | `GET /api/wards/{ward}/dashboard` |
| 病区通知 | 每次替换/冻结/退证/转科都推送病区通知，明确当前照护人 | `GET /api/wards/{ward}/notices` |

陪护证**不只是门禁凭证**：它联动病区感染控制（封闭即冻结）、护士巡查（外出限制/夜间异常）、
家属沟通（病区通知/押金争议）和患者实际照护状态（`current_escort_*` 快照）。

## 2. 本地启动

### 2.1 环境要求
- JDK 17、Maven 3.8+
- PostgreSQL 15（本仓库自带免 sudo 的本地实例脚本）
- Kafka 可选：默认 `ESCORT_KAFKA_ENABLED=false`，门禁同步走本地模拟；设为 true 时走真实 Kafka

### 2.2 启动步骤

```bash
# 1. 初始化并启动本地 PostgreSQL（端口 55432，库 escort/用户 escort/密码 escort）
./scripts/local-db.sh init          # 首次；reset 用 ./scripts/local-db.sh recreate

# 2. 构建
mvn -q -DskipTests package

# 3. 运行（默认连 localhost:55432，无需 Kafka）
java -jar target/escort-pass-service-1.0.0.jar
# 服务监听 http://localhost:8080，Flyway 自动建表并写入 3 个病区策略
```

连接真实 Kafka（可选）：

```bash
ESCORT_KAFKA_ENABLED=true KAFKA_BOOTSTRAP_SERVERS=localhost:9092 java -jar target/escort-pass-service-1.0.0.jar
```

容器化依赖（可选，需要 Docker）：

```bash
docker compose up -d        # 启动 postgres:55432 与 kafka:9092
```

健康检查：`curl http://localhost:8080/actuator/health` → `{"status":"UP"}`

### 2.3 预置病区（测试账号/策略，V1 迁移写入）

| 病区 | 名称 | 允许探视 | 每患者陪护上限 | 夜间陪护需审批 |
|---|---|---|---|---|
| W3A | 内科三病区A | 08:00–20:00 | 1 | 是 |
| W5B | 外科五病区B | 08:00–20:00 | 1 | 是 |
| ICU1 | 重症监护一区 | 10:00–16:00 | 1 | 是 |

系统内角色（接口字段中直接使用的工号，无需登录）：
- 护士站审批人：`NURSE01` / `NURSE02` / `NURSE-HEAD`
- 病区管理员（封闭管理/转科）：`WARD-MGR`
- 安保巡查：`SEC-GUARD-07`
- 家属/陪护人：请求体中的姓名、身份证（6–24 位字母数字）、手机号

## 3. 端到端冒烟脚本

应用启动后执行（申请→审批→发证同步→进出→夜间异常→生病替换→病区通知→看板→退证→归档→关闭）：

```bash
./scripts/smoke.sh
```

## 4. 主要接口示例

```bash
# 家属申请
curl -s -X POST localhost:8080/api/applications -H 'Content-Type: application/json' -d '{
  "patientInpatientNo":"IP001","wardCode":"W3A","bedNo":"09-2",
  "escortName":"周敏","escortIdNo":"IDSMOKE01","escortPhone":"13811112222",
  "relationToPatient":"女儿","healthCommitment":true,"screeningType":"NAT",
  "screeningValidUntil":"2026-10-01T00:00:00Z",
  "periodStart":"2026-09-26T00:00:00Z","periodEnd":"2026-10-10T00:00:00Z",
  "needNightCare":false}'

# 护士站审批（needNightCare=true 时必须 approveNightCare=true）
curl -s -X POST localhost:8080/api/applications/EA-XXXXXXXX/decision -H 'Content-Type: application/json' \
  -d '{"approved":true,"decidedBy":"NURSE01","approveNightCare":false,"note":"病情需要"}'

# 扫码（token 为陪护证号 permitNo 或 qrToken；scannedIdNo 留空=持证人本人）
curl -s -X POST localhost:8080/api/access/scan -H 'Content-Type: application/json' \
  -d '{"token":"EP-XXXXXXXX","scannedIdNo":null,"direction":"IN","gateLocation":"W3A-东门闸机","scannedAt":"2026-09-27T02:00:00Z"}'

# 陪护人生病替换（autoApprove=true 护士站即时审批出新证）
curl -s -X POST localhost:8080/api/replacements -H 'Content-Type: application/json' -d '{
  "oldPermitNo":"EP-XXXXXXXX","reason":"ESCORT_SICK","detail":"发烧由家属接替",
  "escortName":"周母","escortIdNo":"IDSMOKENEW","escortPhone":"13833334444",
  "healthCommitment":true,"screeningType":"NAT","screeningValidUntil":"2026-10-01T00:00:00Z",
  "periodEnd":"2026-10-10T00:00:00Z","autoApprove":true,"decidedBy":"NURSE01"}'

# 病区封闭 / 解封（自动冻结/恢复该病区陪护证）
curl -s -X POST localhost:8080/api/wards/W3A/lockdown -H 'Content-Type: application/json' \
  -d '{"lockdown":true,"reason":"院感事件封闭48小时","operator":"WARD-MGR"}'

# 患者转科
curl -s -X POST localhost:8080/api/wards/cases/EC-XXXXXXXX/transfer -H 'Content-Type: application/json' \
  -d '{"toWardCode":"W5B","bedNo":"08-1","operator":"NURSE01","freezePermit":true}'

# 护士限制外出 / 安保上报冒用（冒用自动吊销）
curl -s -X POST localhost:8080/api/wards/permits/EP-XXXXXXXX/exit-restriction -H 'Content-Type: application/json' \
  -d '{"restricted":true,"reason":"患者病情不稳定需陪护在场","operator":"NURSE01"}'
curl -s -X POST localhost:8080/api/violations -H 'Content-Type: application/json' \
  -d '{"permitNo":"EP-XXXXXXXX","type":"SECURITY_IMPERSONATION","detail":"人证不符","actualUserIdNo":"IDFAKE","reportedBy":"SEC-GUARD-07"}'

# 退证 / 过期扫描 / 关闭记录
curl -s -X POST localhost:8080/api/permits/EP-XXXXXXXX/return -H 'Content-Type: application/json' \
  -d '{"returnedBy":"周母","channel":"WARD_DESK","note":"患者出院"}'
curl -s -X POST localhost:8080/api/permits/maintenance/expire-scan
curl -s -X POST localhost:8080/api/cases/EC-XXXXXXXX/close -H 'Content-Type: application/json' -d '{"operator":"NURSE01"}'

# 单条陪护记录完整归档 / 看板 / 病区通知
curl -s localhost:8080/api/cases/EC-XXXXXXXX/archive
curl -s 'localhost:8080/api/wards/W3A/dashboard?days=7'
curl -s 'localhost:8080/api/wards/W3A/notices?unreadOnly=true'
```

## 5. 测试与验证方式

```bash
mvn test        # 24 个集成测试, 使用 zonky 嵌入式 PostgreSQL + 嵌入式 Kafka, 无需任何外部依赖
```

测试覆盖：
- `EscortLifecycleTest`（7）：申请校验（健康承诺/筛查/夜间专项审批/上限）、审批发证三端同步、正常进出、退证门禁失效、驳回、无效证
- `ScanAnomalyTest`（5）：非允许时段进入拒绝并记夜间异常、批夜间陪护后可入但仍标记超时、人证不符判定未登记换人、2 小时时间窗多证共用、进出记录字段
- `SpecialScenariosTest`（9）：陪护人生病替换、亲属争抢裁决、病区封闭冻结/解封、患者转科双病区通知、安保冒用自动吊销、护士限制外出、过期未退自动作废、押金争议不影响证件、全环节同记录归档
- `DashboardTest`（2）：在院陪护人数、出入次数、夜间异常、违规分类、退证平均耗时、待审批数
- `KafkaSyncTest`（1）：Kafka 模式下发证事件 → 模拟门禁/扫码点/安保消费 → `escort.access.ack` 回执 → `permit_sync` 三个目标全部 SYNCED

### 本次实际执行过的验证（2026-09-26）
1. `mvn test`：**24/24 全部通过**（含嵌入式 PostgreSQL 与嵌入式 Kafka 链路）。
2. `./scripts/local-db.sh init` 启动本地 PostgreSQL 15 :55432，`java -jar` 实际启动服务，`/actuator/health = UP`，psql 确认 Flyway 建立 11 张业务表。
3. `./scripts/smoke.sh` 对运行中的服务跑通完整链路：审批通过后 `syncedTargets=[GATE, WARD_SCAN, SECURITY_PATROL]`；
   22:30 扫码进入返回 `DENIED` 且 `violations=[NIGHT_ABNORMAL]`；生病替换后旧证 `REPLACED`、新证 `ACTIVE`、
   病区通知含"现由 周母 照护"；退证后记录当前陪护人为空；归档事件流覆盖 APPLICATION/PERMIT/SCAN/REPLACEMENT/RETURN 全环节。

## 6. 数据模型要点
- `escort_case`：聚合根，按患者本次住院一条；保存 `current_escort_*` 当前照护人快照。
- `escort_application` / `escort_permit`：申请（INITIAL/REPLACEMENT）与证件（ACTIVE/FROZEN/EXPIRED/RETURNED/REVOKED/REPLACED，乐观锁 version）。
- `scan_record`：每次进出，含 out_of_hours / escort_changed / multi_permit_shared / ward_lockdown 判定位。
- `violation_record` / `replacement_record` / `case_event` / `ward_notice` / `permit_sync`。
- Kafka 主题：`escort.permit.events`（发证/冻结/吊销等，key=permitNo）、`escort.access.ack`（下游同步回执）。

## 7. 脚本
- `scripts/local-db.sh init|start|stop|status|recreate`：本地免 sudo PostgreSQL 实例（数据目录 `.local-pg/`，已 gitignore）
- `scripts/smoke.sh`：对 http://localhost:8080 的端到端冒烟
