package com.hospital.escort.api

import com.hospital.escort.service.ArchiveQueryService
import com.hospital.escort.service.EscortCaseService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 单条陪护记录归档视图: 申请/审批/进出/替换/违规/退证全在同一条记录 */
@RestController
@RequestMapping("/api/cases")
class CaseController(
    private val archiveQueryService: ArchiveQueryService,
    private val caseService: EscortCaseService
) {

    @GetMapping("/{caseNo}/archive")
    fun archive(@PathVariable caseNo: String): CaseArchiveDto = archiveQueryService.getCaseArchive(caseNo)

    /** 陪护结束, 关闭记录(患者出院/不再需要陪护) */
    @PostMapping("/{caseNo}/close")
    fun close(@PathVariable caseNo: String, @Valid @RequestBody req: CloseCaseRequest): Map<String, String> {
        val escortCase = caseService.getByCaseNo(caseNo)
        caseService.closeCase(escortCase, req.operator, req.reason)
        return mapOf("result" to "CLOSED", "caseNo" to caseNo)
    }
}
