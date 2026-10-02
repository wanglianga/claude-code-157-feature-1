package com.hospital.escort.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalTime

@Entity
@Table(name = "ward_policy")
class WardPolicy(
    @Id
    @Column(name = "ward_code")
    var wardCode: String = "",

    @Column(name = "ward_name", nullable = false)
    var wardName: String = "",

    @Column(name = "allow_visit_start", nullable = false)
    var allowVisitStart: LocalTime = LocalTime.of(8, 0),

    @Column(name = "allow_visit_end", nullable = false)
    var allowVisitEnd: LocalTime = LocalTime.of(20, 0),

    @Column(name = "max_escorts_per_patient", nullable = false)
    var maxEscortsPerPatient: Int = 1,

    @Column(name = "night_escort_requires_approval", nullable = false)
    var nightEscortRequiresApproval: Boolean = true,

    @Column(name = "lockdown", nullable = false)
    var lockdown: Boolean = false,

    @Column(name = "lockdown_reason")
    var lockdownReason: String? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

@Entity
@Table(name = "escort_case")
class EscortCase(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "case_no", nullable = false, unique = true)
    var caseNo: String = "",

    @Column(name = "patient_inpatient_no", nullable = false)
    var patientInpatientNo: String = "",

    @Column(name = "ward_code", nullable = false)
    var wardCode: String = "",

    @Column(name = "bed_no")
    var bedNo: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: CaseStatus = CaseStatus.ACTIVE,

    @Column(name = "current_permit_id")
    var currentPermitId: Long? = null,

    @Column(name = "current_escort_name")
    var currentEscortName: String? = null,

    @Column(name = "current_escort_id_no")
    var currentEscortIdNo: String? = null,

    @Column(name = "current_escort_phone")
    var currentEscortPhone: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "closed_at")
    var closedAt: Instant? = null
)

@Entity
@Table(name = "escort_application")
class EscortApplication(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "case_id", nullable = false)
    var caseId: Long = 0,

    @Column(name = "application_no", nullable = false, unique = true)
    var applicationNo: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "apply_type", nullable = false)
    var applyType: ApplicationType = ApplicationType.INITIAL,

    @Column(name = "patient_inpatient_no", nullable = false)
    var patientInpatientNo: String = "",

    @Column(name = "ward_code", nullable = false)
    var wardCode: String = "",

    @Column(name = "escort_name", nullable = false)
    var escortName: String = "",

    @Column(name = "escort_id_no", nullable = false)
    var escortIdNo: String = "",

    @Column(name = "escort_phone", nullable = false)
    var escortPhone: String = "",

    @Column(name = "relation_to_patient")
    var relationToPatient: String? = null,

    @Column(name = "health_commitment", nullable = false)
    var healthCommitment: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "screening_type", nullable = false)
    var screeningType: ScreeningType = ScreeningType.NONE,

    @Column(name = "screening_valid_until")
    var screeningValidUntil: Instant? = null,

    @Column(name = "period_start", nullable = false)
    var periodStart: Instant = Instant.now(),

    @Column(name = "period_end", nullable = false)
    var periodEnd: Instant = Instant.now(),

    @Column(name = "need_night_care", nullable = false)
    var needNightCare: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ApplicationStatus = ApplicationStatus.PENDING,

    @Column(name = "submitted_at", nullable = false)
    var submittedAt: Instant = Instant.now(),

    @Column(name = "decided_at")
    var decidedAt: Instant? = null,

    @Column(name = "decided_by")
    var decidedBy: String? = null,

    @Column(name = "decision_note")
    var decisionNote: String? = null,

    @Column(name = "replaced_application_id")
    var replacedApplicationId: Long? = null
)

