-- =====================================================================
-- V16: Fix Row Level Security on document tables
--
-- V10 enabled RLS on document, document_revision, tag, and document_tag
-- BUT never created any RLS policies. PostgreSQL's behaviour with RLS
-- enabled and zero policies is to DENY ALL rows to every non-superuser,
-- including the application DB user. This causes:
--   • Every SELECT to return 0 rows (GET /documents → empty or 500)
--   • Every INSERT to fail silently (POST /upload → 500)
--
-- The application already enforces tenant isolation via Spring-side
-- org_id scoping and FolderAccessService ACL checks. PostgreSQL-level
-- RLS adds no security value here and only breaks the app.
--
-- Fix: disable RLS on all four tables so the app DB user can read and
-- write normally. The existing org_id + folder ACL guards in Java remain.
-- =====================================================================

alter table document          disable row level security;
alter table document_revision disable row level security;
alter table tag               disable row level security;
alter table document_tag      disable row level security;

-- Also ensure the application user (the one in DB_USER env var) is not
-- accidentally limited to row-level access. If you are using the Supabase
-- service_role key for the app, this is a no-op. If you are using an
-- anon/authenticated key, add a GRANT here:
--
--   grant select, insert, update, delete
--     on document, document_revision, tag, document_tag
--     to <your_db_user>;
--
-- Replace <your_db_user> with the value of your DB_USER env variable.
