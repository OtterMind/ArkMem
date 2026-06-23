drop table if exists arkmem_memory_history;
drop table if exists arkmem_memories;

create extension if not exists vector;

create table arkmem_memories (
  id uuid primary key,
  memory text not null,
  metadata jsonb not null default '{}'::jsonb,
  user_id text generated always as (metadata ->> 'user_id') stored,
  agent_id text generated always as (metadata ->> 'agent_id') stored,
  run_id text generated always as (metadata ->> 'run_id') stored,
  embedding vector not null,
  keyword_search_vector tsvector generated always as (
    to_tsvector('simple'::regconfig, coalesce(memory, ''))
  ) stored,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table arkmem_memory_history (
  id uuid primary key,
  memory_id uuid not null references arkmem_memories(id) on delete cascade,
  old_memory text,
  new_memory text,
  event text not null check (event in ('ADD', 'UPDATE', 'DELETE', 'NONE')),
  created_at timestamptz not null default now()
);

comment on table arkmem_memories is 'Long-term memory records';
comment on column arkmem_memories.id is 'Memory identifier';
comment on column arkmem_memories.memory is 'Memory text';
comment on column arkmem_memories.metadata is 'Memory metadata, including scope and source fields';
comment on column arkmem_memories.user_id is 'User ID derived from metadata.user_id';
comment on column arkmem_memories.agent_id is 'Agent ID derived from metadata.agent_id';
comment on column arkmem_memories.run_id is 'Run ID derived from metadata.run_id';
comment on column arkmem_memories.embedding is 'Vector representation of the memory text';
comment on column arkmem_memories.keyword_search_vector is 'Full-text search vector for the memory text';
comment on column arkmem_memories.created_at is 'Memory creation time';
comment on column arkmem_memories.updated_at is 'Last memory update time';
comment on column arkmem_memories.deleted_at is 'Soft-delete time; non-null means deleted';

comment on table arkmem_memory_history is 'Memory change history';
comment on column arkmem_memory_history.id is 'History record identifier';
comment on column arkmem_memory_history.memory_id is 'Related memory identifier';
comment on column arkmem_memory_history.old_memory is 'Previous memory text';
comment on column arkmem_memory_history.new_memory is 'New memory text';
comment on column arkmem_memory_history.event is 'Change event type: ADD, UPDATE, DELETE, or NONE';
comment on column arkmem_memory_history.created_at is 'History record creation time';

create index arkmem_memories_active_created_at_idx
  on arkmem_memories (created_at desc)
  where deleted_at is null;

create index arkmem_memories_active_user_idx
  on arkmem_memories (user_id, created_at desc)
  where deleted_at is null and user_id is not null;

create index arkmem_memories_active_exact_user_idx
  on arkmem_memories (user_id, created_at desc)
  where deleted_at is null and user_id is not null and agent_id is null and run_id is null;

create index arkmem_memories_active_agent_idx
  on arkmem_memories (agent_id, created_at desc)
  where deleted_at is null and agent_id is not null;

create index arkmem_memories_active_user_agent_idx
  on arkmem_memories (user_id, agent_id, created_at desc)
  where deleted_at is null and user_id is not null and agent_id is not null;

create index arkmem_memories_active_run_idx
  on arkmem_memories (run_id, created_at desc)
  where deleted_at is null and run_id is not null;

create index arkmem_memories_active_user_agent_run_idx
  on arkmem_memories (user_id, agent_id, run_id, created_at desc)
  where deleted_at is null and user_id is not null and agent_id is not null and run_id is not null;

create index arkmem_memories_active_metadata_gin_idx
  on arkmem_memories using gin (metadata jsonb_path_ops)
  where deleted_at is null;

create index arkmem_memories_active_keyword_search_idx
  on arkmem_memories using gin (keyword_search_vector)
  where deleted_at is null;

create index arkmem_memory_history_memory_id_created_at_idx
  on arkmem_memory_history (memory_id, created_at);
