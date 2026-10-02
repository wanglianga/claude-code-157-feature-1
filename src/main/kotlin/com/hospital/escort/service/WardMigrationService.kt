package com.hospital.escort.service

import com.hospital.escort.api.ApplyRequest
import com.hospital.escort.api.FamilyConfirmRequest
import com.hospital.escort.api.HandoverDocDto
import com.hospital.escort.api.TransferDecisionRequest
import com.hospital.escort.api.TransferDto
import com.hospital.escort.api.TransferOldPermitReturnRequest
import com.hospital.escort.api.TransferStartRequest
import com.hospital.escort.common.badRequest
import com.hospital.escort.common.conflict
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.ApplicationStatus
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.CaseStatus
import com.hospital.escort.domain.EscortApplication
import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.domain.EscortPermit
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.HandoverDocType
import com.hospital.escort.domain.HandoverDocument
import com.hospital.escort.domain.HandoverDocumentRepository
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.ScreeningType
import com.hospital.escort.domain.SyncAction
import com.hospital.escort.domain.TransferStatus
import com.hospital.escort.domain.WardPolicy
import com.hospital.escort.domain.WardTransfer
import com.hospital.escort.domain.WardTransferRepository
import com.hospital.escort.domain.WardPolicyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 患者转科后的陪护证迁移工作流(普通病房 -> 重症过渡病区等):
 *  1. 发起转科: 暂停原陪护证(门禁/扫码点/安保三端停权), 通知家属重新确认新病区陪护规则,
 *     患者落到新病区, 双病区留通知;
 *  2. 家属确认规则并提交迁移申请(默认沿用旧证陪护人);
 *  3. 新病区护士审批:
 *     - 允许: 旧证作废(REPLACED), 签发新病区陪护证;
 *     - 不允许: 生成患者物品交接单与家属探视说明, 等待旧证实体退回;
 *  4. 旧证退回后才释放押金、结束陪护责任, 避免家属仍持旧证在医院内通行。
 *
 * 门禁侧(见 ScanService): 迁移完成前旧证进入新病区一律拒绝并记 TRANSFER_BREACH,
 * 仅放行必要交接(离开病区/返回原病区交接)。
 */
