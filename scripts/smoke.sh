#!/usr/bin/env bash
# 端到端冒烟: 申请->审批->发证->扫码->异常->替换->退证->看板->归档->关闭,
# 以及转科陪护证迁移(暂停原证/家属确认/门禁交接放行与闯入拒绝/护士不允许/交接文档/旧证退回释放押金)
# 前置: 应用已在 localhost:8080 运行, 本地 Postgres 已初始化
set -euo pipefail
BASE="http://localhost:8080"
H='-H Content-Type:application/json'
j() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

echo "== 病区列表 =="
curl -s $BASE/api/wards | python3 -m json.tool

echo "== 1. 家属提交申请 =="
APPLY=$(curl -s $H -X POST $BASE/api/applications -d '{
  "patientInpatientNo":"IP-SMOKE-01","wardCode":"W3A","bedNo":"09-2",
  "escortName":"周敏","escortIdNo":"IDSMOKE01","escortPhone":"13811112222",
  "relationToPatient":"女儿","healthCommitment":true,"screeningType":"NAT",
  "screeningValidUntil":"2026-10-20T00:00:00Z",
  "periodStart":"2026-09-26T00:00:00Z","periodEnd":"2026-10-10T00:00:00Z",
  "needNightCare":false}')
echo "$APPLY" | python3 -m json.tool
APP_NO=$(echo "$APPLY" | j "['applicationNo']")
CASE_NO=$(echo "$APPLY" | j "['caseNo']")

echo "== 2. 护士站审批 =="
curl -s $H -X POST $BASE/api/applications/$APP_NO/decision -d '{
  "approved":true,"decidedBy":"NURSE-SMOKE","note":"病情需要,同意","approveNightCare":false}' | python3 -m json.tool

echo "== 3. 查看陪护证(含下游同步状态) =="
PERMIT_NO=$(curl -s $BASE/api/cases/$CASE_NO/archive | j "['permits'][0]['permitNo']")
TOKEN=$(curl -s $BASE/api/permits/$PERMIT_NO | j "['qrToken']")
echo "permit=$PERMIT_NO token=$TOKEN"
curl -s $BASE/api/permits/$PERMIT_NO | python3 -m json.tool

echo "== 4. 正常时段进入/离开 =="
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TOKEN\",\"direction\":\"IN\",\"gateLocation\":\"W3A-东门闸机\",\"scannedAt\":\"2026-09-27T02:00:00Z\"}" | python3 -m json.tool
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TOKEN\",\"direction\":\"OUT\",\"gateLocation\":\"W3A-东门闸机\",\"scannedAt\":\"2026-09-27T03:00:00Z\"}" | python3 -m json.tool

echo "== 5. 夜间非允许时段进入 -> 拒绝+异常 =="
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TOKEN\",\"direction\":\"IN\",\"gateLocation\":\"W3A-东门闸机\",\"scannedAt\":\"2026-09-27T14:30:00Z\"}" | python3 -m json.tool

echo "== 6. 陪护人临时生病, 申请替换并即时审批 =="
curl -s $H -X POST $BASE/api/replacements -d "{
  \"oldPermitNo\":\"$PERMIT_NO\",\"reason\":\"ESCORT_SICK\",\"detail\":\"周敏发烧,由母亲接替\",
  \"escortName\":\"周母\",\"escortIdNo\":\"IDSMOKENEW\",\"escortPhone\":\"13833334444\",
  \"relationToPatient\":\"配偶\",\"healthCommitment\":true,\"screeningType\":\"NAT\",
  \"screeningValidUntil\":\"2026-10-20T00:00:00Z\",
  \"periodEnd\":\"2026-10-10T00:00:00Z\",\"autoApprove\":true,\"decidedBy\":\"NURSE-SMOKE\"}" | python3 -m json.tool

echo "== 7. 病区通知(应含当前照护人变更) =="
curl -s "$BASE/api/wards/W3A/notices?unreadOnly=true" | python3 -c "
import sys,json
for n in json.load(sys.stdin)[:8]: print(n['type'],'-',n['message'])"

echo "== 8. 看板 =="
curl -s "$BASE/api/wards/W3A/dashboard?days=7" | python3 -m json.tool

echo "== 9. 新陪护人退证 =="
NEW_PERMIT=$(curl -s $BASE/api/cases/$CASE_NO/archive | j "['permits'][-1]['permitNo']")
curl -s $H -X POST $BASE/api/permits/$NEW_PERMIT/return -d '{"returnedBy":"周母","channel":"WARD_DESK","note":"患者出院"}' | python3 -m json.tool

