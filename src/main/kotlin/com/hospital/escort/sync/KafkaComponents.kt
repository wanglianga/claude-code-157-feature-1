package com.hospital.escort.sync

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.hospital.escort.event.AccessAckEvent
import com.hospital.escort.event.DomainEventPublisher
import com.hospital.escort.event.PermitEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * 模拟外部门禁/病区扫码点/安保巡查系统:
 * 订阅 escort.permit.events, 处理成功后向 escort.access.ack 回执。
 * 仅在 escort.kafka.enabled=true 时启用; 真实部署时由外部系统取代。
 */
@Component
@ConditionalOnProperty(name = ["escort.kafka.enabled"], havingValue = "true")
class AccessSystemSimulator(
    private val publisher: DomainEventPublisher,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = [DomainEventPublisher.TOPIC_PERMIT_EVENTS], groupId = "escort-access-systems")
    fun onPermitEvent(message: String) {
        val event = objectMapper.readValue<PermitEvent>(message)
        log.info("[access-systems] received {} for permit {} targets={}", event.eventType, event.permitNo, event.targets)
        event.targets.forEach { target ->
            publisher.publishAccessAck(
                AccessAckEvent(
                    eventId = UUID.randomUUID().toString(),
                    permitNo = event.permitNo,
                    target = target,
                    ok = true,
                    message = "${event.action} applied by $target",
                    ackedAt = Instant.now()
                )
            )
        }
    }
}

/** 本服务侧: 消费下游回执, 更新 permit_sync 状态 */
@Component
@ConditionalOnProperty(name = ["escort.kafka.enabled"], havingValue = "true")
class AccessAckConsumer(
    private val syncService: DownstreamSyncService,
    private val objectMapper: ObjectMapper
) {
    @KafkaListener(topics = [DomainEventPublisher.TOPIC_ACCESS_ACK], groupId = "escort-pass-service")
    fun onAck(message: String) {
        syncService.handleAck(objectMapper.readValue<AccessAckEvent>(message))
    }
}