@Service
class WardMigrationService(
    private val transferRepository: WardTransferRepository,
    private val handoverRepository: HandoverDocumentRepository,
    private val wardPolicyRepository: WardPolicyRepository,
    private val applicationRepository: EscortApplicationRepository,
    private val permitRepository: EscortPermitRepository,
    private val caseService: EscortCaseService,
    private val permitService: PermitService,
    private val applicationService: ApplicationService,
    private val syncService: com.hospital.escort.sync.DownstreamSyncService
) {
    companion object {
        /** 迁移未终结、旧证仍受门禁限制的状态 */
        val OPEN_STATUSES = listOf(
            TransferStatus.PENDING_FAMILY_CONFIRM,
            TransferStatus.PENDING_WARD_REVIEW,
            TransferStatus.REJECTED
        )
    }

    /** 1. 发起转科迁移 */
    @Transactional
    fun initiateTransfer(caseNo: String, req: TransferStartRequest): WardTransfer {
        val escortCase = caseService.getByCaseNo(caseNo)
        if (escortCase.status != CaseStatus.ACTIVE) conflict("陪护记录已关闭, 不能发起转科迁移")
        val toPolicy = wardPolicyRepository.findById(req.toWardCode).orElse(null)
            ?: notFound("目标病区不存在: ${req.toWardCode}")
        if (req.toWardCode == escortCase.wardCode) badRequest("患者已在该病区, 无需转科")

        transferRepository.findByCaseIdOrderByInitiatedAtAsc(escortCase.id!!)
            .firstOrNull { it.status in OPEN_STATUSES }
            ?.let { conflict("该患者已有未完成的转科迁移单 ${it.transferNo}(状态 ${it.status}), 请先处理") }

        // 当前有效陪护证: 优先 currentPermitId 指向的证, 否则取最近一张 ACTIVE
        val oldPermit = escortCase.currentPermitId
            ?.let { permitService.getById(it) }
            ?.takeIf { it.status == PermitStatus.ACTIVE }
            ?: permitRepository.findByCaseIdOrderByIssuedAtAsc(escortCase.id!!)
                .lastOrNull { it.status == PermitStatus.ACTIVE }
            ?: conflict("患者当前无有效(ACTIVE)陪护证, 不适用陪护证迁移流程, 请使用普通转科后重新申请")

        val fromWard = escortCase.wardCode
        val fromBed = escortCase.bedNo

        // 暂停原证: 三端同步停权, 旧证不得再进入任何病区(必要交接除外, 见 ScanService)
        permitService.suspendForTransfer(oldPermit, fromWard, req.toWardCode, req.operator)

        // 患者落到新病区/床位
        escortCase.wardCode = req.toWardCode
        if (req.bedNo != null) escortCase.bedNo = req.bedNo

        val transfer = transferRepository.save(
            WardTransfer(
                transferNo = "TR-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
                caseId = escortCase.id!!,
                patientInpatientNo = escortCase.patientInpatientNo,
                fromWardCode = fromWard,
                toWardCode = req.toWardCode,
                fromBedNo = fromBed,
                bedNo = req.bedNo,
                oldPermitId = oldPermit.id,
                status = TransferStatus.PENDING_FAMILY_CONFIRM,
                reason = req.reason,
                initiatedBy = req.operator
            )
        )

        caseService.recordEvent(
            escortCase.id!!, CaseEventType.TRANSFER_INITIATED, req.operator,
            "患者转科发起陪护证迁移: $fromWard -> ${req.toWardCode}, 原证 ${oldPermit.permitNo} 已暂停",
            "迁移单号=${transfer.transferNo}; 原因=${req.reason ?: "病情需要转科"}",
            "TRANSFER", transfer.id
        )
        caseService.recordEvent(
            escortCase.id!!, CaseEventType.PATIENT_TRANSFERRED, req.operator,
            "患者转科: $fromWard -> ${req.toWardCode}${req.bedNo?.let { " 床位 $it" } ?: ""}",
            "陪护证迁移中, 旧证已暂停, 待家属确认新病区规则及护士审批"
        )
        caseService.recordEvent(
            escortCase.id!!, CaseEventType.TRANSFER_FAMILY_RULES_NOTIFIED, req.operator,
            "已通知陪护人 ${oldPermit.escortName} 重新确认新病区陪护规则",
            familyRulesNotice(toPolicy),
            "TRANSFER", transfer.id
        )

        caseService.notifyWard(fromWard, escortCase.id, oldPermit.id, "PATIENT_TRANSFERRED_OUT",
            "患者[${escortCase.patientInpatientNo}]转出至 ${req.toWardCode}, 陪护证 ${oldPermit.permitNo} 已暂停, 仅允许必要交接")
        caseService.notifyWard(req.toWardCode, escortCase.id, oldPermit.id, "TRANSFER_IN_PENDING",
            "患者[${escortCase.patientInpatientNo}]由 $fromWard 转入, 陪护证迁移中: 待家属确认规则、待本病区护士审批; " +
                    "审批完成前旧证不得进入本病区")
        caseService.notifyWard(req.toWardCode, escortCase.id, oldPermit.id, "FAMILY_RULES_NOTICE",
            "请通知陪护人 ${oldPermit.escortName}(${oldPermit.escortPhone})重新确认新病区陪护规则并到护士站办理: ${rulesBrief(toPolicy)}")

        return transfer
    }

    /** 2. 家属重新确认新病区陪护规则, 并提交迁移申请(默认沿用旧证陪护人) */
    @Transactional
    fun familyConfirm(transferNo: String, req: FamilyConfirmRequest): WardTransfer {
        val transfer = getTransferEntity(transferNo)
        if (transfer.status != TransferStatus.PENDING_FAMILY_CONFIRM) {
            conflict("迁移单当前状态(${transfer.status})不允许家属确认")
        }
        if (req.ackRules != true) {
            badRequest("家属必须显式确认已阅读并接受新病区陪护规则(ackRules=true)")
        }
        val oldPermit = transfer.oldPermitId?.let { permitService.getById(it) }
            ?: notFound("原陪护证不存在")
        val escortCase = caseService.getById(transfer.caseId)
        val policy = wardPolicyRepository.findById(transfer.toWardCode).orElseThrow()

        // 迁移申请信息: 请求显式提供的优先, 否则沿用旧证及其申请
        val oldApplication = applicationRepository.findById(oldPermit.applicationId).orElseThrow()
        val applyReq = ApplyRequest(
            patientInpatientNo = escortCase.patientInpatientNo,
            wardCode = transfer.toWardCode,
            bedNo = transfer.bedNo,
            escortName = req.escortName?.takeIf { it.isNotBlank() } ?: oldPermit.escortName,
            escortIdNo = req.escortIdNo?.takeIf { it.isNotBlank() } ?: oldPermit.escortIdNo,
            escortPhone = req.escortPhone?.takeIf { it.isNotBlank() } ?: oldPermit.escortPhone,
            relationToPatient = req.relationToPatient ?: oldApplication.relationToPatient,
            healthCommitment = true,
            screeningType = req.screeningType ?: oldApplication.screeningType,
            screeningValidUntil = req.screeningValidUntil ?: oldApplication.screeningValidUntil,
            periodStart = oldApplication.periodStart,
            periodEnd = req.periodEnd ?: oldApplication.periodEnd,
            needNightCare = req.needNightCare ?: oldApplication.needNightCare
        )
        val newApplication = applicationService.applyTransferDirect(applyReq, oldApplication.id)

        transfer.status = TransferStatus.PENDING_WARD_REVIEW
        transfer.newApplicationId = newApplication.id
        transfer.familyConfirmedAt = Instant.now()
        transfer.familyConfirmedBy = req.confirmedBy
        transfer.familyAckNote = req.note
        transfer.updatedAt = Instant.now()
        val saved = transferRepository.save(transfer)

        caseService.recordEvent(
            transfer.caseId, CaseEventType.TRANSFER_FAMILY_CONFIRMED, req.confirmedBy,
            "陪护人 ${applyReq.escortName} 已确认新病区 ${transfer.toWardCode} 陪护规则, 迁移申请待护士审批",
            "申请号=${newApplication.applicationNo}; 备注=${req.note ?: "无"}",
            "TRANSFER", transfer.id
        )
        caseService.notifyWard(transfer.toWardCode, transfer.caseId, oldPermit.id, "TRANSFER_PENDING_REVIEW",
            "患者[${transfer.patientInpatientNo}]家属已确认陪护规则, 申请在本病区继续陪护(申请 ${newApplication.applicationNo}), 请护士审批: ${rulesBrief(policy)}")
        return saved
    }

    /** 3. 新病区护士审批是否允许继续陪护 */
    @Transactional
    fun decide(transferNo: String, req: TransferDecisionRequest): WardTransfer {
        val transfer = getTransferEntity(transferNo)
        if (transfer.status != TransferStatus.PENDING_WARD_REVIEW) {
            conflict("迁移单当前状态(${transfer.status})不允许护士审批")
        }
        val application = transfer.newApplicationId?.let {
            applicationRepository.findById(it).orElse(null)
        } ?: notFound("迁移申请不存在")
        if (application.status != ApplicationStatus.PENDING) conflict("迁移申请已处理: ${application.status}")
        val escortCase = caseService.getById(transfer.caseId)
        val policy = wardPolicyRepository.findById(transfer.toWardCode).orElseThrow()
        val oldPermit = transfer.oldPermitId?.let { permitService.getById(it) }
            ?: notFound("原陪护证不存在")

        if (req.approved != true) {
            return reject(transfer, application, oldPermit, policy, req)
        }

        // ---- 允许继续陪护: 病区策略复核 ----
        if (policy.lockdown) conflict("病区 ${transfer.toWardCode} 封闭管理中, 不能批准陪护迁移")
        val otherValidPermits = permitRepository.findByCaseIdOrderByIssuedAtAsc(transfer.caseId)
            .count { (it.status == PermitStatus.ACTIVE || it.status == PermitStatus.FROZEN) && it.id != oldPermit.id }
        if (otherValidPermits >= policy.maxEscortsPerPatient) {
            conflict("患者已有 $otherValidPermits 张有效陪护证, 达到病区 ${transfer.toWardCode} 上限")
        }
        if (application.needNightCare && policy.nightEscortRequiresApproval && !req.approveNightCare) {
            badRequest("迁移申请包含夜间陪护, 新病区策略要求专项审批, 请显式批准(approveNightCare=true)或驳回")
        }
        if (application.screeningType != ScreeningType.NONE &&
            application.screeningValidUntil != null && !application.screeningValidUntil!!.isAfter(Instant.now())
        ) {
            conflict("筛查结果已过期, 请家属重新检测后再审批")
        }

        // 审批通过迁移申请
        application.status = ApplicationStatus.APPROVED
        application.decidedAt = Instant.now()
        application.decidedBy = req.decidedBy
        application.decisionNote = req.note
        applicationRepository.save(application)

        // 旧证逻辑作废(实体卡仍需追回, 门禁已按 REPLACED 拒绝)
        oldPermit.status = PermitStatus.REPLACED
        oldPermit.transferSuspended = false
        permitService.saveDirect(oldPermit)
        caseService.recordEvent(transfer.caseId, CaseEventType.PERMIT_REPLACED, req.decidedBy,
            "旧陪护证 ${oldPermit.permitNo} 因转科迁移作废, 新病区签发新证", null, "PERMIT", oldPermit.id)
        syncService.syncPermit(oldPermit, escortCase.caseNo, SyncAction.REVOKE)

        // 签发新病区陪护证(同步更新当前照护人快照并三端发证)
        val newPermit = permitService.issuePermit(
            application, escortCase, req.decidedBy,
            nightCareApproved = application.needNightCare && req.approveNightCare
        )

        transfer.status = TransferStatus.APPROVED
        transfer.newPermitId = newPermit.id
        transfer.decidedAt = Instant.now()
        transfer.decidedBy = req.decidedBy
        transfer.decisionNote = req.note
        transfer.completedAt = Instant.now()
        transfer.updatedAt = Instant.now()
        val saved = transferRepository.save(transfer)

        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_APPROVED, req.decidedBy,
            "新病区 ${transfer.toWardCode} 批准继续陪护: 新证 ${newPermit.permitNo}, 陪护人 ${newPermit.escortName}",
            req.note, "TRANSFER", transfer.id)
        caseService.notifyWard(transfer.toWardCode, transfer.caseId, newPermit.id, "TRANSFER_APPROVED",
            "患者[${transfer.patientInpatientNo}]陪护迁移已批准: 现由 ${newPermit.escortName} 照护, 新证 ${newPermit.permitNo}; 旧证 ${oldPermit.permitNo} 已作废, 请收回实体卡")
        caseService.notifyWard(transfer.fromWardCode, transfer.caseId, oldPermit.id, "TRANSFER_COMPLETED",
            "患者[${transfer.patientInpatientNo}]转出后陪护迁移已完成, 旧证 ${oldPermit.permitNo} 已作废")
        return saved
    }

    /** 不允许陪护: 生成物品交接单与家属探视说明, 等待旧证退回 */
    private fun reject(
        transfer: WardTransfer,
        application: com.hospital.escort.domain.EscortApplication,
        oldPermit: com.hospital.escort.domain.EscortPermit,
        policy: WardPolicy,
        req: TransferDecisionRequest
    ): WardTransfer {
        application.status = ApplicationStatus.REJECTED
        application.decidedAt = Instant.now()
        application.decidedBy = req.decidedBy
        application.decisionNote = req.note
        applicationRepository.save(application)

        transfer.status = TransferStatus.REJECTED
        transfer.decidedAt = Instant.now()
        transfer.decidedBy = req.decidedBy
        transfer.decisionNote = req.note
        transfer.updatedAt = Instant.now()
        transferRepository.save(transfer)

        val docs = issueHandoverDocuments(transfer, policy, req.decidedBy)

        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_REJECTED, req.decidedBy,
            "新病区 ${transfer.toWardCode} 不允许继续陪护, 旧证 ${oldPermit.permitNo} 维持暂停, 等待家属退证",
            req.note, "TRANSFER", transfer.id)
        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_HANDOVER_DOC_ISSUED, req.decidedBy,
            "已生成交接文档: ${docs.joinToString { it.docNo }}(${docs.joinToString { it.docType.name }})",
            docs.joinToString("\n") { "${it.title}(${it.docNo})" },
            "TRANSFER", transfer.id)
        caseService.notifyWard(transfer.toWardCode, transfer.caseId, oldPermit.id, "TRANSFER_REJECTED",
            "患者[${transfer.patientInpatientNo}]新病区不安排陪护: 已出具物品交接单与家属探视说明; " +
                    "旧证 ${oldPermit.permitNo} 退回前, 押金不释放、陪护责任不终止")
        caseService.notifyWard(transfer.fromWardCode, transfer.caseId, oldPermit.id, "TRANSFER_REJECTED",
            "患者[${transfer.patientInpatientNo}]在 ${transfer.toWardCode} 不允许陪护, 旧证 ${oldPermit.permitNo} 待退回, 退回后释放押金并结束陪护责任")
        return transfer
    }

    /** 4. 旧陪护证退回 -> 释放押金、结束陪护责任 */
    @Transactional
    fun returnOldPermit(transferNo: String, req: TransferOldPermitReturnRequest): WardTransfer {
        val transfer = getTransferEntity(transferNo)
        if (transfer.status != TransferStatus.REJECTED) {
            conflict("仅新病区不允许陪护(REJECTED)的迁移单需要退回旧证结算, 当前状态: ${transfer.status}")
        }
        val oldPermit = transfer.oldPermitId?.let { permitService.getById(it) }
            ?: notFound("原陪护证不存在")
        if (transfer.oldPermitReturnedAt != null) conflict("旧证已退回, 请勿重复操作")

        // 转科迁移结算路径: 解除暂停标记后走标准退证(门禁三端失效, 清空当前照护人快照)
        oldPermit.transferSuspended = false
        permitService.saveDirect(oldPermit)
        permitService.returnPermit(
            oldPermit.permitNo, req.returnedBy, req.channel,
            "转科至 ${transfer.toWardCode} 后新病区不允许陪护, 旧证退回: ${req.note ?: ""}"
        )

        val now = Instant.now()
        transfer.oldPermitReturnedAt = now
        transfer.depositReleased = true
        transfer.depositReleasedAt = now
        transfer.depositReleaseNote = "旧陪护证 ${oldPermit.permitNo} 已实体退回($req.channel), 押金予以释放: ${req.note ?: ""}"
        transfer.liabilityEnded = true
        transfer.liabilityEndedAt = now
        transfer.completedAt = now
        transfer.updatedAt = now
        val saved = transferRepository.save(transfer)

        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_OLD_PERMIT_RETURNED, req.returnedBy,
            "旧陪护证 ${oldPermit.permitNo} 已实体退回, 家属不再持旧证在院内通行",
            "渠道=$req.channel", "TRANSFER", transfer.id)
        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_DEPOSIT_RELEASED, req.returnedBy,
            "旧证退回完成: 押金已释放, 陪护责任终止",
            transfer.depositReleaseNote, "TRANSFER", transfer.id)
        caseService.notifyWard(transfer.toWardCode, transfer.caseId, oldPermit.id, "TRANSFER_SETTLED",
            "患者[${transfer.patientInpatientNo}]旧证已收回, 押金已释放、陪护责任已终止, 迁移闭环")
        caseService.notifyWard(transfer.fromWardCode, transfer.caseId, oldPermit.id, "TRANSFER_SETTLED",
            "患者[${transfer.patientInpatientNo}]转科陪护迁移已闭环: 旧证收回, 押金释放, 责任终止")
        return saved
    }

    /** 撤销迁移: 患者在新病区审批完成前转回原病区, 原证恢复 */
    @Transactional
    fun cancelTransfer(transferNo: String, operator: String, reason: String?): WardTransfer {
        val transfer = getTransferEntity(transferNo)
        if (transfer.status !in listOf(TransferStatus.PENDING_FAMILY_CONFIRM, TransferStatus.PENDING_WARD_REVIEW)) {
            conflict("迁移单当前状态(${transfer.status})不能撤销")
        }
        val escortCase = caseService.getById(transfer.caseId)
        transfer.newApplicationId?.let { appId ->
            applicationRepository.findById(appId).ifPresent { app ->
                if (app.status == ApplicationStatus.PENDING) {
                    app.status = ApplicationStatus.WITHDRAWN
                    applicationRepository.save(app)
                }
            }
        }
        transfer.oldPermitId?.let { permitId ->
            val oldPermit = permitService.getById(permitId)
            if (oldPermit.status == PermitStatus.FROZEN && oldPermit.transferSuspended) {
                permitService.resumeAfterTransferCancelled(oldPermit, operator)
            }
        }
        // 患者回到原病区/床位
        escortCase.wardCode = transfer.fromWardCode
        escortCase.bedNo = transfer.fromBedNo
        transfer.status = TransferStatus.CANCELLED
        transfer.updatedAt = Instant.now()
        val saved = transferRepository.save(transfer)

        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_CANCELLED, operator,
            "转科陪护迁移撤销, 患者转回 ${transfer.fromWardCode}, 原陪护证恢复", reason, "TRANSFER", transfer.id)
        caseService.notifyWard(transfer.toWardCode, transfer.caseId, transfer.oldPermitId, "TRANSFER_CANCELLED",
            "患者[${transfer.patientInpatientNo}]转回 ${transfer.fromWardCode}, 迁移撤销")
        caseService.notifyWard(transfer.fromWardCode, transfer.caseId, transfer.oldPermitId, "TRANSFER_CANCELLED",
            "患者[${transfer.patientInpatientNo}]转回本病区, 原陪护证已恢复")
        return saved
    }

    @Transactional(readOnly = true)
    fun getTransferEntity(transferNo: String): WardTransfer =
        transferRepository.findByTransferNo(transferNo) ?: notFound("转科迁移单不存在: $transferNo")

    @Transactional(readOnly = true)
    fun getTransfer(transferNo: String): TransferDto = toDto(getTransferEntity(transferNo))

    @Transactional(readOnly = true)
    fun listByCase(caseId: Long): List<WardTransfer> =
        transferRepository.findByCaseIdOrderByInitiatedAtAsc(caseId)

    @Transactional(readOnly = true)
    fun listByToWardPending(wardCode: String): List<WardTransfer> =
        transferRepository.findByStatusInOrderByInitiatedAtAsc(
            listOf(TransferStatus.PENDING_FAMILY_CONFIRM, TransferStatus.PENDING_WARD_REVIEW, TransferStatus.REJECTED)
        ).filter { it.toWardCode == wardCode }

    private fun issueHandoverDocuments(
        transfer: WardTransfer, policy: WardPolicy, createdBy: String
    ): List<HandoverDocument> {
        val belongings = HandoverDocument(
            transferId = transfer.id!!,
            caseId = transfer.caseId,
            docNo = "HD-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
            docType = HandoverDocType.BELONGINGS_HANDOVER,
            wardCode = transfer.toWardCode,
            title = "患者物品交接单",
            content = belongingsContent(transfer),
            createdBy = createdBy
        )
        val visit = HandoverDocument(
            transferId = transfer.id!!,
            caseId = transfer.caseId,
            docNo = "HD-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
            docType = HandoverDocType.FAMILY_VISIT_INSTRUCTION,
            wardCode = transfer.toWardCode,
            title = "家属探视说明",
            content = visitInstructionContent(transfer, policy),
            createdBy = createdBy
        )
        return handoverRepository.saveAll(listOf(belongings, visit))
    }

    private fun belongingsContent(t: WardTransfer) = """
        患者物品交接单
        ==============================
        交接单号关联: ${t.transferNo}
        患者住院号: ${t.patientInpatientNo}
        转出病区: ${t.fromWardCode}    转入病区: ${t.toWardCode}    床位: ${t.bedNo ?: ""}
        打印时间: ${Instant.now()}

        一、交接清单(由原病区护士、新病区护士与家属现场逐项清点):
          1. 生活用品(餐具/水杯/洗漱用品/纸巾等): ____________
          2. 患者衣物(件数): ____________
          3. 证件与资料(身份证/医保卡/病历资料/检查报告): ____________
          4. 贵重物品(手机/现金/首饰/假牙等, 若无注明"无"): ____________
          5. 自带药品/保健品(名称数量): ____________
          6. 其他: ____________

        二、交接说明:
          - 新病区(${t.toWardCode})不安排家属陪护, 患者在本病区期间由医护照护;
          - 家属原陪护证必须交回护士站, 交回前押金不释放、陪护责任不终止;
          - 物品清点无误后三方签字确认, 各执一份。

        原病区护士签字: ____________   新病区护士签字: ____________
        家属签字: ____________         日期: ____________
    """.trimIndent()

    private fun visitInstructionContent(t: WardTransfer, policy: WardPolicy) = """
        家属探视说明
        ==============================
        关联迁移单: ${t.transferNo}    患者住院号: ${t.patientInpatientNo}
        病区: ${policy.wardName}(${t.toWardCode})

        尊敬的家属:
        患者已转入${policy.wardName}, 本病区为重症过渡病区, 院感与诊疗要求不同于普通病房,
        经护士评估, 本次不安排家属在本病区陪护, 相关安排说明如下:

        1. 探视时间: 每日 ${policy.allowVisitStart} - ${policy.allowVisitEnd}, 请在规定时段内探视;
        2. 探视要求: 凭本人有效身份证件在护士站登记, 遵守病区消毒隔离要求, 听从医护安排;
           每名患者原则上同时探视家属不超过 ${policy.maxEscortsPerPatient} 人;
        3. 夜间不安排陪护与探视, 患者病情变化由病区第一时间电话告知;
        4. 您原病区的陪护证在本病区无效: 迁移审批完成前持旧证进入本病区将被门禁拒绝并记录违规;
        5. 请将原陪护证交回 ${t.toWardCode} 护士站; 旧证退回后, 押金方可释放, 陪护责任同步终止;
           旧证退回前请不要持旧证在院内其他病区通行;
        6. 患者所需生活物品请按《患者物品交接单》准备与交接, 紧急事宜联系本病区护士站。

        出具人: 护士站 ${t.decidedBy ?: ""}        时间: ${Instant.now()}
    """.trimIndent()

    private fun familyRulesNotice(policy: WardPolicy) =
        "新病区【${policy.wardName}】陪护规则: 探视 ${policy.allowVisitStart}-${policy.allowVisitEnd}; " +
                "每患者陪护上限 ${policy.maxEscortsPerPatient} 人; " +
                "夜间陪护需专项审批=${policy.nightEscortRequiresApproval}; " +
                "迁移完成前旧证不得进入新病区, 门禁仅允许必要交接; 若新病区不允许陪护, 旧证退回后才释放押金、结束陪护责任。"

    private fun rulesBrief(policy: WardPolicy) =
        "探视 ${policy.allowVisitStart}-${policy.allowVisitEnd}, 上限 ${policy.maxEscortsPerPatient} 人, " +
                "夜间需审批=${policy.nightEscortRequiresApproval}"

    @Transactional(readOnly = true)
    fun toDto(transfer: WardTransfer): TransferDto {
        val oldPermitNo = transfer.oldPermitId?.let { permitRepository.findById(it).orElse(null)?.permitNo }
        val newPermitNo = transfer.newPermitId?.let { permitRepository.findById(it).orElse(null)?.permitNo }
        val docs = handoverRepository.findByTransferIdOrderByDocTypeAsc(transfer.id!!).map { it.toDto() }
        return TransferDto(
            transferNo = transfer.transferNo,
            caseNo = caseService.getCaseNoById(transfer.caseId),
            patientInpatientNo = transfer.patientInpatientNo,
            fromWardCode = transfer.fromWardCode,
            toWardCode = transfer.toWardCode,
            bedNo = transfer.bedNo,
            status = transfer.status.name,
            reason = transfer.reason,
            initiatedBy = transfer.initiatedBy,
            initiatedAt = transfer.initiatedAt,
            familyConfirmedAt = transfer.familyConfirmedAt,
            familyConfirmedBy = transfer.familyConfirmedBy,
            decidedAt = transfer.decidedAt,
            decidedBy = transfer.decidedBy,
            decisionNote = transfer.decisionNote,
            oldPermitNo = oldPermitNo,
            newPermitNo = newPermitNo,
            oldPermitReturnedAt = transfer.oldPermitReturnedAt,
            depositReleased = transfer.depositReleased,
            depositReleasedAt = transfer.depositReleasedAt,
            depositReleaseNote = transfer.depositReleaseNote,
            liabilityEnded = transfer.liabilityEnded,
            liabilityEndedAt = transfer.liabilityEndedAt,
            completedAt = transfer.completedAt,
            documents = docs
        )
    }

    private fun HandoverDocument.toDto() = HandoverDocDto(
        id = id!!, docNo = docNo, docType = docType.name, wardCode = wardCode,
        title = title, content = content, createdBy = createdBy, createdAt = createdAt
    )
}
