package com.hospital.escort.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 定时任务: 每 10 分钟扫描陪护证过期未退 */
@Component
class PermitExpiryScheduler(private val permitService: PermitService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${escort.expiry.scan-ms:600000}", initialDelay = 30_000)
    fun scanExpiry() {
        val n = permitService.expireOverduePermits()
        if (n > 0) log.info("expiry scan: {} permits expired", n)
    }
}
