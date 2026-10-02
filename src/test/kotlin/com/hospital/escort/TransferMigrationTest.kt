package com.hospital.escort

import com.hospital.escort.domain.ScanDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 患者转科后的陪护证迁移(普通病房 W3A -> 重症过渡病区 ICU1):
 *  - 发起即暂停原证, 通知家属重新确认陪护规则;
 *  - 迁移完成前门禁只允许必要交接, 旧证进入新病区拒绝并记 TRANSFER_BREACH;
 *  - 新病区护士审批: 允许则旧证作废/新证签发; 不允许则出具物品交接与家属探视说明,
 *    旧证退回后才释放押金、结束陪护责任。
 */
class TransferMigrationTest : AbstractApiTest() {

    private fun startMigration(caseNo: String, toWard: String = "ICU1", bedNo: String = "ICU-03-1") =
        post("/api/wards/cases/$caseNo/transfer", mapOf(
            "toWardCode" to toWard, "bedNo" to bedNo,
            "operator" to "NURSE01", "escortMigration" to true,
            "reason" to "病情加重转入重症过渡病区"
        ))

    private fun getTransfer(transferNo: String) = get("/api/transfers/$transferNo").body!!

    @Test
    fun `发起迁移暂停原证通知家属确认规则且双病区留通知`() {
        val (caseNo, permitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-MIG-01", wardCode = "W3A"))

        val tr = startMigration(caseNo)
        assertEquals(200, tr.statusCode.value(), tr.body.toString())
        assertEquals("TRANSFER_MIGRATION_STARTED", tr.body!!["result"].asText())
        val transferNo = tr.body!!["transferNo"].asText()

        val detail = getTransfer(transferNo)
        assertEquals("PENDING_FAMILY_CONFIRM", detail["status"].asText())
        assertEquals("W3A", detail["fromWardCode"].asText())
        assertEquals("ICU1", detail["toWardCode"].asText())
        assertEquals(permitNo, detail["oldPermitNo"].asText())

        // 原证暂停: FROZEN + transferSuspended, 且同步三端停权
        val permit = get("/api/permits/$permitNo").body!!
        assertEquals("FROZEN", permit["status"].asText())
        assertTrue(permit["transferSuspended"].asBoolean())

        // 家属规则确认已通知
        val icuNotices = get("/api/wards/ICU1/notices").body!!
        assertTrue(icuNotices.any { it["type"].asText() == "FAMILY_RULES_NOTICE" }, "应通知家属重新确认新病区规则")
        assertTrue(icuNotices.any { it["type"].asText() == "TRANSFER_IN_PENDING" })
        assertTrue(get("/api/wards/W3A/notices").body!!.any { it["type"].asText() == "PATIENT_TRANSFERRED_OUT" })

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("ICU1", archive["wardCode"].asText())
        val events = archive["events"]
        assertTrue(events.any { it["eventType"].asText() == "TRANSFER_INITIATED" })
        assertTrue(events.any { it["eventType"].asText() == "TRANSFER_FAMILY_RULES_NOTIFIED" })
        assertEquals(1, archive["transfers"].size())
    }

    @Test
    fun `迁移完成前旧证闯入新病区拒绝记违规必要交接放行`() {
        val (caseNo, permitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-MIG-02", wardCode = "W3A"))
        val transferNo = startMigration(caseNo).body!!["transferNo"].asText()
        val token = get("/api/permits/$permitNo").body!!["qrToken"].asText()

        // 旧证进入新病区 ICU1 -> 拒绝 + TRANSFER_BREACH
        val breach = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN, "wardCode" to "ICU1",
            "gateLocation" to "ICU1-过渡病区闸机", "scannedAt" to Instant.parse("2026-10-02T04:00:00Z")
        ))
        assertEquals("DENIED", breach.body!!["result"].asText(), breach.body.toString())
        assertTrue(breach.body!!["transferBreach"].asBoolean())
        assertFalse(breach.body!!["handoverPass"].asBoolean())
        assertTrue(breach.body!!["violations"].any { it.asText() == "TRANSFER_BREACH" })

        // 必要交接: 返回原病区 W3A 取物品 -> 放行(handoverPass), 不恢复权限
        val back = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN, "wardCode" to "W3A",
            "gateLocation" to "W3A-护士站通道", "scannedAt" to Instant.parse("2026-10-02T05:00:00Z")
        ))
        assertEquals("ALLOWED", back.body!!["result"].asText(), back.body.toString())
        assertTrue(back.body!!["handoverPass"].asBoolean())

        // 必要交接: 离开病区 -> 放行
        val out = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.OUT, "wardCode" to "ICU1",
            "gateLocation" to "ICU1-污物通道", "scannedAt" to Instant.parse("2026-10-02T05:30:00Z")
        ))
        assertEquals("ALLOWED", out.body!!["result"].asText())
        assertTrue(out.body!!["handoverPass"].asBoolean())

        // 再次试图进入新病区仍被拒, 原证始终未恢复
        val breach2 = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN, "wardCode" to "ICU1",
            "gateLocation" to "ICU1-过渡病区闸机", "scannedAt" to Instant.parse("2026-10-02T06:00:00Z")
        ))
        assertEquals("DENIED", breach2.body!!["result"].asText())

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertTrue(archive["violations"].any { it["type"].asText() == "TRANSFER_BREACH" })
        val scans = archive["scans"]
        assertTrue(scans.any { it["handoverPass"].asBoolean() })
        assertTrue(scans.count { it["transferBreach"].asBoolean() } >= 2)
        assertTrue(get("/api/wards/ICU1/notices").body!!.any { it["type"].asText() == "TRANSFER_BREACH" })
        assertNotNull(transferNo)
    }

    @Test
    fun `家属未确认规则不能审批未显式确认返回400`() {
        val (caseNo, _, _) = applyAndApprove(applyRequest(patientNo = "IP-MIG-03", wardCode = "W3A"))
        val transferNo = startMigration(caseNo).body!!["transferNo"].asText()

        // 未家属确认, 护士不能审批
        val early = post("/api/transfers/$transferNo/decision",
            mapOf("decidedBy" to "NURSE-HEAD", "approved" to true))
        assertEquals(409, early.statusCode.value())

        // 家属确认但未显式 ackRules -> 400
        val noAck = post("/api/transfers/$transferNo/family-confirm",
            mapOf("confirmedBy" to "家属-张三"))
        assertEquals(400, noAck.statusCode.value())
        assertEquals("PENDING_FAMILY_CONFIRM", getTransfer(transferNo)["status"].asText())
    }

    @Test
    fun `新病区批准继续陪护旧证作废新证签发且新证可进新病区`() {
        val (caseNo, oldPermitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-MIG-04", wardCode = "W3A"))
        val transferNo = startMigration(caseNo).body!!["transferNo"].asText()

        // 家属确认(默认沿用旧证陪护人与筛查)
        val confirm = post("/api/transfers/$transferNo/family-confirm",
            mapOf("confirmedBy" to "家属-张三", "ackRules" to true, "note" to "已知悉重症过渡病区规则"))
        assertEquals(200, confirm.statusCode.value(), confirm.body.toString())
        assertEquals("PENDING_WARD_REVIEW", confirm.body!!["status"].asText())

        // 新病区护士批准
        val decision = post("/api/transfers/$transferNo/decision",
            mapOf("decidedBy" to "NURSE-HEAD", "approved" to true, "note" to "病情需要一名家属陪护"))
        assertEquals(200, decision.statusCode.value(), decision.body.toString())
        assertEquals("APPROVED", decision.body!!["status"].asText())

        val newPermitNo = decision.body!!["newPermitNo"].asText()
        assertTrue(newPermitNo.isNotBlank())
        // 旧证作废, 新证属于 ICU1 且有效
        assertEquals("REPLACED", get("/api/permits/$oldPermitNo").body!!["status"].asText())
        val newPermit = get("/api/permits/$newPermitNo").body!!
        assertEquals("ACTIVE", newPermit["status"].asText())
        assertEquals("ICU1", newPermit["wardCode"].asText())
        assertTrue(newPermit["syncedTargets"].let { it.isArray && it.size() == 3 })

        // 新证在 ICU1 探视时段(UTC 04:00 = 北京 12:00)可进入
        val inIcu = post("/api/access/scan", mapOf(
            "token" to newPermit["qrToken"].asText(), "direction" to ScanDirection.IN, "wardCode" to "ICU1",
            "gateLocation" to "ICU1-过渡病区闸机", "scannedAt" to Instant.parse("2026-10-02T04:00:00Z")
        ))
        assertEquals("ALLOWED", inIcu.body!!["result"].asText(), inIcu.body.toString())
        assertFalse(inIcu.body!!["handoverPass"].asBoolean())

        // 旧证已不能使用
        val oldToken = get("/api/permits/$oldPermitNo").body!!["qrToken"].asText()
        val oldScan = post("/api/access/scan", mapOf(
            "token" to oldToken, "direction" to ScanDirection.IN, "wardCode" to "ICU1",
            "gateLocation" to "ICU1-过渡病区闸机", "scannedAt" to Instant.parse("2026-10-02T04:30:00Z")
        ))
        assertEquals("DENIED", oldScan.body!!["result"].asText())

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("张三", archive["currentEscortName"].asText())
        val events = archive["events"]
        assertTrue(events.any { it["eventType"].asText() == "TRANSFER_FAMILY_CONFIRMED" })
        assertTrue(events.any { it["eventType"].asText() == "TRANSFER_APPROVED" })
        assertTrue(get("/api/wards/ICU1/notices").body!!.any { it["type"].asText() == "TRANSFER_APPROVED" })
    }

    @Test
    fun `新病区不允许陪护出具交接与探视说明旧证退回后才释放押金结束责任`() {
        val (caseNo, oldPermitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-MIG-05", wardCode = "W3A"))
        val transferNo = startMigration(caseNo).body!!["transferNo"].asText()

        post("/api/transfers/$transferNo/family-confirm",
            mapOf("confirmedBy" to "家属-张三", "ackRules" to true))
        val rejected = post("/api/transfers/$transferNo/decision",
            mapOf("decidedBy" to "NURSE-HEAD", "approved" to false, "note" to "ICU过渡区院感要求不安排家属陪护"))
        assertEquals(200, rejected.statusCode.value())
        assertEquals("REJECTED", rejected.body!!["status"].asText())

        // 出具物品交接单 + 家属探视说明
        val docs = rejected.body!!["documents"]
        assertEquals(2, docs.size())
        val types = docs.map { it["docType"].asText() }.toSet()
        assertEquals(setOf("BELONGINGS_HANDOVER", "FAMILY_VISIT_INSTRUCTION"), types)
        assertTrue(docs.any { it["content"].asText().contains("押金不释放") })
        assertTrue(docs.any { it["content"].asText().contains("探视时间") })

        // 押金/责任尚未释放, 旧证仍暂停
        assertFalse(rejected.body!!["depositReleased"].asBoolean())
        assertFalse(rejected.body!!["liabilityEnded"].asBoolean())
        assertEquals("FROZEN", get("/api/permits/$oldPermitNo").body!!["status"].asText())

        // 普通退证接口不能绕过迁移结算
        val bypass = post("/api/permits/$oldPermitNo/return",
            mapOf("returnedBy" to "家属-张三", "channel" to "WARD_DESK", "note" to "试图直接退"))
        assertEquals(409, bypass.statusCode.value())

        // REJECTED 状态下旧证仍不得进入新病区
        val token = get("/api/permits/$oldPermitNo").body!!["qrToken"].asText()
        val breach = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN, "wardCode" to "ICU1",
            "gateLocation" to "ICU1-过渡病区闸机", "scannedAt" to Instant.parse("2026-10-02T04:00:00Z")
        ))
        assertEquals("DENIED", breach.body!!["result"].asText())
        assertTrue(breach.body!!["transferBreach"].asBoolean())

        // 旧证实体退回 -> 释放押金、结束陪护责任
        val settled = post("/api/transfers/$transferNo/old-permit-return",
            mapOf("returnedBy" to "家属-张三", "channel" to "NEW_WARD_DESK", "note" to "实体卡已交回ICU护士站"))
        assertEquals(200, settled.statusCode.value(), settled.body.toString())
        assertTrue(settled.body!!["depositReleased"].asBoolean())
        assertTrue(settled.body!!["liabilityEnded"].asBoolean())
        assertNotNull(settled.body!!["depositReleasedAt"])
        assertEquals("RETURNED", get("/api/permits/$oldPermitNo").body!!["status"].asText())

        // 旧证门禁彻底失效
        val after = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN, "wardCode" to "ICU1",
            "gateLocation" to "ICU1-过渡病区闸机", "scannedAt" to Instant.parse("2026-10-02T05:00:00Z")
        ))
        assertEquals("DENIED", after.body!!["result"].asText())
        assertTrue(after.body!!["denyReason"].asText().contains("退证"))

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertTrue(archive["currentEscortName"].isNull)
        val events = archive["events"]
        assertTrue(events.any { it["eventType"].asText() == "TRANSFER_REJECTED" })
        assertTrue(events.any { it["eventType"].asText() == "TRANSFER_HANDOVER_DOC_ISSUED" })
        assertTrue(events.any { it["eventType"].asText() == "TRANSFER_OLD_PERMIT_RETURNED" })
        assertTrue(events.any { it["eventType"].asText() == "TRANSFER_DEPOSIT_RELEASED" })
        assertTrue(get("/api/wards/ICU1/notices").body!!.any { it["type"].asText() == "TRANSFER_SETTLED" })
    }

    @Test
    fun `审批完成前撤销迁移患者转回原病区原证恢复`() {
        val (caseNo, permitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-MIG-06", wardCode = "W3A"))
        val transferNo = startMigration(caseNo).body!!["transferNo"].asText()
        val token = get("/api/permits/$permitNo").body!!["qrToken"].asText()

        val cancel = post("/api/transfers/$transferNo/cancel?operator=NURSE01&reason=病情稳定暂不转科", emptyMap<String, Any>())
        assertEquals(200, cancel.statusCode.value(), cancel.body.toString())
        assertEquals("CANCELLED", cancel.body!!["status"].asText())

        val permit = get("/api/permits/$permitNo").body!!
        assertEquals("ACTIVE", permit["status"].asText())
        assertFalse(permit["transferSuspended"].asBoolean())

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("W3A", archive["wardCode"].asText())
        // 原证恢复后可正常进出原病区
        val scan = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN, "wardCode" to "W3A",
            "gateLocation" to "W3A-东门闸机", "scannedAt" to Instant.parse("2026-10-02T02:00:00Z")
        ))
        assertEquals("ALLOWED", scan.body!!["result"].asText(), scan.body.toString())
        assertFalse(scan.body!!["handoverPass"].asBoolean())
    }
}
