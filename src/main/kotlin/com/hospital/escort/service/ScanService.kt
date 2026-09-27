package com.hospital.escort.service

import com.hospital.escort.api.ScanRequest
import com.hospital.escort.api.ScanResultDto
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.EscortPermit
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.ScanDirection
import com.hospital.escort.domain.ScanRecord
import com.hospital.escort.domain.ScanRecordRepository
import com.hospital.escort.domain.ScanResult
import com.hospital.escort.domain.ViolationType
import com.hospital.escort.domain.WardPolicyRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 进出扫码核验: 记录每次进出, 并实时检测
 *  - 超出允许时段(探视时间外且未批夜间陪护)
 *  - 更换陪护人(扫码人与证件持有人不一致)
 *  - 多证共用(同一人在时间窗内扫了多张不同陪护证)
 *  - 病区封闭 / 证件冻结 / 过期 / 外出限制
 */
@Service
class ScanService(
    private val scanRepository: ScanRecordRepository,
    private val permitRepository: EscortPermitRepository,
    private val wardPolicyRepository: WardPolicyRepository,
    private val caseService: EscortCaseService,
    private val violationService: ViolationService,
    @Value("\${escort.zone-id}") private val zoneId: String
) {
    private val zone: ZoneId get() = ZoneId.of(zoneId)

    @Transactional
    fun scan(req: ScanRequest): ScanResultDto {
        val scannedAt = req.scannedAt ?: Instant.now()
        val permit = permitRepository.findByPermitNo(req.token) ?: permitRepository.findByQrToken(req.token)

        if (permit == null) {
            // 无法识别的证: 也记录, 便于安保排查
            val rec = scanRepository.save(
                ScanRecord(
                    permitNo = req.token, direction = req.direction!!, gateLocation = req.gateLocation,
                    scannedAt = scannedAt, scannedIdNo = req.scannedIdNo,
                    result = ScanResult.DENIED, denyReason = "无效陪护证"
                )
            )
            return ScanResultDto("DENIED", req.token, null, null, req.gateLocation, scannedAt,
                outOfHours = false, escortChanged = false, multiPermitShared = false,
                wardLockdown = false, denyReason = "无效陪护证", violations = emptyList())
        }

        val policy = wardPolicyRepository.findById(permit.wardCode).orElse(null)
        val violations = mutableListOf<String>()
        var denyReason: String? = null

        // 1. 证件状态
        when (permit.status) {
            PermitStatus.FROZEN -> denyReason = "陪护证已冻结: ${permit.freezeReason ?: ""}"
            PermitStatus.EXPIRED -> denyReason = "陪护证已过期"
            PermitStatus.RETURNED -> denyReason = "陪护证已退证"
            PermitStatus.REVOKED -> denyReason = "陪护证已吊销"
            PermitStatus.REPLACED -> denyReason = "陪护证已被替换, 请使用新证"
            PermitStatus.ACTIVE -> {}
        }

        // 2. 有效期
        if (denyReason == null && scannedAt.isAfter(permit.validUntil)) {
            denyReason = "超出陪护证有效期"
        }

        // 3. 病区封闭管理
        val wardLockdown = policy?.lockdown == true
        if (wardLockdown && denyReason == null) {
            denyReason = "病区封闭管理中, 暂停进出: ${policy?.lockdownReason ?: ""}"
        }

        // 4. 外出限制(护士要求限制外出)
        if (denyReason == null && permit.exitRestricted && req.direction == ScanDirection.OUT) {
            denyReason = "护士站已限制外出: ${permit.exitRestrictReason ?: ""}"
            violations += record(permit, null, ViolationType.EXIT_BREACH,
                "陪护人 ${permit.escortName} 在限制外出期间尝试外出", "system")
        }

        // 5. 时段核验: 探视时间外进入且未批夜间陪护 -> 超出允许时段
        val localTime = LocalTime.ofInstant(scannedAt, zone)
        val inVisitWindow = policy != null && !localTime.isBefore(policy.allowVisitStart) && localTime.isBefore(policy.allowVisitEnd)
        val outOfHours = !inVisitWindow
        if (outOfHours && !permit.nightCareAllowed && req.direction == ScanDirection.IN && denyReason == null) {
            denyReason = "超出允许探视时段(${policy?.allowVisitStart}-${policy?.allowVisitEnd}), 且未批准夜间陪护"
            violations += record(permit, null, ViolationType.NIGHT_ABNORMAL,
                "陪护人 ${permit.escortName} 于 $localTime 非允许时段试图进入", "system")
        } else if (outOfHours && !permit.nightCareAllowed) {
            // 夜间外出也记录异常(不阻止外出, 但留痕)
            violations += record(permit, null, ViolationType.NIGHT_ABNORMAL,
                "陪护人 ${permit.escortName} 于 $localTime 非允许时段${if (req.direction == ScanDirection.OUT) "离开" else "进出"}", "system")
        }

        // 6. 更换陪护人: 扫码人身份证与证件持有人不一致
        val escortChanged = req.scannedIdNo != null && req.scannedIdNo != permit.escortIdNo
        if (escortChanged) {
            if (denyReason == null) denyReason = "扫码人与证件持有人不一致, 疑似更换陪护人未登记"
            violations += record(permit, null, ViolationType.ESCORT_UNREGISTERED_CHANGE,
                "证件持有人 ${permit.escortName}(${permit.escortIdNo}), 实际扫码人身份证 ${req.scannedIdNo}", "system")
        }

        // 7. 多证共用: 同一扫码人在时间窗内扫了多张不同陪护证
        var multiShared = false
        if (req.scannedIdNo != null) {
            val windowStart = scannedAt.minus(Duration.ofHours(2))
            val distinct = scanRepository.countDistinctPermitsByIdNoInWindow(req.scannedIdNo, windowStart, scannedAt)
            val usesOtherPermit = distinct > 0 && scanRepository.findFirstByScannedIdNoOrderByScannedAtDesc(req.scannedIdNo)
                ?.let { it.permitNo != null && it.permitNo != permit.permitNo } == true
            if (distinct >= 1 && usesOtherPermit) {
                multiShared = true
                violations += record(permit, null, ViolationType.MULTI_PERMIT_SHARED,
                    "身份证 ${req.scannedIdNo} 在2小时内使用多张陪护证(含 ${permit.permitNo})", "system")
            }
        }

        val result = if (denyReason == null) ScanResult.ALLOWED else ScanResult.DENIED
        val rec = scanRepository.save(
            ScanRecord(
                caseId = permit.caseId, permitId = permit.id, permitNo = permit.permitNo,
                wardCode = permit.wardCode, direction = req.direction!!, gateLocation = req.gateLocation,
                scannedAt = scannedAt, scannedIdNo = req.scannedIdNo ?: permit.escortIdNo,
                result = result, outOfHours = outOfHours, escortChanged = escortChanged,
                multiPermitShared = multiShared, wardLockdown = wardLockdown, denyReason = denyReason
            )
        )

        // 归档到陪护记录事件流
        val escortCase = caseService.getById(permit.caseId)
        caseService.recordEvent(
            permit.caseId,
            if (req.direction == ScanDirection.IN) CaseEventType.SCAN_IN else CaseEventType.SCAN_OUT,
            permit.escortName,
            "${if (req.direction == ScanDirection.IN) "进入" else "离开"}[${req.gateLocation}] ${result.name}" +
                    (denyReason?.let { ": $it" } ?: ""),
            "证号=${permit.permitNo}, 超时=$outOfHours, 换人=$escortChanged, 多证=$multiShared, 封闭=$wardLockdown",
            "SCAN", rec.id
        )
        if (result == ScanResult.DENIED || escortChanged || multiShared) {
            caseService.notifyWard(permit.wardCode, permit.caseId, permit.id, "SCAN_ABNORMAL",
                "进出异常: ${permit.escortName} 于 ${req.gateLocation} ${result.name}${denyReason?.let { " ($it)" } ?: ""}")
        }

        return ScanResultDto(
            result = result.name, permitNo = permit.permitNo, caseNo = escortCase.caseNo,
            wardCode = permit.wardCode, gateLocation = req.gateLocation, scannedAt = scannedAt,
            outOfHours = outOfHours, escortChanged = escortChanged, multiPermitShared = multiShared,
            wardLockdown = wardLockdown, denyReason = denyReason, violations = violations
        )
    }

    private fun record(permit: EscortPermit, scanId: Long?, type: ViolationType, detail: String, by: String): String {
        violationService.recordViolation(permit.caseId, permit.id, scanId, permit.wardCode, type, detail, by)
        return type.name
    }
}
