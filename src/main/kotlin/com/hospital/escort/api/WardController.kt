package com.hospital.escort.api

import com.hospital.escort.service.ArchiveQueryService
import com.hospital.escort.service.DashboardService
import com.hospital.escort.service.WardAdminService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 病区管理: 策略查询、封闭管理、转科、外出限制、押金争议、病区通知 */
@RestController
@RequestMapping("/api/wards")
class WardController(
    private val wardAdminService: WardAdminService,
    private val archiveQueryService: ArchiveQueryService,
    private val dashboardService: DashboardService
) {

    @GetMapping
    fun listWards(): List<WardPolicyDto> = wardAdminService.listWards()

    @PostMapping("/{wardCode}/lockdown")
    fun lockdown(@PathVariable wardCode: String, @Valid @RequestBody req: WardLockdownRequest): WardPolicyDto =
        wardAdminService.setLockdown(wardCode, req)

    @PostMapping("/cases/{caseNo}/transfer")
    fun transfer(@PathVariable caseNo: String, @Valid @RequestBody req: TransferRequest): Map<String, String> {
        wardAdminService.transferPatient(caseNo, req)
        return mapOf("result" to "TRANSFERRED", "caseNo" to caseNo, "toWard" to req.toWardCode)
    }

    @PostMapping("/permits/{permitNo}/exit-restriction")
    fun exitRestriction(@PathVariable permitNo: String, @Valid @RequestBody req: ExitRestrictionRequest): Map<String, Any> {
        wardAdminService.setExitRestriction(permitNo, req)
        return mapOf("permitNo" to permitNo, "exitRestricted" to req.restricted)
    }

    @PostMapping("/cases/{caseNo}/deposit-dispute")
    fun raiseDispute(@PathVariable caseNo: String, @Valid @RequestBody req: DepositDisputeRequest): Map<String, String> {
        wardAdminService.raiseDepositDispute(caseNo, req)
        return mapOf("result" to "DISPUTE_RAISED", "caseNo" to caseNo)
    }

    @PostMapping("/cases/{caseNo}/deposit-dispute/resolve")
    fun resolveDispute(@PathVariable caseNo: String, @Valid @RequestBody req: DepositDisputeRequest): Map<String, String> {
        wardAdminService.resolveDepositDispute(caseNo, req.raisedBy, req.note)
        return mapOf("result" to "DISPUTE_RESOLVED", "caseNo" to caseNo)
    }

    /** 病区通知: 每次替换/冻结/退证都会推送给病区 */
    @GetMapping("/{wardCode}/notices")
    fun notices(@PathVariable wardCode: String, @RequestParam(defaultValue = "false") unreadOnly: Boolean): List<WardNoticeDto> =
        archiveQueryService.listWardNotices(wardCode, unreadOnly)

    /** 管理者看板 */
    @GetMapping("/{wardCode}/dashboard")
    fun dashboard(@PathVariable wardCode: String, @RequestParam(defaultValue = "7") days: Long): WardDashboardDto =
        dashboardService.wardDashboard(wardCode, days)
}
