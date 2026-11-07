-- V2: align the HR sample table with the employee contract.
--
-- Review before applying to a non-empty database: the NOT NULL / CHECK / UUID
-- statements require conforming rows. Local and test databases can be rebuilt;
-- production needs a data clean-up migration before this one.
ALTER TABLE hr_employee RENAME COLUMN phone TO contact;
ALTER TABLE hr_employee ADD COLUMN IF NOT EXISTS id_type TEXT;
ALTER TABLE hr_employee ADD COLUMN IF NOT EXISTS terminated_at TIMESTAMPTZ(6);
ALTER TABLE hr_employee ADD COLUMN IF NOT EXISTS terminated_reason TEXT;

ALTER TABLE hr_employee
    ALTER COLUMN org_unit_id TYPE UUID USING NULLIF(org_unit_id, '')::uuid;

CREATE SEQUENCE IF NOT EXISTS hr_employee_no_seq START 1;

ALTER TABLE hr_employee ALTER COLUMN employee_no SET NOT NULL;
ALTER TABLE hr_employee ALTER COLUMN name SET NOT NULL;
ALTER TABLE hr_employee ALTER COLUMN status SET NOT NULL;
ALTER TABLE hr_employee ALTER COLUMN hire_date SET NOT NULL;

ALTER TABLE hr_employee ADD CONSTRAINT ck_hr_employee_employee_no
    CHECK (employee_no ~ '^EMP-[0-9]{6,}$');
ALTER TABLE hr_employee ADD CONSTRAINT ck_hr_employee_name
    CHECK (char_length(name) >= 1);
ALTER TABLE hr_employee ADD CONSTRAINT ck_hr_employee_status
    CHECK (status IN ('pending_onboarding', 'active', 'terminated', 'cancelled'));
ALTER TABLE hr_employee ADD CONSTRAINT ck_hr_employee_employment_type
    CHECK (employment_type IS NULL
        OR employment_type IN ('full_time', 'part_time', 'intern', 'contract'));
ALTER TABLE hr_employee ADD CONSTRAINT ck_hr_employee_work_location
    CHECK (work_location IS NULL OR char_length(work_location) >= 1);
ALTER TABLE hr_employee ADD CONSTRAINT ck_hr_employee_id_type
    CHECK (id_type IS NULL OR id_type IN ('national_id', 'passport'));
ALTER TABLE hr_employee ADD CONSTRAINT ck_hr_employee_id_number_hash
    CHECK (id_number_hash IS NULL OR id_number_hash ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX uq_hr_employee_employee_no
    ON hr_employee (employee_no) WHERE superseded_at IS NULL;
