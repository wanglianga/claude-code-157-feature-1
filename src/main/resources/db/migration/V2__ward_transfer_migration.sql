-- 患者转科陪护证迁移工作流
-- 普通病房 -> 重症过渡病区(ICU1)等场景: 原证暂停, 新病区护士审批是否允许继续陪护;
-- 迁移完成前旧证不得直接进入新病区, 门禁仅放行必要交接;
-- 新病区不允许陪护时, 旧陪护证退回后才释放押金/结束陪护责任。

-- 一次转科对应一条迁移单, 全程挂在同一条 escort_case 上
CREATE TABLE ward_transfer (
    id                        BIGSERIAL PRIMARY KEY,
    transfer_no               VARCHAR(32) NOT NULL UNIQUE,
    case_id                   BIGINT NOT NULL REFERENCES escort_case (id),
    patient_inpatient_no      VARCHAR(64) NOT NULL,
    from_ward_code            VARCHAR(64) NOT NULL,
    to_ward_code              VARCHAR(64) NOT NULL,
    from_bed_no               VARCHAR(32),
    bed_no                    VARCHAR(32),
    old_permit_id             BIGINT REFERENCES escort_permit (id),
    new_application_id        BIGINT REFERENCES escort_application (id),
    new_permit_id             BIGINT REFERENCES escort_permit (id),
    status                    VARCHAR(24) NOT NULL,   -- PENDING_FAMILY_CONFIRM / PENDING_WARD_REVIEW / APPROVED / REJECTED / CANCELLED
    reason                    VARCHAR(512),
    initiated_by              VARCHAR(64) NOT NULL,
    initiated_at              TIMESTAMP NOT NULL DEFAULT now(),
    family_confirmed_at       TIMESTAMP,
    family_confirmed_by       VARCHAR(64),
    family_ack_note           VARCHAR(512),
    decided_at                TIMESTAMP,
    decided_by                VARCHAR(64),
    decision_note             VARCHAR(512),
    -- 不允许陪护时: 旧证退回后才释放押金/结束陪护责任
    old_permit_returned_at    TIMESTAMP,
    deposit_released          BOOLEAN NOT NULL DEFAULT FALSE,
    deposit_released_at       TIMESTAMP,
    deposit_release_note      VARCHAR(512),
    liability_ended           BOOLEAN NOT NULL DEFAULT FALSE,
    liability_ended_at        TIMESTAMP,
    completed_at              TIMESTAMP,
    created_at                TIMESTAMP NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_transfer_case ON ward_transfer (case_id);
CREATE INDEX idx_transfer_status ON ward_transfer (status);
CREATE INDEX idx_transfer_to_ward ON ward_transfer (to_ward_code, status);

-- 新病区不允许陪护时生成的交接说明: 患者物品交接 + 家属探视说明
CREATE TABLE handover_document (
    id               BIGSERIAL PRIMARY KEY,
    transfer_id      BIGINT NOT NULL REFERENCES ward_transfer (id),
    case_id          BIGINT NOT NULL REFERENCES escort_case (id),
    doc_no           VARCHAR(32) NOT NULL UNIQUE,
    doc_type         VARCHAR(32) NOT NULL,          -- BELONGINGS_HANDOVER / FAMILY_VISIT_INSTRUCTION
    ward_code        VARCHAR(64) NOT NULL,
    title            VARCHAR(128) NOT NULL,
    content          TEXT NOT NULL,
    created_by       VARCHAR(64) NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_handover_transfer ON handover_document (transfer_id);

-- 原证暂停标记: 患者转科后旧证权限不再适用(门禁/扫码点/安保三端同步停权)
ALTER TABLE escort_permit ADD COLUMN transfer_suspended BOOLEAN NOT NULL DEFAULT FALSE;

-- 扫码记录: 迁移期间的必要交接放行, 以及持旧证闯入新病区
ALTER TABLE scan_record ADD COLUMN handover_pass BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE scan_record ADD COLUMN transfer_breach BOOLEAN NOT NULL DEFAULT FALSE;
