package com.hospital.escort.api

import com.hospital.escort.common.ApiException
import com.hospital.escort.domain.ReplacementRecordRepository
import com.hospital.escort.service.ReplacementService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 陪护人替换(临时生病/亲属争抢/排班调整) */
@RestController
@RequestMapping("/api/replacements")
class ReplacementController(
    private val replacementService: ReplacementService,
    private val replacementRepository: ReplacementRecordRepository
) {

    @PostMapping
    fun request(@Valid @RequestBody req: ReplacementRequest): Map<String, Any?> {
        val record = replacementService.requestReplacement(req)
        return mapOf(
            "replacementId" to record.id,
            "status" to record.status.name,
            "newApplicationId" to record.newApplicationId,
            "newPermitId" to record.newPermitId
        )
    }

    @PostMapping("/{id}/decision")
    fun decide(@PathVariable id: Long, @Valid @RequestBody req: ReplacementDecisionRequest): Map<String, Any?> {
        val record = replacementService.decideReplacement(id, req.decidedBy, req.approved == true, req.note)
        return mapOf(
            "replacementId" to record.id,
            "status" to record.status.name,
            "newPermitId" to record.newPermitId
        )
    }
}
