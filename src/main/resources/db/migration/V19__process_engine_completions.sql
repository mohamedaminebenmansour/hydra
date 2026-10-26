-- ============================================================================
-- V19: Process engine completions
--
-- Adds missing columns / indexes to support the full process spec:
--   1. process_step_instance.requires_attachment (snapshot from template step)
--   2. process_step_instance.started_at          (when the step became PENDING)
--   3. project_process_template.default_for_subject_type (optional default)
--
-- All DDL is idempotent (DO $$ BEGIN … EXCEPTION WHEN duplicate_column).
-- ============================================================================

-- 1. requires_attachment snapshot on step instance
DO $$ BEGIN
    ALTER TABLE process_step_instance ADD COLUMN requires_attachment boolean NOT NULL DEFAULT false;
EXCEPTION WHEN duplicate_column THEN NULL; END $$;

-- 2. started_at for when the step opened (populated at insert; same as created_at but explicit)
DO $$ BEGIN
    ALTER TABLE process_step_instance ADD COLUMN started_at timestamptz;
EXCEPTION WHEN duplicate_column THEN NULL; END $$;

-- Back-fill started_at from created_at for existing rows
UPDATE process_step_instance SET started_at = created_at WHERE started_at IS NULL;

-- 3. default_for_subject_type on project↔template mapping (nullable; e.g. 'PAYMENT')
DO $$ BEGIN
    ALTER TABLE project_process_template ADD COLUMN default_for_subject_type text;
EXCEPTION WHEN duplicate_column THEN NULL; END $$;

-- Disable RLS on all process tables (same fix pattern as V16)
ALTER TABLE process_template          DISABLE ROW LEVEL SECURITY;
ALTER TABLE process_template_step     DISABLE ROW LEVEL SECURITY;
ALTER TABLE process_instance          DISABLE ROW LEVEL SECURITY;
ALTER TABLE process_step_instance     DISABLE ROW LEVEL SECURITY;
ALTER TABLE process_step_document     DISABLE ROW LEVEL SECURITY;
ALTER TABLE project_process_template  DISABLE ROW LEVEL SECURITY;
