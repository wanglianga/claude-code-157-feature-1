package com.hospital.escort.service

import com.hospital.escort.api.FamilyRuleConfirmRequest
import com.hospital.escort.api.HandoverDto
import com.hospital.escort.api.HandoverPassGrantDto
import com.hospital.escort.api.HandoverPassGrantRequest
import com.hospital.escort.api.StartTransferRequest
import com.hospital.escort.api.TransferDecisionRequest
import com.hospital.escort.api.TransferDepositReleaseRequest
import com.hospital.escort.api.TransferDto
import com.hospital.escort.api.TransferOldPermitReturnRequest
import com.hospital.escort.common.badRequest
import com.hospital.escort.common.conflict
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.ApplicationStatus
import com.hospital.escort.domain.ApplicationType
import com.hospital.escort.domain.BelongingsHandover
import com.hospital.escort.domain.BelongingsHandoverRepository
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.CaseStatus
import com.hospital.escort.domain.EscortApplication
import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.domain.EscortCaseRepository
import com.hospital.escort.domain.EscortPermit
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.HandoverPassGrant
import com.hospital.escort.domain.HandoverPassGrantRepository
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.PermitTransfer
import com.hospital.escort.domain.PermitTransferRepository
import com.hospital.escort.domain.ScanDirection
import com.hospital.escort.domain.SyncAction
import com.hospital.escort.domain.TransferStatus
import com.hospital.escort.domain.WardCategory
import com.hospital.escort.domain.WardPolicy
import com.hospital.escort.domain.WardPolicyRepository
import com.hospital.escort.sync.DownstreamSyncService
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 患者转科陪护证迁移(普通病房 -> 重症过渡病区)。
 *
 * 流程:
 *  1. startTransfer   暂停原陪护证(门禁停权), 通知家属重新确认新病区陪护规则;
 *                     迁移完成前旧证不得进入新病区, 仅可凭"必要交接"授权在限时窗口进出一次;
 *  2. familyConfirm   家属重新确认陪护规则;
 *  3. wardDecision    新病区护士审批:
 *                       允许 -> 原证作废、签发新病区陪护证, 迁移完成(MIGRATED);
 *                       不允许 -> 生成患者物品交接单与家属探视说明(ESCORT_DENIED);
 *  4. returnOldPermit 旧陪护证实体退回 -> 交接单签收, 陪护责任结束, 押金方可释放;
 *  5. releaseDeposit  旧证退回后释放押金(未退回一律拒绝, 防止家属仍持旧证在院内通行)。
 */
