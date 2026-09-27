-- 医院陪护证服务数据库结构
-- 一条陪护记录(escort_case)聚合: 申请、审批、电子陪护证、进出扫码、替换、违规、退证全生命周期

CREATE TABLE ward_policy (
    ward_code                     VARCHAR(64) PRIMARY KEY,
    ward_name                     VARCHAR(128) NOT NULL,
    allow_visit_start             TIME NOT NULL DEFAULT '08:00',
    allow_visit_end               TIME NOT NULL DEFAULT '20:00',
    max_escorts_per_patient       INT NOT NULL DEFAULT 1,
    night_escort_requires_approval BOOLEAN NOT NULL DEFAULT TRUE,
    lockdown                      BOOLEAN NOT NULL DEFAULT FALSE,
    lockdown_reason               VARCHAR(255),
    updated_at                    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE escort_case (
    id                     BIGSERIAL PRIMARY KEY,
    case_no                VARCHAR(32) NOT NULL UNIQUE,
    patient_inpatient_no   VARCHAR(64) NOT NULL,
    ward_code              VARCHAR(64) NOT NULL,
    bed_no                 VARCHAR(32),
    status                 VARCHAR(16) NOT NULL,          -- ACTIVE / CLOSED
    current_permit_id      BIGINT,
    current_escort_name    VARCHAR(64),
    current_escort_id_no   VARCHAR(64),
    current_escort_phone   VARCHAR(32),
    created_at             TIMESTAMP NOT NULL DEFAULT now(),
    closed_at              TIMESTAMP
);
CREATE INDEX idx_case_patient ON escort_case (patient_inpatient_no);
CREATE INDEX idx_case_ward ON escort_case (ward_code, status);

CREATE TABLE escort_application (
    id                      BIGSERIAL PRIMARY KEY,
    case_id                 BIGINT NOT NULL REFERENCES escort_case (id),
    application_no          VARCHAR(32) NOT NULL UNIQUE,
    apply_type              VARCHAR(16) NOT NULL,          -- INITIAL / REPLACEMENT
    patient_inpatient_no    VARCHAR(64) NOT NULL,
    ward_code               VARCHAR(64) NOT NULL,
    escort_name             VARCHAR(64) NOT NULL,
    escort_id_no            VARCHAR(64) NOT NULL,
    escort_phone            VARCHAR(32) NOT NULL,
    relation_to_patient     VARCHAR(64),
    health_commitment       BOOLEAN NOT NULL,
    screening_type          VARCHAR(16) NOT NULL,          -- NAT / ANTIGEN / NONE
    screening_valid_until   TIMESTAMP,
    period_start            TIMESTAMP NOT NULL,
    period_end              TIMESTAMP NOT NULL,
    need_night_care         BOOLEAN NOT NULL DEFAULT FALSE,
    status                  VARCHAR(16) NOT NULL,          -- PENDING / APPROVED / REJECTED / WITHDRAWN
    submitted_at            TIMESTAMP NOT NULL DEFAULT now(),
    decided_at              TIMESTAMP,
    decided_by              VARCHAR(64),
    decision_note           VARCHAR(512),
    replaced_application_id BIGINT
);
CREATE INDEX idx_application_case ON escort_application (case_id);
CREATE INDEX idx_application_status ON escort_application (status);

CREATE TABLE escort_permit (
    id                   BIGSERIAL PRIMARY KEY,
    case_id              BIGINT NOT NULL REFERENCES escort_case (id),
    application_id       BIGINT NOT NULL REFERENCES escort_application (id),
    permit_no            VARCHAR(32) NOT NULL UNIQUE,
    qr_token             VARCHAR(64) NOT NULL UNIQUE,
    ward_code            VARCHAR(64) NOT NULL,
    escort_name          VARCHAR(64) NOT NULL,
    escort_id_no         VARCHAR(64) NOT NULL,
    escort_phone         VARCHAR(32) NOT NULL,
    valid_from           TIMESTAMP NOT NULL,
    valid_until          TIMESTAMP NOT NULL,
    night_care_allowed   BOOLEAN NOT NULL DEFAULT FALSE,
    status               VARCHAR(16) NOT NULL,             -- ACTIVE / FROZEN / EXPIRED / RETURNED / REVOKED / REPLACED
    issued_at            TIMESTAMP NOT NULL DEFAULT now(),
    freeze_reason        VARCHAR(255),
    frozen_at            TIMESTAMP,
    frozen_by            VARCHAR(64),
    lockdown_frozen      BOOLEAN NOT NULL DEFAULT FALSE,
    exit_restricted      BOOLEAN NOT NULL DEFAULT FALSE,
    exit_restrict_reason VARCHAR(255),
    returned_at          TIMESTAMP,
    returned_by          VARCHAR(64),
    return_channel       VARCHAR(32),
    version              BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_permit_case ON escort_permit (case_id);
CREATE INDEX idx_permit_ward_status ON escort_permit (ward_code, status);
CREATE INDEX idx_permit_escort ON escort_permit (escort_id_no, status);

-- 电子陪护证向门禁 / 病区扫码点 / 安保巡查的同步记录
CREATE TABLE permit_sync (
    id         BIGSERIAL PRIMARY KEY,
    permit_id  BIGINT NOT NULL REFERENCES escort_permit (id),
    target     VARCHAR(32) NOT NULL,                       -- GATE / WARD_SCAN / SECURITY_PATROL
    action     VARCHAR(16) NOT NULL,                       -- ISSUE / REVOKE / FREEZE / UNFREEZE / UPDATE
    status     VARCHAR(16) NOT NULL,                       -- PENDING / SYNCED / FAILED
    payload    TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    synced_at  TIMESTAMP
);
CREATE INDEX idx_sync_permit ON permit_sync (permit_id, status);

CREATE TABLE replacement_record (
    id                 BIGSERIAL PRIMARY KEY,
    case_id            BIGINT NOT NULL REFERENCES escort_case (id),
    old_permit_id      BIGINT REFERENCES escort_permit (id),
    new_application_id BIGINT NOT NULL REFERENCES escort_application (id),
    new_permit_id      BIGINT REFERENCES escort_permit (id),
    reason             VARCHAR(32) NOT NULL,               -- ESCORT_SICK / FAMILY_DISPUTE / SCHEDULE / OTHER
    detail             VARCHAR(512),
    status             VARCHAR(16) NOT NULL,               -- PENDING / APPROVED / REJECTED
    requested_at       TIMESTAMP NOT NULL DEFAULT now(),
    decided_at         TIMESTAMP,
    decided_by         VARCHAR(64)
);
CREATE INDEX idx_replacement_case ON replacement_record (case_id);

CREATE TABLE scan_record (
    id                  BIGSERIAL PRIMARY KEY,
    case_id             BIGINT REFERENCES escort_case (id),
    permit_id           BIGINT REFERENCES escort_permit (id),
    permit_no           VARCHAR(32),
    ward_code           VARCHAR(64),
    direction           VARCHAR(8) NOT NULL,               -- IN / OUT
    gate_location       VARCHAR(128) NOT NULL,
    scanned_at          TIMESTAMP NOT NULL,
    scanned_id_no       VARCHAR(64),
    result              VARCHAR(16) NOT NULL,              -- ALLOWED / DENIED
    out_of_hours        BOOLEAN NOT NULL DEFAULT FALSE,    -- 是否超出允许时段
    escort_changed      BOOLEAN NOT NULL DEFAULT FALSE,    -- 是否更换陪护人(人证不符)
    multi_permit_shared BOOLEAN NOT NULL DEFAULT FALSE,    -- 是否多证共用
    ward_lockdown       BOOLEAN NOT NULL DEFAULT FALSE,
    deny_reason         VARCHAR(255),
    created_at          TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_scan_case ON scan_record (case_id, scanned_at);
CREATE INDEX idx_scan_permit ON scan_record (permit_id, scanned_at);
CREATE INDEX idx_scan_ward ON scan_record (ward_code, scanned_at);

CREATE TABLE violation_record (
    id             BIGSERIAL PRIMARY KEY,
    case_id        BIGINT REFERENCES escort_case (id),
    permit_id      BIGINT REFERENCES escort_permit (id),
    scan_record_id BIGINT REFERENCES scan_record (id),
    ward_code      VARCHAR(64),
    type           VARCHAR(48) NOT NULL,
    detail         VARCHAR(512),
    reported_by    VARCHAR(64),
    status         VARCHAR(16) NOT NULL DEFAULT 'OPEN',    -- OPEN / CONFIRMED / RESOLVED / DISMISSED
    created_at     TIMESTAMP NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMP,
    resolved_by    VARCHAR(64),
    resolution_note VARCHAR(512)
);
CREATE INDEX idx_violation_case ON violation_record (case_id);
CREATE INDEX idx_violation_ward ON violation_record (ward_code, type);

-- 同一条陪护记录上的归档事件流(申请/审批/进出/替换/违规/退证)
CREATE TABLE case_event (
    id         BIGSERIAL PRIMARY KEY,
    case_id    BIGINT NOT NULL REFERENCES escort_case (id),
    event_type VARCHAR(48) NOT NULL,
    actor      VARCHAR(64),
    summary    VARCHAR(512) NOT NULL,
    detail     TEXT,
    ref_type   VARCHAR(32),
    ref_id     BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_event_case ON case_event (case_id, created_at);

-- 病区通知: 每次替换/冻结/退证等让病区知道患者当下由谁照护
CREATE TABLE ward_notice (
    id         BIGSERIAL PRIMARY KEY,
    ward_code  VARCHAR(64) NOT NULL,
    case_id    BIGINT,
    permit_id  BIGINT,
    type       VARCHAR(48) NOT NULL,
    message    VARCHAR(512) NOT NULL,
    read       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_notice_ward ON ward_notice (ward_code, read);

-- 预置病区策略(测试数据)
INSERT INTO ward_policy (ward_code, ward_name, allow_visit_start, allow_visit_end, max_escorts_per_patient, night_escort_requires_approval, lockdown)
VALUES ('W3A',  '内科三病区A',  '08:00', '20:00', 1, TRUE,  FALSE),
       ('W5B',  '外科五病区B',  '08:00', '20:00', 1, TRUE,  FALSE),
       ('ICU1', '重症监护一区', '10:00', '16:00', 1, TRUE,  FALSE);
