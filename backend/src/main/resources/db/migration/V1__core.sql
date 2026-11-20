-- ---------------------------------------------------------------------------
-- Core: connected accounts, token storage, collector observability.
-- ---------------------------------------------------------------------------

create table ig_account (
    id                  bigserial primary key,
    ig_user_id          text        not null unique,   -- Instagram professional account id
    username            text        not null,
    account_type        text,                          -- BUSINESS | MEDIA_CREATOR
    name                text,
    biography           text,
    website             text,
    profile_picture_url text,
    followers_count     integer,
    follows_count       integer,
    media_count         integer,

    -- Which login path this account was connected through. Decides the API host
    -- and which metrics are reachable (see IgMetricCatalog).
    login_type          text        not null default 'INSTAGRAM',  -- INSTAGRAM | FACEBOOK

    -- Long-lived token. Encrypted at rest by TokenCipher, never logged.
    access_token_enc    text        not null,
    token_expires_at    timestamptz not null,
    token_refreshed_at  timestamptz,

    connected_at        timestamptz not null default now(),
    last_synced_at      timestamptz,
    active              boolean     not null default true
);

comment on column ig_account.token_expires_at is
    'Long-lived tokens last 60 days. TokenRefreshJob refreshes well before this.';

-- ---------------------------------------------------------------------------
-- Collector runs. Every scheduled job writes one row so failures are visible
-- instead of silent, and so a job can tell what it already covered.
-- ---------------------------------------------------------------------------

create table collector_run (
    id              bigserial primary key,
    account_id      bigint      not null references ig_account (id) on delete cascade,
    collector       text        not null,
    started_at      timestamptz not null default now(),
    finished_at     timestamptz,
    status          text        not null default 'RUNNING',  -- RUNNING | OK | PARTIAL | FAILED
    items_written   integer     not null default 0,
    api_calls       integer     not null default 0,
    error_code      integer,
    error_message   text
);

create index idx_collector_run_lookup
    on collector_run (account_id, collector, started_at desc);

-- ---------------------------------------------------------------------------
-- Raw API response log. Optional but cheap, and the only way to backfill a
-- metric you didn't model yet without re-querying a window Meta has aged out.
-- ---------------------------------------------------------------------------

create table api_response_log (
    id          bigserial primary key,
    account_id  bigint      not null references ig_account (id) on delete cascade,
    endpoint    text        not null,
    params      jsonb       not null,
    payload     jsonb       not null,
    fetched_at  timestamptz not null default now()
);

create index idx_api_response_log_fetch on api_response_log (account_id, fetched_at desc);
