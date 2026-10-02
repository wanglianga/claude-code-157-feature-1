-- 患者转科(普通病房 -> 重症过渡病区)陪护证迁移
-- 1) 病区类别: 重症过渡病区陪护规则更严, 原普通病房陪护权限不适用, 必须重新审批
ALTER TABLE ward_policy ADD COLUMN ward_category VARCHAR(32) NOT NULL DEFAULT 'GENERAL'; -- GENERAL / ICU_TRANSITION

INSERT INTO ward_policy
    (ward_code, ward_name, allow_visit_start, allow_visit_end, max_escorts_per_patient, night_escort_requires_approval, lockdown, ward_category)
VALUES
    ('ITU1', '重症过渡病区', '10:00', '16:00', 1, TRUE, FALSE, 'ICU_TRANSITION');

-- 2) 陪护证转科迁移挂起标记: 转科后旧证冻结(FROZEN)且标记为"迁移挂起", 迁移完成前不得直接进入新病区
ALTER TABLE escort_permit ADD COLUMN transfer_suspended BOOLEAN NOT NULL DEFAULT FALSE;

-- 3) 扫码记录: 迁移期凭"必要交接授权"放行的进出
ALTER TABLE scan_record ADD COLUMN handover_pass BOOLEAN NOT NULL DEFAULT FALSE;

-- 4) 陪护证转科迁移单: 暂停原证 -> 家属重新确认陪护规则 -> 新病区护士审批 -> 继续陪护/不允许陪护
CREATE TABLE permit_transfer (
    id                        BIGSERIAL PRIMARY KEY,
    case_id                   BIGINT NOT NULL REFERENCES escort_case (id),
    transfer_no               VARCHAR(32) NOT NULL UNIQUE,
    old_ward_code             VARCHAR(64) NOT NULL,
    new_ward_code             VARCHAR(64) NOT NULL,
    bed_no                    VARCHAR(32),
    old_permit_id             BIGINT REFERENCES escort_permit (id),
    old_permit_no             VARCHAR(32),
    escort_name               VARCHAR(64) NOT NULL,
    escort_id_no              VARCHAR(64) NOT NULL,
    escort_phone              VARCHAR(32),
    status                    VARCHAR(24) NOT NULL,  -- PENDING_FAMILY_CONFIRM / PENDING_WARD_APPROVAL / MIGRATED / ESCORT_DENIED / CANCELLED
    family_rule_notice        TEXT,
    family_rule_acknowledged  BOOLEAN NOT NULL DEFAULT FALSE,
    family_confirmed_at       TIMESTAMP,
    family_confirmed_by       VARCHAR(64),
    family_note               VARCHAR(512),
    new_application_id        BIGINT REFERENCES escort_application (id),
    new_permit_id             BIGINT REFERENCES escort_permit (id),
    old_permit_returned_at    TIMESTAMP,
    old_permit_returned_by    VARCHAR(64),
    decided_by                VARCHAR(64),
    decided_at                TIMESTAMP,
    decision_note             VARCHAR(512),
    deposit_released          BOOLEAN NOT NULL DEFAULT FALSE,
    deposit_released_at       TIMESTAMP,
    deposit_released_by       VARCHAR(64),
    release_note              VARCHAR(512),
    escort_liability_ended    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMP NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_transfer_case ON permit_transfer (case_id, created_at);
CREATE INDEX idx_transfer_new_ward_status ON permit_transfer (new_ward_code, status);
CREATE INDEX idx_transfer_old_permit ON permit_transfer (old_permit_id);

-- 5) 物品交接与家属探视说明: 新病区不允许陪护时生成, 旧证退回后方可释放押金/结束陪护责任
CREATE TABLE belongings_handover (
    id                     BIGSERIAL PRIMARY KEY,
    transfer_id            BIGINT NOT NULL REFERENCES permit_transfer (id),
    case_id                BIGINT NOT NULL REFERENCES escort_case (id),
    doc_no                 VARCHAR(32) NOT NULL UNIQUE,
    patient_inpatient_no   VARCHAR(64) NOT NULL,
    old_ward_code          VARCHAR(64) NOT NULL,
    new_ward_code          VARCHAR(64) NOT NULL,
    escort_name            VARCHAR(64) NOT NULL,
    items                  TEXT NOT NULL,
    visit_instruction      TEXT NOT NULL,
    old_permit_returned    BOOLEAN NOT NULL DEFAULT FALSE,
    old_permit_returned_at TIMESTAMP,
    received_by            VARCHAR(64),
    deposit_releasable     BOOLEAN NOT NULL DEFAULT FALSE,
    generated_by           VARCHAR(64) NOT NULL,
    created_at             TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_handover_transfer ON belongings_handover (transfer_id);
CREATE INDEX idx_handover_case ON belongings_handover (case_id);

-- 6) 必要交接通行授权: 迁移未完成期间, 旧证仅允许在授权时间窗/授权病区缓冲点各进出一次用于交接
CREATE TABLE handover_pass_grant (
    id          BIGSERIAL PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES permit_transfer (id),
    case_id     BIGINT NOT NULL REFERENCES escort_case (id),
    permit_id   BIGINT NOT NULL REFERENCES escort_permit (id),
    ward_code   VARCHAR(64) NOT NULL,              -- 仅允许进入的新病区(缓冲交接点)
    valid_from  TIMESTAMP NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    used_in     BOOLEAN NOT NULL DEFAULT FALSE,
    used_out    BOOLEAN NOT NULL DEFAULT FALSE,
    granted_by  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_handover_grant_permit ON handover_pass_grant (permit_id, valid_until);
