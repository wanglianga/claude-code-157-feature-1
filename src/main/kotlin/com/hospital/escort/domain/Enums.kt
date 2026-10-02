package com.hospital.escort.domain

enum class CaseStatus { ACTIVE, CLOSED }

/** 转科陪护证迁移状态 */
enum class TransferStatus {
    PENDING_FAMILY_CONFIRM,  // 已暂停原证, 等待家属重新确认陪护规则
    PENDING_WARD_REVIEW,     // 家属已确认, 等待新病区护士审批
    APPROVED,                // 新病区允许继续陪护, 新证已签发
    REJECTED,                // 新病区不允许陪护, 等待旧证退回与交接
    CANCELLED                // 迁移撤销(如患者转回原病区)
}

/** 交接文档类型: BELONGINGS_HANDOVER=患者物品交接, FAMILY_VISIT_INSTRUCTION=家属探视说明 */
enum class HandoverDocType { BELONGINGS_HANDOVER, FAMILY_VISIT_INSTRUCTION }

enum class ApplicationType { INITIAL, REPLACEMENT, TRANSFER }

enum class ApplicationStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }

/** 核酸或传染病筛查要求: NAT=核酸检测, ANTIGEN=抗原快筛, NONE=病区不要求 */
enum class ScreeningType { NAT, ANTIGEN, NONE }

enum class PermitStatus { ACTIVE, FROZEN, EXPIRED, RETURNED, REVOKED, REPLACED }

enum class SyncTarget { GATE, WARD_SCAN, SECURITY_PATROL }

enum class SyncAction { ISSUE, REVOKE, FREEZE, UNFREEZE, UPDATE }

enum class SyncStatus { PENDING, SYNCED, FAILED }

/** 替换原因: ESCORT_SICK=陪护人临时生病, FAMILY_DISPUTE=亲属争抢陪护资格, SCHEDULE=排班调整, OTHER=其他 */
enum class ReplacementReason { ESCORT_SICK, FAMILY_DISPUTE, SCHEDULE, OTHER }

enum class ReplacementStatus { PENDING, APPROVED, REJECTED }

enum class ScanDirection { IN, OUT }

enum class ScanResult { ALLOWED, DENIED }

enum class ViolationType {
    ESCORT_UNREGISTERED_CHANGE,  // 未登记更换陪护人(人证不符)
    MULTI_PERMIT_SHARED,         // 多证共用
    NIGHT_ABNORMAL,              // 夜间异常出入
    EXIT_BREACH,                 // 违反护士外出限制
    EXPIRED_NOT_RETURNED,        // 陪护证过期未退
    SECURITY_IMPERSONATION,      // 安保发现冒用
    LOCKDOWN_BREACH,             // 封闭管理期间违规
    TRANSFER_BREACH              // 转科迁移期间持旧证闯入新病区
}

enum class ViolationStatus { OPEN, CONFIRMED, RESOLVED, DISMISSED }

enum class CaseEventType {
    APPLICATION_SUBMITTED,
    APPLICATION_APPROVED,
    APPLICATION_REJECTED,
    PERMIT_ISSUED,
    PERMIT_FROZEN,
    PERMIT_UNFROZEN,
    PERMIT_EXPIRED,
    PERMIT_RETURNED,
    PERMIT_REVOKED,
    PERMIT_REPLACED,
    REPLACEMENT_REQUESTED,
    REPLACEMENT_APPROVED,
    REPLACEMENT_REJECTED,
    SCAN_IN,
    SCAN_OUT,
    VIOLATION_REPORTED,
    VIOLATION_RESOLVED,
    PATIENT_TRANSFERRED,
    TRANSFER_INITIATED,             // 转科迁移发起, 原陪护证暂停
    TRANSFER_FAMILY_RULES_NOTIFIED, // 已通知家属重新确认新病区陪护规则
    TRANSFER_FAMILY_CONFIRMED,      // 家属已确认陪护规则
    TRANSFER_APPROVED,              // 新病区护士批准继续陪护
    TRANSFER_REJECTED,              // 新病区护士不允许陪护
    TRANSFER_HANDOVER_DOC_ISSUED,   // 生成物品交接/家属探视说明
    TRANSFER_OLD_PERMIT_RETURNED,   // 旧陪护证已退回
    TRANSFER_DEPOSIT_RELEASED,      // 押金释放、陪护责任结束
    TRANSFER_CANCELLED,             // 迁移撤销
    WARD_LOCKDOWN,
    WARD_LOCKDOWN_LIFTED,
    EXIT_RESTRICTED,
    EXIT_RESTRICTION_LIFTED,
    DEPOSIT_DISPUTE_RAISED,
    DEPOSIT_DISPUTE_RESOLVED,
    CAREGIVER_CHANGED,
    CASE_CLOSED
}
