CREATE TABLE hr_employee (
    id              eis_id        NOT NULL,
    version         eis_version   NOT NULL DEFAULT 1,
    valid_from      eis_timestamp NOT NULL,
    valid_to        eis_timestamp,
    recorded_at     eis_timestamp NOT NULL,
    superseded_at   eis_timestamp,
    created_by      eis_id        NOT NULL,
    created_at      eis_timestamp NOT NULL,
    updated_by      eis_id,
    updated_at      eis_timestamp,
    employee_no TEXT,
    name TEXT,
    status TEXT,
    hire_date DATE,
    employment_type TEXT,
    work_location TEXT,
    phone TEXT,
    id_number_hash TEXT,
    org_unit_id TEXT,
    CONSTRAINT pk_hr_employee PRIMARY KEY (id, recorded_at),
    CONSTRAINT valid_range CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT recorded_range CHECK (superseded_at IS NULL OR superseded_at > recorded_at)
);
CREATE UNIQUE INDEX uq_hr_employee_current ON hr_employee (id) WHERE superseded_at IS NULL;
ALTER TABLE hr_employee ADD CONSTRAINT ex_hr_employee_valid_overlap EXCLUDE USING gist (
    id WITH =,
    tstzrange(valid_from, valid_to, '[)') WITH &&,
    tstzrange(recorded_at, superseded_at, '[)') WITH &&
);
CREATE INDEX idx_hr_employee_valid_time ON hr_employee (valid_from, valid_to);
CREATE INDEX idx_hr_employee_recorded_time ON hr_employee (recorded_at, superseded_at);
CREATE INDEX idx_hr_employee_bitemporal ON hr_employee (valid_from, valid_to, recorded_at, superseded_at);
CREATE INDEX idx_hr_employee_employee_no ON hr_employee (employee_no) WHERE superseded_at IS NULL;
CREATE INDEX idx_hr_employee_org_unit_id ON hr_employee (org_unit_id) WHERE superseded_at IS NULL;
CREATE FUNCTION hr_employee_as_of_valid(p_id UUID, p_time TIMESTAMPTZ) RETURNS SETOF hr_employee AS 'SELECT * FROM hr_employee WHERE id = $1 AND valid_from <= $2 AND (valid_to IS NULL OR valid_to > $2)' LANGUAGE sql STABLE;
CREATE FUNCTION hr_employee_as_of_recorded(p_id UUID, p_time TIMESTAMPTZ) RETURNS SETOF hr_employee AS 'SELECT * FROM hr_employee WHERE id = $1 AND recorded_at <= $2 AND (superseded_at IS NULL OR superseded_at > $2)' LANGUAGE sql STABLE;
CREATE FUNCTION hr_employee_as_of_bitemporal(p_id UUID, p_valid_time TIMESTAMPTZ, p_recorded_time TIMESTAMPTZ) RETURNS SETOF hr_employee AS 'SELECT * FROM hr_employee WHERE id = $1 AND valid_from <= $2 AND (valid_to IS NULL OR valid_to > $2) AND recorded_at <= $3 AND (superseded_at IS NULL OR superseded_at > $3)' LANGUAGE sql STABLE;
