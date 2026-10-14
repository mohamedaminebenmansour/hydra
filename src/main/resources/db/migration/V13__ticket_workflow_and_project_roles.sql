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
-- IMPORTANT: drop the old constraint FIRST, then migrate data, then add the new one.
-- Otherwise UPDATE to AS_BUILT / DRAFT / etc. violates the still-active old check.

alter table ticket drop constraint if exists ticket_status_check;

update ticket
   set status = case status
                  when 'OUVERT'     then 'DRAFT'
                  when 'EN_COURS'   then 'IN_REVIEW'
                  when 'EN_ATTENTE' then 'IN_REVIEW'
                  when 'RESOLU'     then 'AS_BUILT'
                  -- already-migrated or app-written values stay as-is
                  when 'DRAFT'      then 'DRAFT'
                  when 'IN_REVIEW'  then 'IN_REVIEW'
                  when 'APPROVED'   then 'APPROVED'
                  when 'IFC'        then 'IFC'
                  when 'AS_BUILT'   then 'AS_BUILT'
                  else 'DRAFT'
                end
 where status is distinct from case status
                  when 'OUVERT'     then 'DRAFT'
                  when 'EN_COURS'   then 'IN_REVIEW'
                  when 'EN_ATTENTE' then 'IN_REVIEW'
                  when 'RESOLU'     then 'AS_BUILT'
                  when 'DRAFT'      then 'DRAFT'
                  when 'IN_REVIEW'  then 'IN_REVIEW'
                  when 'APPROVED'   then 'APPROVED'
                  when 'IFC'        then 'IFC'
                  when 'AS_BUILT'   then 'AS_BUILT'
                  else 'DRAFT'
                end
    or status is null;

alter table ticket add constraint ticket_status_check
  check (status in ('DRAFT','IN_REVIEW','APPROVED','IFC','AS_BUILT'));

-- ---------------------------------------------------------------------
-- 2. Project member role → ADMIN / MANAGER / ENGINEER / VIEWER
-- ---------------------------------------------------------------------
alter table project_member drop constraint if exists project_member_project_role_check;

update project_member
   set project_role = case project_role
                        when 'MANAGER' then 'MANAGER'
                        when 'MEMBER'  then 'ENGINEER'
                        when 'VIEWER'  then 'VIEWER'
                        when 'ADMIN'   then 'ADMIN'
                        when 'ENGINEER'then 'ENGINEER'
                        else 'ENGINEER'
                      end
 where project_role is distinct from case project_role
                        when 'MANAGER' then 'MANAGER'
                        when 'MEMBER'  then 'ENGINEER'
                        when 'VIEWER'  then 'VIEWER'
                        when 'ADMIN'   then 'ADMIN'
                        when 'ENGINEER'then 'ENGINEER'
                        else 'ENGINEER'
                      end
    or project_role is null;

alter table project_member add constraint project_member_project_role_check
  check (project_role in ('ADMIN','MANAGER','ENGINEER','VIEWER'));