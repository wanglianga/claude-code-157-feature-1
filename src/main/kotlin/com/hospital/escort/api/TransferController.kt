package com.hospital.escort.api

import com.hospital.escort.service.WardMigrationService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 患者转科后的陪护证迁移工作流:
 * 发起(也可用 /api/wards/cases/{caseNo}/transfer + escortMigration=true) ->
 * 家属确认新病区陪护规则 -> 新病区护士审批 -> (允许)新证签发 / (不允许)交接文档+旧证退回结算。
 */
@RestController
@RequestMapping("/api/transfers")
class TransferController(private val migrationService: WardMigrationService) {

    /** 迁移单详情(含交接文档) */
    @GetMapping("/{transferNo}")
    fun get(@PathVariable transferNo: String): TransferDto =
        migrationService.getTransfer(transferNo)

    /** 家属重新确认新病区陪护规则并提交迁移申请 */
    @PostMapping("/{transferNo}/family-confirm")
    fun familyConfirm(@PathVariable transferNo: String, @Valid @RequestBody req: FamilyConfirmRequest): TransferDto {
        migrationService.familyConfirm(transferNo, req)
        return migrationService.getTransfer(transferNo)
    }

    /** 新病区护士审批是否允许继续陪护 */
    @PostMapping("/{transferNo}/decision")
    fun decide(@PathVariable transferNo: String, @Valid @RequestBody req: TransferDecisionRequest): TransferDto {
        migrationService.decide(transferNo, req)
        return migrationService.getTransfer(transferNo)
    }

    /** 新病区不允许陪护: 旧陪护证实体退回 -> 释放押金、结束陪护责任 */
    @PostMapping("/{transferNo}/old-permit-return")
    fun returnOldPermit(@PathVariable transferNo: String, @Valid @RequestBody req: TransferOldPermitReturnRequest): TransferDto {
        migrationService.returnOldPermit(transferNo, req)
        return migrationService.getTransfer(transferNo)
    }

    /** 撤销迁移(审批完成前患者转回原病区), 原证恢复 */
    @PostMapping("/{transferNo}/cancel")
    fun cancel(
        @PathVariable transferNo: String,
        @RequestParam(defaultValue = "WARD-MGR") operator: String,
        @RequestParam(required = false) reason: String?
    ): TransferDto {
        migrationService.cancelTransfer(transferNo, operator, reason)
        return migrationService.getTransfer(transferNo)
    }
}
