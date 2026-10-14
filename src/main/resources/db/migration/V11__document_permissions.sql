-- =====================================================================
-- V11: Document approval permission + role grants (idempotent, like V4)
-- DOCUMENT_READ / UPLOAD / DOWNLOAD / DELETE already exist from V2.
-- =====================================================================
insert into permission (id, description) values
  ('DOCUMENT_APPROVE', 'Approuver / rejeter des documents')
on conflict (id) do nothing;

-- Grant to system roles (org_id is null).
insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r
join permission p on p.id = 'DOCUMENT_APPROVE'
where r.org_id is null
  and r.code in ('OWNER','CHEF_PROJET','QA')
on conflict (role_id, permission_id) do nothing;