package com.hospital.escort.api

import com.hospital.escort.domain.ApplicationStatus
import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.service.ApplicationService
import com.hospital.escort.service.toDto
import com.hospital.escort.service.EscortCaseService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** 家属提交申请 + 护士站审批 */
@RestController
@RequestMapping("/api/applications")
class ApplicationController(
    private val applicationService: ApplicationService,
    private val applicationRepository: EscortApplicationRepository,
    private val caseService: EscortCaseService
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun apply(@Valid @RequestBody req: ApplyRequest): ApplicationDto {
        val saved = applicationService.apply(req)
        val caseNo = caseService.getById(saved.caseId).caseNo
        return saved.toDto(caseNo)
    }

    @GetMapping
    fun list(@RequestParam wardCode: String, @RequestParam(required = false) status: ApplicationStatus?): List<ApplicationDto> =
        applicationRepository.findAll()
            .filter { it.wardCode == wardCode && (status == null || it.status == status) }
            .sortedBy { it.submittedAt }
            .map { it.toDto(caseService.getById(it.caseId).caseNo) }

    @GetMapping("/{applicationNo}")
    fun get(@PathVariable applicationNo: String): ApplicationDto {
        val app = applicationRepository.findByApplicationNo(applicationNo)
            ?: throw com.hospital.escort.common.ApiException(HttpStatus.NOT_FOUND, "申请不存在: $applicationNo")
        return app.toDto(caseService.getById(app.caseId).caseNo)
    }

    /** 护士站审批(通过/驳回), 通过后自动签发电子陪护证并同步门禁 */
    @PostMapping("/{applicationNo}/decision")
    fun decide(@PathVariable applicationNo: String, @Valid @RequestBody req: DecisionRequest): ApplicationDto {
        val saved = applicationService.decide(applicationNo, req)
        return saved.toDto(caseService.getById(saved.caseId).caseNo)
    }
}
