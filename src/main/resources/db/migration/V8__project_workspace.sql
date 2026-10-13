-- STEP 1: Enrich project columns
alter table project add column if not exists client_name    text;
alter table project add column if not exists location       text;
alter table project add column if not exists governorate    text;
alter table project add column if not exists start_date     date;
alter table project add column if not exists end_date       date;
alter table project add column if not exists project_type   text;
alter table project add column if not exists description    text;
alter table project add column if not exists contract_value numeric(14,3);
alter table project add column if not exists currency       text not null default 'TND';

-- STEP 2: Company-wide folder templates (org_id null = global/system template)
create table if not exists doc_folder_template (
  id         uuid primary key default gen_random_uuid(),
  org_id     uuid references organization(id),  -- null = global default
  code       text not null,
  name       text not null,
  sort_order int  not null default 0,
  unique (org_id, code)
);

insert into doc_folder_template (org_id, code, name, sort_order) values
  (null, '01 ADMIN', 'Administratif',          1),
  (null, '02 TECH',  'Technique',              2),
  (null, '03 EXEC',  'Exécution',              3),
  (null, '04 QUAL',  'Qualité / Réception',    4),
  (null, '05 ACHAT', 'Achats',                 5),
  (null, '06 FIN',   'Finance',                6),
  (null, '07 INT',   'Interne',                7)
on conflict (org_id, code) do nothing;

-- STEP 3: Per-project folders (cloned from template on project create)
create table if not exists doc_folder (
  id         uuid primary key default gen_random_uuid(),
  project_id uuid not null references project(id) on delete cascade,
  parent_id  uuid references doc_folder(id) on delete cascade,
  code       text,
  name       text not null,
  sort_order int  not null default 0,
  created_at timestamptz not null default now()
);

create index if not exists idx_doc_folder_project_sort on doc_folder (project_id, sort_order);

-- STEP 4: Project members (who can work on this project)
create table if not exists project_member (
  project_id   uuid not null references project(id) on delete cascade,
  user_id      uuid not null references app_user(id) on delete cascade,
  project_role text not null default 'MEMBER'
    check (project_role in ('MANAGER','MEMBER','VIEWER')),
  can_upload   boolean not null default true,
  created_at   timestamptz not null default now(),
  created_by   uuid references app_user(id),
  primary key (project_id, user_id)
);

create index if not exists idx_project_member_user on project_member (user_id);
