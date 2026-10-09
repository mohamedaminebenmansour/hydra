insert into permission values
 ('USER_CREATE','Add a user'),
 ('USER_UPDATE','Edit user, reset password'),
 ('USER_DEACTIVATE','Deactivate user and revoke sessions'),
 ('ROLE_ASSIGN','Assign or remove roles'),
 ('ROLE_MANAGE','Create and edit custom roles'),
 ('PROJECT_MANAGE','Create and manage projects'),
 ('DOCUMENT_READ','View documents in app'),
 ('DOCUMENT_UPLOAD','Upload documents and versions'),
 ('DOCUMENT_DOWNLOAD','Download document files'),
 ('AUDIT_VIEW','See the activity report'),
 ('TICKET_READ','See tickets'),
 ('TICKET_WRITE','Create and close tickets');

insert into role (org_id, code, label, scope_type) values
 (null,'OWNER','Gérant','ALL'),
 (null,'BUREAU','Bureau','ALL'),
 (null,'CHEF_PROJET','Chef de projet','ASSIGNED'),
 (null,'CONDUCTEUR','Conducteur de travaux','ASSIGNED'),
 (null,'QA','Qualité','ASSIGNED'),
 (null,'FINANCE','Finance','ALL'),
 (null,'ACHAT','Achat','ALL');

insert into role_permission (role_id, permission_id)
select r.id, p.id from role r, permission p
where r.code='OWNER';

insert into role_permission (role_id, permission_id)
select r.id, p.id from role r, permission p
where (r.code='BUREAU'      and p.id in ('DOCUMENT_READ','DOCUMENT_UPLOAD','TICKET_READ','TICKET_WRITE'))
   or (r.code='CHEF_PROJET' and p.id in ('DOCUMENT_READ','DOCUMENT_UPLOAD','TICKET_READ','TICKET_WRITE'))
   or (r.code='CONDUCTEUR'  and p.id in ('DOCUMENT_READ','TICKET_READ','TICKET_WRITE'))
   or (r.code='QA'          and p.id = 'DOCUMENT_READ')
   or (r.code='FINANCE'     and p.id in ('DOCUMENT_READ','DOCUMENT_DOWNLOAD','TICKET_READ'))
   or (r.code='ACHAT'       and p.id in ('DOCUMENT_READ','DOCUMENT_UPLOAD'));

insert into incompatible_role_pair values
 ('CHEF_PROJET','QA','The executor cannot verify his own work.'),
 ('QA','CHEF_PROJET','The executor cannot verify his own work.'),
 ('FINANCE','ACHAT','The person who buys cannot be the person who pays.'),
 ('ACHAT','FINANCE','The person who buys cannot be the person who pays.');