create table project (
  id          uuid primary key default gen_random_uuid(),
  org_id      uuid not null references organization(id),
  code        text,
  name        text not null,
  status      text not null default 'ACTIF' check (status in ('ACTIF','CLOTURE')),
  created_by  uuid not null references app_user(id),
  created_at  timestamptz not null default now()
);

create index on project (org_id, created_at desc);