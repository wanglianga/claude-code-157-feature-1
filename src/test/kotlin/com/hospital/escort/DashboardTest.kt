package com.hospital.escort

import com.hospital.escort.api.ApplyRequest
import com.hospital.escort.api.DecisionRequest
import com.hospital.escort.api.ReturnRequest
import com.hospital.escort.domain.ScanDirection
import com.hospital.escort.domain.ScreeningType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 管理者看板: 陪护人数、夜间异常、违规频次、退证效率 */
class DashboardTest : AbstractApiTest() {

    @Test
    fun `看板统计在院陪护人数出入次数违规与退证效率`() {
        // 患者1: 正常陪护, 当前在病区
        val (case1, permit1, _) = applyAndApprove(applyRequest(
            patientNo = "IP-DB-01", escortIdNo = "IDDB01", escortPhone = "13800000011"))
        val token1 = get("/api/permits/$permit1").body!!["qrToken"].asText()
        post("/api/access/scan", mapOf(
            "token" to token1, "direction" to ScanDirection.IN, "gateLocation" to "G1",
            "scannedAt" to Instant.now().minusSeconds(7200)))
        post("/api/access/scan", mapOf(
            "token" to token1, "direction" to ScanDirection.OUT, "gateLocation" to "G1",
            "scannedAt" to Instant.now().minusSeconds(3600)))
        post("/api/access/scan", mapOf(
            "token" to token1, "direction" to ScanDirection.IN, "gateLocation" to "G1",
            "scannedAt" to Instant.now().minusSeconds(1800)))

        // 患者2: 夜间异常 + 之后退证
        val (case2, permit2, _) = applyAndApprove(applyRequest(
            patientNo = "IP-DB-02", escortIdNo = "IDDB02", escortPhone = "13800000022"))
        val token2 = get("/api/permits/$permit2").body!!["qrToken"].asText()
        post("/api/access/scan", mapOf(
            "token" to token2, "direction" to ScanDirection.IN, "gateLocation" to "G2",
            "scannedAt" to Instant.now().minusSeconds(5400)))
        post("/api/access/scan", mapOf(
            "token" to token2, "direction" to ScanDirection.IN, "gateLocation" to "G2",
            "scannedAt" to Instant.parse("2026-09-27T14:30:00Z"))) // 22:30 本地
        post("/api/permits/$permit2/return",
            ReturnRequest(returnedBy = "家属2", channel = "WARD_DESK"))

        val dash = get("/api/wards/W3A/dashboard?days=7").body!!
        // 患者2退证后, 当前在病区陪护人数 = 1
        assertEquals(1L, dash["activeEscortCount"].asLong(), dash.toString())
        assertTrue(dash["activePatientCount"].asLong() >= 2L)
        assertTrue(dash["nightAbnormalCount"].asLong() >= 1L)
        assertTrue(dash["violationCount"].asLong() >= 1L)
        assertTrue(dash["violationsByType"]["NIGHT_ABNORMAL"].asLong() >= 1L)
        assertTrue(dash["avgReturnHours"].asDouble() >= 0.0)
        assertTrue(dash["violationsByType"].fieldNames().asSequence().toList().contains("NIGHT_ABNORMAL"))
    }

    @Test
    fun `待审批申请数和过期未退统计`() {
        post("/api/applications", applyRequest(patientNo = "IP-DB-03"))
        val dash = get("/api/wards/W3A/dashboard").body!!
        assertTrue(dash["pendingApplications"].asLong() >= 1L)
    }
}
