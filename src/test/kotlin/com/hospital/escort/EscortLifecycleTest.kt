package com.hospital.escort

import com.hospital.escort.api.DecisionRequest
import com.hospital.escort.api.ReturnRequest
import com.hospital.escort.domain.ScanDirection
import com.hospital.escort.domain.ScreeningType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 核心生命周期: 申请 -> 审批 -> 发证(同步门禁) -> 进出 -> 退证 -> 归档
 */
class EscortLifecycleTest : AbstractApiTest() {

    @Test
    fun `申请审批发证同步门禁并退证归档`() {
        // 1. 家属提交申请
        val created = post("/api/applications", applyRequest(patientNo = "IP-LIFE-01"))
        assertEquals(201, created.statusCode.value())
        val appNo = created.body!!["applicationNo"].asText()
        val caseNo = created.body!!["caseNo"].asText()
        assertEquals("PENDING", created.body!!["status"].asText())

        // 2. 护士站审批通过
        val decided = post(
            "/api/applications/$appNo/decision",
            DecisionRequest(approved = true, decidedBy = "NURSE01", note = "病情需要, 同意")
        )
        assertEquals(200, decided.statusCode.value())
        assertEquals("APPROVED", decided.body!!["status"].asText())

        // 3. 陪护证已签发并同步到三类下游
        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals(1, archive["permits"].size())
        val permit = archive["permits"][0]
        assertEquals("ACTIVE", permit["status"].asText())
        val targets = permit["syncedTargets"].map { it.asText() }
        assertTrue(targets.containsAll(listOf("GATE", "WARD_SCAN", "SECURITY_PATROL")),
            "门禁/扫码点/安保巡查均应已同步, 实际: $targets")
        assertEquals("张三", archive["currentEscortName"].asText())

        // 4. 正常时段进出扫码
        val token = permit["qrToken"].asText()
        val inScan = post("/api/access/scan", mapOf(
            "token" to token, "direction" to "IN", "gateLocation" to "W3A-病区东门闸机",
            "scannedAt" to Instant.parse("2026-09-27T10:15:00Z")
        ))
        assertEquals("ALLOWED", inScan.body!!["result"].asText(), inScan.body.toString())
        assertFalse(inScan.body!!["outOfHours"].asBoolean())

        val outScan = post("/api/access/scan", mapOf(
            "token" to token, "direction" to "OUT", "gateLocation" to "W3A-病区东门闸机",
            "scannedAt" to Instant.parse("2026-09-27T11:20:00Z")
        ))
        assertEquals("ALLOWED", outScan.body!!["result"].asText())
        assertEquals(2, archive.let { get("/api/cases/$caseNo/archive").body!!["scans"].size() })

        // 5. 病区收到过"当前照护人"通知
        val notices = get("/api/wards/W3A/notices").body!!
        assertTrue(notices.any { it["type"].asText() == "CAREGIVER_CHANGED" }, "应通知病区当前照护人")

        // 6. 退证
        val returned = post("/api/permits/${permit["permitNo"].asText()}/return",
            ReturnRequest(returnedBy = "张三", channel = "WARD_DESK", note = "患者出院"))
        assertEquals("RETURNED", returned.body!!["status"].asText())

        // 7. 退证后门禁拒绝, 记录上当前无陪护人, 完整归档包含全部事件
        val denied = post("/api/access/scan", mapOf(
            "token" to token, "direction" to "IN", "gateLocation" to "W3A-病区东门闸机",
            "scannedAt" to Instant.parse("2026-09-27T12:00:00Z")
        ))
        assertEquals("DENIED", denied.body!!["result"].asText())
        assertNotNull(denied.body!!["denyReason"])

        val finalArchive = get("/api/cases/$caseNo/archive").body!!
        assertTrue(finalArchive["currentEscortName"].isNull, "退证后当前陪护人应为空")
        val eventTypes = finalArchive["events"].map { it["eventType"].asText() }
        assertTrue(eventTypes.containsAll(listOf(
            "APPLICATION_SUBMITTED", "APPLICATION_APPROVED", "PERMIT_ISSUED",
            "SCAN_IN", "SCAN_OUT", "PERMIT_RETURNED"
        )), "事件流应覆盖完整生命周期, 实际: $eventTypes")
    }

