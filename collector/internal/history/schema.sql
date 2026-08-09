CREATE TABLE IF NOT EXISTS peers (
  peer_id BLOB PRIMARY KEY,
  interface_id TEXT NOT NULL,
  label TEXT,
  first_seen INTEGER NOT NULL,
  last_seen INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS samples_raw (
  peer_id BLOB NOT NULL,
  ts INTEGER NOT NULL,
  online INTEGER NOT NULL,
  observed_s INTEGER NOT NULL,
  online_delta_s INTEGER NOT NULL,
  handshake_raw INTEGER,
  handshake_age_s INTEGER,
  upload_delta INTEGER NOT NULL,
  download_delta INTEGER NOT NULL,
  counter_generation INTEGER NOT NULL,
  counter_reset INTEGER NOT NULL,
  PRIMARY KEY(peer_id, ts)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS samples_5m (
  peer_id BLOB NOT NULL,
  bucket_ts INTEGER NOT NULL,
  observed_s INTEGER NOT NULL,
  online_s INTEGER NOT NULL,
  upload_delta INTEGER NOT NULL,
  download_delta INTEGER NOT NULL,
  last_online_ts INTEGER,
  reset_count INTEGER NOT NULL,
  PRIMARY KEY(peer_id, bucket_ts)
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS samples_1h (
  peer_id BLOB NOT NULL,
  bucket_ts INTEGER NOT NULL,
  observed_s INTEGER NOT NULL,
  online_s INTEGER NOT NULL,
  upload_delta INTEGER NOT NULL,
  download_delta INTEGER NOT NULL,
  last_online_ts INTEGER,
  reset_count INTEGER NOT NULL,
  PRIMARY KEY(peer_id, bucket_ts)
) WITHOUT ROWID;
