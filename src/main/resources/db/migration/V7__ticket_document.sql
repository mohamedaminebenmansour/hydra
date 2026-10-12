-- Optional document reference on tickets (no FK yet).
alter table ticket add column if not exists document_id uuid;

create index if not exists ticket_document_idx on ticket (document_id);