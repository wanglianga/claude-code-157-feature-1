package com.hospital.escort.service

import com.hospital.escort.api.ApplyRequest
import com.hospital.escort.api.DecisionRequest
import com.hospital.escort.common.badRequest
import com.hospital.escort.common.conflict
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.ApplicationStatus
import com.hospital.escort.domain.ApplicationType
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.EscortApplication
import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.domain.EscortCaseRepository
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.ScreeningType
import com.hospital.escort.domain.WardPolicyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 陪护申请与护士站审批。
 * 审批校验: 病区策略(探视限制/封闭)、床位陪护人数上限、夜间陪护审批、筛查有效性。
 */
@Service
class ApplicationService(
    private val applicationRepository: EscortApplicationRepository,
    private val permitRepository: EscortPermitRepository,
    private val caseRepository: EscortCaseRepository,
    private val wardPolicyRepository: WardPolicyRepository,
    private val caseService: EscortCaseService,
    private val permitService: PermitService
) {

    @Transactional
    fun apply(req: ApplyRequest, applyType: ApplicationType = ApplicationType.INITIAL): EscortApplication {
        val policy = wardPolicyRepository.findById(req.wardCode).orElse(null)
            ?: notFound("病区不存在: ${req.wardCode}")
        if (policy.lockdown) conflict("病区[${req.wardCode}]封闭管理中, 暂停新陪护申请: ${policy.lockdownReason ?: ""}")
        if (req.healthCommitment != true) badRequest("必须确认健康承诺才能提交申请")
        validateScreening(req.screeningType!!, req.screeningValidUntil)
        if (!req.periodEnd!!.isAfter(req.periodStart)) badRequest("陪护结束时间必须晚于开始时间")

        val escortCase = caseService.getOrCreateActiveCase(req.patientInpatientNo, req.wardCode, req.bedNo)

        // 病区床位陪护人数上限: 同一患者同时只能有 maxEscortsPerPatient 张有效证
        val activePermits = permitRepository.findByCaseIdOrderByIssuedAtAsc(escortCase.id!!)
            .count { it.status == PermitStatus.ACTIVE || it.status == PermitStatus.FROZEN }
        if (applyType == ApplicationType.INITIAL && activePermits >= policy.maxEscortsPerPatient) {
            conflict("患者[${req.patientInpatientNo}]已有 $activePermits 张有效陪护证, 达到病区上限, 请使用替换流程")
        }
        val hasPending = applicationRepository.findByCaseIdOrderBySubmittedAtAsc(escortCase.id!!)
            .any { it.status == ApplicationStatus.PENDING }
        if (hasPending) conflict("该患者已有待审批的陪护申请, 请勿重复提交")

        val application = EscortApplication(
            caseId = escortCase.id!!,
            applicationNo = "EA-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
            applyType = applyType,
            patientInpatientNo = req.patientInpatientNo,
            wardCode = req.wardCode,
            escortName = req.escortName,
            escortIdNo = req.escortIdNo,
            escortPhone = req.escortPhone,
            relationToPatient = req.relationToPatient,
            healthCommitment = true,
            screeningType = req.screeningType,
            screeningValidUntil = req.screeningValidUntil,
            periodStart = req.periodStart!!,
            periodEnd = req.periodEnd,
            needNightCare = req.needNightCare
        )
        val saved = applicationRepository.save(application)
        caseService.recordEvent(
            escortCase.id!!, CaseEventType.APPLICATION_SUBMITTED, req.escortName,
            "提交陪护申请(${applyType.name}): ${req.escortName} 照护患者 ${req.patientInpatientNo}",
            "病区=${req.wardCode}, 期限=${req.periodStart}~${req.periodEnd}, 夜间陪护=${req.needNightCare}, 筛查=${req.screeningType}",
            "APPLICATION", saved.id
        )
        caseService.notifyWard(
            req.wardCode, escortCase.id, null, "APPLICATION_PENDING",
            "新陪护申请待审批: ${req.escortName} -> 患者[${req.patientInpatientNo}]"
        )
        return saved
    }

    /** 护士站审批: 结合病区策略、床位占用、探视限制与夜间陪护需求 */
    @Transactional
    fun decide(applicationNo: String, req: DecisionRequest): EscortApplication {
        val application = applicationRepository.findByApplicationNo(applicationNo)
            ?: notFound("申请不存在: $applicationNo")
        if (application.status != ApplicationStatus.PENDING) conflict("申请已审批, 当前状态: ${application.status}")

        val policy = wardPolicyRepository.findById(application.wardCode).orElse(null)
            ?: notFound("病区不存在: ${application.wardCode}")
        val escortCase = caseRepository.findById(application.caseId).orElseThrow {
            IllegalStateException("申请关联的陪护记录不存在")
        }

        if (req.approved == true) {
            // 审批前再校验: 封闭管理 / 床位上限 / 夜间陪护需明确批准 / 筛查仍有效
            if (policy.lockdown) conflict("病区封闭管理中, 不能批准新陪护证")
            val activePermits = permitRepository.findByCaseIdOrderByIssuedAtAsc(escortCase.id!!)
                .count { it.status == PermitStatus.ACTIVE || it.status == PermitStatus.FROZEN }
            if (application.applyType == ApplicationType.INITIAL && activePermits >= policy.maxEscortsPerPatient) {
                conflict("患者已有 $activePermits 张有效陪护证, 达到病区上限")
            }
            if (application.needNightCare && policy.nightEscortRequiresApproval && !req.approveNightCare) {
                badRequest("该申请包含夜间陪护, 病区策略要求夜间陪护专项审批, 请显式批准(approveNightCare=true)或驳回")
            }
            validateScreening(application.screeningType, application.screeningValidUntil)

            application.status = ApplicationStatus.APPROVED
            application.decidedAt = Instant.now()
            application.decidedBy = req.decidedBy
            application.decisionNote = req.note
            val saved = applicationRepository.save(application)

            val permit = permitService.issuePermit(saved, escortCase, req.decidedBy, req.approveNightCare)
            caseService.recordEvent(
                escortCase.id!!, CaseEventType.APPLICATION_APPROVED, req.decidedBy,
                "申请审批通过: ${application.escortName}, 发证 ${permit.permitNo}",
                req.note, "APPLICATION", application.id
            )
            caseService.notifyWard(
                application.wardCode, escortCase.id, permit.id, "APPLICATION_APPROVED",
                "陪护申请已批准: ${application.escortName} -> 患者[${application.patientInpatientNo}], 证号 ${permit.permitNo}"
            )
            return saved
        } else {
            application.status = ApplicationStatus.REJECTED
            application.decidedAt = Instant.now()
            application.decidedBy = req.decidedBy
            application.decisionNote = req.note
            val saved = applicationRepository.save(application)
            caseService.recordEvent(
                escortCase.id!!, CaseEventType.APPLICATION_REJECTED, req.decidedBy,
                "申请被驳回: ${application.escortName}", req.note, "APPLICATION", application.id
            )
            caseService.notifyWard(
                application.wardCode, escortCase.id, null, "APPLICATION_REJECTED",
                "陪护申请被驳回: ${application.escortName} -> 患者[${application.patientInpatientNo}]: ${req.note ?: "无理由"}"
            )
            return saved
        }
    }

    /**
     * 替换流程专用: 直接落一条 REPLACEMENT 申请, 跳过"已有有效证/已有待审批"限制
     * (替换是病区允许的合规场景), 其余校验(筛查/封闭/健康承诺)仍然保留。
     */
    @Transactional
    fun applyReplacementDirect(req: ApplyRequest, replacedApplicationId: Long?): EscortApplication {
        val policy = wardPolicyRepository.findById(req.wardCode).orElse(null)
            ?: notFound("病区不存在: ${req.wardCode}")
        if (req.healthCommitment != true) badRequest("必须确认健康承诺才能提交申请")
        validateScreening(req.screeningType!!, req.screeningValidUntil)
        if (!req.periodEnd!!.isAfter(req.periodStart)) badRequest("陪护结束时间必须晚于开始时间")

        val escortCase = caseService.getOrCreateActiveCase(req.patientInpatientNo, req.wardCode, req.bedNo)
        val application = EscortApplication(
            caseId = escortCase.id!!,
            applicationNo = "EA-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
            applyType = ApplicationType.REPLACEMENT,
            patientInpatientNo = req.patientInpatientNo,
            wardCode = req.wardCode,
            escortName = req.escortName,
            escortIdNo = req.escortIdNo,
            escortPhone = req.escortPhone,
            relationToPatient = req.relationToPatient,
            healthCommitment = true,
            screeningType = req.screeningType,
            screeningValidUntil = req.screeningValidUntil,
            periodStart = req.periodStart!!,
            periodEnd = req.periodEnd,
            needNightCare = req.needNightCare,
            replacedApplicationId = replacedApplicationId
        )
        val saved = applicationRepository.save(application)
        caseService.recordEvent(
            escortCase.id!!, CaseEventType.APPLICATION_SUBMITTED, req.escortName,
            "提交替换陪护申请: ${req.escortName} 照护患者 ${req.patientInpatientNo}",
            "筛查=${req.screeningType}, 期限=${req.periodStart}~${req.periodEnd}",
            "APPLICATION", saved.id
        )
        return saved
    }

    private fun validateScreening(type: ScreeningType, validUntil: Instant?) {
        when (type) {
            ScreeningType.NONE -> badRequest("当前病区要求核酸或传染病筛查, 不能选择 NONE")
            ScreeningType.NAT, ScreeningType.ANTIGEN -> {
                if (validUntil == null) badRequest("需提供筛查结果有效期")
                if (!validUntil.isAfter(Instant.now())) badRequest("筛查结果已过期, 请重新检测")
            }
        }
    }
}
