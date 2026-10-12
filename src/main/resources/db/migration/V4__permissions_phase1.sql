-- Phase 1 permissions for projects / finer document & ticket actions.
-- Idempotent: safe to re-run, never touches rows seeded in V2.
insert into permission (id, description) values
 ('PROJECT_READ',   'Voir les chantiers / projets'),
 ('PROJECT_WRITE',  'Créer et modifier les projets'),
 ('PROJECT_ASSIGN', 'Assigner des personnes aux projets'),
 ('DOCUMENT_DELETE','Supprimer des documents'),
 ('TICKET_ASSIGN',  'Assigner des tickets')
on conflict (id) do nothing;

-- Grants to system roles only (org_id is null). OWNER gets everything so
-- GET /api/roles keeps showing OWNER as "has all permissions".
-- FINANCE deliberately stays without PROJECT_WRITE / PROJECT_ASSIGN.
insert into role_permission (role_id, permission_id)
select r.id, p.id from role r, permission p
where r.org_id is null
  and ((r.code = 'OWNER'       and p.id in ('PROJECT_READ','PROJECT_WRITE','PROJECT_ASSIGN','DOCUMENT_DELETE','TICKET_ASSIGN'))
    or (r.code = 'CHEF_PROJET' and p.id in ('PROJECT_READ','PROJECT_WRITE','PROJECT_ASSIGN','TICKET_ASSIGN'))
    or (r.code = 'BUREAU'      and p.id in ('PROJECT_READ','PROJECT_WRITE','PROJECT_ASSIGN','TICKET_ASSIGN'))
    or (r.code = 'CONDUCTEUR'  and p.id = 'PROJECT_READ')
    or (r.code = 'FINANCE'     and p.id = 'PROJECT_READ'))
on conflict (role_id, permission_id) do nothing;
