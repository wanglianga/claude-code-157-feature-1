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
| 病区管理 | 封闭管理（批量冻结/解封）、患者转科（普通病区间）、护士限制外出、押金争议登记 | `POST /api/wards/...` |
| 转科迁移 | 普通病房→**重症过渡病区**：暂停原证、家属重新确认陪护规则、新病区护士审批、必要交接通行、旧证退回后释放押金 | `POST /api/transfers/...` |
| 违规 | 安保发现冒用（自动吊销证件）、夜间异常、多证共用、过期未退等；可确认/驳回处理 | `POST /api/violations` |
| 退证 | 正常退证/过期自动作废（定时任务）/违规吊销；门禁即时失效 | `POST /api/permits/{no}/return` |
| 归档 | 单条陪护记录聚合申请、证件、进出、替换、违规、完整事件流 | `GET /api/cases/{caseNo}/archive` |
| 看板 | 病区在院陪护人数、今日出入、夜间异常、违规频次、过期未退、退证平均耗时 | `GET /api/wards/{ward}/dashboard` |
| 病区通知 | 每次替换/冻结/退证/转科都推送病区通知，明确当前照护人 | `GET /api/wards/{ward}/notices` |

陪护证**不只是门禁凭证**：它联动病区感染控制（封闭即冻结）、护士巡查（外出限制/夜间异常）、
家属沟通（病区通知/押金争议）和患者实际照护状态（`current_escort_*` 快照）。

## 2. 本地启动

### 2.0 Docker 一键启动（推荐）

```bash
docker compose up -d --build      # 启动 PostgreSQL + 陪护证服务(Flyway 自动建表/迁移)
curl http://localhost:8080/actuator/health   # {"status":"UP"}
# 需要真实 Kafka 时: docker compose --profile kafka up -d
```

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

接口文档（后端服务内置）：
- Swagger UI：http://localhost:8080/swagger-ui/index.html （`/swagger-ui.html` 自动跳转）
- OpenAPI 3 JSON：http://localhost:8080/v3/api-docs

### 2.3 预置病区（测试账号/策略，V1 迁移写入）

| 病区 | 名称 | 类别 | 允许探视 | 每患者陪护上限 | 夜间陪护需审批 |
|---|---|---|---|---|---|
| W3A | 内科三病区A | 普通病房 | 08:00–20:00 | 1 | 是 |
| W5B | 外科五病区B | 普通病房 | 08:00–20:00 | 1 | 是 |
| ICU1 | 重症监护一区 | 普通病区（ICU） | 10:00–16:00 | 1 | 是 |
| ITU1 | 重症过渡病区 | **重症过渡（ICU_TRANSITION）** | 10:00–16:00 | 1 | 是 |

> 类别 `ICU_TRANSITION` 的病区执行更严格的陪护规则：普通病房患者转入时，**原陪护权限不再适用**，
> 必须走转科迁移流程（暂停原证 → 家属重新确认规则 → 新病区护士审批）。

系统内角色（接口字段中直接使用的工号，无需登录）：
- 护士站审批人：`NURSE01` / `NURSE02` / `NURSE-HEAD`
- 病区管理员（封闭管理/转科）：`WARD-MGR`
- 安保巡查：`SEC-GUARD-07`
- 家属/陪护人：请求体中的姓名、身份证（6–24 位字母数字）、手机号

## 3. 端到端冒烟脚本

应用启动后执行（申请→审批→发证同步→进出→夜间异常→生病替换→病区通知→**转科迁移（不允许陪护/押金闸门/交接通行）**→看板→退证→归档→关闭）：

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

# 患者转科(普通病区间): 冻结原证, 需在新病区重新申请
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

### 4.1 患者转科陪护证迁移（普通病房 → 重症过渡病区）

患者由普通病房转入重症过渡病区（`ICU_TRANSITION`），原陪护权限不再适用。流程接口：

| 步骤 | 接口 | 说明 |
|---|---|---|
| ① 启动迁移 | `POST /api/transfers/cases/{caseNo}/start` | 暂停原证（FROZEN + transfer_suspended，门禁 FREEZE 同步）；患者落到新病区床位；生成并推送家属《重症过渡病区陪护规则》；可同步发一次限时必要交接通行 |
| ② 家属确认 | `POST /api/transfers/{transferNo}/family-confirm` | 家属重新确认陪护规则（必须 `acknowledged=true`） |
| ③ 新病区审批 | `POST /api/transfers/{transferNo}/decision` | 新病区护士决定是否允许继续陪护 |
| ④ 旧证退回 | `POST /api/transfers/{transferNo}/old-permit-return` | 旧实体卡交回新病区护士站 |
| ⑤ 释放押金 | `POST /api/transfers/{transferNo}/release-deposit` | **仅不允许陪护路径**；旧证未实体退回一律 409 |
| 追加交接通行 | `POST /api/transfers/{transferNo}/handover-pass` | 迁移/交接期间限时、限新病区交接点、一进一出 |
| 查询迁移单 | `GET /api/transfers/{transferNo}` | 状态/家属确认/新证/旧证退回/押金释放 |

