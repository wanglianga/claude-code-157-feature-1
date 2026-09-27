package com.hospital.escort.api

import com.hospital.escort.service.ScanService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 门禁/病区扫码点: 陪护人进出扫码核验 */
@RestController
@RequestMapping("/api/access")
class AccessController(private val scanService: ScanService) {

    @PostMapping("/scan")
    fun scan(@Valid @RequestBody req: ScanRequest): ScanResultDto = scanService.scan(req)
}
