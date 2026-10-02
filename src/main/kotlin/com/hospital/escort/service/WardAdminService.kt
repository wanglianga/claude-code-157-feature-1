package com.hospital.escort.service

import com.hospital.escort.api.DepositDisputeRequest
import com.hospital.escort.api.ExitRestrictionRequest
import com.hospital.escort.api.TransferRequest
import com.hospital.escort.api.TransferStartRequest
import com.hospital.escort.api.WardLockdownRequest
import com.hospital.escort.api.WardPolicyDto
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.CaseStatus
import com.hospital.escort.domain.EscortCaseRepository
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.WardPolicy
import com.hospital.escort.domain.WardPolicyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 病区管理: 封闭管理、患者转科、护士限制外出、费用押金争议归档。
 * 每一次状态变化都让病区知道患者当下由谁照护。
 */
@Service
class WardAdminService(
    private val wardPolicyRepository: WardPolicyRepository,
    private val caseRepository: EscortCaseRepository,
    private val permitRepository: EscortPermitRepository,
    private val caseService: EscortCaseService,
    private val permitService: PermitService,
    private val migrationService: WardMigrationService
) {

    @Transactional(readOnly = true)
    fun listWards(): List<WardPolicyDto> = wardPolicyRepository.findAll().map { it.toDto() }

    @Transactional
    fun setLockdown(wardCode: String, req: WardLockdownRequest): WardPolicyDto {
        val policy = wardPolicyRepository.findById(wardCode).orElse(null) ?: notFound("病区不存在: $wardCode")
        policy.lockdown = req.lockdown
        policy.lockdownReason = if (req.lockdown) req.reason else null
        policy.updatedAt = Instant.now()
        wardPolicyRepository.save(policy)

        // 病区封闭: 冻结该病区全部有效陪护证(门禁立即停权); 解封时恢复封闭冻结的证件
        val activeCases = caseRepository.findByWardCodeAndStatus(wardCode, CaseStatus.ACTIVE)
        if (req.lockdown) {
            activeCases.forEach { escortCase ->
                permitRepository.findByCaseIdOrderByIssuedAtAsc(escortCase.id!!)
                    .filter { it.status == PermitStatus.ACTIVE }
                    .forEach { permit ->
                        permitService.freezePermit(
                            permit.permitNo,
                            "病区封闭管理: ${req.reason ?: ""}",
                            req.operator,
                            lockdown = true
                        )
                    }
                caseService.recordEvent(escortCase.id!!, CaseEventType.WARD_LOCKDOWN, req.operator,
                    "病区 $wardCode 启动封闭管理", req.reason)
            }
        } else {
            activeCases.forEach { escortCase ->
                permitRepository.findByCaseIdOrderByIssuedAtAsc(escortCase.id!!)
                    .filter { it.status == PermitStatus.FROZEN && it.lockdownFrozen }
                    .forEach { permitService.unfreezePermit(it.permitNo, req.operator) }
                caseService.recordEvent(escortCase.id!!, CaseEventType.WARD_LOCKDOWN_LIFTED, req.operator,
                    "病区 $wardCode 解除封闭管理", req.reason)
            }
        }
        return policy.toDto()
    }

    /**
     * 患者转科:
     *  - escortMigration=true(如普通病房转入重症过渡病区): 走陪护证迁移工作流
     *    (暂停原证 -> 家属确认新病区规则 -> 新病区护士审批 -> 新证或旧证退回), 返回迁移单号;
     *  - escortMigration=false: 保持原普通转科逻辑(默认冻结原证, 在新病区重新申请)。
     */
    @Transactional
    fun transferPatient(caseNo: String, req: TransferRequest): String? {
        if (req.escortMigration) {
            val transfer = migrationService.initiateTransfer(
                caseNo,
                TransferStartRequest(
                    toWardCode = req.toWardCode,
                    bedNo = req.bedNo,
                    reason = req.reason,
                    operator = req.operator
                )
            )
            return transfer.transferNo
        }

        val escortCase = caseRepository.findByCaseNo(caseNo) ?: notFound("陪护记录不存在: $caseNo")
        wardPolicyRepository.findById(req.toWardCode).orElse(null) ?: notFound("目标病区不存在: ${req.toWardCode}")
        val oldWard = escortCase.wardCode
        if (req.freezePermit) {
            permitRepository.findByCaseIdOrderByIssuedAtAsc(escortCase.id!!)
                .filter { it.status == PermitStatus.ACTIVE }
                .forEach {
                    permitService.freezePermit(it.permitNo, "患者转科: $oldWard -> ${req.toWardCode}", req.operator)
                }
        }
        escortCase.wardCode = req.toWardCode
        if (req.bedNo != null) escortCase.bedNo = req.bedNo
        caseRepository.save(escortCase)
        caseService.recordEvent(escortCase.id!!, CaseEventType.PATIENT_TRANSFERRED, req.operator,
            "患者转科: $oldWard -> ${req.toWardCode}${req.bedNo?.let { " 床位 $it" } ?: ""}",
            "原陪护证${if (req.freezePermit) "已冻结, 需在新病区重新申请" else "未冻结"}")
        caseService.notifyWard(oldWard, escortCase.id, escortCase.currentPermitId, "PATIENT_TRANSFERRED_OUT",
            "患者[${escortCase.patientInpatientNo}]转出至 $req.toWardCode")
        caseService.notifyWard(req.toWardCode, escortCase.id, escortCase.currentPermitId, "PATIENT_TRANSFERRED_IN",
            "患者[${escortCase.patientInpatientNo}]由 $oldWard 转入, 陪护安排待确认")
        return null
    }

    /** 护士要求限制/解除陪护人外出 */
    @Transactional
    fun setExitRestriction(permitNo: String, req: ExitRestrictionRequest) {
        val permit = permitService.getByPermitNo(permitNo)
        permit.exitRestricted = req.restricted
        permit.exitRestrictReason = if (req.restricted) req.reason else null
        permitService.saveDirect(permit)
        val escortCase = caseService.getById(permit.caseId)
        caseService.recordEvent(
            permit.caseId,
            if (req.restricted) CaseEventType.EXIT_RESTRICTED else CaseEventType.EXIT_RESTRICTION_LIFTED,
            req.operator,
            if (req.restricted) "护士限制陪护人外出: ${req.reason ?: ""}" else "护士解除外出限制",
            null, "PERMIT", permit.id
        )
        caseService.notifyWard(permit.wardCode, permit.caseId, permit.id,
            if (req.restricted) "EXIT_RESTRICTED" else "EXIT_RESTRICTION_LIFTED",
            "患者[${escortCase.patientInpatientNo}]陪护人 ${permit.escortName} " +
                    if (req.restricted) "已被限制外出: ${req.reason ?: ""}" else "已解除外出限制")
    }

    /** 费用押金争议: 不改变证件有效性, 但归档到陪护记录并通知病区协调 */
    @Transactional
    fun raiseDepositDispute(caseNo: String, req: DepositDisputeRequest) {
        val escortCase = caseService.getByCaseNo(caseNo)
        caseService.recordEvent(escortCase.id!!, CaseEventType.DEPOSIT_DISPUTE_RAISED, req.raisedBy,
            "押金/费用争议登记", req.note)
        caseService.notifyWard(escortCase.wardCode, escortCase.id, escortCase.currentPermitId,
            "DEPOSIT_DISPUTE", "患者[${escortCase.patientInpatientNo}]家属提出押金争议: ${req.note ?: ""}, 请护士站协调")
    }

    @Transactional
    fun resolveDepositDispute(caseNo: String, operator: String, note: String?) {
        val escortCase = caseService.getByCaseNo(caseNo)
        caseService.recordEvent(escortCase.id!!, CaseEventType.DEPOSIT_DISPUTE_RESOLVED, operator,
            "押金/费用争议已协调解决", note)
        caseService.notifyWard(escortCase.wardCode, escortCase.id, escortCase.currentPermitId,
            "DEPOSIT_DISPUTE_RESOLVED", "患者[${escortCase.patientInpatientNo}]押金争议已解决: $note")
    }

    private fun WardPolicy.toDto() = WardPolicyDto(
        wardCode, wardName, allowVisitStart.toString(), allowVisitEnd.toString(),
        maxEscortsPerPatient, nightEscortRequiresApproval, lockdown, lockdownReason
    )
}