@Entity
@Table(name = "escort_permit")
class EscortPermit(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "case_id", nullable = false)
    var caseId: Long = 0,

    @Column(name = "application_id", nullable = false)
    var applicationId: Long = 0,

    @Column(name = "permit_no", nullable = false, unique = true)
    var permitNo: String = "",

    @Column(name = "qr_token", nullable = false, unique = true)
    var qrToken: String = "",

    @Column(name = "ward_code", nullable = false)
    var wardCode: String = "",

    @Column(name = "escort_name", nullable = false)
    var escortName: String = "",

    @Column(name = "escort_id_no", nullable = false)
    var escortIdNo: String = "",

    @Column(name = "escort_phone", nullable = false)
    var escortPhone: String = "",

    @Column(name = "valid_from", nullable = false)
    var validFrom: Instant = Instant.now(),

    @Column(name = "valid_until", nullable = false)
    var validUntil: Instant = Instant.now(),

    @Column(name = "night_care_allowed", nullable = false)
    var nightCareAllowed: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: PermitStatus = PermitStatus.ACTIVE,

    @Column(name = "issued_at", nullable = false)
    var issuedAt: Instant = Instant.now(),

    @Column(name = "freeze_reason")
    var freezeReason: String? = null,

    @Column(name = "frozen_at")
    var frozenAt: Instant? = null,

    @Column(name = "frozen_by")
    var frozenBy: String? = null,

    @Column(name = "lockdown_frozen", nullable = false)
    var lockdownFrozen: Boolean = false,

    @Column(name = "exit_restricted", nullable = false)
    var exitRestricted: Boolean = false,

    @Column(name = "exit_restrict_reason")
    var exitRestrictReason: String? = null,

    @Column(name = "returned_at")
    var returnedAt: Instant? = null,

    @Column(name = "returned_by")
    var returnedBy: String? = null,

    @Column(name = "return_channel")
    var returnChannel: String? = null,

    /** 患者转科后原证权限暂停(迁移流程专用, 区别于冻结/封闭): 门禁停权, 等待迁移结论 */
    @Column(name = "transfer_suspended", nullable = false)
    var transferSuspended: Boolean = false,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
)

@Entity
@Table(name = "permit_sync")
class PermitSync(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "permit_id", nullable = false)
    var permitId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "target", nullable = false)
    var target: SyncTarget = SyncTarget.GATE,

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false)
    var action: SyncAction = SyncAction.ISSUE,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: SyncStatus = SyncStatus.PENDING,

    @Column(name = "payload")
    var payload: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "synced_at")
    var syncedAt: Instant? = null
)

@Entity
@Table(name = "replacement_record")
class ReplacementRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "case_id", nullable = false)
    var caseId: Long = 0,

    @Column(name = "old_permit_id")
    var oldPermitId: Long? = null,

    @Column(name = "new_application_id", nullable = false)
    var newApplicationId: Long = 0,

    @Column(name = "new_permit_id")
    var newPermitId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false)
    var reason: ReplacementReason = ReplacementReason.OTHER,

    @Column(name = "detail")
    var detail: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ReplacementStatus = ReplacementStatus.PENDING,

    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant = Instant.now(),

    @Column(name = "decided_at")
    var decidedAt: Instant? = null,

    @Column(name = "decided_by")
    var decidedBy: String? = null
)

@Entity
@Table(name = "scan_record")
class ScanRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "case_id")
    var caseId: Long? = null,

    @Column(name = "permit_id")
    var permitId: Long? = null,

    @Column(name = "permit_no")
    var permitNo: String? = null,

    @Column(name = "ward_code")
    var wardCode: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    var direction: ScanDirection = ScanDirection.IN,

    @Column(name = "gate_location", nullable = false)
    var gateLocation: String = "",

    @Column(name = "scanned_at", nullable = false)
    var scannedAt: Instant = Instant.now(),

    @Column(name = "scanned_id_no")
    var scannedIdNo: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false)
    var result: ScanResult = ScanResult.ALLOWED,

    @Column(name = "out_of_hours", nullable = false)
    var outOfHours: Boolean = false,

    @Column(name = "escort_changed", nullable = false)
    var escortChanged: Boolean = false,

    @Column(name = "multi_permit_shared", nullable = false)
    var multiPermitShared: Boolean = false,

    @Column(name = "ward_lockdown", nullable = false)
    var wardLockdown: Boolean = false,

    /** 转科迁移期间, 旧证仅被放行必要交接(离开/返回原病区) */
    @Column(name = "handover_pass", nullable = false)
    var handoverPass: Boolean = false,

    /** 转科迁移完成前持旧证闯入新病区 */
    @Column(name = "transfer_breach", nullable = false)
    var transferBreach: Boolean = false,

    @Column(name = "deny_reason")
    var denyReason: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

@Entity
@Table(name = "violation_record")
class ViolationRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "case_id")
    var caseId: Long? = null,

    @Column(name = "permit_id")
    var permitId: Long? = null,

    @Column(name = "scan_record_id")
    var scanRecordId: Long? = null,

    @Column(name = "ward_code")
    var wardCode: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    var type: ViolationType = ViolationType.ESCORT_UNREGISTERED_CHANGE,

    @Column(name = "detail")
    var detail: String? = null,

    @Column(name = "reported_by")
    var reportedBy: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: ViolationStatus = ViolationStatus.OPEN,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,

    @Column(name = "resolved_by")
    var resolvedBy: String? = null,

    @Column(name = "resolution_note")
    var resolutionNote: String? = null
)

