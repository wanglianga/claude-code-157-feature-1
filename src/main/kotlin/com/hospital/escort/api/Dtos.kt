package com.hospital.escort.api

import com.hospital.escort.domain.ApplicationType
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.ScreeningType
import com.hospital.escort.domain.ViolationType

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.Instant

// ---------- 申请 ----------

data class ApplyRequest(
    @field:NotBlank(message = "住院号不能为空")
    val patientInpatientNo: String = "",
    @field:NotBlank(message = "病区不能为空")
    val wardCode: String = "",
    val bedNo: String? = null,
    @field:NotBlank(message = "陪护人姓名不能为空")
    val escortName: String = "",
    @field:NotBlank(message = "陪护人身份证号不能为空")
    @field:Pattern(regexp = "^[0-9A-Za-z]{6,24}$", message = "身份证号格式不正确")
    val escortIdNo: String = "",
    @field:NotBlank(message = "联系方式不能为空")
    @field:Pattern(regexp = "^[0-9+\\-]{7,20}$", message = "联系方式格式不正确")
    val escortPhone: String = "",
    val relationToPatient: String? = null,
    @field:NotNull(message = "必须确认健康承诺")
    val healthCommitment: Boolean? = null,
    @field:NotNull(message = "筛查类型不能为空")
    val screeningType: ScreeningType? = null,
    val screeningValidUntil: Instant? = null,
    @field:NotNull(message = "陪护开始时间不能为空")
    val periodStart: Instant? = null,
    @field:NotNull(message = "陪护结束时间不能为空")
    val periodEnd: Instant? = null,
    val needNightCare: Boolean = false
)

data class DecisionRequest(
    @field:NotNull(message = "审批结果不能为空")
    val approved: Boolean? = null,
    @field:NotBlank(message = "审批人工号不能为空")
    val decidedBy: String = "",
    val note: String? = null,
    /** 夜间陪护审批(病区策略要求时必须显式批准) */
    val approveNightCare: Boolean = false
)

data class ApplicationDto(
    val applicationNo: String,
    val caseNo: String,
    val applyType: ApplicationType,
    val patientInpatientNo: String,
    val wardCode: String,
    val escortName: String,
    val escortIdNo: String,
    val escortPhone: String,
    val relationToPatient: String?,
    val healthCommitment: Boolean,
    val screeningType: ScreeningType,
    val screeningValidUntil: Instant?,
    val periodStart: Instant,
    val periodEnd: Instant,
    val needNightCare: Boolean,
    val status: String,
    val submittedAt: Instant,
    val decidedAt: Instant?,
    val decidedBy: String?,
    val decisionNote: String?
)

// ---------- 陪护证 ----------

data class PermitDto(
    val permitNo: String,
    val caseNo: String,
    val wardCode: String,
    val qrToken: String,
    val escortName: String,
    val escortIdNo: String,
    val escortPhone: String,
    val validFrom: Instant,
    val validUntil: Instant,
    val nightCareAllowed: Boolean,
    val status: PermitStatus,
    val lockdownFrozen: Boolean,
    val transferSuspended: Boolean = false,
    val exitRestricted: Boolean,
    val exitRestrictReason: String?,
    val issuedAt: Instant,
    val returnedAt: Instant?,
    val syncedTargets: List<String>
)

// ---------- 扫码进出 ----------

data class ScanRequest(
    @field:NotBlank(message = "陪护证编号/二维码token不能为空")
    val token: String = "",
    /** 实际扫码/被核验人身份证; 缺省视为证件持证人本人 */
    val scannedIdNo: String? = null,
    @field:NotNull(message = "进出方向不能为空")
    val direction: com.hospital.escort.domain.ScanDirection? = null,
    @field:NotBlank(message = "门禁位置不能为空")
    val gateLocation: String = "",
    /** 扫码点所属病区; 转科迁移期用于区分旧病区(必要交接放行)与新病区(禁止旧证进入)。缺省取证件病区 */
    val wardCode: String? = null,
    val scannedAt: Instant? = null
)

data class ScanResultDto(
    val result: String,
    val permitNo: String?,
    val caseNo: String?,
    val wardCode: String?,
    val gateLocation: String,
    val scannedAt: Instant,
    val outOfHours: Boolean,
    val escortChanged: Boolean,
    val multiPermitShared: Boolean,
    val wardLockdown: Boolean,
    /** 转科迁移期的必要交接放行(旧病区/离开) */
    val handoverPass: Boolean = false,
    /** 持旧证闯入新病区 */
    val transferBreach: Boolean = false,
    val denyReason: String?,
    val violations: List<String>
)

// ---------- 替换 ----------

