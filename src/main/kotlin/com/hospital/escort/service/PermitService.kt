package com.hospital.escort.service

import com.hospital.escort.common.conflict
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.EscortApplication
import com.hospital.escort.domain.EscortCase
import com.hospital.escort.domain.EscortPermit
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.SyncAction
import com.hospital.escort.domain.ViolationType
import com.hospital.escort.sync.DownstreamSyncService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 电子陪护证生命周期: 签发 -> (冻结/解冻/限制外出) -> 退证/过期/吊销/被替换。
 * 每次状态变化都同步到门禁/病区扫码点/安保巡查, 并归档到陪护记录。
 */
@Service
class PermitService(
    private val permitRepository: EscortPermitRepository,
    private val caseService: EscortCaseService,
    private val syncService: DownstreamSyncService,
    @Lazy private val violationService: ViolationService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun issuePermit(
        application: EscortApplication,
        escortCase: EscortCase,
        operator: String,
        nightCareApproved: Boolean
    ): EscortPermit {
        val permit = EscortPermit(
            caseId = escortCase.id!!,
            applicationId = application.id!!,
            permitNo = "EP-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
            qrToken = "QR" + UUID.randomUUID().toString().replace("-", "").substring(0, 22),
            wardCode = application.wardCode,
            escortName = application.escortName,
            escortIdNo = application.escortIdNo,
            escortPhone = application.escortPhone,
            validFrom = application.periodStart,
            validUntil = application.periodEnd,
            nightCareAllowed = application.needNightCare && nightCareApproved,
            status = PermitStatus.ACTIVE
        )
        val saved = permitRepository.save(permit)
        caseService.recordEvent(
            escortCase.id!!, CaseEventType.PERMIT_ISSUED, operator,
            "签发电子陪护证 ${saved.permitNo}: ${saved.escortName}, 有效期 ${saved.validFrom}~${saved.validUntil}",
            "夜间陪护=${saved.nightCareAllowed}", "PERMIT", saved.id
        )
        caseService.updateCurrentEscort(
            escortCase, saved.id, saved.escortName, saved.escortIdNo, saved.escortPhone,
            operator, "签发陪护证 ${saved.permitNo}"
        )
        syncService.syncPermit(saved, escortCase.caseNo, SyncAction.ISSUE)
        return saved
    }

    @Transactional(readOnly = true)
    fun getByPermitNo(permitNo: String): EscortPermit =
        permitRepository.findByPermitNo(permitNo) ?: notFound("陪护证不存在: $permitNo")

    @Transactional
    fun saveDirect(permit: EscortPermit): EscortPermit = permitRepository.save(permit)

    @Transactional(readOnly = true)
    fun getById(permitId: Long): EscortPermit =
        permitRepository.findById(permitId).orElse(null) ?: notFound("陪护证不存在: id=$permitId")

    @Transactional(readOnly = true)
    fun getByToken(token: String): EscortPermit? =
        permitRepository.findByPermitNo(token) ?: permitRepository.findByQrToken(token)

    @Transactional
    fun freezePermit(permitNo: String, reason: String, operator: String, lockdown: Boolean = false): EscortPermit {
        val permit = getByPermitNo(permitNo)
        if (permit.status != PermitStatus.ACTIVE) conflict("只有有效(ACTIVE)陪护证可以冻结, 当前: ${permit.status}")
        permit.status = PermitStatus.FROZEN
        permit.freezeReason = reason
        permit.frozenAt = Instant.now()
        permit.frozenBy = operator
        permit.lockdownFrozen = lockdown
        val saved = permitRepository.save(permit)
        val escortCase = caseService.getById(saved.caseId)
        caseService.recordEvent(
            saved.caseId, CaseEventType.PERMIT_FROZEN, operator,
            "陪护证 ${saved.permitNo} 被冻结: $reason", null, "PERMIT", saved.id
        )
        caseService.notifyWard(saved.wardCode, saved.caseId, saved.id, "PERMIT_FROZEN",
            "陪护证 ${saved.permitNo}(${saved.escortName})已冻结: $reason, 患者[${escortCase.patientInpatientNo}]暂由家属自行协调照护")
        syncService.syncPermit(saved, escortCase.caseNo, SyncAction.FREEZE)
        return saved
    }

    @Transactional
    fun unfreezePermit(permitNo: String, operator: String): EscortPermit {
        val permit = getByPermitNo(permitNo)
        if (permit.status != PermitStatus.FROZEN) conflict("陪护证未处于冻结状态, 当前: ${permit.status}")
        if (permit.transferSuspended) conflict("该证因患者转科暂停, 须完成迁移审批或撤销迁移后才能恢复, 不能直接解冻")
        permit.status = PermitStatus.ACTIVE
        permit.freezeReason = null
        permit.frozenAt = null
        permit.frozenBy = null
        permit.lockdownFrozen = false
        val saved = permitRepository.save(permit)
        val escortCase = caseService.getById(saved.caseId)
        caseService.recordEvent(saved.caseId, CaseEventType.PERMIT_UNFROZEN, operator,
            "陪护证 ${saved.permitNo} 解冻", null, "PERMIT", saved.id)
        caseService.notifyWard(saved.wardCode, saved.caseId, saved.id, "PERMIT_UNFROZEN",
            "陪护证 ${saved.permitNo}(${saved.escortName})已解冻, 可正常进出")
        syncService.syncPermit(saved, escortCase.caseNo, SyncAction.UNFREEZE)
        return saved
    }

    /**
     * 患者转科: 原陪护证权限不再适用, 暂停原证并同步门禁/扫码点/安保停权。
     * 迁移完成前旧证不得进入新病区, 门禁仅放行必要交接(见 ScanService)。
     * 状态置 FROZEN 并打 transferSuspended 标记, 与封闭冻结/普通冻结区分。
     */
    @Transactional
    fun suspendForTransfer(permit: EscortPermit, fromWard: String, toWard: String, operator: String): EscortPermit {
        if (permit.status != PermitStatus.ACTIVE) {
            conflict("只有有效(ACTIVE)陪护证可以随转科暂停, 当前: ${permit.status}")
        }
        permit.status = PermitStatus.FROZEN
        permit.transferSuspended = true
        permit.lockdownFrozen = false
        permit.freezeReason = "患者转科 $fromWard -> $toWard, 原病区陪护权限暂停, 待新病区审批"
        permit.frozenAt = Instant.now()
        permit.frozenBy = operator
        val saved = permitRepository.save(permit)
        val escortCase = caseService.getById(saved.caseId)
        caseService.recordEvent(
            saved.caseId, CaseEventType.PERMIT_FROZEN, operator,
            "陪护证 ${saved.permitNo} 因患者转科暂停: $fromWard -> $toWard, 待新病区审批迁移",
            "迁移期旧证不得进入新病区, 门禁仅放行必要交接", "PERMIT", saved.id
        )
        syncService.syncPermit(saved, escortCase.caseNo, SyncAction.FREEZE)
        return saved
    }

    /** 迁移撤销(患者未完成新病区审批即转回): 恢复原证 */
    @Transactional
    fun resumeAfterTransferCancelled(permit: EscortPermit, operator: String): EscortPermit {
        permit.status = PermitStatus.ACTIVE
        permit.transferSuspended = false
        permit.freezeReason = null
        permit.frozenAt = null
        permit.frozenBy = null
        val saved = permitRepository.save(permit)
        val escortCase = caseService.getById(saved.caseId)
        caseService.recordEvent(saved.caseId, CaseEventType.PERMIT_UNFROZEN, operator,
            "转科迁移撤销, 陪护证 ${saved.permitNo} 恢复原病区权限", null, "PERMIT", saved.id)
        syncService.syncPermit(saved, escortCase.caseNo, SyncAction.UNFREEZE)
        return saved
    }

    /** 退证: 陪护结束或提前终止, 证件回收并同步门禁失效 */
    @Transactional
    fun returnPermit(permitNo: String, returnedBy: String, channel: String, note: String?): EscortPermit {
        val permit = getByPermitNo(permitNo)
        if (permit.status != PermitStatus.ACTIVE && permit.status != PermitStatus.FROZEN && permit.status != PermitStatus.EXPIRED) {
            conflict("当前状态(${permit.status})不允许退证")
        }
        if (permit.transferSuspended) {
            conflict("该陪护证因患者转科暂停, 须通过转科迁移结算退回旧证(迁移单 old-permit-return), 退回后才释放押金、结束陪护责任")
        }
        permit.status = PermitStatus.RETURNED
        permit.returnedAt = Instant.now()
        permit.returnedBy = returnedBy
        permit.returnChannel = channel
        val saved = permitRepository.save(permit)
        val escortCase = caseService.getById(saved.caseId)
        caseService.recordEvent(saved.caseId, CaseEventType.PERMIT_RETURNED, returnedBy,
            "陪护证 ${saved.permitNo} 退证(渠道=$channel)", note, "PERMIT", saved.id)
        // 退证后患者当前无登记照护人
        if (escortCase.currentPermitId == saved.id) {
            caseService.updateCurrentEscort(escortCase, null, null, null, null, returnedBy,
                "陪护证 ${saved.permitNo} 已退证")
        }
        caseService.notifyWard(saved.wardCode, saved.caseId, saved.id, "PERMIT_RETURNED",
            "陪护证 ${saved.permitNo}(${saved.escortName})已退证, 患者[${escortCase.patientInpatientNo}]当前无登记陪护人")
        syncService.syncPermit(saved, escortCase.caseNo, SyncAction.REVOKE)
        return saved
    }

    /** 吊销: 安保确认冒用等严重违规后强制作废 */
    @Transactional
    fun revokePermit(permitNo: String, reason: String, operator: String): EscortPermit {
        val permit = getByPermitNo(permitNo)
        if (permit.status == PermitStatus.RETURNED || permit.status == PermitStatus.REVOKED) {
            conflict("陪护证已${permit.status}, 不能吊销")
        }
        permit.status = PermitStatus.REVOKED
        val saved = permitRepository.save(permit)
        val escortCase = caseService.getById(saved.caseId)
        caseService.recordEvent(saved.caseId, CaseEventType.PERMIT_REVOKED, operator,
            "陪护证 ${saved.permitNo} 被吊销: $reason", null, "PERMIT", saved.id)
        if (escortCase.currentPermitId == saved.id) {
            caseService.updateCurrentEscort(escortCase, null, null, null, null, operator,
                "陪护证 ${saved.permitNo} 被吊销: $reason")
        }
        caseService.notifyWard(saved.wardCode, saved.caseId, saved.id, "PERMIT_REVOKED",
            "陪护证 ${saved.permitNo}(${saved.escortName})已被吊销: $reason")
        syncService.syncPermit(saved, escortCase.caseNo, SyncAction.REVOKE)
        return saved
    }

    /** 过期扫描任务: 到期未退的证置为 EXPIRED 并记违规 */
    @Transactional
    fun expireOverduePermits(now: Instant = Instant.now()): Int {
        val overdue = permitRepository.findByStatusAndValidUntilBefore(PermitStatus.ACTIVE, now) +
                permitRepository.findByStatusAndValidUntilBefore(PermitStatus.FROZEN, now)
                    .filterNot { it.transferSuspended }
        overdue.forEach { permit ->
            permit.status = PermitStatus.EXPIRED
            permitRepository.save(permit)
            val escortCase = caseService.getById(permit.caseId)
            caseService.recordEvent(permit.caseId, CaseEventType.PERMIT_EXPIRED, "system",
                "陪护证 ${permit.permitNo} 已过期未退", "有效期至 ${permit.validUntil}", "PERMIT", permit.id)
            violationService.recordViolation(
                caseId = permit.caseId, permitId = permit.id, scanRecordId = null,
                wardCode = permit.wardCode, type = ViolationType.EXPIRED_NOT_RETURNED,
                detail = "陪护证 ${permit.permitNo} 于 ${permit.validUntil} 到期, 陪护人 ${permit.escortName} 未退证",
                reportedBy = "system"
            )
            if (escortCase.currentPermitId == permit.id) {
                caseService.updateCurrentEscort(escortCase, null, null, null, null, "system",
                    "陪护证 ${permit.permitNo} 过期未退")
            }
            caseService.notifyWard(permit.wardCode, permit.caseId, permit.id, "PERMIT_EXPIRED",
                "陪护证 ${permit.permitNo}(${permit.escortName})已过期未退, 请督促家属到护士站退证")
            syncService.syncPermit(permit, escortCase.caseNo, SyncAction.REVOKE)
        }
        if (overdue.isNotEmpty()) log.info("expired {} overdue permits", overdue.size)
        return overdue.size
    }
}
