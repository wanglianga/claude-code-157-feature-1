package com.hospital.escort.event

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * 领域事件发布出口:
 * - escort.kafka.enabled=true  时发布到 Kafka
 * - false(本地默认)时只写日志, 由本地同步模拟器直接完成门禁同步
 */
@Component
class DomainEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    @Value("\${escort.kafka.enabled}") private val kafkaEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publishPermitEvent(event: PermitEvent) {
        val json = objectMapper.writeValueAsString(event)
        if (kafkaEnabled) {
            kafkaTemplate.send(TOPIC_PERMIT_EVENTS, event.permitNo, json).get()
            log.info("kafka event published: {} permit={}", event.eventType, event.permitNo)
        } else {
            log.info("[local] permit event: {}", json)
        }
    }

    fun publishAccessAck(ack: AccessAckEvent) {
        val json = objectMapper.writeValueAsString(ack)
        if (kafkaEnabled) {
            kafkaTemplate.send(TOPIC_ACCESS_ACK, ack.permitNo, json).get()
        } else {
            log.info("[local] access ack: {}", json)
        }
    }

    companion object {
        const val TOPIC_PERMIT_EVENTS = "escort.permit.events"
        const val TOPIC_ACCESS_ACK = "escort.access.ack"
    }
}
