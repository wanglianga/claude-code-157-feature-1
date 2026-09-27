package com.hospital.escort

import com.hospital.escort.api.ApplyRequest
import com.hospital.escort.api.ReplacementRequest
import com.hospital.escort.domain.ScanDirection
import com.hospital.escort.domain.ScreeningType
import com.hospital.escort.domain.ReplacementReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 特殊场景: 陪护人临时生病替换 / 病区封闭 / 患者转科 / 过期未退 /
 * 亲属争抢陪护资格 / 安保冒用 / 护士限制外出 / 押金争议。
 * 要求: 全部归档在同一条陪护记录, 且病区始终知道当下由谁照护。
 */
class SpecialScenariosTest : AbstractApiTest() {

    private fun permit(caseNo: String) =
        get("/api/cases/$caseNo/archive").body!!["permits"][0]

    private fun newEscort(
        name: String, idNo: String, phone: String, relation: String? = null,
        needNightCare: Boolean = false
    ) = mapOf(
        "escortName" to name, "escortIdNo" to idNo, "escortPhone" to phone,
        "relationToPatient" to relation, "healthCommitment" to true,
        "screeningType" to "NAT", "screeningValidUntil" to Instant.now().plus(2, ChronoUnit.DAYS).toString(),
        "periodEnd" to Instant.now().plus(7, ChronoUnit.DAYS).toString(),
        "needNightCare" to needNightCare
    )

    @Test
    fun `陪护人临时生病走替换流程旧证作废新证签发且病区知情`() {
        val (caseNo, oldPermitNo, _) = applyAndApprove(applyRequest(
            patientNo = "IP-SICK-01", escortName = "王芳", escortIdNo = "IDSICKOLD", escortPhone = "13900000001"))

        val rep = post("/api/replacements", mapOf(
            "oldPermitNo" to oldPermitNo,
            "reason" to "ESCORT_SICK",
            "detail" to "王芳急性肠胃炎, 其妹妹接替",
            "autoApprove" to true, "decidedBy" to "NURSE02"
        ) + newEscort("王梅", "ID-SICK-NEW", "13900000002", "妹妹"))
        assertEquals(200, rep.statusCode.value(), rep.body.toString())
        assertEquals("APPROVED", rep.body!!["status"].asText())

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("王梅", archive["currentEscortName"].asText())
        assertEquals(2, archive["permits"].size())
        val oldP = archive["permits"][0]
        val newP = archive["permits"][1]
        assertEquals("REPLACED", oldP["status"].asText())
        assertEquals("ACTIVE", newP["status"].asText())
        assertEquals(1, archive["replacements"].size())
        assertEquals("ESCORT_SICK", archive["replacements"][0]["reason"].asText())
        // 申请/审批/替换/发证全部在同一条记录
        assertEquals(2, archive["applications"].size())
        // 病区收到替换完成通知, 明确当下照护人
        val notices = get("/api/wards/W3A/notices").body!!
        assertTrue(notices.any { it["message"].asText().contains("王梅") && it["type"].asText() == "REPLACEMENT_APPROVED" },
            "病区应被告知现由王梅照护")
    }

    @Test
    fun `亲属争抢陪护资格通过替换审批裁决并留痕`() {
        val (caseNo, permitNo, _) = applyAndApprove(applyRequest(
            patientNo = "IP-DISPUTE-01", escortName = "李大姐", escortIdNo = "IDDISPUTE1"))
        val rep = post("/api/replacements", mapOf(
            "oldPermitNo" to permitNo, "reason" to "FAMILY_DISPUTE",
            "detail" to "子女对由谁陪护有争议, 护士站协调后改由二女儿陪护",
            "autoApprove" to true, "decidedBy" to "NURSE-HEAD"
        ) + newEscort("李二妹", "ID-D-2", "13700000003", "二女儿"))
        assertEquals(200, rep.statusCode.value())
        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("FAMILY_DISPUTE", archive["replacements"][0]["reason"].asText())
        assertEquals("李二妹", archive["currentEscortName"].asText())
        assertTrue(archive["events"].any { it["detail"].asText().contains("子女对由谁陪护有争议") })
    }

