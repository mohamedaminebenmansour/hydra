-- STEP 1: Fix doc_folder_template so the 7 global (org_id IS NULL) folders are reliable.
-- Root cause: V8 used unique (org_id, code) which cannot enforce uniqueness when
-- org_id IS NULL (Postgres treats NULLs as distinct), so ON CONFLICT never matched.

-- 1) Remove duplicate null-org rows, keeping the earliest per code (by id).
--    min(uuid) does not exist in PostgreSQL → cast to text then back to uuid.
delete from doc_folder_template t
where t.org_id is null
  and t.id not in (
      select min(id::text)::uuid
      from doc_folder_template
      where org_id is null
      group by code
  );

-- 2) Drop the broken inline unique constraint (auto-named by Postgres).
alter table doc_folder_template drop constraint if exists doc_folder_template_org_id_code_key;

-- 3) Replace with partial unique indexes that handle NULL vs non-NULL org_id correctly.
drop index if exists uq_doc_folder_template_system_code;
drop index if exists uq_doc_folder_template_org_code;
create unique index uq_doc_folder_template_system_code on doc_folder_template (code) where org_id is null;
create unique index uq_doc_folder_template_org_code on doc_folder_template (org_id, code) where org_id is not null;

-- 4) Standardize on underscore codes: drop the old space-coded null rows from V8.
delete from doc_folder_template
where org_id is null
  and code in ('01 ADMIN','02 TECH','03 EXEC','04 QUAL','05 ACHAT','06 FIN','07 INT');

-- 5) Seed the 7 global folders (org_id IS NULL) idempotently, without relying on ON CONFLICT.
insert into doc_folder_template (org_id, code, name, sort_order)
select v.org_id, v.code, v.name, v.sort_order
from (values
    (null::uuid, '01_ADMIN', 'Administratif',        1),
    (null::uuid, '02_TECH',  'Technique',            2),
    (null::uuid, '03_EXEC',  'Exécution',            3),
    (null::uuid, '04_QUAL',  'Qualité / Réception',  4),
    (null::uuid, '05_ACHAT', 'Achats',               5),
    (null::uuid, '06_FIN',   'Finance',              6),
    (null::uuid, '07_INT',   'Interne',              7)
) as v(org_id, code, name, sort_order)
where not exists (
    select 1 from doc_folder_template t
    where t.org_id is null and t.code = v.code
);