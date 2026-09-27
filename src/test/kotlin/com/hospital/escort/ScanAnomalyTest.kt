package com.hospital.escort

import com.hospital.escort.api.ApplyRequest
import com.hospital.escort.domain.ScanDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 进出核验异常检测: 超出允许时段 / 更换陪护人 / 多证共用 / 夜间出入
 */
class ScanAnomalyTest : AbstractApiTest() {

    private fun permitOf(caseNo: String) =
        get("/api/cases/$caseNo/archive").body!!["permits"][0]

    @Test
    fun `探视时段外进入且无夜间陪护被拒并记夜间异常`() {
        val (caseNo, permitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-SCAN-01"))
        val token = permitOf(caseNo)["qrToken"].asText()

        // W3A 允许时段 08:00-20:00 (Asia/Shanghai)。22:30 本地 = 14:30 UTC
        val r = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN,
            "gateLocation" to "W3A-东门", "scannedAt" to Instant.parse("2026-09-27T14:30:00Z")
        ))
        assertEquals("DENIED", r.body!!["result"].asText())
        assertTrue(r.body!!["outOfHours"].asBoolean())
        assertTrue(r.body!!["violations"].any { it.asText() == "NIGHT_ABNORMAL" })

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertTrue(archive["violations"].any { it["type"].asText() == "NIGHT_ABNORMAL" })
        assertEquals("DENIED", archive["scans"].last()["result"].asText())
    }

    @Test
    fun `批准夜间陪护后夜间进入允许但仍标记非探视时段`() {
        val req = applyRequest(patientNo = "IP-SCAN-02", needNightCare = true)
        val (caseNo, _, _) = applyAndApprove(req, approveNightCare = true)
        val token = permitOf(caseNo)["qrToken"].asText()

        val r = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN,
            "gateLocation" to "W3A-东门", "scannedAt" to Instant.parse("2026-09-27T15:00:00Z") // 23:00 本地
        ))
        assertEquals("ALLOWED", r.body!!["result"].asText(), r.body.toString())
        assertTrue(r.body!!["outOfHours"].asBoolean(), "即使允许夜间陪护, 仍应标记超出探视时段供统计")
    }

    @Test
    fun `扫码人与持证人不一致判定为未登记更换陪护人并拒绝`() {
        val (caseNo, _, _) = applyAndApprove(applyRequest(patientNo = "IP-SCAN-03"))
        val token = permitOf(caseNo)["qrToken"].asText()

        val r = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN,
            "scannedIdNo" to "ID-SOMEONE-ELSE", "gateLocation" to "W3A-东门",
            "scannedAt" to Instant.parse("2026-09-27T09:00:00Z")
        ))
        assertEquals("DENIED", r.body!!["result"].asText())
        assertTrue(r.body!!["escortChanged"].asBoolean())
        assertTrue(r.body!!["violations"].any { it.asText() == "ESCORT_UNREGISTERED_CHANGE" })
    }

    @Test
    fun `同一身份证时间窗内使用多张陪护证判定多证共用`() {
        // 证 A
        val (caseA, _, _) = applyAndApprove(applyRequest(
            patientNo = "IP-SCAN-04A", escortName = "家属甲", escortIdNo = "IDHOLDERA", escortPhone = "13800000011"))
        val tokenA = permitOf(caseA)["qrToken"].asText()
        // 证 B
        val (caseB, _, _) = applyAndApprove(applyRequest(
            patientNo = "IP-SCAN-04B", escortName = "家属乙", escortIdNo = "IDHOLDERB", escortPhone = "13800000012"))
        val tokenB = permitOf(caseB)["qrToken"].asText()

        // 同一实际使用人先扫 A(人证不符但有效时段), 再扫 B
        post("/api/access/scan", mapOf(
            "token" to tokenA, "direction" to ScanDirection.IN, "scannedIdNo" to "ID-USURPER",
            "gateLocation" to "G1", "scannedAt" to Instant.parse("2026-09-27T09:00:00Z")
        ))
        val second = post("/api/access/scan", mapOf(
            "token" to tokenB, "direction" to ScanDirection.IN, "scannedIdNo" to "ID-USURPER",
            "gateLocation" to "G2", "scannedAt" to Instant.parse("2026-09-27T09:30:00Z")
        ))
        assertTrue(second.body!!["multiPermitShared"].asBoolean(), "应判定多证共用: ${second.body}")
        assertTrue(second.body!!["violations"].any { it.asText() == "MULTI_PERMIT_SHARED" })

        val archiveB = get("/api/cases/$caseB/archive").body!!
        assertTrue(archiveB["violations"].any { it["type"].asText() == "MULTI_PERMIT_SHARED" })
    }

    @Test
    fun `正常进出记录包含时间位置方向`() {
        val (caseNo, permitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-SCAN-05"))
        val token = permitOf(caseNo)["qrToken"].asText()
        val at = Instant.parse("2026-09-27T09:05:00Z")
        val r = post("/api/access/scan", mapOf(
            "token" to token, "direction" to ScanDirection.IN,
            "gateLocation" to "W3A-南门闸机", "scannedAt" to at
        ))
        assertEquals("ALLOWED", r.body!!["result"].asText())
        assertEquals(permitNo, r.body!!["permitNo"].asText())
        assertEquals("W3A-南门闸机", r.body!!["gateLocation"].asText())
        assertFalse(r.body!!["escortChanged"].asBoolean())
    }
}
