create table if not exists shows (
  id text primary key,
  name text not null,
  price_paise bigint not null check (price_paise > 0),
  per_user_limit int not null default 4,
  created_at timestamptz not null default now()
);
create table if not exists reservations (
  id text primary key,
  show_id text not null references shows(id),
  user_id text not null,
  idempotency_key text not null,
  request_hash text not null,
  seats text not null,
  amount_paise bigint not null,
  status text not null check (status in ('confirmed','cancelled')),
  created_at timestamptz not null default now(),
  constraint uq_user_idem unique (user_id, idempotency_key)
);
create table if not exists seats (
  show_id text not null references shows(id),
  label text not null,
  ord int not null,
  status text not null check (status in ('available','held','confirmed')),
  user_id text,
  reservation_id text,
  primary key (show_id, label)
);
create index if not exists idx_seats_user on seats (show_id, user_id) where user_id is not null;
create index if not exists idx_seats_resv on seats (reservation_id) where reservation_id is not null;