data class ReplacementRequest(
    @field:NotBlank(message = "原陪护证编号不能为空")
    val oldPermitNo: String = "",
    val reason: com.hospital.escort.domain.ReplacementReason = com.hospital.escort.domain.ReplacementReason.OTHER,
    val detail: String? = null,
    // 新陪护人申请字段
    val escortName: String = "",
    val escortIdNo: String = "",
    val escortPhone: String = "",
    val relationToPatient: String? = null,
    val healthCommitment: Boolean = true,
    val screeningType: ScreeningType = ScreeningType.NONE,
    val screeningValidUntil: Instant? = null,
    val periodStart: Instant? = null,
    val periodEnd: Instant? = null,
    val needNightCare: Boolean = false,
    /** true=护士站直接审批通过并出新证(走正常审批时为 false, 后续用 /decide) */
    val autoApprove: Boolean = false,
    val decidedBy: String? = null
)

// ---------- 违规上报(安保/护士) ----------

data class ViolationReportRequest(
    val permitNo: String? = null,
    @field:NotNull(message = "违规类型不能为空")
    val type: ViolationType? = null,
    val detail: String? = null,
    @field:NotBlank(message = "上报人不能为空")
    val reportedBy: String = "",
    /** 安保发现冒用等场景: 实际使用人身份证 */
    val actualUserIdNo: String? = null
)

data class ViolationResolveRequest(
    @field:NotBlank(message = "处理人不能为空")
    val resolvedBy: String = "",
    val note: String? = null,
    val confirmed: Boolean = true
)

// ---------- 退证 ----------

data class ReturnRequest(
    @field:NotBlank(message = "退证人不能为空")
    val returnedBy: String = "",
    val channel: String = "WARD_DESK",
    val note: String? = null
)

data class FreezeRequest(
    @field:NotBlank(message = "操作人不能为空")
    val operator: String = "",
    @field:NotBlank(message = "冻结原因不能为空")
    val reason: String = ""
)

data class UnfreezeRequest(
    @field:NotBlank(message = "操作人不能为空")
    val operator: String = ""
)

data class RevokeRequest(
    @field:NotBlank(message = "操作人不能为空")
    val operator: String = "",
    @field:NotBlank(message = "吊销原因不能为空")
    val reason: String = ""
)

data class ReplacementDecisionRequest(
    @field:NotBlank(message = "操作人不能为空")
    val decidedBy: String = "",
    @field:NotNull(message = "审批结果不能为空")
    val approved: Boolean? = null,
    val note: String? = null
)

data class CloseCaseRequest(
    @field:NotBlank(message = "操作人不能为空")
    val operator: String = "",
    val reason: String = "陪护结束, 记录归档关闭"
)

// ---------- 病区管理 ----------

data class WardPolicyDto(
    val wardCode: String,
    val wardName: String,
    val allowVisitStart: String,
    val allowVisitEnd: String,
    val maxEscortsPerPatient: Int,
    val nightEscortRequiresApproval: Boolean,
    val lockdown: Boolean,
    val lockdownReason: String?
)

data class WardLockdownRequest(
    val lockdown: Boolean = true,
    val reason: String? = null,
    @field:NotBlank(message = "操作人不能为空")
    val operator: String = ""
)

data class ExitRestrictionRequest(
    val restricted: Boolean = true,
    val reason: String? = null,
    @field:NotBlank(message = "操作护士不能为空")
    val operator: String = ""
)

data class TransferRequest(
    @field:NotBlank(message = "目标病区不能为空")
    val toWardCode: String = "",
    val bedNo: String? = null,
    @field:NotBlank(message = "操作人不能为空")
    val operator: String = "",
    /** 转科时是否同步冻结原陪护证(默认冻结, 需在新病区重新申请) */
    val freezePermit: Boolean = true,
    /** true=走陪护证迁移审批工作流(暂停原证/家属确认/新病区护士审批); 普通病区调转保持 false */
    val escortMigration: Boolean = false,
    val reason: String? = null
)

// ---------- 转科陪护证迁移 ----------

data class TransferStartRequest(
    @field:NotBlank(message = "目标病区不能为空")
    val toWardCode: String = "",
    val bedNo: String? = null,
    val reason: String? = null,
    @field:NotBlank(message = "操作人不能为空")
    val operator: String = ""
)

