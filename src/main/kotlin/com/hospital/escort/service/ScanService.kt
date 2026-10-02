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
import com.hospital.escort.domain.WardTransferRepository
import com.hospital.escort.service.WardMigrationService.Companion.OPEN_STATUSES
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
    private val transferRepository: WardTransferRepository,
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

        // 0. 转科迁移期门禁管控: 旧证已暂停, 迁移完成前不得进入新病区, 仅放行必要交接
        val openTransfer = if (permit.status == PermitStatus.FROZEN && permit.transferSuspended) {
            transferRepository.findFirstByOldPermitIdAndStatusIn(permit.id!!, OPEN_STATUSES)
        } else null
        val scanWard = req.wardCode ?: permit.wardCode
        if (openTransfer != null) {
            return handleTransferGate(
                permit = permit, req = req, scannedAt = scannedAt, scanWard = scanWard,
                transfer = openTransfer, direction = req.direction!!
            )
        }

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

    /**
     * 转科迁移期门禁(原证已暂停):
     *  - 进入新病区: 一律拒绝并记 TRANSFER_BREACH 违规, 旧证不得直接进入新病区;
     *  - 其余动作(离开、返回原病区办理交接): 仅放行必要交接, 记录 handoverPass, 不再恢复陪护权限。
     */
    private fun handleTransferGate(
        permit: EscortPermit,
        req: ScanRequest,
        scannedAt: Instant,
        scanWard: String,
        transfer: com.hospital.escort.domain.WardTransfer,
        direction: ScanDirection
    ): ScanResultDto {
        val enteringNewWard = direction == ScanDirection.IN && scanWard == transfer.toWardCode
        val denyReason: String?
        var handoverPass = false
        var transferBreach = false
        val violationTypes = mutableListOf<String>()

        if (enteringNewWard) {
            denyReason = "患者已转科, 原陪护证权限暂停, 新病区陪护迁移尚未完成(${transfer.status}), 旧证不得进入新病区"
            transferBreach = true
            violationTypes += violationService.recordViolation(
                caseId = permit.caseId, permitId = permit.id, scanRecordId = null,
                wardCode = transfer.toWardCode, type = ViolationType.TRANSFER_BREACH,
                detail = "陪护人 ${permit.escortName} 持转科前旧证 ${permit.permitNo} 试图进入新病区 ${transfer.toWardCode}(迁移单 ${transfer.transferNo}, 状态 ${transfer.status})",
                reportedBy = "system"
            ).let { ViolationType.TRANSFER_BREACH.name }
        } else {
            // 必要交接: 离开任意病区(OUT)或进入原病区(IN) — 放行但不恢复陪护权限
            denyReason = null
            handoverPass = true
        }

        val rec = scanRepository.save(
            ScanRecord(
                caseId = permit.caseId, permitId = permit.id, permitNo = permit.permitNo,
                wardCode = scanWard, direction = direction, gateLocation = req.gateLocation,
                scannedAt = scannedAt, scannedIdNo = req.scannedIdNo ?: permit.escortIdNo,
                result = if (denyReason == null) ScanResult.ALLOWED else ScanResult.DENIED,
                wardLockdown = false, handoverPass = handoverPass, transferBreach = transferBreach,
                denyReason = denyReason
            )
        )
        val escortCase = caseService.getById(permit.caseId)
        caseService.recordEvent(
            permit.caseId,
            if (direction == ScanDirection.IN) CaseEventType.SCAN_IN else CaseEventType.SCAN_OUT,
            permit.escortName,
            if (denyReason != null)
                "迁移期进入新病区被拒[${req.gateLocation}]: $denyReason"
            else
                "迁移期必要交接放行[${req.gateLocation}] 方向=$direction 扫码病区=$scanWard, 旧证不恢复陪护权限",
            "证号=${permit.permitNo}, 迁移单=${transfer.transferNo}, handoverPass=$handoverPass, transferBreach=$transferBreach",
            "SCAN", rec.id
        )
        if (transferBreach) {
            caseService.notifyWard(transfer.toWardCode, permit.caseId, permit.id, "TRANSFER_BREACH",
                "陪护人 ${permit.escortName} 持旧证 ${permit.permitNo} 试图进入新病区, 已拒绝并记录违规(迁移单 ${transfer.transferNo})")
        }
        return ScanResultDto(
            result = if (denyReason == null) ScanResult.ALLOWED.name else ScanResult.DENIED.name,
            permitNo = permit.permitNo, caseNo = escortCase.caseNo,
            wardCode = scanWard, gateLocation = req.gateLocation, scannedAt = scannedAt,
            outOfHours = false, escortChanged = false, multiPermitShared = false,
            wardLockdown = false, handoverPass = handoverPass, transferBreach = transferBreach,
            denyReason = denyReason, violations = violationTypes
        )
    }

    private fun record(permit: EscortPermit, scanId: Long?, type: ViolationType, detail: String, by: String): String {
        violationService.recordViolation(permit.caseId, permit.id, scanId, permit.wardCode, type, detail, by)
        return type.name
    }
}