门禁规则（`POST /api/access/scan`，可用 `wardCode` 或门禁位置前缀指定扫码点病区）：
- 迁移完成前（`PENDING_FAMILY_CONFIRM / PENDING_WARD_APPROVAL / ESCORT_DENIED`），**旧证进入新病区一律 DENIED**，
  并记违规 `TRANSFER_ZONE_BREACH`；
- 仅持有效的「必要交接」授权时在新病区缓冲交接点放行（`handoverPass=true`，一进一出，超时/重复使用无效）；
- 离开（OUT）始终放行，保证家属可取回物品/离开；
- 非迁移场景，有效证跨病区进入同样拒绝。

审批结果：
- **允许继续陪护**（`approved=true`）：旧证置 REPLACED 并 REVOKE 同步门禁，按新病区规则签发同陪护人的新证，
  当前照护人切到新证，迁移完成（`MIGRATED`）；
- **不允许陪护**（`approved=false`）：生成《患者物品交接单》与《家属探视说明》（归档 `handovers`，
  可用 `handoverItems/visitNote` 自定义），患者当前陪护人清空；旧证维持暂停，家属须交回旧实体卡；
  **旧证退回后才结束陪护责任、押金才具备释放条件**，防止家属仍持旧证在医院内通行。

```bash
# ① 启动迁移(issueHandoverPass 默认 true; handoverPassFrom 可指定通行生效时间)
curl -s -X POST localhost:8080/api/transfers/cases/EC-XXXXXXXX/start -H 'Content-Type: application/json' -d '{
  "toWardCode":"ITU1","bedNo":"T-01","operator":"NURSE01",
  "issueHandoverPass":true,"handoverPassMinutes":120}'

# ② 家属重新确认重症过渡病区陪护规则
curl -s -X POST localhost:8080/api/transfers/TF-XXXXXXXX/family-confirm -H 'Content-Type: application/json' \
  -d '{"confirmedBy":"周敏","acknowledged":true,"note":"已知悉探视/感控/夜间陪护规则"}'

# ③a 新病区护士批准继续陪护 -> 旧证作废、签发 ITU1 新证
curl -s -X POST localhost:8080/api/transfers/TF-XXXXXXXX/decision -H 'Content-Type: application/json' \
  -d '{"approved":true,"decidedBy":"NURSE-ITU","note":"病情需要一名家属陪护","approveNightCare":false}'

# ③b 或: 新病区不允许陪护 -> 生成物品交接单+家属探视说明
curl -s -X POST localhost:8080/api/transfers/TF-XXXXXXXX/decision -H 'Content-Type: application/json' -d '{
  "approved":false,"decidedBy":"NURSE-ITU","note":"重症过渡病区不安排家属陪护",
  "handoverItems":["患者随身衣物: 已当面交接","旧陪护证实体卡: 待家属交回"],
  "visitNote":"每日 15:00-15:30 可电话询问病情"}'

# 迁移期扫码: 无授权进入新病区 -> DENIED + TRANSFER_ZONE_BREACH; OUT 放行
curl -s -X POST localhost:8080/api/access/scan -H 'Content-Type: application/json' -d '{
  "token":"EP-XXXXXXXX","direction":"IN","wardCode":"ITU1",
  "gateLocation":"ITU1-缓冲交接闸机","scannedAt":"2026-10-02T04:00:00Z"}'

# ④ 旧实体卡退回  ⑤(不允许路径)旧证退回后释放押金
curl -s -X POST localhost:8080/api/transfers/TF-XXXXXXXX/old-permit-return -H 'Content-Type: application/json' \
  -d '{"returnedBy":"周敏","channel":"NEW_WARD_DESK","receivedBy":"NURSE-ITU"}'
curl -s -X POST localhost:8080/api/transfers/TF-XXXXXXXX/release-deposit -H 'Content-Type: application/json' \
  -d '{"operator":"WARD-MGR","note":"旧证已收回, 押金原路退还"}'
```

## 5. 测试与验证方式

```bash
mvn test        # 29 个集成测试, 使用 zonky 嵌入式 PostgreSQL + 嵌入式 Kafka, 无需任何外部依赖
```

