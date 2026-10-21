create table if not exists process_template (
    id                uuid primary key default gen_random_uuid(),
    key               text    not null unique,
    name              text    not null,
    org_id            uuid references organization(id),
    active            boolean not null default true,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

insert into process_template (key, name, org_id, active)
values ('PAYMENT_APPROVAL', 'Payment Approval', null, true)
on conflict (key) do nothing;

create table if not exists process_template_step (
    id                uuid primary key default gen_random_uuid(),
    template_id       uuid    not null references process_template(id) on delete cascade,
    step_order        int     not null,
    step_key          text    not null,
    name              text    not null,
    assignee_role     text    not null,
    requires_attachment boolean not null default false,
    constraint uq_template_step_order unique (template_id, step_order),
    constraint uq_template_step_key unique (template_id, step_key)
);

insert into process_template_step (template_id, step_order, step_key, name, assignee_role, requires_attachment)
select t.id, s.step_order, s.step_key, s.name, s.assignee_role, s.requires_attachment
from (values
    (1, 1, 'DRAFT',   'Create/confirm payment intent',       'FINANCE',    false),
    (1, 2, 'APPROVE', 'Approve/reject payment',              'OWNER',      false),
    (1, 3, 'POST',    'Marks completed (hook for caisse post)', 'FINANCE',    false)
) as s(step_order, step_key, name, assignee_role, requires_attachment)
cross join process_template t
where t.key = 'PAYMENT_APPROVAL' and t.org_id is null
on conflict (template_id, step_order) do nothing;

create table if not exists process_instance (
    id                uuid primary key default gen_random_uuid(),
    org_id            uuid    not null references organization(id),
    project_id        uuid    not null references project(id),
    template_id       uuid    not null references process_template(id),
    subject_type      text    not null,
    subject_id        uuid    not null,
    status            text    not null default 'RUNNING'
        check (status in ('RUNNING', 'COMPLETED', 'REJECTED', 'CANCELLED')),
    current_step_order int,
    created_by        uuid    not null references app_user(id),
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index if not exists idx_process_instance_org_subj on process_instance (org_id, subject_type, subject_id);
create index if not exists idx_process_instance_status on process_instance (status, current_step_order);

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
    created_at        timestamptz not null default now(),
    constraint idx_step_instance_instance_order on process_step_instance (instance_id, step_order),
    constraint idx_step_instance_assignee on process_step_instance (assignee_role, status)
);

create table if not exists process_step_document (
    step_instance_id  uuid    not null references process_step_instance(id) on delete cascade,
    document_id       uuid    not null references document(id),
    primary key (step_instance_id, document_id)
);

create index if not exists idx_process_step_inst_org on process_step_instance (instance_id, created_at desc);
create index if not exists idx_process_step_status on process_step_instance (status, instance_id);

comment on table process_template is 'Reusable linear process template definition (scheme vs issue).';
comment on table process_template_step is 'Ordered steps within a template; step_key values are the stable API keys.';
comment on table process_instance is 'Runtime instance linked to a business subject (payment, achat, etc.).';
comment on table process_step_instance is 'Immutable history of step execution; never delete rows; only insert new PENDING.';
comment on table process_step_document is 'Junction: which documents serve as proof for which step.';