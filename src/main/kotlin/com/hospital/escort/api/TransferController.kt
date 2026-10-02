package com.hospital.escort.api

import com.hospital.escort.service.PermitMigrationService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 患者转科陪护证迁移(普通病房 -> 重症过渡病区):
 * 暂停原证 -> 家属重新确认陪护规则 -> 新病区护士审批 -> 继续陪护/物品交接 -> 旧证退回 -> 押金释放。
 */
@RestController
@RequestMapping("/api/transfers")
class TransferController(private val migrationService: PermitMigrationService) {

    /** 启动迁移: 暂停原证, 通知家属重新确认规则, 患者落到新病区; 可同步发放一次必要交接通行 */
    @PostMapping("/cases/{caseNo}/start")
    fun start(@PathVariable caseNo: String, @Valid @RequestBody req: StartTransferRequest): TransferDto {
        val transfer = migrationService.startTransfer(caseNo, req)
        return migrationService.toTransferDto(transfer)
    }

    @GetMapping("/{transferNo}")
    fun get(@PathVariable transferNo: String): TransferDto = migrationService.getTransfer(transferNo)

    /** 家属重新确认重症过渡病区陪护规则 */
    @PostMapping("/{transferNo}/family-confirm")
    fun familyConfirm(@PathVariable transferNo: String, @Valid @RequestBody req: FamilyRuleConfirmRequest): TransferDto {
        val transfer = migrationService.familyConfirm(transferNo, req)
        return migrationService.toTransferDto(transfer)
    }

    /** 新病区护士审批是否允许继续陪护 */
    @PostMapping("/{transferNo}/decision")
    fun decision(@PathVariable transferNo: String, @Valid @RequestBody req: TransferDecisionRequest): TransferDto {
        val transfer = migrationService.wardDecision(transferNo, req)
        return migrationService.toTransferDto(transfer)
    }

    /** 旧陪护证实体卡退回(批准换新/不允许陪护两条路径都要收) */
    @PostMapping("/{transferNo}/old-permit-return")
    fun returnOldPermit(@PathVariable transferNo: String, @Valid @RequestBody req: TransferOldPermitReturnRequest): TransferDto {
        val transfer = migrationService.returnOldPermit(transferNo, req)
        return migrationService.toTransferDto(transfer)
    }

    /** 旧证退回后释放押金/结清陪护责任(未退回旧证一律拒绝) */
    @PostMapping("/{transferNo}/release-deposit")
    fun releaseDeposit(@PathVariable transferNo: String, @Valid @RequestBody req: TransferDepositReleaseRequest): TransferDto {
        val transfer = migrationService.releaseDeposit(transferNo, req)
        return migrationService.toTransferDto(transfer)
    }

    /** 追加发放一次必要交接临时通行(限新病区交接点, 限时, 一进一出) */
    @PostMapping("/{transferNo}/handover-pass")
    fun handoverPass(@PathVariable transferNo: String, @Valid @RequestBody req: HandoverPassGrantRequest): HandoverPassGrantDto {
        val grant = migrationService.grantHandoverPass(transferNo, req)
        return migrationService.toGrantDto(grant)
    }
}