测试覆盖：
- `EscortLifecycleTest`（7）：申请校验（健康承诺/筛查/夜间专项审批/上限）、审批发证三端同步、正常进出、退证门禁失效、驳回、无效证
- `ScanAnomalyTest`（5）：非允许时段进入拒绝并记夜间异常、批夜间陪护后可入但仍标记超时、人证不符判定未登记换人、2 小时时间窗多证共用、进出记录字段
- `SpecialScenariosTest`（9）：陪护人生病替换、亲属争抢裁决、病区封闭冻结/解封、患者转科双病区通知、安保冒用自动吊销、护士限制外出、过期未退自动作废、押金争议不影响证件、全环节同记录归档
- `PermitMigrationTest`（5）：重症过渡病区预置/迁移入口校验；批准迁移（暂停原证→家属确认→新病区审批出新证→旧证作废→旧实体卡退回）；不允许陪护（交接单+探视说明→旧证未退押金 409→交接通行一进一出→退旧证→释放押金）；迁移期门禁隔离与 `TRANSFER_ZONE_BREACH`；有效证跨病区拒绝
- `DashboardTest`（2）：在院陪护人数、出入次数、夜间异常、违规分类、退证平均耗时、待审批数、待新病区迁移审批数
- `KafkaSyncTest`（1）：Kafka 模式下发证事件 → 模拟门禁/扫码点/安保消费 → `escort.access.ack` 回执 → `permit_sync` 三个目标全部 SYNCED

### 本次实际执行过的验证（2026-10-02）
1. `mvn test`：**29/29 全部通过**（含嵌入式 PostgreSQL 与嵌入式 Kafka 链路；新增 `PermitMigrationTest` 5 个用例）。
2. `./scripts/local-db.sh init` 启动本地 PostgreSQL 15 :55432，`mvn package` + `java -jar` 实际启动服务，
   `/actuator/health = UP`；psql 确认 Flyway 完成 V1+V2 迁移，共 13 张业务表（V2 新增 `permit_transfer / belongings_handover / handover_pass_grant`），
   并预置 `ITU1` 重症过渡病区（`ward_category=ICU_TRANSITION`）。
3. `./scripts/smoke.sh` 真实跑通完整链路（含新增迁移段，退出码 0）：
   迁移期旧证进入 ITU1 返回 `DENIED ['TRANSFER_ZONE_BREACH']`；旧证未退回时释放押金返回 HTTP 409
   "旧陪护证尚未实体退回…"；必要交接进入 `ALLOWED handoverPass=True`；旧证退回后 `ESCORT_DENIED` 迁移单
   `depositReleased=True / escortLiabilityEnded=True`。
4. 另手工验证批准路径：启动迁移后旧证 `FROZEN` → 家属确认 → 新病区批准，迁移单 `MIGRATED`、旧证 `REPLACED`、
   新证 `ITU1/ACTIVE` 正常扫码进入（非交接通行）→ 旧实体卡退回登记成功。
5. OpenAPI/Swagger：`GET /swagger-ui/index.html` 返回 200，`GET /v3/api-docs` 返回 OpenAPI 3.0.1 文档，
   含全部 `/api/transfers/**` 迁移接口。
6. 再次 `mvn test` 确认 29/29 全部通过（含 springdoc 依赖，无回归）。

### 历史验证（2026-09-26）
1. `mvn test`：**24/24 全部通过**（含嵌入式 PostgreSQL 与嵌入式 Kafka 链路）。
2. `./scripts/local-db.sh init` 启动本地 PostgreSQL 15 :55432，`java -jar` 实际启动服务，`/actuator/health = UP`，psql 确认 Flyway 建立 11 张业务表。
3. `./scripts/smoke.sh` 对运行中的服务跑通完整链路：审批通过后 `syncedTargets=[GATE, WARD_SCAN, SECURITY_PATROL]`；
   22:30 扫码进入返回 `DENIED` 且 `violations=[NIGHT_ABNORMAL]`；生病替换后旧证 `REPLACED`、新证 `ACTIVE`、
   病区通知含"现由 周母 照护"；退证后记录当前陪护人为空；归档事件流覆盖 APPLICATION/PERMIT/SCAN/REPLACEMENT/RETURN 全环节。

## 6. 数据模型要点
- `escort_case`：聚合根，按患者本次住院一条；保存 `current_escort_*` 当前照护人快照。
- `escort_application` / `escort_permit`：申请（INITIAL/REPLACEMENT）与证件（ACTIVE/FROZEN/EXPIRED/RETURNED/REVOKED/REPLACED，乐观锁 version，`transfer_suspended` 迁移挂起标记）。
- `permit_transfer`：转科陪护证迁移单（待家属确认/待新病区审批/已迁移/不允许陪护），串联旧证暂停、家属确认、新证、旧证实体退回、押金释放。
- `belongings_handover` / `handover_pass_grant`：新病区不允许陪护时的物品交接单+家属探视说明；迁移期必要交接限时通行授权（一进一出）。
- `scan_record`：每次进出，含 out_of_hours / escort_changed / multi_permit_shared / ward_lockdown / handover_pass 判定位。
- `violation_record` / `replacement_record` / `case_event` / `ward_notice` / `permit_sync`；新增违规类型 `TRANSFER_ZONE_BREACH`。
- Kafka 主题：`escort.permit.events`（发证/冻结/吊销等，key=permitNo）、`escort.access.ack`（下游同步回执）。

## 7. 脚本
- `scripts/local-db.sh init|start|stop|status|recreate`：本地免 sudo PostgreSQL 实例（数据目录 `.local-pg/`，已 gitignore）
- `scripts/smoke.sh`：对 http://localhost:8080 的端到端冒烟