@Entity
@Table(name = "case_event")
class CaseEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "case_id", nullable = false)
    var caseId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    var eventType: CaseEventType = CaseEventType.APPLICATION_SUBMITTED,

    @Column(name = "actor")
    var actor: String? = null,

    @Column(name = "summary", nullable = false)
    var summary: String = "",

    @Column(name = "detail")
    var detail: String? = null,

    @Column(name = "ref_type")
    var refType: String? = null,

    @Column(name = "ref_id")
    var refId: Long? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

@Entity
@Table(name = "ward_notice")
class WardNotice(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "ward_code", nullable = false)
    var wardCode: String = "",

    @Column(name = "case_id")
    var caseId: Long? = null,

    @Column(name = "permit_id")
    var permitId: Long? = null,

    @Column(name = "type", nullable = false)
    var type: String = "",

    @Column(name = "message", nullable = false)
    var message: String = "",

    @Column(name = "read", nullable = false)
    var read: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)

/**
 * 转科陪护证迁移单: 患者从普通病房转入重症过渡病区等场景,
 * 原证暂停 -> 家属确认新病区陪护规则 -> 新病区护士审批 -> 新证签发或旧证退回。
 */
@Entity
@Table(name = "ward_transfer")
class WardTransfer(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "transfer_no", nullable = false, unique = true)
    var transferNo: String = "",

    @Column(name = "case_id", nullable = false)
    var caseId: Long = 0,

    @Column(name = "patient_inpatient_no", nullable = false)
    var patientInpatientNo: String = "",

    @Column(name = "from_ward_code", nullable = false)
    var fromWardCode: String = "",

    @Column(name = "to_ward_code", nullable = false)
    var toWardCode: String = "",

    @Column(name = "from_bed_no")
    var fromBedNo: String? = null,

    @Column(name = "bed_no")
    var bedNo: String? = null,

    @Column(name = "old_permit_id")
    var oldPermitId: Long? = null,

    @Column(name = "new_application_id")
    var newApplicationId: Long? = null,

    @Column(name = "new_permit_id")
    var newPermitId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: TransferStatus = TransferStatus.PENDING_FAMILY_CONFIRM,

    @Column(name = "reason")
    var reason: String? = null,

    @Column(name = "initiated_by", nullable = false)
    var initiatedBy: String = "",

    @Column(name = "initiated_at", nullable = false)
    var initiatedAt: Instant = Instant.now(),

    @Column(name = "family_confirmed_at")
    var familyConfirmedAt: Instant? = null,

    @Column(name = "family_confirmed_by")
    var familyConfirmedBy: String? = null,

    @Column(name = "family_ack_note")
    var familyAckNote: String? = null,

    @Column(name = "decided_at")
    var decidedAt: Instant? = null,

    @Column(name = "decided_by")
    var decidedBy: String? = null,

    @Column(name = "decision_note")
    var decisionNote: String? = null,

    @Column(name = "old_permit_returned_at")
    var oldPermitReturnedAt: Instant? = null,

    @Column(name = "deposit_released", nullable = false)
    var depositReleased: Boolean = false,

    @Column(name = "deposit_released_at")
    var depositReleasedAt: Instant? = null,

    @Column(name = "deposit_release_note")
    var depositReleaseNote: String? = null,

    @Column(name = "liability_ended", nullable = false)
    var liabilityEnded: Boolean = false,

    @Column(name = "liability_ended_at")
    var liabilityEndedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

/** 交接文档: 新病区不允许陪护时生成的患者物品交接单/家属探视说明 */
@Entity
@Table(name = "handover_document")
class HandoverDocument(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "transfer_id", nullable = false)
    var transferId: Long = 0,

    @Column(name = "case_id", nullable = false)
    var caseId: Long = 0,

    @Column(name = "doc_no", nullable = false, unique = true)
    var docNo: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false)
    var docType: HandoverDocType = HandoverDocType.BELONGINGS_HANDOVER,

    @Column(name = "ward_code", nullable = false)
    var wardCode: String = "",

    @Column(name = "title", nullable = false)
    var title: String = "",

    @Column(name = "content", nullable = false)
    var content: String = "",

    @Column(name = "created_by", nullable = false)
    var createdBy: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
)
