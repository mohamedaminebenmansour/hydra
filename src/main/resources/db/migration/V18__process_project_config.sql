-- ============================================================================
-- V18: Process project template configuration
--
-- Templates live at org/system level (process_template) and are made available
-- on a project through this mapping. Idempotent: safe to re-run.
-- ============================================================================

-- Mapping: which templates are enabled on which project
create table if not exists project_process_template (
    id         uuid primary key default gen_random_uuid(),
    project_id uuid    not null references project(id),
    template_id uuid   not null references process_template(id),
    enabled    boolean not null default true,
    constraint uq_project_process_template unique (project_id, template_id)
);

-- Index: list instances by project + status (chantier screen)
create index if not exists idx_process_instance_project_status
    on process_instance (project_id, status);

comment on table project_process_template is 'Links a process template to a project and whether it is enabled there. When no rows exist for a project, all active templates are open (default).';
