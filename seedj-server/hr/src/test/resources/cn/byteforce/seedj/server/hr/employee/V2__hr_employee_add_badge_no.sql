-- V2 candidate: reviewed example of adding one field after V1 is frozen.
-- seedj-codegen emits this shape as a candidate; never executed automatically.
ALTER TABLE hr_employee ADD COLUMN IF NOT EXISTS badge_no TEXT;