@Service
class PermitMigrationService(
    private val transferRepository: PermitTransferRepository,
    private val handoverRepository: BelongingsHandoverRepository,
    private val handoverGrantRepository: HandoverPassGrantRepository,
    private val caseRepository: EscortCaseRepository,
    private val permitRepository: EscortPermitRepository,
    private val applicationRepository: EscortApplicationRepository,
    private val wardPolicyRepository: WardPolicyRepository,
    private val caseService: EscortCaseService,
    private val permitService: PermitService,
    @Lazy private val syncService: DownstreamSyncService
) {

    /** 门禁隔离: 这些状态下旧证不得进入新病区 */
    private val blockingStatuses = listOf(
        TransferStatus.PENDING_FAMILY_CONFIRM,
        TransferStatus.PENDING_WARD_APPROVAL,
        TransferStatus.ESCORT_DENIED
    )

    @Transactional
    fun startTransfer(caseNo: String, req: StartTransferRequest): PermitTransfer {
        val escortCase = caseRepository.findByCaseNo(caseNo) ?: notFound("陪护记录不存在: $caseNo")
        if (escortCase.status != CaseStatus.ACTIVE) conflict("陪护记录已关闭, 不能办理转科迁移")
        val newPolicy = wardPolicyRepository.findById(req.toWardCode).orElse(null)
            ?: notFound("目标病区不存在: ${req.toWardCode}")
        if (newPolicy.wardCategory != WardCategory.ICU_TRANSITION) {
            conflict("目标病区[${req.toWardCode}]不是重症过渡病区, 普通病区间转科请使用 /api/wards/cases/{caseNo}/transfer")
        }
        val oldWard = escortCase.wardCode
        if (oldWard == req.toWardCode) conflict("患者已在该病区, 无需迁移")
        val open = transferRepository.findByCaseIdOrderByCreatedAtAsc(escortCase.id!!)
            .any { it.status in blockingStatuses }
        if (open) conflict("该患者已有进行中的陪护证迁移, 请先完成审批")

        // 1. 找当前有效陪护证(优先当前照护证), 暂停
        val permits = permitRepository.findByCaseIdOrderByIssuedAtAsc(escortCase.id!!)
        val oldPermit = permits.firstOrNull { it.id == escortCase.currentPermitId && it.status in resumableStatuses }
            ?: permits.lastOrNull { it.status in resumableStatuses }
            ?: conflict("患者没有可迁移的有效陪护证(ACTIVE/FROZEN)")
        if (oldPermit.status == PermitStatus.ACTIVE) {
            permitService.freezePermit(
                oldPermit.permitNo,
                "患者转入重症过渡病区 $oldWard -> ${req.toWardCode}, 原普通病房陪护权限不再适用, 暂停待迁移审批",
                req.operator
            )
        }
        oldPermit.transferSuspended = true
        permitRepository.save(oldPermit)

        // 2. 患者随转科落到新病区床位
        escortCase.wardCode = req.toWardCode
        if (req.bedNo != null) escortCase.bedNo = req.bedNo
        caseRepository.save(escortCase)

        // 3. 迁移单 + 家属规则告知
        val ruleNotice = buildFamilyRuleNotice(escortCase.patientInpatientNo, oldWard, newPolicy)
        val transfer = transferRepository.save(
            PermitTransfer(
                caseId = escortCase.id!!,
                transferNo = "TF-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
                oldWardCode = oldWard,
                newWardCode = req.toWardCode,
                bedNo = req.bedNo,
                oldPermitId = oldPermit.id,
                oldPermitNo = oldPermit.permitNo,
                escortName = oldPermit.escortName,
                escortIdNo = oldPermit.escortIdNo,
                escortPhone = oldPermit.escortPhone,
                status = TransferStatus.PENDING_FAMILY_CONFIRM,
                familyRuleNotice = ruleNotice
            )
        )

        caseService.recordEvent(escortCase.id!!, CaseEventType.PERMIT_TRANSFER_SUSPENDED, req.operator,
            "转入重症过渡病区: 原陪护证 ${oldPermit.permitNo} 暂停, 迁移完成前不得进入 ${req.toWardCode}",
            "$oldWard -> ${req.toWardCode}${req.bedNo?.let { " 床位 $it" } ?: ""}", "TRANSFER", transfer.id)
        caseService.recordEvent(escortCase.id!!, CaseEventType.TRANSFER_RULES_NOTIFIED, req.operator,
            "已通知家属(${oldPermit.escortName})重新确认重症过渡病区陪护规则", ruleNotice, "TRANSFER", transfer.id)
        caseService.notifyWard(oldWard, escortCase.id, oldPermit.id, "PERMIT_TRANSFERRED_OUT",
            "患者[${escortCase.patientInpatientNo}]转入重症过渡病区 ${req.toWardCode}, 原陪护证 ${oldPermit.permitNo} 已暂停")
        caseService.notifyWard(req.toWardCode, escortCase.id, oldPermit.id, "MIGRATION_PENDING_FAMILY",
            "患者[${escortCase.patientInpatientNo}]由 $oldWard 转入, 陪护人 ${oldPermit.escortName} 的旧证已暂停, " +
                    "待家属重新确认陪护规则后由本病区护士审批; 迁移完成前旧证不得进入本病区")
        caseService.notifyWard(req.toWardCode, escortCase.id, oldPermit.id, "FAMILY_RULE_TO_CONFIRM",
            "请转交/告知家属 ${oldPermit.escortName}(${oldPermit.escortPhone ?: "无电话"}): $ruleNotice")

        // 4. 必要交接临时通行(限时一次)
        if (req.issueHandoverPass) {
            grantHandoverPass(transfer, req.operator, req.handoverPassMinutes.coerceIn(15, 240), req.handoverPassFrom)
        }
        return transfer
    }

    @Transactional
    fun familyConfirm(transferNo: String, req: FamilyRuleConfirmRequest): PermitTransfer {
        val transfer = getTransferEntity(transferNo)
        if (transfer.status != TransferStatus.PENDING_FAMILY_CONFIRM) {
            conflict("迁移单当前状态(${transfer.status})无需家属确认")
        }
        if (req.acknowledged != true) badRequest("家属必须确认已阅读并同意重症过渡病区陪护规则")
        transfer.familyRuleAcknowledged = true
        transfer.familyConfirmedAt = Instant.now()
        transfer.familyConfirmedBy = req.confirmedBy
        transfer.familyNote = req.note
        transfer.status = TransferStatus.PENDING_WARD_APPROVAL
        val saved = transferRepository.save(transfer)
        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_FAMILY_CONFIRMED, req.confirmedBy,
            "家属 ${transfer.escortName} 已重新确认重症过渡病区陪护规则", req.note, "TRANSFER", transfer.id)
        caseService.notifyWard(transfer.newWardCode, transfer.caseId, transfer.oldPermitId, "MIGRATION_PENDING_APPROVAL",
            "患者转入陪护迁移: 家属 ${transfer.escortName} 已确认规则, 请新病区护士审批是否允许继续陪护(${transfer.transferNo})")
        return saved
    }

    @Transactional
    fun wardDecision(transferNo: String, req: TransferDecisionRequest): PermitTransfer {
        val transfer = getTransferEntity(transferNo)
        if (transfer.status != TransferStatus.PENDING_WARD_APPROVAL) {
            conflict("迁移单当前状态(${transfer.status})不能审批")
        }
        val newPolicy = wardPolicyRepository.findById(transfer.newWardCode).orElseThrow()
        val escortCase = caseService.getById(transfer.caseId)
        val oldPermit = transfer.oldPermitId?.let { permitService.getById(it) }
            ?: conflict("迁移单缺少原陪护证")

        if (req.approved != true) {
            return denyEscort(transfer, oldPermit, newPolicy, req)
        }

        if (newPolicy.lockdown) conflict("重症过渡病区封闭管理中, 不能批准陪护")
        val oldApplication = applicationRepository.findById(oldPermit.applicationId).orElseThrow()

        // 新病区陪护申请(同一陪护人, 按新病区规则重新审批后发证)
        val periodEnd = req.newPeriodEnd ?: oldPermit.validUntil
        if (!periodEnd.isAfter(Instant.now())) badRequest("新陪护有效期截止时间必须晚于当前时间")
        val application = EscortApplication(
            caseId = transfer.caseId,
            applicationNo = "EA-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
            applyType = ApplicationType.REPLACEMENT,
            patientInpatientNo = escortCase.patientInpatientNo,
            wardCode = transfer.newWardCode,
            escortName = oldPermit.escortName,
            escortIdNo = oldPermit.escortIdNo,
            escortPhone = oldPermit.escortPhone,
            relationToPatient = oldApplication.relationToPatient,
            healthCommitment = true,
            screeningType = oldApplication.screeningType,
            screeningValidUntil = oldApplication.screeningValidUntil,
            periodStart = Instant.now(),
            periodEnd = periodEnd,
            needNightCare = oldApplication.needNightCare,
            status = ApplicationStatus.APPROVED,
            submittedAt = Instant.now(),
            decidedAt = Instant.now(),
            decidedBy = req.decidedBy,
            decisionNote = "转科迁移新病区审批: ${req.note ?: ""}",
            replacedApplicationId = oldApplication.id
        )
        val savedApplication = applicationRepository.save(application)

        // 旧证作废(REPLACED)并同步门禁
        if (oldPermit.status != PermitStatus.RETURNED && oldPermit.status != PermitStatus.REVOKED) {
            oldPermit.status = PermitStatus.REPLACED
            oldPermit.transferSuspended = false
            permitService.saveDirect(oldPermit)
            caseService.recordEvent(transfer.caseId, CaseEventType.PERMIT_REPLACED, req.decidedBy,
                "转科迁移: 旧陪护证 ${oldPermit.permitNo} 作废, 由重症过渡病区新证接替", null, "PERMIT", oldPermit.id)
            syncService.syncPermit(oldPermit, escortCase.caseNo, SyncAction.REVOKE)
        }

        // 签发新病区陪护证(同时刷新"当前照护人"快照并通知新病区)
        val newPermit = permitService.issuePermit(savedApplication, escortCase, req.decidedBy, req.approveNightCare)

        transfer.status = TransferStatus.MIGRATED
        transfer.newApplicationId = savedApplication.id
        transfer.newPermitId = newPermit.id
        transfer.decidedBy = req.decidedBy
        transfer.decidedAt = Instant.now()
        transfer.decisionNote = req.note
        val saved = transferRepository.save(transfer)

        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_WARD_APPROVED, req.decidedBy,
            "新病区 ${transfer.newWardCode} 批准继续陪护: 新证 ${newPermit.permitNo}(${newPermit.escortName}), 迁移完成",
            req.note, "TRANSFER", transfer.id)
        caseService.notifyWard(transfer.oldWardCode, transfer.caseId, newPermit.id, "PERMIT_TRANSFER_COMPLETED",
            "患者[${escortCase.patientInpatientNo}]陪护迁移完成, 旧证 ${oldPermit.permitNo} 已作废, 现由 ${newPermit.escortName} 持新病区陪护证照护")
        caseService.notifyWard(transfer.newWardCode, transfer.caseId, newPermit.id, "PERMIT_TRANSFER_COMPLETED",
            "本病区已批准陪护: 患者[${escortCase.patientInpatientNo}]现由 ${newPermit.escortName} 照护, 新证 ${newPermit.permitNo}; 旧实体卡请收回")
        return saved
    }

    private fun denyEscort(
        transfer: PermitTransfer,
        oldPermit: EscortPermit,
        newPolicy: WardPolicy,
        req: TransferDecisionRequest
    ): PermitTransfer {
        val escortCase = caseService.getById(transfer.caseId)
        transfer.status = TransferStatus.ESCORT_DENIED
        transfer.decidedBy = req.decidedBy
        transfer.decidedAt = Instant.now()
        transfer.decisionNote = req.note
        transferRepository.save(transfer)

        // 患者在新病区暂无登记陪护人
        caseService.updateCurrentEscort(escortCase, null, null, null, null, req.decidedBy,
            "重症过渡病区 ${transfer.newWardCode} 不允许陪护, 待家属按探视说明探视")

        // 生成物品交接单 + 家属探视说明
        val items = req.handoverItems?.takeIf { it.isNotEmpty() } ?: defaultHandoverItems(oldPermit.permitNo)
        val visitInstruction = buildVisitInstruction(newPolicy, req.visitNote)
        val handover = handoverRepository.save(
            BelongingsHandover(
                transferId = transfer.id!!,
                caseId = transfer.caseId,
                docNo = "BH-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
                patientInpatientNo = escortCase.patientInpatientNo,
                oldWardCode = transfer.oldWardCode,
                newWardCode = transfer.newWardCode,
                escortName = transfer.escortName,
                items = items.joinToString("\n"),
                visitInstruction = visitInstruction,
                generatedBy = req.decidedBy
            )
        )

        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_WARD_DENIED, req.decidedBy,
            "新病区 ${transfer.newWardCode} 不允许继续陪护, 原证 ${oldPermit.permitNo} 维持暂停, 待退回",
            req.note, "TRANSFER", transfer.id)
        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_HANDOVER_PREPARED, req.decidedBy,
            "已生成物品交接与家属探视说明 ${handover.docNo}",
            "物品清单:\n${items.joinToString("\n")}\n探视说明:\n$visitInstruction",
            "HANDOVER", handover.id)
        caseService.notifyWard(transfer.newWardCode, transfer.caseId, oldPermit.id, "MIGRATION_ESCORT_DENIED",
            "新病区不允许陪护: 患者[${escortCase.patientInpatientNo}]原陪护 ${oldPermit.escortName} 须交回旧证 ${oldPermit.permitNo}, " +
                    "已生成交接单 ${handover.docNo}; 旧证退回前不得释放押金")
        caseService.notifyWard(transfer.newWardCode, transfer.caseId, oldPermit.id, "FAMILY_VISIT_INSTRUCTION",
            "请转交家属 ${transfer.escortName}(${transfer.escortPhone ?: "无电话"}): 病区不安排陪护, 可按探视说明探视。$visitInstruction")
        return transfer
    }

    @Transactional
    fun returnOldPermit(transferNo: String, req: TransferOldPermitReturnRequest): PermitTransfer {
        val transfer = getTransferEntity(transferNo)
        if (transfer.status != TransferStatus.MIGRATED && transfer.status != TransferStatus.ESCORT_DENIED) {
            conflict("迁移单当前状态(${transfer.status})不能退回旧证")
        }
        if (transfer.oldPermitReturnedAt != null) conflict("旧陪护证已退回, 请勿重复操作")
        val oldPermit = transfer.oldPermitId?.let { permitService.getById(it) }
            ?: conflict("迁移单缺少原陪护证")

        when (oldPermit.status) {
            // 批准迁移路径: 旧证已作废(REPLACED), 此处仅登记实体卡物理退回
            PermitStatus.REPLACED -> {
                oldPermit.returnedAt = Instant.now()
                oldPermit.returnedBy = req.returnedBy
                oldPermit.returnChannel = req.channel
                oldPermit.transferSuspended = false
                permitService.saveDirect(oldPermit)
            }
            // 不允许陪护路径: 旧证仍为 FROZEN, 走正常退证(RETURNED + 门禁 REVOKE 同步)
            PermitStatus.ACTIVE, PermitStatus.FROZEN, PermitStatus.EXPIRED ->
                permitService.returnPermit(
                    oldPermit.permitNo, req.returnedBy, req.channel,
                    "转科迁移旧实体卡退回: ${req.note ?: ""}"
                ).also { it.transferSuspended = false; permitService.saveDirect(it) }
            else -> conflict("旧陪护证当前状态(${oldPermit.status})无法退回")
        }

        transfer.oldPermitReturnedAt = Instant.now()
        transfer.oldPermitReturnedBy = req.returnedBy

        val handover = handoverRepository.findFirstByTransferIdOrderByIdDesc(transfer.id!!)
        if (transfer.status == TransferStatus.ESCORT_DENIED) {
            // 旧证实体退回 = 家属不再持旧证在院内通行, 陪护责任结束, 押金具备释放条件
            transfer.escortLiabilityEnded = true
            if (handover != null) {
                handover.oldPermitReturned = true
                handover.oldPermitReturnedAt = transfer.oldPermitReturnedAt
                handover.receivedBy = req.receivedBy ?: req.returnedBy
                handover.depositReleasable = true
                handoverRepository.save(handover)
            }
        }
        val saved = transferRepository.save(transfer)

        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_OLD_PERMIT_RETURNED, req.returnedBy,
            "转科迁移旧陪护证 ${oldPermit.permitNo} 实体卡已退回(渠道=${req.channel})",
            req.note, "TRANSFER", transfer.id)
        if (transfer.status == TransferStatus.ESCORT_DENIED) {
            caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_OLD_PERMIT_RETURNED, req.returnedBy,
                "旧证已退回: 陪护责任结束, 押金具备释放条件(待释放确认)", null, "TRANSFER", transfer.id)
            caseService.notifyWard(transfer.newWardCode, transfer.caseId, oldPermit.id, "MIGRATION_PERMIT_RETURNED",
                "家属 ${transfer.escortName} 已交回旧证 ${oldPermit.permitNo}, 陪护责任结束; 交接单 ${handover?.docNo ?: "-"} 已签收, 可办理押金释放")
        } else {
            caseService.notifyWard(transfer.newWardCode, transfer.caseId, oldPermit.id, "MIGRATION_PERMIT_RETURNED",
                "迁移换新: 家属 ${transfer.escortName} 已交回旧证 ${oldPermit.permitNo}")
        }
        return saved
    }

    @Transactional
    fun releaseDeposit(transferNo: String, req: TransferDepositReleaseRequest): PermitTransfer {
        val transfer = getTransferEntity(transferNo)
        if (transfer.status != TransferStatus.ESCORT_DENIED) {
            conflict("仅新病区不允许陪护的迁移单办理押金释放, 当前状态: ${transfer.status}")
        }
        if (transfer.oldPermitReturnedAt == null) {
            // 关键闸门: 家属仍可能持旧证在医院内通行, 禁止释放押金/结束责任
            conflict("旧陪护证尚未实体退回, 不得释放押金; 请先收回旧证 ${transfer.oldPermitNo}")
        }
        if (transfer.depositReleased) conflict("押金已释放, 请勿重复操作")
        transfer.depositReleased = true
        transfer.depositReleasedAt = Instant.now()
        transfer.depositReleasedBy = req.operator
        transfer.releaseNote = req.note
        val saved = transferRepository.save(transfer)

        caseService.recordEvent(transfer.caseId, CaseEventType.TRANSFER_DEPOSIT_RELEASED, req.operator,
            "旧证已退回, 释放陪护押金并结清陪护责任", req.note, "TRANSFER", transfer.id)
        caseService.notifyWard(transfer.newWardCode, transfer.caseId, null, "MIGRATION_DEPOSIT_RELEASED",
            "患者[${caseService.getById(transfer.caseId).patientInpatientNo}]旧证已退回, 家属 ${transfer.escortName} 的押金已释放, 陪护责任终止")
        return saved
    }

    @Transactional
    fun grantHandoverPass(transferNo: String, req: HandoverPassGrantRequest): HandoverPassGrant {
        val transfer = getTransferEntity(transferNo)
        return grantHandoverPass(transfer, req.operator, req.validMinutes.coerceIn(15, 240), req.validFrom)
    }

    private fun grantHandoverPass(
        transfer: PermitTransfer,
        operator: String,
        validMinutes: Long,
        validFrom: Instant? = null
    ): HandoverPassGrant {
        if (transfer.status != TransferStatus.PENDING_FAMILY_CONFIRM &&
            transfer.status != TransferStatus.PENDING_WARD_APPROVAL &&
            transfer.status != TransferStatus.ESCORT_DENIED
        ) {
            conflict("迁移审批已结束(${transfer.status}), 不再发放交接通行")
        }
        val oldPermitId = transfer.oldPermitId ?: conflict("迁移单缺少原陪护证")
        val from = validFrom ?: Instant.now()
        val grant = handoverGrantRepository.save(
            HandoverPassGrant(
                transferId = transfer.id!!,
                caseId = transfer.caseId,
                permitId = oldPermitId,
                wardCode = transfer.newWardCode,
                validFrom = from,
                validUntil = from.plus(validMinutes, ChronoUnit.MINUTES),
                grantedBy = operator
            )
        )
        caseService.recordEvent(transfer.caseId, CaseEventType.HANDOVER_PASS_GRANTED, operator,
            "发放必要交接临时通行: 旧证 ${transfer.oldPermitNo} 限 ${transfer.newWardCode} 缓冲交接点, " +
                    "${grant.validFrom}~${grant.validUntil} 一进一出",
            null, "TRANSFER", transfer.id)
        caseService.notifyWard(transfer.newWardCode, transfer.caseId, oldPermitId, "HANDOVER_PASS_GRANTED",
            "家属 ${transfer.escortName} 持旧证的必要交接通行已发放(至 $validMinutes 分钟内, 限交接点一进一出), 请做好交接登记")
        return grant
    }

    // ---------- 门禁使用 ----------

    @Transactional(readOnly = true)
    fun findBlockingTransfer(permitId: Long): PermitTransfer? =
        transferRepository.findFirstByOldPermitIdAndStatusInOrderByCreatedAtDesc(permitId, blockingStatuses)

    @Transactional(readOnly = true)
    fun findValidHandoverGrant(permitId: Long, wardCode: String, at: Instant): HandoverPassGrant? =
        handoverGrantRepository
            .findFirstByPermitIdAndWardCodeAndValidFromLessThanEqualAndValidUntilGreaterThanOrderByIdDesc(
                permitId, wardCode, at, at
            )

    @Transactional
    fun markGrantUsed(grant: HandoverPassGrant, direction: ScanDirection) {
        when (direction) {
            ScanDirection.IN -> grant.usedIn = true
            ScanDirection.OUT -> grant.usedOut = true
        }
        handoverGrantRepository.save(grant)
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    fun getTransferEntity(transferNo: String): PermitTransfer =
        transferRepository.findByTransferNo(transferNo) ?: notFound("迁移单不存在: $transferNo")

    @Transactional(readOnly = true)
    fun getTransfer(transferNo: String): TransferDto =
        toTransferDto(getTransferEntity(transferNo))

    @Transactional(readOnly = true)
    fun listByCase(caseId: Long): List<PermitTransfer> =
        transferRepository.findByCaseIdOrderByCreatedAtAsc(caseId)

    @Transactional(readOnly = true)
    fun listHandoversByCase(caseId: Long): List<BelongingsHandover> =
        handoverRepository.findByCaseIdOrderByCreatedAtAsc(caseId)

    @Transactional(readOnly = true)
    fun pendingWardApprovals(wardCode: String): Long =
        transferRepository.countByNewWardCodeAndStatus(wardCode, TransferStatus.PENDING_WARD_APPROVAL)

    // ---------- 文案与映射 ----------

    fun toTransferDto(transfer: PermitTransfer): TransferDto {
        val caseNo = caseService.getCaseNoById(transfer.caseId)
        val newPermitNo = transfer.newPermitId?.let { permitService.getById(it).permitNo }
        return TransferDto(
            transferNo = transfer.transferNo, caseNo = caseNo,
            oldWardCode = transfer.oldWardCode, newWardCode = transfer.newWardCode, bedNo = transfer.bedNo,
            oldPermitNo = transfer.oldPermitNo, escortName = transfer.escortName, escortIdNo = transfer.escortIdNo, escortPhone = transfer.escortPhone,
            status = transfer.status.name, familyRuleNotice = transfer.familyRuleNotice,
            familyRuleAcknowledged = transfer.familyRuleAcknowledged,
            familyConfirmedAt = transfer.familyConfirmedAt, familyConfirmedBy = transfer.familyConfirmedBy, familyNote = transfer.familyNote,
            newPermitNo = newPermitNo, decidedBy = transfer.decidedBy, decidedAt = transfer.decidedAt, decisionNote = transfer.decisionNote,
            oldPermitReturnedAt = transfer.oldPermitReturnedAt, depositReleased = transfer.depositReleased,
            depositReleasedAt = transfer.depositReleasedAt, escortLiabilityEnded = transfer.escortLiabilityEnded, createdAt = transfer.createdAt
        )
    }

    fun toHandoverDto(handover: BelongingsHandover): HandoverDto {
        val caseNo = caseService.getCaseNoById(handover.caseId)
        val transferNo = transferRepository.findById(handover.transferId).orElseThrow().transferNo
        return HandoverDto(
            docNo = handover.docNo, transferNo = transferNo, caseNo = caseNo,
            patientInpatientNo = handover.patientInpatientNo, oldWardCode = handover.oldWardCode, newWardCode = handover.newWardCode,
            escortName = handover.escortName, items = handover.items.split("\n"), visitInstruction = handover.visitInstruction,
            oldPermitReturned = handover.oldPermitReturned, oldPermitReturnedAt = handover.oldPermitReturnedAt,
            receivedBy = handover.receivedBy, depositReleasable = handover.depositReleasable,
            generatedBy = handover.generatedBy, createdAt = handover.createdAt
        )
    }

    fun toGrantDto(grant: HandoverPassGrant): HandoverPassGrantDto {
        val transferNo = transferRepository.findById(grant.transferId).orElseThrow().transferNo
        val permitNo = permitService.getById(grant.permitId).permitNo
        return HandoverPassGrantDto(
            transferNo = transferNo, permitNo = permitNo, wardCode = grant.wardCode,
            validFrom = grant.validFrom, validUntil = grant.validUntil, usedIn = grant.usedIn, usedOut = grant.usedOut,
            grantedBy = grant.grantedBy
        )
    }

    private fun buildFamilyRuleNotice(patientNo: String, oldWard: String, policy: WardPolicy): String = buildString {
        append("您的家属[住院号 $patientNo]已由 $oldWard 转入重症过渡病区 ${policy.wardCode}(${policy.wardName})。")
        append("原普通病房陪护证已暂停, 重症过渡病区执行更严格的陪护规则: ")
        append("陪护/探视时段 ${policy.allowVisitStart}-${policy.allowVisitEnd}; ")
        append("每位患者陪护上限 ${policy.maxEscortsPerPatient} 人; ")
        append("夜间陪护${if (policy.nightEscortRequiresApproval) "须经病区专项审批" else "按病区安排"}; ")
        append("进入须遵守感染控制要求(防护用品/手卫生/健康申报), 患者治疗期间陪护人须在指定区域等候。")
        append("请重新确认以上规则; 确认后由新病区护士审批是否允许继续陪护。")
        append("审批完成前, 原陪护证不能进入本病区, 仅可凭限时交接通行完成必要交接。")
    }

    private fun buildVisitInstruction(policy: WardPolicy, extra: String?): String = buildString {
        append("重症过渡病区 ${policy.wardCode} 实行限制性探视: ")
        append("探视时间 ${policy.allowVisitStart}-${policy.allowVisitEnd}, 家属凭本人身份证在护士站登记后按指引探视; ")
        append("不得留宿、不得替代护理操作; 每次探视限 1 人, 请遵守感控要求(防护用品/手卫生); ")
        append("患者病情变化或需补充物品, 由病区护士站电话联系/代收转交。")
        if (!extra.isNullOrBlank()) append(" 病区补充说明: $extra")
    }

    private fun defaultHandoverItems(oldPermitNo: String): List<String> = listOf(
        "患者随身物品(衣物/餐具/洗漱用品): 已当面清点并随患者交接",
        "患者药品/医疗器械: 由新病区护士核对接收",
        "贵重物品(手机/现金/证件/首饰): 由家属带回, 不随患者进入重症过渡病区",
        "原病区资料/检查胶片: 已随病历交接",
        "旧陪护证(实体卡 $oldPermitNo): 家属交回新病区护士站, 凭退回回执释放押金"
    )

    companion object {
        private val resumableStatuses = setOf(PermitStatus.ACTIVE, PermitStatus.FROZEN)
    }
}