    @Test
    fun `病区封闭管理冻结全部陪护证解封后恢复`() {
        val (caseNo, permitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-LOCK-01"))

        val locked = post("/api/wards/W3A/lockdown",
            mapOf("lockdown" to true, "reason" to "出现院感事件, 封闭48小时", "operator" to "WARD-MGR"))
        assertTrue(locked.body!!["lockdown"].asBoolean())

        // 证件被冻结, 扫码拒绝
        val token = get("/api/permits/$permitNo").body!!["qrToken"].asText()
        val denied = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN,
            "gateLocation" to "G1", "scannedAt" to Instant.parse("2026-09-27T09:00:00Z")
        ))
        assertEquals("DENIED", denied.body!!["result"].asText())
        assertTrue(denied.body!!["wardLockdown"].asBoolean())
        assertTrue(get("/api/permits/$permitNo").body!!["lockdownFrozen"].asBoolean())

        // 解封
        post("/api/wards/W3A/lockdown", mapOf("lockdown" to false, "operator" to "WARD-MGR"))
        val allowed = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN,
            "gateLocation" to "G1", "scannedAt" to Instant.parse("2026-09-27T10:00:00Z")
        ))
        assertEquals("ALLOWED", allowed.body!!["result"].asText())

        val events = get("/api/cases/$caseNo/archive").body!!["events"]
        assertTrue(events.any { it["eventType"].asText() == "WARD_LOCKDOWN" })
        assertTrue(events.any { it["eventType"].asText() == "WARD_LOCKDOWN_LIFTED" })
    }

    @Test
    fun `患者转科冻结原证并通知两个病区`() {
        val (caseNo, permitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-TRANS-01", wardCode = "W3A"))
        val tr = post("/api/wards/cases/$caseNo/transfer",
            mapOf("toWardCode" to "W5B", "bedNo" to "08-1", "operator" to "NURSE01", "freezePermit" to true))
        assertEquals(200, tr.statusCode.value())
        assertEquals("FROZEN", get("/api/permits/$permitNo").body!!["status"].asText())

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("W5B", archive["wardCode"].asText())
        assertTrue(archive["events"].any { it["eventType"].asText() == "PATIENT_TRANSFERRED" })
        val oldNotices = get("/api/wards/W3A/notices").body!!
        val newNotices = get("/api/wards/W5B/notices").body!!
        assertTrue(oldNotices.any { it["type"].asText() == "PATIENT_TRANSFERRED_OUT" })
        assertTrue(newNotices.any { it["type"].asText() == "PATIENT_TRANSFERRED_IN" })
    }

    @Test
    fun `安保发现冒用上报后陪护证立即吊销门禁失效`() {
        val (caseNo, permitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-IMP-01"))
        val rep = post("/api/violations", mapOf(
            "permitNo" to permitNo, "type" to "SECURITY_IMPERSONATION",
            "detail" to "巡查发现持证人与照片不符, 疑似冒用",
            "actualUserIdNo" to "ID-FAKE-USER", "reportedBy" to "SEC-GUARD-07"
        ))
        assertEquals(200, rep.statusCode.value())
        assertEquals("REVOKED", get("/api/permits/$permitNo").body!!["status"].asText())

        val token = get("/api/permits/$permitNo").body!!["qrToken"].asText()
        val denied = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN,
            "gateLocation" to "G1", "scannedAt" to Instant.parse("2026-09-27T09:00:00Z")
        ))
        assertEquals("DENIED", denied.body!!["result"].asText())

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertTrue(archive["violations"].any { it["type"].asText() == "SECURITY_IMPERSONATION" })
        assertTrue(archive["currentEscortName"].isNull)
    }

    @Test
    fun `护士限制外出后陪护人尝试外出被拒并记违规`() {
        val (caseNo, permitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-EXIT-01"))
        // 先进病区
        val token = get("/api/permits/$permitNo").body!!["qrToken"].asText()
        post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN,
            "gateLocation" to "G1", "scannedAt" to Instant.parse("2026-09-27T09:00:00Z")
        ))
        // 护士限制外出
        val rr = post("/api/wards/permits/$permitNo/exit-restriction",
            mapOf("restricted" to true, "reason" to "患者病情不稳定需陪护在场", "operator" to "NURSE03"))
        assertEquals(200, rr.statusCode.value())
        // 尝试外出
        val out = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.OUT,
            "gateLocation" to "G1", "scannedAt" to Instant.parse("2026-09-27T09:30:00Z")
        ))
        assertEquals("DENIED", out.body!!["result"].asText())
        assertTrue(out.body!!["violations"].any { it.asText() == "EXIT_BREACH" })
        // 解除限制后可外出
        post("/api/wards/permits/$permitNo/exit-restriction",
            mapOf("restricted" to false, "operator" to "NURSE03"))
        val out2 = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.OUT,
            "gateLocation" to "G1", "scannedAt" to Instant.parse("2026-09-27T10:30:00Z")
        ))
        assertEquals("ALLOWED", out2.body!!["result"].asText())
    }

    @Test
    fun `陪护证过期未退被定时扫描置为过期并记违规通知病区`() {
        // 申请一张"已经到期"的证
        val req = ApplyRequest(
            patientInpatientNo = "IP-EXP-01", wardCode = "W3A", bedNo = "1-1",
            escortName = "赵六", escortIdNo = "IDEXP01", escortPhone = "13600000006",
            relationToPatient = "配偶", healthCommitment = true,
            screeningType = ScreeningType.NAT,
            screeningValidUntil = Instant.now().plus(2, ChronoUnit.DAYS),
            periodStart = Instant.now().minus(3, ChronoUnit.DAYS),
            periodEnd = Instant.now().minus(1, ChronoUnit.HOURS)
        )
        val (caseNo, permitNo, _) = applyAndApprove(req)

        val scan = post("/api/permits/maintenance/expire-scan", emptyMap<String, Any>())
        assertEquals(200, scan.statusCode.value())
        assertTrue(scan.body!!["expiredCount"].asInt() >= 1)
        assertEquals("EXPIRED", get("/api/permits/$permitNo").body!!["status"].asText())

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertTrue(archive["violations"].any { it["type"].asText() == "EXPIRED_NOT_RETURNED" })
        assertTrue(archive["events"].any { it["eventType"].asText() == "PERMIT_EXPIRED" })
        assertTrue(get("/api/wards/W3A/notices").body!!.any { it["type"].asText() == "PERMIT_EXPIRED" })
    }

    @Test
    fun `押金争议登记在陪护记录且不影响证件有效性`() {
        val (caseNo, permitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-DEP-01"))
        val d = post("/api/wards/cases/$caseNo/deposit-dispute",
            mapOf("raisedBy" to "家属-钱七", "note" to "退证时押金200元未退, 要求核实"))
        assertEquals(200, d.statusCode.value())
        assertEquals("ACTIVE", get("/api/permits/$permitNo").body!!["status"].asText())

        val resolve = post("/api/wards/cases/$caseNo/deposit-dispute/resolve",
            mapOf("raisedBy" to "NURSE01", "note" to "押金已于出院结算时一并退还"))
        assertEquals(200, resolve.statusCode.value())

        val events = get("/api/cases/$caseNo/archive").body!!["events"]
        assertTrue(events.any { it["eventType"].asText() == "DEPOSIT_DISPUTE_RAISED" })
        assertTrue(events.any { it["eventType"].asText() == "DEPOSIT_DISPUTE_RESOLVED" })
    }

    @Test
    fun `同一条陪护记录聚合所有环节`() {
        // 申请审批 -> 进出 -> 替换 -> 违规 -> 退证 -> 关闭, 全部在同一个 caseNo
        val (caseNo, oldPermit, _) = applyAndApprove(applyRequest(patientNo = "IP-ALL-01"))
        val token = get("/api/permits/$oldPermit").body!!["qrToken"].asText()
        post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN,
            "gateLocation" to "G1", "scannedAt" to Instant.parse("2026-09-27T09:00:00Z")))

        post("/api/replacements", mapOf(
            "oldPermitNo" to oldPermit, "reason" to "SCHEDULE",
            "autoApprove" to true, "decidedBy" to "NURSE01"
        ) + newEscort("孙九", "ID-ALL-NEW", "13500000009"))

        val newPermit = get("/api/cases/$caseNo/archive").body!!["permits"][1]["permitNo"].asText()
        post("/api/violations", mapOf(
            "permitNo" to newPermit, "type" to "NIGHT_ABNORMAL",
            "detail" to "夜间在走廊逗留", "reportedBy" to "NURSE01"))
        post("/api/permits/$newPermit/return", mapOf(
            "returnedBy" to "孙九", "channel" to "WARD_DESK", "note" to "陪护结束"))
        val close = post("/api/cases/$caseNo/close", mapOf("operator" to "NURSE01"))
        assertEquals(200, close.statusCode.value())

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("CLOSED", archive["status"].asText())
        assertTrue(archive["applications"].size() >= 2)
        assertTrue(archive["permits"].size() >= 2)
        assertTrue(archive["scans"].size() >= 1)
        assertTrue(archive["replacements"].size() >= 1)
        assertTrue(archive["violations"].size() >= 1)
        assertTrue(archive["events"].size() >= 8)
        // 所有子记录都属于同一 caseNo
        assertEquals(caseNo, archive["caseNo"].asText())
    }
}
