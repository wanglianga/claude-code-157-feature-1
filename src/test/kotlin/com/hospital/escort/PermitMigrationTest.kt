package com.hospital.escort

import com.hospital.escort.domain.ScanDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 患者转科陪护证迁移(普通病房 W3A -> 重症过渡病区 ITU1):
 * 暂停原证 -> 家属重新确认规则 -> 新病区护士审批 ->
 *   批准: 旧证作废/新证签发;
 *   不批准: 物品交接+探视说明, 旧证退回后才释放押金/结束陪护责任。
 * 迁移完成前门禁只允许必要交接, 旧证不得直接进入新病区。
 */
class PermitMigrationTest : AbstractApiTest() {

    private val noon = Instant.parse("2026-10-05T04:00:00Z") // 北京时间 12:00, 在 ITU1 探视时段 10:00-16:00 内

    private fun scanOldPermit(permitNo: String, ward: String, direction: ScanDirection, at: Instant = noon,
                              gate: String = "$ward-缓冲交接闸机") =
        post("/api/access/scan", mapOf(
            "token" to permitNo, "direction" to direction,
            "gateLocation" to gate, "wardCode" to ward, "scannedAt" to at.toString()
        ))

    @Test
    fun `重症过渡病区已预置且类别正确`() {
        val wards = get("/api/wards").body!!
        val itu = wards.first { it["wardCode"].asText() == "ITU1" }
        assertEquals("重症过渡病区", itu["wardName"].asText())
        assertEquals("ICU_TRANSITION", itu["wardCategory"].asText())
    }

    @Test
    fun `迁移接口仅允许转入重症过渡病区`() {
        val (caseNo, _, _) = applyAndApprove(applyRequest(patientNo = "IP-TF-GEN-01", wardCode = "W3A"))
        val resp = post("/api/transfers/cases/$caseNo/start", mapOf(
            "toWardCode" to "W5B", "bedNo" to "08-1", "operator" to "NURSE01"
        ))
        assertEquals(409, resp.statusCode.value(), resp.body.toString())
        assertTrue(resp.body!!["message"].asText().contains("重症过渡病区"))
    }

