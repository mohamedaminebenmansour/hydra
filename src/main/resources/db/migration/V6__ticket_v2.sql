-- Work-order columns for tickets: assignment, priority, dates, project link.
alter table ticket add column if not exists project_id uuid references project(id);
alter table ticket add column if not exists assigned_to uuid references app_user(id);
alter table ticket add column if not exists priority text not null default 'MEDIUM'
  check (priority in ('LOW','MEDIUM','HIGH'));
alter table ticket add column if not exists start_at date;
alter table ticket add column if not exists due_at date;
alter table ticket add column if not exists updated_at timestamptz;

-- Expand the status workflow: OUVERT -> EN_COURS -> EN_ATTENTE -> RESOLU.
-- Postgres auto-names the V1 inline check 'ticket_status_check'; drop by that
-- name if present (it exists only when V5 ran before V6 in a fresh DB).
alter table ticket drop constraint if exists ticket_status_check;
alter table ticket add constraint ticket_status_check
  check (status in ('OUVERT','EN_COURS','EN_ATTENTE','RESOLU'));

create index if not exists ticket_org_status_idx on ticket (org_id, status);
create index if not exists ticket_assigned_to_idx on ticket (assigned_to);
create index if not exists ticket_project_idx on ticket (project_id);
create index if not exists ticket_due_at_idx on ticket (due_at);