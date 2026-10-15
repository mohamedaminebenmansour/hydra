-- =====================================================================
-- V14: Company-level roles + folder / project-admin permissions
--  - 3 new permissions : FOLDER_MANAGE, FOLDER_GRANT, PROJECT_ADMIN
--  - 6 new system roles: DIRECTION, INGENIEUR, HSE, DOC_CTRL,
--                        CHEF_CHANTIER, SOUS_TRAITANT (org_id IS NULL)
--  - Default matrix for ALL system roles (OWNER gets every permission)
--  - Toxic pairs CHEF_PROJET<->QA and FINANCE<->ACHAT are kept untouched
-- Idempotent: re-running inserts nothing new (ON CONFLICT / NOT EXISTS).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. New permissions (idempotent)
-- ---------------------------------------------------------------------
insert into permission (id, description) values
  ('FOLDER_MANAGE', 'Configurer les droits d''accès à un dossier projet'),
  ('FOLDER_GRANT',  'Attribuer / retirer l''accès à un dossier pour un utilisateur ou un rôle'),
  ('PROJECT_ADMIN', 'Administrateur de projet (membres + ACL des dossiers)')
on conflict (id) do nothing;

-- ---------------------------------------------------------------------
-- 2. New system roles (org_id IS NULL, scope ASSIGNED)
--    Cannot use ON CONFLICT (org_id, code): NULL org_id never conflicts.
--    Use NOT EXISTS instead (same trick as V9 folder templates).
-- ---------------------------------------------------------------------
insert into role (org_id, code, label, scope_type)
select null::uuid, v.code, v.label, 'ASSIGNED'
from (values
  ('DIRECTION',      'Direction / DG adjoint'),
  ('INGENIEUR',      'Ingénieur bureau d''études'),
  ('HSE',            'Hygiène Sécurité Environnement'),
  ('DOC_CTRL',       'Contrôle documentaire'),
  ('CHEF_CHANTIER',  'Chef d''équipe / Team leader chantier'),
  ('SOUS_TRAITANT',  'Sous-traitant (accès restreint)')
) as v(code, label)
where not exists (
  select 1 from role r where r.org_id is null and r.code = v.code
);

-- ---------------------------------------------------------------------
-- 3. Default matrix — additive grants only (never deletes existing grants).
--    'DOCUMENT_*' = READ, UPLOAD, DOWNLOAD, DELETE, APPROVE
--    'TICKET_*'   = READ, WRITE, ASSIGN
--    OWNER always receives EVERY permission, including new ones.
-- ---------------------------------------------------------------------
insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r
cross join permission p
where r.org_id is null
  and (
    -- OWNER: everything, including FOLDER_* / PROJECT_ADMIN
    r.code = 'OWNER'

    or (r.code = 'DIRECTION'
        and p.id in ('PROJECT_READ','DOCUMENT_READ','DOCUMENT_DOWNLOAD',
                     'TICKET_READ','AUDIT_VIEW'))

    -- CHEF_PROJET: project lead + full doc/ticket + folder config
    or (r.code = 'CHEF_PROJET'
        and p.id in ('PROJECT_READ','PROJECT_WRITE','PROJECT_ASSIGN',
                     'DOCUMENT_READ','DOCUMENT_UPLOAD','DOCUMENT_DOWNLOAD',
                     'DOCUMENT_DELETE','DOCUMENT_APPROVE',
                     'TICKET_READ','TICKET_WRITE','TICKET_ASSIGN',
                     'FOLDER_MANAGE'))

    or (r.code = 'CONDUCTEUR'
        and p.id in ('PROJECT_READ','DOCUMENT_READ','DOCUMENT_UPLOAD',
                     'TICKET_READ','TICKET_WRITE'))

    or (r.code = 'INGENIEUR'
        and p.id in ('PROJECT_READ','DOCUMENT_READ','DOCUMENT_UPLOAD',
                     'TICKET_READ','TICKET_WRITE'))

    or (r.code = 'QA'
        and p.id in ('PROJECT_READ','DOCUMENT_READ','DOCUMENT_APPROVE',
                     'TICKET_READ'))

    -- FINANCE: read + download only (no upload on technical folders later)
    or (r.code = 'FINANCE'
        and p.id in ('PROJECT_READ','DOCUMENT_READ','DOCUMENT_DOWNLOAD'))

    or (r.code = 'ACHAT'
        and p.id in ('PROJECT_READ','DOCUMENT_READ','DOCUMENT_UPLOAD'))

    or (r.code = 'HSE'
        and p.id in ('PROJECT_READ','DOCUMENT_READ','DOCUMENT_UPLOAD'))

    or (r.code = 'DOC_CTRL'
        and p.id in ('PROJECT_READ',
                     'DOCUMENT_READ','DOCUMENT_UPLOAD','DOCUMENT_DOWNLOAD',
                     'DOCUMENT_DELETE','DOCUMENT_APPROVE',
                     'FOLDER_MANAGE'))

    or (r.code = 'CHEF_CHANTIER'
        and p.id in ('PROJECT_READ','DOCUMENT_READ','DOCUMENT_UPLOAD',
                     'TICKET_READ'))

    -- SOUS_TRAITANT: very restricted — read-only on project + documents
    or (r.code = 'SOUS_TRAITANT'
        and p.id in ('PROJECT_READ','DOCUMENT_READ'))
  )
on conflict (role_id, permission_id) do nothing;

-- ---------------------------------------------------------------------
-- 4. Toxic pairs (separation of duties) — keep existing, add if missing.
--    CHEF_PROJET <-> QA  and  FINANCE <-> ACHAT (both directions).
-- ---------------------------------------------------------------------
insert into incompatible_role_pair (role_a, role_b, reason)
select v.role_a, v.role_b, v.reason
from (values
  ('CHEF_PROJET','QA','The executor cannot verify his own work.'),
  ('QA','CHEF_PROJET','The executor cannot verify his own work.'),
  ('FINANCE','ACHAT','The person who buys cannot be the person who pays.'),
  ('ACHAT','FINANCE','The person who buys cannot be the person who pays.')
) as v(role_a, role_b, reason)
where not exists (
  select 1 from incompatible_role_pair irp
  where irp.role_a = v.role_a and irp.role_b = v.role_b
);
