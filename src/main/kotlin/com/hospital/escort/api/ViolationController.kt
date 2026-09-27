package com.hospital.escort.api

import com.hospital.escort.common.ApiException
import com.hospital.escort.domain.ViolationRecordRepository
import com.hospital.escort.service.ViolationService
import com.hospital.escort.service.toDto
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 违规上报与处理(安保冒用、护士上报等) */
@RestController
@RequestMapping("/api/violations")
class ViolationController(
    private val violationService: ViolationService,
    private val violationRepository: ViolationRecordRepository
) {

    @PostMapping
    fun report(@Valid @RequestBody req: ViolationReportRequest): ViolationDto {
        val v = violationService.reportViolation(req.permitNo, req.type!!, req.detail, req.reportedBy, req.actualUserIdNo)
        return v.toDto()
    }

    @GetMapping
    fun list(@RequestParam wardCode: String): List<ViolationDto> =
        violationRepository.findAll().filter { it.wardCode == wardCode }
            .sortedBy { it.createdAt }.map { it.toDto() }

    @PostMapping("/{id}/resolve")
    fun resolve(@PathVariable id: Long, @Valid @RequestBody req: ViolationResolveRequest): ViolationDto =
        violationService.resolveViolation(id, req.resolvedBy, req.note, req.confirmed).toDto()
}
