#!/usr/bin/env bash
# 端到端冒烟: 申请->审批->发证->扫码->异常->替换->退证->看板->归档
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

echo "== 7b. 患者转科陪护证迁移(W3A -> 重症过渡病区 ITU1, 不允许陪护路径) =="
TF_APPLY=$(curl -s $H -X POST $BASE/api/applications -d '{
  "patientInpatientNo":"IP-SMOKE-02","wardCode":"W3A","bedNo":"10-1",
  "escortName":"吴兰","escortIdNo":"IDSMOKE02","escortPhone":"13822223333",
  "relationToPatient":"配偶","healthCommitment":true,"screeningType":"NAT",
  "screeningValidUntil":"2026-10-10T00:00:00Z",
  "periodStart":"2026-09-26T00:00:00Z","periodEnd":"2026-10-20T00:00:00Z",
  "needNightCare":false}')
TF_CASE=$(echo "$TF_APPLY" | j "['caseNo']")
TF_APP=$(echo "$TF_APPLY" | j "['applicationNo']")
curl -s $H -X POST $BASE/api/applications/$TF_APP/decision -d '{
  "approved":true,"decidedBy":"NURSE-SMOKE","note":"同意","approveNightCare":false}' > /dev/null
TF_PERMIT=$(curl -s $BASE/api/cases/$TF_CASE/archive | j "['permits'][0]['permitNo']")

# 启动迁移: 原证暂停, 通知家属重新确认规则(不自动发交接通行)
TF_NO=$(curl -s $H -X POST $BASE/api/transfers/cases/$TF_CASE/start -d '{
  "toWardCode":"ITU1","bedNo":"T-09","operator":"NURSE-SMOKE","issueHandoverPass":false}' | j "['transferNo']")
echo "transfer=$TF_NO 旧证状态:"
curl -s $BASE/api/permits/$TF_PERMIT | python3 -c "import sys,json;p=json.load(sys.stdin);print(p['status'],p.get('freezeReason'))"

# 迁移未完成: 旧证进入新病区 -> DENIED + TRANSFER_ZONE_BREACH
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TF_PERMIT\",\"direction\":\"IN\",\"wardCode\":\"ITU1\",\"gateLocation\":\"ITU1-缓冲交接闸机\",\"scannedAt\":\"2026-10-02T04:00:00Z\"}" | python3 -c "
import sys,json;r=json.load(sys.stdin);print('旧证进入新病区:',r['result'],r.get('violations'),r.get('denyReason'))"

# 家属确认规则 -> 新病区不允许陪护 -> 交接单+探视说明
curl -s $H -X POST $BASE/api/transfers/$TF_NO/family-confirm -d '{"confirmedBy":"吴兰","acknowledged":true}' > /dev/null
curl -s $H -X POST $BASE/api/transfers/$TF_NO/decision -d '{
  "approved":false,"decidedBy":"NURSE-ITU","note":"重症过渡病区不安排家属陪护",
  "handoverItems":["患者随身衣物: 已当面交接","旧陪护证实体卡: 待家属交回"]}' | python3 -m json.tool

# 旧证未退回 -> 押金释放必须 409
echo "未退旧证释放押金(应失败):"
curl -s -o /tmp/tf_rel.json -w "%{http_code}\n" $H -X POST $BASE/api/transfers/$TF_NO/release-deposit -d '{"operator":"WARD-MGR"}'
cat /tmp/tf_rel.json | python3 -m json.tool

# 必要交接通行(一进一出) -> 旧证实体卡退回 -> 押金释放
curl -s $H -X POST $BASE/api/transfers/$TF_NO/handover-pass -d '{"operator":"NURSE-ITU","validMinutes":60,"validFrom":"2026-10-02T04:00:00Z"}' > /dev/null
curl -s $H -X POST $BASE/api/access/scan -d "{\"token\":\"$TF_PERMIT\",\"direction\":\"IN\",\"wardCode\":\"ITU1\",\"gateLocation\":\"ITU1-缓冲交接闸机\",\"scannedAt\":\"2026-10-02T04:30:00Z\"}" | python3 -c "
import sys,json;r=json.load(sys.stdin);print('必要交接进入:',r['result'],'handoverPass=',r['handoverPass'])"
curl -s $H -X POST $BASE/api/transfers/$TF_NO/old-permit-return -d '{"returnedBy":"吴兰","receivedBy":"NURSE-ITU"}' > /dev/null
curl -s $H -X POST $BASE/api/transfers/$TF_NO/release-deposit -d '{"operator":"WARD-MGR","note":"旧证已收, 押金退还"}' | python3 -c "
import sys,json;t=json.load(sys.stdin);print('迁移终态:',t['status'],'押金已释放=',t['depositReleased'],'陪护责任结束=',t['escortLiabilityEnded'])"

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
echo "SMOKE OK: CASE_NO=$CASE_NO"
