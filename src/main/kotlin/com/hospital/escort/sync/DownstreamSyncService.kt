package com.hospital.escort.sync

import com.fasterxml.jackson.databind.ObjectMapper
import com.hospital.escort.domain.EscortPermit
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.PermitSync
import com.hospital.escort.domain.PermitSyncRepository
import com.hospital.escort.domain.SyncAction
import com.hospital.escort.domain.SyncStatus
import com.hospital.escort.domain.SyncTarget
import com.hospital.escort.event.AccessAckEvent
import com.hospital.escort.event.DomainEventPublisher
import com.hospital.escort.event.PermitEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 把电子陪护证状态同步到三类下游:
 *  GATE            门禁
 *  WARD_SCAN       病区扫码点
 *  SECURITY_PATROL 安保巡查手持终端
 *
 * Kafka 模式: 写 PENDING 同步记录 -> 发布 escort.permit.events ->
 *             下游(真实系统或 AccessSystemSimulator)回执 escort.access.ack -> 标记 SYNCED
 * 本地模式:  同步记录直接标记 SYNCED(模拟下游即时成功)
 */
@Service
class DownstreamSyncService(
    private val syncRepository: PermitSyncRepository,
    private val permitRepository: EscortPermitRepository,
    private val publisher: DomainEventPublisher,
    private val objectMapper: ObjectMapper,
    @Value("\${escort.kafka.enabled}") private val kafkaEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun syncPermit(permit: EscortPermit, caseNo: String, action: SyncAction) {
        val rows = SyncTarget.entries.map { target ->
            PermitSync(
                permitId = permit.id!!,
                target = target,
                action = action,
                status = SyncStatus.PENDING,
                payload = objectMapper.writeValueAsString(
                    mapOf(
                        "permitNo" to permit.permitNo,
                        "qrToken" to permit.qrToken,
                        "wardCode" to permit.wardCode,
                        "escortName" to permit.escortName,
                        "escortIdNo" to permit.escortIdNo,
                        "validFrom" to permit.validFrom.toString(),
                        "validUntil" to permit.validUntil.toString(),
                        "permitStatus" to permit.status.name,
                        "nightCareAllowed" to permit.nightCareAllowed
                    )
                )
            )
        }
        syncRepository.saveAll(rows)

        val event = PermitEvent(
            eventId = UUID.randomUUID().toString(),
            eventType = "PERMIT_${action.name}",
            permitNo = permit.permitNo,
            qrToken = permit.qrToken,
            caseNo = caseNo,
            wardCode = permit.wardCode,
            escortName = permit.escortName,
            escortIdNo = permit.escortIdNo,
            targets = SyncTarget.entries.map { it.name },
            action = action.name,
            occurredAt = Instant.now()
        )
        publisher.publishPermitEvent(event)

        if (!kafkaEnabled) {
            // 本地模式: 模拟门禁/扫码点/安保终端即时同步成功
            rows.forEach { markSynced(it.id!!) }
            log.info("[local-sync] permit={} action={} -> GATE/WARD_SCAN/SECURITY_PATROL 全部同步完成", permit.permitNo, action)
        }
    }

    @Transactional
    fun handleAck(ack: AccessAckEvent) {
        val permit = permitRepository.findByPermitNo(ack.permitNo) ?: run {
            log.warn("access-ack for unknown permit {}", ack.permitNo)
            return
        }
        val target = runCatching { SyncTarget.valueOf(ack.target) }.getOrNull() ?: return
        syncRepository.findByPermitId(permit.id!!)
            .filter { it.status == SyncStatus.PENDING && it.target == target }
            .forEach { row ->
                row.status = if (ack.ok) SyncStatus.SYNCED else SyncStatus.FAILED
                row.syncedAt = Instant.now()
                syncRepository.save(row)
                log.info("access-ack permit={} target={} ok={}", ack.permitNo, ack.target, ack.ok)
            }
    }

    @Transactional
    fun markSynced(syncId: Long) {
        syncRepository.findById(syncId).ifPresent {
            if (it.status == SyncStatus.PENDING) {
                it.status = SyncStatus.SYNCED
                it.syncedAt = Instant.now()
            }
        }
    }
}