echo "== 10. 完整归档事件流 =="
curl -s $BASE/api/cases/$CASE_NO/archive | python3 -c "
import sys,json
a=json.load(sys.stdin)
print('caseNo:',a['caseNo'],'status:',a['status'],'currentEscort:',a['currentEscortName'])
print('applications:',len(a['applications']),'permits:',len(a['permits']),
      'scans:',len(a['scans']),'replacements:',len(a['replacements']),
      'violations:',len(a['violations']))
for e in a['events']: print(' -',e['eventType'],'|',e['summary'])"

echo "== 11. 关闭记录 =="
curl -s $H -X POST $BASE/api/cases/$CASE_NO/close -d '{"operator":"NURSE-SMOKE"}' | python3 -m json.tool

echo "== 12. 转科陪护证迁移: 普通病房 W3A -> 重症过渡病区 ICU1 =="
A2=$(curl -s $H -X POST $BASE/api/applications -d '{
  "patientInpatientNo":"IP-SMOKE-02","wardCode":"W3A","bedNo":"11-1",
  "escortName":"吴芳","escortIdNo":"IDSMOKE02","escortPhone":"13822223333",
  "relationToPatient":"女儿","healthCommitment":true,"screeningType":"NAT",
  "screeningValidUntil":"2026-10-20T00:00:00Z",
  "periodStart":"2026-09-26T00:00:00Z","periodEnd":"2026-10-15T00:00:00Z",
  "needNightCare":false}')
APP2=$(echo "$A2" | j "['applicationNo']"); CASE2=$(echo "$A2" | j "['caseNo']")
curl -s $H -X POST $BASE/api/applications/$APP2/decision -d '{"approved":true,"decidedBy":"NURSE-SMOKE","note":"同意"}' > /dev/null
OLD2=$(curl -s $BASE/api/cases/$CASE2/archive | j "['permits'][0]['permitNo']")
TOK2=$(curl -s $BASE/api/permits/$OLD2 | j "['qrToken']")

echo "-- 12.1 发起迁移(原证暂停, 通知家属确认规则) --"
TR=$(curl -s $H -X POST $BASE/api/wards/cases/$CASE2/transfer -d '{
  "toWardCode":"ICU1","bedNo":"ICU-03-1","operator":"NURSE-SMOKE",
  "escortMigration":true,"reason":"病情加重转入重症过渡病区"}')
echo "$TR" | python3 -m json.tool
TRNO=$(echo "$TR" | j "['transferNo']")

echo "-- 12.2 迁移完成前旧证进入新病区 -> DENIED + TRANSFER_BREACH --"
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TOK2\",\"direction\":\"IN\",\"wardCode\":\"ICU1\",\"gateLocation\":\"ICU1-过渡病区闸机\",\"scannedAt\":\"2026-10-02T04:00:00Z\"}" | python3 -m json.tool
echo "-- 12.3 必要交接(返回原病区取物品) -> ALLOWED handoverPass=true --"
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TOK2\",\"direction\":\"IN\",\"wardCode\":\"W3A\",\"gateLocation\":\"W3A-护士站通道\",\"scannedAt\":\"2026-10-02T05:00:00Z\"}" | python3 -m json.tool

echo "-- 12.4 家属重新确认新病区陪护规则 --"
curl -s $H -X POST $BASE/api/transfers/$TRNO/family-confirm -d '{"confirmedBy":"吴芳","ackRules":true,"note":"已知悉ICU探视与陪护规则"}' | python3 -m json.tool

echo "-- 12.5 新病区护士不允许陪护 -> 出具交接单/探视说明 --"
curl -s $H -X POST $BASE/api/transfers/$TRNO/decision -d '{"decidedBy":"NURSE-HEAD","approved":false,"note":"院感要求不安排家属陪护"}' | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('status:',d['status'],'depositReleased:',d['depositReleased'],'liabilityEnded:',d['liabilityEnded'])
for x in d['documents']: print(' doc:',x['docType'],x['docNo'],x['title'])"

echo "-- 12.6 普通退证接口被拒(必须走迁移结算) --"
curl -s -o /dev/null -w "普通退证 HTTP %{http_code} (预期409)\n" $H -X POST $BASE/api/permits/$OLD2/return -d '{"returnedBy":"吴芳","channel":"WARD_DESK"}'

echo "-- 12.7 旧证实体退回 -> 释放押金、结束陪护责任 --"
curl -s $H -X POST $BASE/api/transfers/$TRNO/old-permit-return -d '{"returnedBy":"吴芳","channel":"NEW_WARD_DESK","note":"实体卡已交回ICU护士站"}' | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('status:',d['status'],'depositReleased:',d['depositReleased'],'liabilityEnded:',d['liabilityEnded'])"
echo "SMOKE OK: CASE_NO=$CASE_NO TRANSFER_NO=$TRNO"
