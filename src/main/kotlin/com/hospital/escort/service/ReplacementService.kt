package com.hospital.escort.service

import com.hospital.escort.api.ApplyRequest
import com.hospital.escort.api.DecisionRequest
import com.hospital.escort.api.ReplacementRequest
import com.hospital.escort.common.badRequest
import com.hospital.escort.common.conflict
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.ApplicationStatus
import com.hospital.escort.domain.ApplicationType
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.ReplacementRecord
import com.hospital.escort.domain.ReplacementRecordRepository
import com.hospital.escort.domain.ReplacementStatus
import com.hospital.escort.domain.SyncAction
import com.hospital.escort.domain.WardPolicyRepository
import com.hospital.escort.sync.DownstreamSyncService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 陪护人替换: 临时生病、亲属争抢、排班调整等场景。
 * 旧证作废(REPLACED) -> 新申请审批 -> 新证签发, 全程在同一条陪护记录上。
 */
@Service
class ReplacementService(
    private val replacementRepository: ReplacementRecordRepository,
    private val applicationRepository: EscortApplicationRepository,
    private val applicationService: ApplicationService,
    private val permitService: PermitService,
    private val caseService: EscortCaseService,
    private val syncService: DownstreamSyncService,
    private val wardPolicyRepository: WardPolicyRepository
) {

    @Transactional
    fun requestReplacement(req: ReplacementRequest): ReplacementRecord {
        val oldPermit = permitService.getByPermitNo(req.oldPermitNo)
        if (oldPermit.status != PermitStatus.ACTIVE && oldPermit.status != PermitStatus.FROZEN) {
            conflict("原陪护证状态(${oldPermit.status})不允许替换")
        }
        val escortCase = caseService.getById(oldPermit.caseId)
        val policy = wardPolicyRepository.findById(escortCase.wardCode).orElse(null)
            ?: notFound("病区不存在: ${escortCase.wardCode}")
        if (policy.lockdown) conflict("病区[${escortCase.wardCode}]封闭管理中, 暂停陪护替换申请")
        if (req.escortIdNo.isBlank() || req.escortName.isBlank() || req.escortPhone.isBlank()) {
            badRequest("新陪护人姓名/身份证/联系方式不能为空")
        }
        if (!req.healthCommitment) badRequest("新陪护人必须确认健康承诺")

        // 新陪护人申请(类型 REPLACEMENT), 期限默认沿用旧证
        val applyReq = ApplyRequest(
            patientInpatientNo = escortCase.patientInpatientNo,
            wardCode = escortCase.wardCode,
            bedNo = escortCase.bedNo,
            escortName = req.escortName,
            escortIdNo = req.escortIdNo,
            escortPhone = req.escortPhone,
            relationToPatient = req.relationToPatient,
            healthCommitment = true,
            screeningType = req.screeningType,
            screeningValidUntil = req.screeningValidUntil,
            periodStart = req.periodStart ?: Instant.now(),
            periodEnd = req.periodEnd ?: oldPermit.validUntil,
            needNightCare = req.needNightCare
        )
        // 替换申请不受"已有有效证"限制, 直接落库
        val newApplication = applicationService.applyReplacementDirect(applyReq, oldPermit.applicationId)

        val record = replacementRepository.save(
            ReplacementRecord(
                caseId = escortCase.id!!,
                oldPermitId = oldPermit.id,
                newApplicationId = newApplication.id!!,
                reason = req.reason,
                detail = req.detail,
                status = ReplacementStatus.PENDING
            )
        )
        caseService.recordEvent(
            escortCase.id!!, CaseEventType.REPLACEMENT_REQUESTED, req.escortName,
            "申请替换陪护人: ${oldPermit.escortName} -> ${req.escortName} (${req.reason.name})",
            req.detail, "REPLACEMENT", record.id
        )
        caseService.notifyWard(escortCase.wardCode, escortCase.id, oldPermit.id, "REPLACEMENT_PENDING",
            "患者[${escortCase.patientInpatientNo}]申请替换陪护: ${oldPermit.escortName} -> ${req.escortName}, 待审批")

        if (req.autoApprove) {
            return decideReplacement(record.id!!, req.decidedBy ?: "nurse-station", approve = true, note = "护士站即时审批")
        }
        return record
    }

    @Transactional
    fun decideReplacement(replacementId: Long, decidedBy: String, approve: Boolean, note: String?): ReplacementRecord {
        val record = replacementRepository.findById(replacementId).orElse(null)
            ?: notFound("替换记录不存在: $replacementId")
        if (record.status != ReplacementStatus.PENDING) conflict("替换申请已处理: ${record.status}")

        val newApplication = applicationRepository.findById(record.newApplicationId).orElseThrow()
        val oldPermit = record.oldPermitId?.let { permitService.getById(it) }
        val escortCase = caseService.getById(record.caseId)

        if (!approve) {
            record.status = ReplacementStatus.REJECTED
            record.decidedAt = Instant.now()
            record.decidedBy = decidedBy
            newApplication.status = ApplicationStatus.REJECTED
            newApplication.decidedAt = Instant.now()
            newApplication.decidedBy = decidedBy
            newApplication.decisionNote = note
            applicationRepository.save(newApplication)
            val saved = replacementRepository.save(record)
            caseService.recordEvent(record.caseId, CaseEventType.REPLACEMENT_REJECTED, decidedBy,
                "替换申请被驳回", note, "REPLACEMENT", record.id)
            caseService.notifyWard(escortCase.wardCode, record.caseId, null, "REPLACEMENT_REJECTED",
                "患者[${escortCase.patientInpatientNo}]替换陪护被驳回: ${note ?: ""}")
            return saved
        }

        // 1. 审批新申请
        newApplication.status = ApplicationStatus.APPROVED
        newApplication.decidedAt = Instant.now()
        newApplication.decidedBy = decidedBy
        newApplication.decisionNote = note
        applicationRepository.save(newApplication)

        // 2. 旧证作废
        if (oldPermit != null && oldPermit.status != PermitStatus.RETURNED && oldPermit.status != PermitStatus.REVOKED) {
            oldPermit.status = PermitStatus.REPLACED
            permitService.saveDirect(oldPermit)
            caseService.recordEvent(record.caseId, CaseEventType.PERMIT_REPLACED, decidedBy,
                "旧陪护证 ${oldPermit.permitNo} 因替换作废", null, "PERMIT", oldPermit.id)
            syncService.syncPermit(oldPermit, escortCase.caseNo, SyncAction.REVOKE)
        }

        // 3. 签发新证
        val newPermit = permitService.issuePermit(newApplication, escortCase, decidedBy, nightCareApproved = true)

        record.status = ReplacementStatus.APPROVED
        record.newPermitId = newPermit.id
        record.decidedAt = Instant.now()
        record.decidedBy = decidedBy
        val saved = replacementRepository.save(record)

        caseService.recordEvent(record.caseId, CaseEventType.REPLACEMENT_APPROVED, decidedBy,
            "替换完成: ${oldPermit?.escortName ?: "?"} -> ${newPermit.escortName}, 新证 ${newPermit.permitNo}",
            note, "REPLACEMENT", record.id)
        caseService.notifyWard(escortCase.wardCode, record.caseId, newPermit.id, "REPLACEMENT_APPROVED",
            "患者[${escortCase.patientInpatientNo}]陪护人已替换: 现由 ${newPermit.escortName} 照护(证号 ${newPermit.permitNo})")
        return saved
    }
}
