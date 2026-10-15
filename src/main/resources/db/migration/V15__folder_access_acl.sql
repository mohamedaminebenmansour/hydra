-- =====================================================================
-- V15: folder_access — per-folder ACL (role grants or direct user grants)
--  - can_read / can_upload / can_download / can_approve / can_delete
--  - exactly one target per row: role_code XOR user_id (check constraint)
--  - two partial unique indexes for idempotent upserts
--  - backfill: default matrix for folders that already exist
-- Idempotent (where not exists) — safe to re-run.
-- =====================================================================

create table folder_access (
  id           uuid primary key default gen_random_uuid(),
  folder_id    uuid not null references doc_folder(id) on delete cascade,
  -- role_code is NOT a FK: role.code is only unique per (org_id, code) and via a
  -- partial unique index (org_id IS NULL). PostgreSQL cannot reference a partial
  -- unique index. Application code validates that role_code exists.
  role_code    text,
  user_id      uuid references app_user(id) on delete cascade,
  can_read     boolean not null default true,
  can_upload   boolean not null default false,
  can_download boolean not null default true,
  can_approve  boolean not null default false,
  can_delete   boolean not null default false,
  granted_by   uuid references app_user(id),
  created_at   timestamptz not null default now(),
  constraint folder_access_target_check check (
      (role_code is not null and user_id is null)
   or (role_code is null     and user_id is not null))
);

create unique index uq_folder_access_role on folder_access (folder_id, role_code)
  where role_code is not null;
create unique index uq_folder_access_user on folder_access (folder_id, user_id)
  where user_id is not null;
create index idx_folder_access_folder on folder_access (folder_id);

alter table folder_access enable row level security;

-- ---------------------------------------------------------------------
-- Backfill: apply the default matrix to every existing folder.
-- Match by code prefix so legacy codes ('01 ADMIN', '07_INT') also work.
-- Kept in sync with FolderAccessService.MATRIX (Java).
-- ---------------------------------------------------------------------
with matrix(prefix, role_code, can_read, can_upload, can_download, can_approve, can_delete) as (
  values
    -- 01_ADMIN
    ('01','OWNER',       true, true, true, true,  true),
    ('01','DIRECTION',   true, false, true, false, false),
    ('01','CHEF_PROJET', true, false, true, false, false),
    ('01','DOC_CTRL',    true, true, true, false, true),
    -- 02_TECH
    ('02','OWNER',       true, true, true, true,  true),
    ('02','CHEF_PROJET', true, true, true, true,  true),
    ('02','INGENIEUR',   true, true, true, false, false),
    ('02','CONDUCTEUR',  true, true, true, false, false),
    ('02','QA',          true, false, true, true,  false),
    ('02','DOC_CTRL',    true, true, true, false, true),
    -- 03_EXEC
    ('03','OWNER',         true, true, true, true,  true),
    ('03','CHEF_PROJET',   true, true, true, false, false),
    ('03','CONDUCTEUR',    true, true, true, false, false),
    ('03','CHEF_CHANTIER', true, true, true, false, false),
    ('03','INGENIEUR',     true, true, true, false, false),
    -- 04_QUAL
    ('04','OWNER',       true, true, true, true,  true),
    ('04','CHEF_PROJET', true, true, true, true,  true),
    ('04','QA',          true, true, true, true,  false),
    ('04','DOC_CTRL',    true, true, true, false, true),
    -- 05_ACHAT
    ('05','OWNER',       true, true, true, true,  true),
    ('05','ACHAT',       true, true, true, false, false),
    ('05','FINANCE',     true, false, true, false, false),
    ('05','CHEF_PROJET', true, true, true, false, false),
    -- 06_FIN — never CONDUCTEUR / CHEF_CHANTIER / SOUS_TRAITANT
    ('06','OWNER',     true, true, true, true,  true),
    ('06','FINANCE',   true, false, true, false, false),
    ('06','DIRECTION', true, false, true, false, false),
    -- 07_HSE (prefix also matches legacy '07_INT')
    ('07','OWNER',        true, true, true, true,  true),
    ('07','HSE',          true, true, true, false, false),
    ('07','CHEF_PROJET',  true, true, true, false, false),
    ('07','CONDUCTEUR',   true, true, true, false, false)
)
insert into folder_access (folder_id, role_code, can_read, can_upload,
                           can_download, can_approve, can_delete)
select f.id, m.role_code, m.can_read, m.can_upload,
       m.can_download, m.can_approve, m.can_delete
from doc_folder f
join matrix m on m.prefix = left(f.code, 2)
where not exists (
  select 1 from folder_access fa
  where fa.folder_id = f.id and fa.role_code = m.role_code
);