package com.hospital.escort.api

import com.hospital.escort.service.ArchiveQueryService
import com.hospital.escort.service.PermitService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 电子陪护证: 查询、冻结/解冻、退证、吊销 */
@RestController
@RequestMapping("/api/permits")
class PermitController(
    private val permitService: PermitService,
    private val archiveQueryService: ArchiveQueryService
) {

    @GetMapping("/{permitNo}")
    fun get(@PathVariable permitNo: String): PermitDto = archiveQueryService.getPermitDetail(permitNo)

    @PostMapping("/{permitNo}/freeze")
    fun freeze(@PathVariable permitNo: String, @Valid @RequestBody req: FreezeRequest): PermitDto {
        permitService.freezePermit(permitNo, req.reason, req.operator)
        return archiveQueryService.getPermitDetail(permitNo)
    }

    @PostMapping("/{permitNo}/unfreeze")
    fun unfreeze(@PathVariable permitNo: String, @Valid @RequestBody req: UnfreezeRequest): PermitDto {
        permitService.unfreezePermit(permitNo, req.operator)
        return archiveQueryService.getPermitDetail(permitNo)
    }

    /** 退证: 陪护结束/提前终止 */
    @PostMapping("/{permitNo}/return")
    fun returnPermit(@PathVariable permitNo: String, @Valid @RequestBody req: ReturnRequest): PermitDto {
        permitService.returnPermit(permitNo, req.returnedBy, req.channel, req.note)
        return archiveQueryService.getPermitDetail(permitNo)
    }

    @PostMapping("/{permitNo}/revoke")
    fun revoke(@PathVariable permitNo: String, @Valid @RequestBody req: RevokeRequest): PermitDto {
        permitService.revokePermit(permitNo, req.reason, req.operator)
        return archiveQueryService.getPermitDetail(permitNo)
    }

    /** 手动触发过期扫描(正常由定时任务执行) */
    @PostMapping("/maintenance/expire-scan")
    fun expireScan(): Map<String, Any> =
        mapOf("expiredCount" to permitService.expireOverduePermits())
}