    @Test
    fun `批准迁移_暂停原证家属确认护士审批出新证旧证退回`() {
        val (caseNo, oldPermitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-TF-OK-01", wardCode = "W3A"))

        // 1. 启动迁移(默认发放一次必要交接通行, 固定在中午探视时段生效)
        val started = post("/api/transfers/cases/$caseNo/start", mapOf(
            "toWardCode" to "ITU1", "bedNo" to "T-01", "operator" to "NURSE01",
            "handoverPassFrom" to noon.toString(), "handoverPassMinutes" to 120
        ))
        assertEquals(200, started.statusCode.value(), started.body.toString())
        val transferNo = started.body!!["transferNo"].asText()
        assertEquals("PENDING_FAMILY_CONFIRM", started.body!!["status"].asText())
        assertNotNull(started.body!!["familyRuleNotice"].asText())
        assertTrue(started.body!!["familyRuleNotice"].asText().contains("重症过渡病区"))
        // 原证暂停
        val oldPermit = get("/api/permits/$oldPermitNo").body!!
        assertEquals("FROZEN", oldPermit["status"].asText())
        // 双病区通知
        assertTrue(get("/api/wards/W3A/notices").body!!.any { it["type"].asText() == "PERMIT_TRANSFERRED_OUT" })
        assertTrue(get("/api/wards/ITU1/notices").body!!.any { it["type"].asText() == "MIGRATION_PENDING_FAMILY" })

        // 2. 迁移期门禁: 首次进入 ITU1 凭交接通行放行, 第二次即拒绝并记违规
        val passIn = scanOldPermit(oldPermitNo, "ITU1", ScanDirection.IN)
        assertEquals("ALLOWED", passIn.body!!["result"].asText(), passIn.body.toString())
        assertTrue(passIn.body!!["handoverPass"].asBoolean())
        val breach = scanOldPermit(oldPermitNo, "ITU1", ScanDirection.IN)
        assertEquals("DENIED", breach.body!!["result"].asText())
        assertTrue(breach.body!!["violations"].any { it.asText() == "TRANSFER_ZONE_BREACH" })
        assertTrue(breach.body!!["denyReason"].asText().contains("迁移未完成"))
        // 离开始终放行
        val leave = scanOldPermit(oldPermitNo, "ITU1", ScanDirection.OUT)
        assertEquals("ALLOWED", leave.body!!["result"].asText())

        // 3. 家属未确认规则不能审批; 未勾选 acknowledged 被拒
        val badConfirm = post("/api/transfers/$transferNo/family-confirm", mapOf(
            "confirmedBy" to "张母", "acknowledged" to false
        ))
        assertEquals(400, badConfirm.statusCode.value())
        val confirm = post("/api/transfers/$transferNo/family-confirm", mapOf(
            "confirmedBy" to "张母", "acknowledged" to true, "note" to "已知悉重症过渡病区规则"
        ))
        assertEquals(200, confirm.statusCode.value(), confirm.body.toString())
        assertEquals("PENDING_WARD_APPROVAL", confirm.body!!["status"].asText())

        // 看板出现待新病区审批
        assertTrue(get("/api/wards/ITU1/dashboard").body!!["pendingTransferApprovals"].asInt() >= 1)

        // 4. 新病区护士批准继续陪护
        val decision = post("/api/transfers/$transferNo/decision", mapOf(
            "approved" to true, "decidedBy" to "NURSE-ITU",
            "note" to "病情需要一名家属陪护, 同意", "approveNightCare" to false
        ))
        assertEquals(200, decision.statusCode.value(), decision.body.toString())
        assertEquals("MIGRATED", decision.body!!["status"].asText())
        val newPermitNo = decision.body!!["newPermitNo"].asText()
        assertTrue(newPermitNo.startsWith("EP-"))

        // 旧证作废, 新证在 ITU1 有效
        assertEquals("REPLACED", get("/api/permits/$oldPermitNo").body!!["status"].asText())
        val newPermit = get("/api/permits/$newPermitNo").body!!
        assertEquals("ACTIVE", newPermit["status"].asText())
        assertEquals("ITU1", newPermit["wardCode"].asText())
        // 新证在探视时段可正常进入新病区(非交接通行)
        val newIn = post("/api/access/scan", mapOf(
            "token" to newPermitNo, "direction" to ScanDirection.IN,
            "gateLocation" to "ITU1-正门闸机", "wardCode" to "ITU1", "scannedAt" to noon.toString()
        ))
        assertEquals("ALLOWED", newIn.body!!["result"].asText())
        assertFalse(newIn.body!!["handoverPass"].asBoolean())

        // 当前照护人为新证, 归档含迁移单
        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("张三", archive["currentEscortName"].asText())
        assertEquals(1, archive["transfers"].size())
        assertEquals("MIGRATED", archive["transfers"][0]["status"].asText())
        assertTrue(archive["events"].any { it["eventType"].asText() == "TRANSFER_WARD_APPROVED" })

        // 5. 旧实体卡退回; MIGRATED 路径不走押金释放
        val ret = post("/api/transfers/$transferNo/old-permit-return", mapOf(
            "returnedBy" to "张母", "channel" to "NEW_WARD_DESK", "receivedBy" to "NURSE-ITU"
        ))
        assertEquals(200, ret.statusCode.value(), ret.body.toString())
        assertNotNull(ret.body!!["oldPermitReturnedAt"])
        assertFalse(ret.body!!["depositReleased"].asBoolean())
        val releaseWrong = post("/api/transfers/$transferNo/release-deposit", mapOf("operator" to "NURSE-ITU"))
        assertEquals(409, releaseWrong.statusCode.value())
    }

    @Test
    fun `不允许陪护_生成交接与探视说明旧证退回后才可释放押金`() {
        val (caseNo, oldPermitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-TF-DENY-01", wardCode = "W3A"))

        // 启动迁移, 不自动发放交接通行
        val started = post("/api/transfers/cases/$caseNo/start", mapOf(
            "toWardCode" to "ITU1", "bedNo" to "T-02", "operator" to "NURSE01",
            "issueHandoverPass" to false
        ))
        assertEquals(200, started.statusCode.value())
        val transferNo = started.body!!["transferNo"].asText()
        assertEquals("FROZEN", get("/api/permits/$oldPermitNo").body!!["status"].asText())

        // 无交接授权: 旧证直接进入新病区被拒
        val breach = scanOldPermit(oldPermitNo, "ITU1", ScanDirection.IN)
        assertEquals("DENIED", breach.body!!["result"].asText())
        assertTrue(breach.body!!["violations"].any { it.asText() == "TRANSFER_ZONE_BREACH" })
        // 离开仍放行
        assertEquals("ALLOWED", scanOldPermit(oldPermitNo, "ITU1", ScanDirection.OUT).body!!["result"].asText())

        // 家属确认
        post("/api/transfers/$transferNo/family-confirm", mapOf(
            "confirmedBy" to "李四家属", "acknowledged" to true
        ))

        // 旧证未退回时不得释放押金(此时还没拒绝, 先拒绝)
        // 新病区不允许陪护 -> 生成交接单与探视说明
        val denied = post("/api/transfers/$transferNo/decision", mapOf(
            "approved" to false, "decidedBy" to "NURSE-ITU",
            "note" to "重症过渡病区感控要求不安排家属陪护",
            "handoverItems" to listOf("患者随身衣物一袋: 已交接", "旧陪护证实体卡: 待家属交回"),
            "visitNote" to "每日 15:00-15:30 可电话询问病情"
        ))
        assertEquals(200, denied.statusCode.value(), denied.body.toString())
        assertEquals("ESCORT_DENIED", denied.body!!["status"].asText())

        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals(1, archive["handovers"].size())
        val handover = archive["handovers"][0]
        assertTrue(handover["items"].size() >= 2)
        assertTrue(handover["visitInstruction"].asText().contains("探视"))
        assertFalse(handover["oldPermitReturned"].asBoolean())
        assertFalse(handover["depositReleasable"].asBoolean())
        // 当前无登记陪护人
        assertNull(archive["currentEscortName"].textValue())
        assertTrue(archive["events"].any { it["eventType"].asText() == "TRANSFER_HANDOVER_PREPARED" })

        // 关键闸门: 旧证未退回, 拒绝释放押金
        val earlyRelease = post("/api/transfers/$transferNo/release-deposit",
            mapOf("operator" to "WARD-MGR", "note" to "家属要求先退押金"))
        assertEquals(409, earlyRelease.statusCode.value())
        assertTrue(earlyRelease.body!!["message"].asText().contains("旧陪护证尚未实体退回"))

        // 发放一次必要交接通行: 限时进入与离开各一次
        val grant = post("/api/transfers/$transferNo/handover-pass",
            mapOf("operator" to "NURSE-ITU", "validMinutes" to 60, "validFrom" to noon.toString()))
        assertEquals(200, grant.statusCode.value(), grant.body.toString())
        assertEquals("ALLOWED", scanOldPermit(oldPermitNo, "ITU1", ScanDirection.IN).body!!["result"].asText())
        assertEquals("ALLOWED", scanOldPermit(oldPermitNo, "ITU1", ScanDirection.OUT).body!!["result"].asText())
        // 交接通行一进一出后失效
        assertEquals("DENIED", scanOldPermit(oldPermitNo, "ITU1", ScanDirection.IN).body!!["result"].asText())

        // 旧证实体卡退回 -> 陪护责任结束, 交接单签收, 押金具备释放条件
        val ret = post("/api/transfers/$transferNo/old-permit-return", mapOf(
            "returnedBy" to "李四家属", "channel" to "NEW_WARD_DESK", "receivedBy" to "NURSE-ITU"
        ))
        assertEquals(200, ret.statusCode.value())
        assertTrue(ret.body!!["escortLiabilityEnded"].asBoolean())
        assertFalse(ret.body!!["depositReleased"].asBoolean())
        assertEquals("RETURNED", get("/api/permits/$oldPermitNo").body!!["status"].asText())
        val archive2 = get("/api/cases/$caseNo/archive").body!!
        assertTrue(archive2["handovers"][0]["oldPermitReturned"].asBoolean())
        assertTrue(archive2["handovers"][0]["depositReleasable"].asBoolean())

        // 押金释放/陪护责任结清
        val release = post("/api/transfers/$transferNo/release-deposit",
            mapOf("operator" to "WARD-MGR", "note" to "旧证已收回, 押金200元原路退还"))
        assertEquals(200, release.statusCode.value())
        assertTrue(release.body!!["depositReleased"].asBoolean())
        assertNotNull(release.body!!["depositReleasedAt"])

        // 重复释放被拒
        assertEquals(409, post("/api/transfers/$transferNo/release-deposit",
            mapOf("operator" to "WARD-MGR")).statusCode.value())
        // 全流程事件归档
        val events = get("/api/cases/$caseNo/archive").body!!["events"]
        listOf(
            "PERMIT_TRANSFER_SUSPENDED", "TRANSFER_FAMILY_CONFIRMED", "TRANSFER_WARD_DENIED",
            "TRANSFER_HANDOVER_PREPARED", "TRANSFER_OLD_PERMIT_RETURNED", "TRANSFER_DEPOSIT_RELEASED"
        ).forEach { type -> assertTrue(events.any { it["eventType"].asText() == type }, "缺少事件 $type") }
    }

    @Test
    fun `有效证不得跨病区扫码进入`() {
        val (_, permitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-TF-CROSS-01", wardCode = "W3A"))
        val resp = post("/api/access/scan", mapOf(
            "token" to permitNo, "direction" to ScanDirection.IN,
            "gateLocation" to "W5B-闸机", "wardCode" to "W5B",
            "scannedAt" to Instant.parse("2026-10-05T03:00:00Z").toString()
        ))
        assertEquals("DENIED", resp.body!!["result"].asText())
        assertTrue(resp.body!!["denyReason"].asText().contains("属于病区 W3A"))
    }
}
