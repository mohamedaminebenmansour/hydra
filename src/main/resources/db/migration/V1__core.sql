create table organization (
  id         uuid primary key default gen_random_uuid(),
  name       text not null,
  created_at timestamptz not null default now()
);

create table app_user (
  id            uuid primary key default gen_random_uuid(),
  org_id        uuid not null references organization(id),
  email         text not null unique,
  password_hash text not null,
  full_name     text not null,
  is_owner      boolean not null default false,
  is_active     boolean not null default true,
  created_at    timestamptz not null default now()
);

create table permission (
  id          text primary key,
  description text
);

create table role (
  id         uuid primary key default gen_random_uuid(),
  org_id     uuid references organization(id),
  code       text not null,
  label      text not null,
  scope_type text not null default 'ASSIGNED' check (scope_type in ('ALL','ASSIGNED'))
);
alter table role add constraint uq_role_org_code unique (org_id, code);
create unique index uq_role_system_code on role (code) where org_id is null;

create table role_permission (
  role_id       uuid not null references role(id) on delete cascade,
  permission_id text not null references permission(id) on delete cascade,
  primary key (role_id, permission_id)
);

create table user_role (
  user_id     uuid not null references app_user(id) on delete cascade,
  role_id     uuid not null references role(id) on delete cascade,
  assigned_by uuid references app_user(id),
  assigned_at timestamptz not null default now(),
  primary key (user_id, role_id)
);

create table incompatible_role_pair (
  role_a text not null,
  role_b text not null,
  reason text not null,
  primary key (role_a, role_b)
);

create table toxic_pair_override (
  id         uuid primary key default gen_random_uuid(),
  org_id     uuid not null references organization(id),
  user_id    uuid not null references app_user(id),
  role_a     text not null,
  role_b     text not null,
  reason     text not null,
  granted_by uuid not null references app_user(id),
  created_at timestamptz not null default now()
);

create table ticket (
  id          uuid primary key default gen_random_uuid(),
  org_id      uuid not null references organization(id),
  title       text not null,
  description text,
  status      text not null default 'OUVERT' check (status in ('OUVERT','RESOLU')),
  created_by  uuid not null references app_user(id),
  created_at  timestamptz not null default now(),
  resolved_at timestamptz,
  resolved_by uuid references app_user(id)
);

create table event_log (
  id          bigserial primary key,
  org_id      uuid,
  user_id     uuid,
  action      text not null,
  entity_type text,
  entity_id   uuid,
  details     jsonb,
  created_at  timestamptz not null default now()
);
create index on event_log (org_id, created_at desc);

create or replace function block_event_log_mutation() returns trigger as $$ begin
  raise exception 'event_log is append-only: % is not allowed', tg_op;
end;
 $$ language plpgsql;

create trigger event_log_append_only
  before update or delete on event_log
  for each row execute function block_event_log_mutation();

alter table organization enable row level security;
alter table app_user enable row level security;
alter table role enable row level security;
alter table ticket enable row level security;
alter table event_log enable row level security;