package com.hospital.escort

import com.hospital.escort.api.DecisionRequest
import com.hospital.escort.domain.PermitSyncRepository
import com.hospital.escort.domain.SyncStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * Kafka 模式验证: 发证事件发布 -> 模拟门禁/扫码点/安保消费 -> 回执 -> permit_sync SYNCED。
 */
@EmbeddedKafka(partitions = 1, topics = ["escort.permit.events", "escort.access.ack"])
@ActiveProfiles("test", "kafka")
@TestPropertySource(properties = ["spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}"])
class KafkaSyncTest : AbstractApiTest() {

    @Autowired
    private lateinit var syncRepository: PermitSyncRepository

    @Test
    fun `发证事件经Kafka同步到三类下游并收到回执`() {
        val created = post("/api/applications", applyRequest(patientNo = "IP-KAFKA-01"))
        val appNo = created.body!!["applicationNo"].asText()
        val caseNo = created.body!!["caseNo"].asText()
        val decided = post("/api/applications/$appNo/decision",
            DecisionRequest(approved = true, decidedBy = "NURSE01"))
        assertEquals(200, decided.statusCode.value())

        val permitNo = get("/api/cases/$caseNo/archive").body!!["permits"][0]["permitNo"].asText()

        // 等待模拟器消费事件并回执
        val deadline = System.currentTimeMillis() + 15_000
        var synced = 0
        while (System.currentTimeMillis() < deadline) {
            val rows = syncRepository.findAll().filter {
                it.status == SyncStatus.SYNCED && it.payload?.contains(permitNo) == true
            }
            synced = rows.size
            if (synced >= 3) break
            Thread.sleep(200)
        }
        assertTrue(synced >= 3, "GATE/WARD_SCAN/SECURITY_PATROL 三个目标应均通过 Kafka 回执 SYNCED, 实际=$synced")
        val targets = syncRepository.findAll()
            .filter { it.payload?.contains(permitNo) == true }
            .map { it.target.name }.toSet()
        assertTrue(targets.containsAll(setOf("GATE", "WARD_SCAN", "SECURITY_PATROL")), "实际目标: $targets")
    }
}
