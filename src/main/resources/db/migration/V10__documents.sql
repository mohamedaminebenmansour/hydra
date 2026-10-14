-- =====================================================================
-- V10: Document Management foundation
--  - document, document_revision, tag, document_tag
--  - link ticket.document_id -> document(id)
--  - project.phase (identification -> close-out)
--  - rename folder 07_INT -> 07_HSE (HSE & Environnement)
-- Idempotent where possible; Flyway runs each version once.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Project phase (metadata for filtering/reporting; folders stay fixed 7)
-- ---------------------------------------------------------------------
alter table project add column if not exists phase text not null default 'EXECUTION';

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'project_phase_check') then
    alter table project add constraint project_phase_check
      check (phase in ('FEASIBILITY','DESIGN','TENDER','AWARD','EXECUTION','RECEPTION','GUARANTEE','CLOSEOUT'));
  end if;
end $$;

-- ---------------------------------------------------------------------
-- Rename mandatory folder 07_INT -> 07_HSE
-- ---------------------------------------------------------------------
update doc_folder_template
   set code = '07_HSE', name = 'HSE & Environnement'
 where org_id is null and code = '07_INT';

update doc_folder
   set code = '07_HSE', name = 'HSE & Environnement'
 where code = '07_INT';

-- ---------------------------------------------------------------------
-- Tag (org-scoped)
-- ---------------------------------------------------------------------
create table if not exists tag (
  id         uuid primary key default gen_random_uuid(),
  org_id     uuid not null references organization(id) on delete cascade,
  name       text not null,
  created_by uuid not null references app_user(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz,
  unique (org_id, name)
);
create index if not exists idx_tag_org on tag (org_id);

-- ---------------------------------------------------------------------
-- Document (belongs to one folder of its project; project derived from folder)
-- ---------------------------------------------------------------------
create table if not exists document (
  id          uuid primary key default gen_random_uuid(),
  org_id      uuid not null references organization(id) on delete cascade,
  folder_id   uuid not null references doc_folder(id) on delete restrict,
  ticket_id   uuid references ticket(id) on delete set null,
  title       text not null,
  doc_type    text not null,
  current_rev text,
  status      text not null default 'DRAFT'
                check (status in ('DRAFT','IN_REVIEW','APPROVED','REJECTED','SUPERSEDED','ARCHIVED')),
  deleted_at  timestamptz,
  created_by  uuid not null references app_user(id),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz
);
create index if not exists idx_document_org_folder on document (org_id, folder_id);
create index if not exists idx_document_ticket on document (ticket_id);
create index if not exists idx_document_created on document (created_at desc);

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'document_doc_type_check') then
    alter table document add constraint document_doc_type_check
      check (doc_type in ('APD','ETUDE','PLAN','PV_RECEPTION','MARCHE','FACTURE',
                          'SITUATION','BON_LIVRAISON','RAPPORT','PHOTO','HSE','AUTRE'));
  end if;
end $$;

-- ---------------------------------------------------------------------
-- Document revision (immutable; one row per Rev)
-- ---------------------------------------------------------------------
create table if not exists document_revision (
  id              uuid primary key default gen_random_uuid(),
  document_id     uuid not null references document(id) on delete cascade,
  rev_label       text not null,
  file_name       text not null,
  storage_key     text not null,
  mime_type       text,
  size_bytes      bigint,
  checksum_sha256 text,
  change_note     text,
  created_by      uuid not null references app_user(id),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz,
  unique (document_id, rev_label)
);
create index if not exists idx_revision_document on document_revision (document_id, created_at);

-- ---------------------------------------------------------------------
-- Document <-> Tag (many-to-many)
-- ---------------------------------------------------------------------
create table if not exists document_tag (
  document_id uuid not null references document(id) on delete cascade,
  tag_id      uuid not null references tag(id) on delete cascade,
  primary key (document_id, tag_id)
);
create index if not exists idx_document_tag_tag on document_tag (tag_id);

-- ---------------------------------------------------------------------
-- Formalise ticket.document_id (column added in V7 without FK)
-- ---------------------------------------------------------------------
alter table ticket drop constraint if exists ticket_document_id_fkey;
alter table ticket add constraint ticket_document_id_fkey
  foreign key (document_id) references document(id) on delete set null;

-- ---------------------------------------------------------------------
-- Row level security (same convention as V1)
-- ---------------------------------------------------------------------
alter table document         enable row level security;
alter table document_revision enable row level security;
alter table tag              enable row level security;
alter table document_tag     enable row level security;