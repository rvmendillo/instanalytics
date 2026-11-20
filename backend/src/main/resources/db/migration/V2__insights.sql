-- ---------------------------------------------------------------------------
-- Account-level insights.
--
-- Stored long/narrow: one row per (account, day, metric, breakdown). Meta adds
-- and retires metrics constantly (impressions died, reposts arrived, views
-- replaced plays) and a wide table would need a migration every time. This
-- shape absorbs new metrics and new breakdown dimensions with no DDL.
-- ---------------------------------------------------------------------------

create table account_metric_daily (
    account_id      bigint      not null references ig_account (id) on delete cascade,
    stat_date       date        not null,
    metric          text        not null,      -- views, reach, accounts_engaged, ...
    breakdown       text        not null default '',  -- '' | follow_type | media_product_type | ...
    dimension       text        not null default '',  -- '' | FOLLOWER | REELS | ...
    value           bigint      not null,
    collected_at    timestamptz not null default now(),

    primary key (account_id, stat_date, metric, breakdown, dimension)
);

create index idx_account_metric_series
    on account_metric_daily (account_id, metric, stat_date);

-- ---------------------------------------------------------------------------
-- Media (feed posts + reels). One row per published object.
-- ---------------------------------------------------------------------------

create table media (
    id                  bigserial primary key,
    account_id          bigint      not null references ig_account (id) on delete cascade,
    ig_media_id         text        not null,
    media_product_type  text        not null,  -- FEED | REELS | STORY
    media_type          text,                  -- IMAGE | VIDEO | CAROUSEL_ALBUM
    caption             text,
    permalink           text,
    media_url           text,
    thumbnail_url       text,
    alt_text            text,
    published_at        timestamptz not null,
    like_count          integer,
    comments_count      integer,
    is_shared_to_feed   boolean,
    first_seen_at       timestamptz not null default now(),

    unique (account_id, ig_media_id)
);

create index idx_media_timeline on media (account_id, published_at desc);
create index idx_media_type on media (account_id, media_product_type, published_at desc);

-- ---------------------------------------------------------------------------
-- Media insight snapshots.
--
-- A post keeps accruing views for weeks, so a single value is meaningless.
-- Each sync writes a snapshot; the delta between snapshots is the real
-- velocity signal, and the newest snapshot is the lifetime total.
-- ---------------------------------------------------------------------------

create table media_metric_snapshot (
    media_id        bigint      not null references media (id) on delete cascade,
    captured_at     timestamptz not null,
    metric          text        not null,
    breakdown       text        not null default '',
    dimension       text        not null default '',
    value           bigint      not null,

    primary key (media_id, captured_at, metric, breakdown, dimension)
);

create index idx_media_metric_latest on media_metric_snapshot (media_id, metric, captured_at desc);

-- ---------------------------------------------------------------------------
-- STORY ARCHIVE -- the point of this whole project.
--
-- Instagram exposes story insights for 24 hours and then they are gone for
-- good; there is no historical story endpoint at any price. StoryInsightsJob
-- polls inside that window and these two tables are the permanent record.
-- Nothing here can be rebuilt after the fact, so writes are append-only and
-- the tables are never pruned.
-- ---------------------------------------------------------------------------

create table story_frame (
    id                  bigserial primary key,
    account_id          bigint      not null references ig_account (id) on delete cascade,
    ig_media_id         text        not null,
    media_type          text,
    permalink           text,
    media_url           text,                  -- expires; mirror the bytes if you want them
    archived_media_path text,                  -- local copy written by StoryArchiver
    published_at        timestamptz not null,
    expires_at          timestamptz not null,

    -- The local day this frame belongs to, in the configured zone (ig.zone).
    -- Never derive it from published_at in SQL: date_trunc uses the session
    -- zone (UTC), and a 7am Manila story is 11pm the previous day in UTC -
    -- frames of one story would split across two "days".
    story_day           date        not null,

    -- Position in that day's sequence, so drop-off across a multi-frame story
    -- can be reconstructed. Assigned at capture time by publish order.
    frame_index         integer,
    frames_that_day     integer,

    -- Latest capture wins for display; history lives in story_metric.
    views               bigint,
    reach               bigint,
    replies             bigint,
    shares              bigint,
    reposts             bigint,
    total_interactions  bigint,
    follows             bigint,
    profile_visits      bigint,
    link_clicks         bigint,                -- Facebook Login only
    nav_tap_forward     bigint,
    nav_tap_back        bigint,
    nav_swipe_forward   bigint,                -- "next story" -- swiped away
    nav_tap_exit        bigint,

    -- Set when the API answered code 10 (fewer than 5 viewers). Distinguishes
    -- "too small to report" from "never captured", which otherwise look the same.
    suppressed_low_volume boolean not null default false,

    capture_count       integer     not null default 0,
    first_captured_at   timestamptz,
    last_captured_at    timestamptz,
    complete            boolean     not null default false,  -- captured at least once before expiry

    unique (account_id, ig_media_id)
);

create index idx_story_timeline on story_frame (account_id, published_at desc);
create index idx_story_day on story_frame (account_id, story_day, frame_index);
create index idx_story_expiry on story_frame (expires_at) where complete = false;

-- Every individual capture, so you can see how a story accumulated views
-- hour by hour rather than only its final number.
create table story_metric (
    story_frame_id  bigint      not null references story_frame (id) on delete cascade,
    captured_at     timestamptz not null,
    metric          text        not null,
    dimension       text        not null default '',
    value           bigint      not null,

    primary key (story_frame_id, captured_at, metric, dimension)
);

-- ---------------------------------------------------------------------------
-- Follower demographics. Lifetime snapshots -- Meta returns the audience as it
-- stands now, with no history, so each run is a dated snapshot and the series
-- is something only you will have.
-- ---------------------------------------------------------------------------

create table audience_demographic (
    account_id   bigint      not null references ig_account (id) on delete cascade,
    snapshot_on  date        not null,
    metric       text        not null,  -- follower_demographics | engaged_audience_demographics | reached_audience_demographics
    breakdown    text        not null,  -- age | city | country | gender
    dimension    text        not null,  -- '25-34' | 'Manila, Metro Manila' | 'PH' | 'M'
    value        bigint      not null,
    timeframe    text,                  -- this_week | this_month | ...

    primary key (account_id, snapshot_on, metric, breakdown, dimension)
);

create index idx_audience_series on audience_demographic (account_id, metric, breakdown, snapshot_on);

-- ---------------------------------------------------------------------------
-- Daily account rollup. Denormalised for the dashboard; rebuilt by a nightly
-- job so the follower series survives even when Meta's own follows_and_unfollows
-- metric is unavailable (under 100 followers) or patchy.
-- ---------------------------------------------------------------------------

create table account_daily_snapshot (
    account_id       bigint      not null references ig_account (id) on delete cascade,
    stat_date        date        not null,
    followers_count  integer,
    follows_count    integer,
    media_count      integer,
    follower_delta   integer,
    captured_at      timestamptz not null default now(),

    primary key (account_id, stat_date)
);