    @Test
    fun `健康承诺或筛查不满足不能提交申请`() {
        val badCommitment = post("/api/applications", applyRequest(patientNo = "IP-V-01").let {
            it.copy(healthCommitment = false)
        })
        assertEquals(400, badCommitment.statusCode.value())

        val expiredScreening = post("/api/applications", applyRequest(patientNo = "IP-V-02").let {
            it.copy(screeningValidUntil = Instant.now().minus(1, ChronoUnit.DAYS))
        })
        assertEquals(400, expiredScreening.statusCode.value())

        val noneScreening = post("/api/applications", applyRequest(patientNo = "IP-V-03").let {
            it.copy(screeningType = ScreeningType.NONE)
        })
        assertEquals(400, noneScreening.statusCode.value())
    }

    @Test
    fun `夜间陪护需要专项审批`() {
        val created = post("/api/applications", applyRequest(patientNo = "IP-NIGHT-01", needNightCare = true))
        val appNo = created.body!!["applicationNo"].asText()
        // 未显式批准夜间陪护 -> 400
        val blocked = post("/api/applications/$appNo/decision",
            DecisionRequest(approved = true, decidedBy = "NURSE01"))
        assertEquals(400, blocked.statusCode.value())
        assertTrue(blocked.body!!["message"].asText().contains("夜间"))

        // 重新申请(原申请仍 PENDING, 不能重复, 直接用同一申请显式批准夜间)
        val ok = post("/api/applications/$appNo/decision",
            DecisionRequest(approved = true, decidedBy = "NURSE01", approveNightCare = true))
        assertEquals(200, ok.statusCode.value())
        val caseNo = created.body!!["caseNo"].asText()
        val permit = get("/api/cases/$caseNo/archive").body!!["permits"][0]
        assertTrue(permit["nightCareAllowed"].asBoolean())
    }

    @Test
    fun `驳回申请不出证`() {
        val created = post("/api/applications", applyRequest(patientNo = "IP-REJ-01"))
        val appNo = created.body!!["applicationNo"].asText()
        val caseNo = created.body!!["caseNo"].asText()
        post("/api/applications/$appNo/decision",
            DecisionRequest(approved = false, decidedBy = "NURSE01", note = "病情不需要"))
        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals(0, archive["permits"].size())
        assertEquals("REJECTED", archive["applications"][0]["status"].asText())
    }

    @Test
    fun `同一患者有效证达到上限时拒绝直接申请需走替换`() {
        post("/api/applications", applyRequest(patientNo = "IP-CAP-01"))
        val appNo1 = get("/api/applications?wardCode=W3A").body!!
            .last { it["patientInpatientNo"].asText() == "IP-CAP-01" }["applicationNo"].asText()
        post("/api/applications/$appNo1/decision",
            DecisionRequest(approved = true, decidedBy = "NURSE01"))

        val second = post("/api/applications", applyRequest(patientNo = "IP-CAP-01"))
        assertEquals(409, second.statusCode.value())
        assertTrue(second.body!!["message"].asText().contains("上限"))
    }

    @Test
    fun `病区不存在返回404`() {
        val resp = post("/api/applications", applyRequest(patientNo = "IP-X-01", wardCode = "NOPE"))
        assertEquals(404, resp.statusCode.value())
    }

    @Test
    fun `无效陪护证扫码被拒并留痕`() {
        val r = post("/api/access/scan", mapOf(
            "token" to "EP-FAKE", "direction" to ScanDirection.IN,
            "gateLocation" to "G1", "scannedAt" to Instant.parse("2026-09-27T10:00:00Z")
        ))
        assertEquals("DENIED", r.body!!["result"].asText())
        assertNotNull(r.body!!["denyReason"])
    }
}
