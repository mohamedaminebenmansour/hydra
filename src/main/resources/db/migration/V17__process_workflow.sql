-- ============================================================================
-- V17: Process workflow (process_template, process_template_step,
-- process_instance, process_step_instance, process_step_document)
--
-- Flyway repair note (production): If a previous V17 run already applied partial
-- changes and Flyway shows a FAILED V17 row in flyway_schema_history, do NOT
-- delete prod data. Instead:
--   1. Inspect flyway_schema_history to confirm V17 status (FAILED vs SUCCESS).
--   2. If FAILED and partial objects exist, drop the partially created objects
--      manually (process_template, process_template_step, process_instance,
--      process_step_instance, process_step_document plus their indexes), then
--      delete the failed V17 row from flyway_schema_history.
--   3. Re-run: ./mvnw flyway:migrate (or let Spring Boot Flyway auto-migrate).
--   4. Never run 'flyway repair' blindly on production without a backup.
-- ============================================================================

-- 1. process_template (system templates have org_id = NULL; org-scoped have org_id set)
create table if not exists process_template (
    id         uuid primary key default gen_random_uuid(),
    key        text    not null,
    name       text    not null,
    org_id     uuid references organization(id),
    active     boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- Partial unique indexes: system-wide key (org_id IS NULL) and org-scoped key
-- (org_id IS NOT NULL). No single UNIQUE on (key) anymore, so ON CONFLICT (key)
-- would not match any constraint.
create unique index if not exists uq_process_template_system_key
    on process_template (key) where org_id is null;
create unique index if not exists uq_process_template_org_key
    on process_template (key, org_id) where org_id is not null;

-- Seed PAYMENT_APPROVAL system template (WHERE NOT EXISTS; no ON CONFLICT since
-- there is no single unique constraint on (key) anymore).
insert into process_template (key, name, org_id, active)
select 'PAYMENT_APPROVAL', 'Payment Approval', null, true
where not exists (
    select 1 from process_template
    where key = 'PAYMENT_APPROVAL' and org_id is null
);

-- 2. process_template_step
create table if not exists process_template_step (
    id                 uuid primary key default gen_random_uuid(),
    template_id        uuid    not null references process_template(id) on delete cascade,
    step_order         int     not null,
    step_key           text    not null,
    name               text    not null,
    assignee_role      text    not null,
    requires_attachment boolean not null default false,
    constraint uq_template_step_order unique (template_id, step_order),
    constraint uq_template_step_key   unique (template_id, step_key)
);

-- Seed PAYMENT_APPROVAL steps. VALUES arity is exactly:
--   (step_order, step_key, name, assignee_role, requires_attachment)
-- No extra leading number in VALUES.
insert into process_template_step (template_id, step_order, step_key, name, assignee_role, requires_attachment)
select t.id, s.step_order, s.step_key, s.name, s.assignee_role, s.requires_attachment
from (values
    (1, 'DRAFT',   'Create/confirm payment intent',       'FINANCE',    false),
    (2, 'APPROVE', 'Approve/reject payment',              'OWNER',      false),
    (3, 'POST',    'Marks completed (hook for caisse post)', 'FINANCE', false)
) as s(step_order, step_key, name, assignee_role, requires_attachment)
cross join process_template t
where t.key = 'PAYMENT_APPROVAL' and t.org_id is null
on conflict (template_id, step_order) do nothing;

-- 3. process_instance
create table if not exists process_instance (
    id               uuid primary key default gen_random_uuid(),
    org_id           uuid    not null references organization(id),
    project_id       uuid    not null references project(id),
    template_id      uuid    not null references process_template(id),
    subject_type     text    not null,
    subject_id       uuid    not null,
    status           text    not null default 'RUNNING'
        check (status in ('RUNNING', 'COMPLETED', 'REJECTED', 'CANCELLED')),
    current_step_order int,
    created_by       uuid    not null references app_user(id),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

-- Partial UNIQUE index: only one RUNNING instance per (org, subject_type, subject_id).
-- Not an inline UNIQUE ... WHERE on CREATE TABLE.
create unique index if not exists uq_process_instance_running_subject
    on process_instance (org_id, subject_type, subject_id)
    where status = 'RUNNING';

create index if not exists idx_process_instance_status
    on process_instance (status, current_step_order);

-- 4. process_step_instance
create table if not exists process_step_instance (
    id                uuid primary key default gen_random_uuid(),
    instance_id       uuid    not null references process_instance(id) on delete cascade,
    step_order        int     not null,
    step_key          text    not null,
    name              text    not null,
    status            text    not null default 'PENDING'
        check (status in ('PENDING', 'DONE', 'REJECTED', 'SKIPPED')),
    assignee_role     text    not null,
    assignee_user_id  uuid,
    completed_by      uuid,
    completed_at      timestamptz,
    comment           text,
    created_at        timestamptz not null default now()
);

-- Real indexes (not fake CONSTRAINTs named idx_...).
create index if not exists idx_process_step_instance_instance_order
    on process_step_instance (instance_id, step_order);
create index if not exists idx_process_step_instance_assignee
    on process_step_instance (assignee_role, status);
create index if not exists idx_process_step_instance_org
    on process_step_instance (instance_id, created_at desc);
create index if not exists idx_process_step_instance_status
    on process_step_instance (status, instance_id);

-- 5. process_step_document junction to document(id)
create table if not exists process_step_document (
    step_instance_id  uuid    not null references process_step_instance(id) on delete cascade,
    document_id       uuid    not null references document(id),
    primary key (step_instance_id, document_id)
);

comment on table process_template is 'Reusable linear process template definition.';
comment on table process_template_step is 'Ordered steps within a template; step_key values are the stable API keys.';
comment on table process_instance is 'Runtime instance linked to a business subject (payment, achat, etc.).';
comment on table process_step_instance is 'Immutable history of step execution; never delete rows; only insert new PENDING.';
comment on table process_step_document is 'Junction: which documents serve as proof for which step.';
