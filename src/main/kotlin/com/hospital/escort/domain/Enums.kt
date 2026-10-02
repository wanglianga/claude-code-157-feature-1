package com.hospital.escort.domain

enum class CaseStatus { ACTIVE, CLOSED }

enum class ApplicationType { INITIAL, REPLACEMENT }

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
    TRANSFER_ZONE_BREACH         // 转科迁移未完成, 持旧证进入新病区
}

enum class ViolationStatus { OPEN, CONFIRMED, RESOLVED, DISMISSED }

/** 病区类别: GENERAL=普通病房, ICU_TRANSITION=重症过渡病区(陪护规则更严, 转入须重新审批陪护) */
enum class WardCategory { GENERAL, ICU_TRANSITION }

/**
 * 陪护证转科迁移状态:
 * PENDING_FAMILY_CONFIRM 已暂停原证, 待家属重新确认新病区陪护规则
 * PENDING_WARD_APPROVAL  家属已确认, 待新病区护士审批
 * MIGRATED               新病区允许继续陪护, 新证签发, 迁移完成
 * ESCORT_DENIED          新病区不允许陪护, 待物品交接/旧证退回/押金释放
 * CANCELLED              迁移取消(如患者转回原病区)
 */
enum class TransferStatus { PENDING_FAMILY_CONFIRM, PENDING_WARD_APPROVAL, MIGRATED, ESCORT_DENIED, CANCELLED }

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
    WARD_LOCKDOWN,
    WARD_LOCKDOWN_LIFTED,
    EXIT_RESTRICTED,
    EXIT_RESTRICTION_LIFTED,
    DEPOSIT_DISPUTE_RAISED,
    DEPOSIT_DISPUTE_RESOLVED,
    CAREGIVER_CHANGED,
    PERMIT_TRANSFER_SUSPENDED,     // 转科: 原陪护证暂停(门禁停权)
    TRANSFER_RULES_NOTIFIED,       // 转科: 已通知家属重新确认新病区陪护规则
    TRANSFER_FAMILY_CONFIRMED,     // 转科: 家属已重新确认陪护规则
    TRANSFER_WARD_APPROVED,        // 转科: 新病区护士批准继续陪护
    TRANSFER_WARD_DENIED,          // 转科: 新病区不允许陪护
    TRANSFER_HANDOVER_PREPARED,    // 转科: 已生成物品交接与家属探视说明
    TRANSFER_OLD_PERMIT_RETURNED,  // 转科: 旧陪护证已退回
    TRANSFER_DEPOSIT_RELEASED,     // 转科: 旧证退回后释放押金/结束陪护责任
    TRANSFER_CANCELLED,            // 转科: 迁移取消
    HANDOVER_PASS_GRANTED,         // 转科: 发放必要交接临时通行
    CASE_CLOSED
}
