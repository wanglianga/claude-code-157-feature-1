package com.hospital.escort.event

import com.hospital.escort.domain.SyncAction
import com.hospital.escort.domain.SyncTarget
import java.time.Instant

/**
 * 陪护证领域事件, 发布到 Kafka 主题 escort.permit.events,
 * 供门禁系统、病区扫码点、安保巡查以及下游统计消费。
 */
data class PermitEvent(
    val eventId: String,
    val eventType: String,          // PERMIT_ISSUED / PERMIT_FROZEN / PERMIT_REVOKED ...
    val permitNo: String,
    val qrToken: String,
    val caseNo: String,
    val wardCode: String,
    val escortName: String,
    val escortIdNo: String,
    val targets: List<String>,      // GATE / WARD_SCAN / SECURITY_PATROL
    val action: String,             // SyncAction
    val occurredAt: Instant
)

data class AccessAckEvent(
    val eventId: String,
    val permitNo: String,
    val target: String,
    val ok: Boolean,
    val message: String?,
    val ackedAt: Instant
)
