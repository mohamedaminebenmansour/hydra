-- =====================================================================
-- V13: Align DB constraints with the new ticket workflow + project roles
--  - Ticket status: DRAFT → IN_REVIEW → APPROVED → IFC → AS_BUILT
--    (replaces OUVERT / EN_COURS / EN_ATTENTE / RESOLU)
--  - Project role : ADMIN / MANAGER / ENGINEER / VIEWER
--    (replaces MANAGER / MEMBER / VIEWER)
-- Existing rows are migrated to the closest new value. Idempotent.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Ticket status → engineering transmittal workflow
-- ---------------------------------------------------------------------
-- Migrate existing data before tightening the constraint.
update ticket
   set status = case status
                  when 'OUVERT'   then 'DRAFT'
                  when 'EN_COURS' then 'IN_REVIEW'
                  when 'EN_ATTENTE' then 'IN_REVIEW'
                  when 'RESOLU'   then 'AS_BUILT'
                  else 'DRAFT'
                end
 where status in ('OUVERT','EN_COURS','EN_ATTENTE','RESOLU')
    or status is null;

alter table ticket drop constraint if exists ticket_status_check;
alter table ticket add constraint ticket_status_check
  check (status in ('DRAFT','IN_REVIEW','APPROVED','IFC','AS_BUILT'));

-- ---------------------------------------------------------------------
-- 2. Project member role → ADMIN / MANAGER / ENGINEER / VIEWER
-- ---------------------------------------------------------------------
update project_member
   set project_role = case project_role
                        when 'MANAGER' then 'MANAGER'  -- project manager stays
                        when 'MEMBER'  then 'ENGINEER'
                        when 'VIEWER'  then 'VIEWER'
                        else 'ENGINEER'
                      end
 where project_role in ('MANAGER','MEMBER','VIEWER')
    or project_role is null;

-- The V8 inline check is auto-named 'project_member_project_role_check'.
alter table project_member drop constraint if exists project_member_project_role_check;
alter table project_member add constraint project_member_project_role_check
  check (project_role in ('ADMIN','MANAGER','ENGINEER','VIEWER'));
