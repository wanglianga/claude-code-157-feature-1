package com.hospital.escort.service

import com.hospital.escort.common.conflict
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.ViolationRecord
import com.hospital.escort.domain.ViolationRecordRepository
import com.hospital.escort.domain.ViolationStatus
import com.hospital.escort.domain.ViolationType
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 违规记录: 进出异常(扫码时自动产生)与人工上报(安保冒用、护士限制等)。
 * 所有违规都挂在同一条陪护记录上。
 */
@Service
class ViolationService(
    private val violationRepository: ViolationRecordRepository,
    @Lazy private val caseService: EscortCaseService,
    @Lazy private val permitService: PermitService
) {

    @Transactional
    fun recordViolation(
        caseId: Long?,
        permitId: Long?,
        scanRecordId: Long?,
        wardCode: String?,
        type: ViolationType,
        detail: String?,
        reportedBy: String?
    ): ViolationRecord {
        val saved = violationRepository.save(
            ViolationRecord(
                caseId = caseId, permitId = permitId, scanRecordId = scanRecordId,
                wardCode = wardCode, type = type, detail = detail, reportedBy = reportedBy,
                status = ViolationStatus.OPEN
            )
        )
        if (caseId != null) {
            caseService.recordEvent(caseId, CaseEventType.VIOLATION_REPORTED, reportedBy,
                "违规记录[${type.name}]: ${detail ?: ""}", null, "VIOLATION", saved.id)
        }
        if (wardCode != null) {
            caseService.notifyWard(wardCode, caseId, permitId, "VIOLATION",
                "违规告警[${type.name}]: ${detail ?: ""}")
        }
        return saved
    }

    /** 安保/护士人工上报违规; 冒用类违规会联动吊销陪护证 */
    @Transactional
    fun reportViolation(permitNo: String?, type: ViolationType, detail: String?, reportedBy: String, actualUserIdNo: String?): ViolationRecord {
        val permit = permitNo?.let { permitService.getByToken(it) }
        val caseId = permit?.caseId
        val wardCode = permit?.wardCode
        val fullDetail = buildString {
            append(detail ?: "")
            if (actualUserIdNo != null) append(" | 实际使用人证件: $actualUserIdNo")
        }
        val violation = recordViolation(caseId, permit?.id, null, wardCode, type, fullDetail.ifBlank { null }, reportedBy)

        if (type == ViolationType.SECURITY_IMPERSONATION && permit != null) {
            // 冒用属于严重违规: 立即吊销证件, 门禁同步失效
            permitService.revokePermit(permit.permitNo, "安保发现冒用: ${detail ?: ""}", reportedBy)
        }
        return violation
    }

    @Transactional
    fun resolveViolation(violationId: Long, resolvedBy: String, note: String?, confirmed: Boolean): ViolationRecord {
        val violation = violationRepository.findById(violationId).orElse(null)
            ?: notFound("违规记录不存在: $violationId")
        if (violation.status != ViolationStatus.OPEN) conflict("违规记录已处理, 当前状态: ${violation.status}")
        violation.status = if (confirmed) ViolationStatus.CONFIRMED else ViolationStatus.DISMISSED
        violation.resolvedAt = Instant.now()
        violation.resolvedBy = resolvedBy
        violation.resolutionNote = note
        val saved = violationRepository.save(violation)
        if (saved.caseId != null) {
            caseService.recordEvent(saved.caseId!!, CaseEventType.VIOLATION_RESOLVED, resolvedBy,
                "违规[${saved.type.name}]处理为 ${saved.status.name}", note, "VIOLATION", saved.id)
        }
        return saved
    }
}