data class FamilyConfirmRequest(
    @field:NotBlank(message = "确认人不能为空")
    val confirmedBy: String = "",
    /** 家属必须显式确认已阅读并接受新病区陪护规则 */
    @field:NotNull(message = "必须确认新病区陪护规则(ackRules)")
    val ackRules: Boolean? = null,
    val note: String? = null,
    // 以下为空时默认沿用旧证陪护人信息与筛查/期限
    val escortName: String? = null,
    val escortIdNo: String? = null,
    val escortPhone: String? = null,
    val relationToPatient: String? = null,
    val screeningType: ScreeningType? = null,
    val screeningValidUntil: Instant? = null,
    val periodEnd: Instant? = null,
    val needNightCare: Boolean? = null
)

data class TransferDecisionRequest(
    @field:NotBlank(message = "审批护士不能为空")
    val decidedBy: String = "",
    @field:NotNull(message = "审批结果不能为空")
    val approved: Boolean? = null,
    val note: String? = null,
    val approveNightCare: Boolean = false
)

data class TransferOldPermitReturnRequest(
    @field:NotBlank(message = "退证人不能为空")
    val returnedBy: String = "",
    val channel: String = "NEW_WARD_DESK",
    val note: String? = null
)

data class HandoverDocDto(
    val id: Long,
    val docNo: String,
    val docType: String,
    val wardCode: String,
    val title: String,
    val content: String,
    val createdBy: String,
    val createdAt: Instant
)

data class TransferDto(
    val transferNo: String,
    val caseNo: String,
    val patientInpatientNo: String,
    val fromWardCode: String,
    val toWardCode: String,
    val bedNo: String?,
    val status: String,
    val reason: String?,
    val initiatedBy: String,
    val initiatedAt: Instant,
    val familyConfirmedAt: Instant?,
    val familyConfirmedBy: String?,
    val decidedAt: Instant?,
    val decidedBy: String?,
    val decisionNote: String?,
    val oldPermitNo: String?,
    val newPermitNo: String?,
    val oldPermitReturnedAt: Instant?,
    val depositReleased: Boolean,
    val depositReleasedAt: Instant?,
    val depositReleaseNote: String?,
    val liabilityEnded: Boolean,
    val liabilityEndedAt: Instant?,
    val completedAt: Instant?,
    val documents: List<HandoverDocDto>
)

data class DepositDisputeRequest(
    @field:NotBlank(message = "发起人不能为空")
    val raisedBy: String = "",
    val note: String? = null
)

// ---------- 查询 ----------

data class CaseArchiveDto(
    val caseNo: String,
    val patientInpatientNo: String,
    val wardCode: String,
    val status: String,
    val currentEscortName: String?,
    val currentEscortPhone: String?,
    val applications: List<ApplicationDto>,
    val permits: List<PermitDto>,
    val scans: List<ScanRecordDto>,
    val replacements: List<ReplacementDto>,
    val transfers: List<TransferDto>,
    val violations: List<ViolationDto>,
    val events: List<EventDto>,
    val createdAt: Instant,
    val closedAt: Instant?
)

data class ScanRecordDto(
    val id: Long,
    val permitNo: String?,
    val direction: String,
    val gateLocation: String,
    val scannedAt: Instant,
    val scannedIdNo: String?,
    val result: String,
    val outOfHours: Boolean,
    val escortChanged: Boolean,
    val multiPermitShared: Boolean,
    val wardLockdown: Boolean,
    val handoverPass: Boolean = false,
    val transferBreach: Boolean = false,
    val denyReason: String?
)

data class ReplacementDto(
    val id: Long,
    val reason: String,
    val detail: String?,
    val status: String,
    val oldPermitNo: String?,
    val newPermitNo: String?,
    val requestedAt: Instant,
    val decidedAt: Instant?,
    val decidedBy: String?
)

data class ViolationDto(
    val id: Long,
    val type: String,
    val detail: String?,
    val reportedBy: String?,
    val status: String,
    val createdAt: Instant,
    val resolvedAt: Instant?,
    val resolutionNote: String?
)

data class EventDto(
    val eventType: String,
    val actor: String?,
    val summary: String,
    val detail: String?,
    val refType: String?,
    val refId: Long?,
    val createdAt: Instant
)

data class WardNoticeDto(
    val id: Long,
    val type: String,
    val message: String,
    val read: Boolean,
    val createdAt: Instant
)

// ---------- 管理看板 ----------

data class WardDashboardDto(
    val wardCode: String,
    val lockdown: Boolean,
    val activeEscortCount: Long,
    val activePatientCount: Long,
    val todayEntryCount: Long,
    val todayExitCount: Long,
    val nightAbnormalCount: Long,
    val outOfHoursCount: Long,
    val violationCount: Long,
    val violationsByType: Map<String, Long>,
    val expiredNotReturnedCount: Long,
    val pendingApplications: Long,
    val avgReturnHours: Double?,
    val openViolations: List<ViolationDto>
)
