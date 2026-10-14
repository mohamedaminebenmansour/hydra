-- =====================================================================
-- V12: Document internal reference + description
--  - reference: unique human-facing id (generated from a global sequence)
--  - description: free-text metadata
-- Idempotent; Flyway runs each version once.
-- =====================================================================

alter table document add column if not exists reference text;
alter table document add column if not exists description text;

-- Race-free monotonic sequence for unique internal references.
create sequence if not exists doc_reference_seq;

-- Unique internal reference (NULLs allowed for pre-existing rows).
create unique index if not exists uq_document_reference on document (reference);

-- Backfill any existing rows that have no reference yet (idempotent).
update document
   set reference = 'PRJ-' || lpad(nextval('doc_reference_seq')::text, 5, '0')
 where reference is null;