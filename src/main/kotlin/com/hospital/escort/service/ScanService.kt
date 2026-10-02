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
 *  - 患者转科迁移: 迁移完成前旧证不得进入新病区, 仅允许限时"必要交接"通行与正常离开
 */
@Service
class ScanService(
    private val scanRepository: ScanRecordRepository,
    private val permitRepository: EscortPermitRepository,
    private val wardPolicyRepository: WardPolicyRepository,
    private val caseService: EscortCaseService,
    private val violationService: ViolationService,
    private val migrationService: PermitMigrationService,
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
                wardLockdown = false, handoverPass = false, denyReason = "无效陪护证", violations = emptyList())
        }

        val escortCase = caseService.getById(permit.caseId)
        val (targetWard, wardExplicit) = resolveTargetWard(req, permit)
        val policy = wardPolicyRepository.findById(targetWard).orElse(null)
            ?: wardPolicyRepository.findById(permit.wardCode).orElse(null)
        val violations = mutableListOf<String>()
        var denyReason: String? = null
        var handoverPass = false

        // 0. 转科迁移隔离(优先于证件状态): 迁移未完成, 旧证(仍处暂停态)只允许"必要交接"进入新病区, 离开一律放行
        val blockingTransfer = migrationService.findBlockingTransfer(permit.id!!)
            ?.takeIf { permit.status == PermitStatus.FROZEN }
        val transferBlocking = blockingTransfer != null
        val leaveDuringTransfer = transferBlocking && req.direction == ScanDirection.OUT
        if (transferBlocking && req.direction == ScanDirection.OUT) {
            migrationService.findValidHandoverGrant(permit.id!!, targetWard, scannedAt)
                ?.takeIf { !it.usedOut }
                ?.let {
                    handoverPass = true
                    migrationService.markGrantUsed(it, ScanDirection.OUT)
                }
        }

        // 1. 证件状态(迁移期离开不拦截)
        if (!leaveDuringTransfer) {
            when (permit.status) {
                PermitStatus.FROZEN -> denyReason = "陪护证已冻结: ${permit.freezeReason ?: ""}"
                PermitStatus.EXPIRED -> denyReason = "陪护证已过期"
                PermitStatus.RETURNED -> denyReason = "陪护证已退证"
                PermitStatus.REVOKED -> denyReason = "陪护证已吊销"
                PermitStatus.REPLACED -> denyReason = "陪护证已被替换, 请使用新证"
                PermitStatus.ACTIVE -> {}
            }
        }

        // 1b. 迁移期进入新病区: 仅凭有效交接授权放行一次
        if (transferBlocking && req.direction == ScanDirection.IN) {
            val transfer = blockingTransfer!!
            if (targetWard == transfer.newWardCode) {
                val grant = migrationService.findValidHandoverGrant(permit.id!!, targetWard, scannedAt)
                if (grant != null && !grant.usedIn) {
                    handoverPass = true
                    migrationService.markGrantUsed(grant, ScanDirection.IN)
                    denyReason = null
                } else {
                    denyReason = "患者转科迁移未完成, 旧陪护证不得进入 $targetWard; 仅允许有效交接授权时间窗内的必要交接"
                    violations += record(permit, null, ViolationType.TRANSFER_ZONE_BREACH, targetWard,
                        "陪护人 ${permit.escortName} 持旧证 ${permit.permitNo} 在迁移审批期间试图进入新病区 $targetWard(${req.gateLocation})")
                }
            } else {
                denyReason = "患者已转入 ${transfer.newWardCode}, 原陪护证在迁移审批期间暂停使用"
            }
        }

        // 1c. 非迁移场景: ACTIVE 证只能进入证件所属病区, 不得跨病区使用
        if (!transferBlocking && denyReason == null && wardExplicit &&
            targetWard != permit.wardCode && req.direction == ScanDirection.IN
        ) {
            denyReason = "陪护证 ${permit.permitNo} 属于病区 ${permit.wardCode}, 不能进入 $targetWard"
        }

        // 2. 有效期
        if (denyReason == null && !handoverPass && scannedAt.isAfter(permit.validUntil)) {
            denyReason = "超出陪护证有效期"
        }

        // 3. 病区封闭管理
        val wardLockdown = policy?.lockdown == true && targetWard == policy.wardCode
        if (wardLockdown && denyReason == null && !handoverPass) {
            denyReason = "病区封闭管理中, 暂停进出: ${policy?.lockdownReason ?: ""}"
        }

        // 4. 外出限制(护士要求限制外出; 迁移交接离开不受此限)
        if (denyReason == null && !handoverPass && permit.exitRestricted && req.direction == ScanDirection.OUT) {
            denyReason = "护士站已限制外出: ${permit.exitRestrictReason ?: ""}"
            violations += record(permit, null, ViolationType.EXIT_BREACH, permit.wardCode,
                "陪护人 ${permit.escortName} 在限制外出期间尝试外出")
        }

        // 5. 时段核验: 探视时间外进入且未批夜间陪护 -> 超出允许时段(必要交接通行除外)
        val localTime = LocalTime.ofInstant(scannedAt, zone)
        val inVisitWindow = policy != null && !localTime.isBefore(policy.allowVisitStart) && localTime.isBefore(policy.allowVisitEnd)
        val outOfHours = !inVisitWindow
        if (!handoverPass) {
            if (outOfHours && !permit.nightCareAllowed && req.direction == ScanDirection.IN && denyReason == null) {
                denyReason = "超出允许探视时段(${policy?.allowVisitStart}-${policy?.allowVisitEnd}), 且未批准夜间陪护"
                violations += record(permit, null, ViolationType.NIGHT_ABNORMAL, targetWard,
                    "陪护人 ${permit.escortName} 于 $localTime 非允许时段试图进入")
            } else if (outOfHours && !permit.nightCareAllowed) {
                // 夜间外出也记录异常(不阻止外出, 但留痕)
                violations += record(permit, null, ViolationType.NIGHT_ABNORMAL, targetWard,
                    "陪护人 ${permit.escortName} 于 $localTime 非允许时段${if (req.direction == ScanDirection.OUT) "离开" else "进出"}")
            }
        }

        // 6. 更换陪护人: 扫码人身份证与证件持有人不一致(交接通行同样核验持证人本人)
        val escortChanged = req.scannedIdNo != null && req.scannedIdNo != permit.escortIdNo
        if (escortChanged) {
            if (denyReason == null) denyReason = "扫码人与证件持有人不一致, 疑似更换陪护人未登记"
            violations += record(permit, null, ViolationType.ESCORT_UNREGISTERED_CHANGE, targetWard,
                "证件持有人 ${permit.escortName}(${permit.escortIdNo}), 实际扫码人身份证 ${req.scannedIdNo}")
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
                violations += record(permit, null, ViolationType.MULTI_PERMIT_SHARED, targetWard,
                    "身份证 ${req.scannedIdNo} 在2小时内使用多张陪护证(含 ${permit.permitNo})")
            }
        }

        val result = if (denyReason == null) ScanResult.ALLOWED else ScanResult.DENIED
        val rec = scanRepository.save(
            ScanRecord(
                caseId = permit.caseId, permitId = permit.id, permitNo = permit.permitNo,
                wardCode = targetWard, direction = req.direction!!, gateLocation = req.gateLocation,
                scannedAt = scannedAt, scannedIdNo = req.scannedIdNo ?: permit.escortIdNo,
                result = result, outOfHours = outOfHours && !handoverPass, escortChanged = escortChanged,
                multiPermitShared = multiShared, wardLockdown = wardLockdown, handoverPass = handoverPass,
                denyReason = denyReason
            )
        )

        // 归档到陪护记录事件流
        caseService.recordEvent(
            permit.caseId,
            if (req.direction == ScanDirection.IN) CaseEventType.SCAN_IN else CaseEventType.SCAN_OUT,
            permit.escortName,
            "${if (req.direction == ScanDirection.IN) "进入" else "离开"}[${req.gateLocation}] ${result.name}" +
                    (if (handoverPass) "(必要交接通行)" else "") +
                    (denyReason?.let { ": $it" } ?: ""),
            "证号=${permit.permitNo}, 超时=${outOfHours && !handoverPass}, 换人=$escortChanged, 多证=$multiShared, 封闭=$wardLockdown, 交接通行=$handoverPass",
            "SCAN", rec.id
        )
        if (result == ScanResult.DENIED || escortChanged || multiShared) {
            caseService.notifyWard(targetWard, permit.caseId, permit.id,
                if (transferBlocking) "MIGRATION_ZONE_BREACH" else "SCAN_ABNORMAL",
                "进出异常: ${permit.escortName} 于 ${req.gateLocation} ${result.name}${denyReason?.let { " ($it)" } ?: ""}")
        }

        return ScanResultDto(
            result = result.name, permitNo = permit.permitNo, caseNo = escortCase.caseNo,
            wardCode = targetWard, gateLocation = req.gateLocation, scannedAt = scannedAt,
            outOfHours = outOfHours && !handoverPass, escortChanged = escortChanged, multiPermitShared = multiShared,
            wardLockdown = wardLockdown, handoverPass = handoverPass,
            denyReason = denyReason, violations = violations
        )
    }

    /**
     * 解析本次扫码针对的病区:
     *  - 请求显式带 wardCode 时以其为准;
     *  - 否则门禁位置以"病区码-"开头(如 ITU1-缓冲交接闸机)时取前缀;
     *  - 都无法判定时回落到证件所属病区(与历史行为一致)。
     */
    private fun resolveTargetWard(req: ScanRequest, permit: EscortPermit): Pair<String, Boolean> {
        req.wardCode?.let { return it to true }
        val prefix = req.gateLocation.substringBefore('-')
        if (prefix != req.gateLocation && wardPolicyRepository.findById(prefix).isPresent) {
            return prefix to true
        }
        return permit.wardCode to false
    }

    private fun record(permit: EscortPermit, scanId: Long?, type: ViolationType, wardCode: String, detail: String): String {
        violationService.recordViolation(permit.caseId, permit.id, scanId, wardCode, type, detail, "system")
        return type.name
    }
}
