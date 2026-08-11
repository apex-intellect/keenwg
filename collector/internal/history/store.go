package history

import (
	"context"
	"database/sql"
	_ "embed"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"sync/atomic"
	"time"

	"github.com/apex-intellect/keenwg/collector/internal/model"
	_ "modernc.org/sqlite"
)

//go:embed schema.sql
var schemaSQL string

var (
	ErrInvalidPeerID   = errors.New("invalid peer id")
	ErrInvalidSample   = errors.New("invalid history sample")
	ErrStorageDegraded = errors.New("history storage is degraded")
)

type Resolution string

const (
	ResolutionAuto Resolution = "auto"
	ResolutionRaw  Resolution = "raw"
	Resolution5M   Resolution = "5m"
	Resolution1H   Resolution = "1h"
)

type StorageStatus string

const (
	StorageOK       StorageStatus = "ok"
	StorageDegraded StorageStatus = "degraded"
)

type Config struct {
	Path                string
	MaxBytes            int64
	RawRetention        time.Duration
	FiveMinuteRetention time.Duration
	HourlyRetention     time.Duration
}

type Store struct {
	db                  *sql.DB
	path                string
	max                 int64
	rawRetention        time.Duration
	fiveMinuteRetention time.Duration
	hourlyRetention     time.Duration
	status              atomic.Uint32
}

func Open(ctx context.Context, cfg Config) (*Store, error) {
	if cfg.Path == "" {
		return nil, errors.New("database path is required")
	}
	if cfg.MaxBytes <= 0 {
		cfg.MaxBytes = 96 << 20
	}
	if cfg.RawRetention <= 0 {
		cfg.RawRetention = 7 * 24 * time.Hour
	}
	if cfg.FiveMinuteRetention <= 0 {
		cfg.FiveMinuteRetention = 90 * 24 * time.Hour
	}
	if cfg.HourlyRetention <= 0 {
		cfg.HourlyRetention = 400 * 24 * time.Hour
	}
	db, err := sql.Open("sqlite", cfg.Path)
	if err != nil {
		return nil, fmt.Errorf("open history database: %w", err)
	}
	db.SetMaxOpenConns(1)
	s := &Store{db: db, path: cfg.Path, max: cfg.MaxBytes, rawRetention: cfg.RawRetention, fiveMinuteRetention: cfg.FiveMinuteRetention, hourlyRetention: cfg.HourlyRetention}
	for _, statement := range []string{
		"PRAGMA journal_mode=WAL", "PRAGMA synchronous=NORMAL", "PRAGMA foreign_keys=ON",
		"PRAGMA busy_timeout=2000", "PRAGMA wal_autocheckpoint=256",
		"PRAGMA journal_size_limit=4194304", "PRAGMA cache_size=-2048", "PRAGMA temp_store=MEMORY",
	} {
		if _, err := db.ExecContext(ctx, statement); err != nil {
			db.Close()
			return nil, fmt.Errorf("configure sqlite: %w", err)
		}
	}
	if _, err := db.ExecContext(ctx, schemaSQL); err != nil {
		db.Close()
		return nil, fmt.Errorf("initialize schema: %w", err)
	}
	var check string
	if err := db.QueryRowContext(ctx, "PRAGMA quick_check").Scan(&check); err != nil || check != "ok" {
		db.Close()
		return nil, fmt.Errorf("sqlite quick_check failed: %s: %w", check, err)
	}
	if err := s.refreshStorageState(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("measure sqlite storage: %w", err)
	}
	return s, nil
}

func decodePeerID(id string) ([]byte, error) {
	if len(id) != 64 {
		return nil, ErrInvalidPeerID
	}
	b, err := hex.DecodeString(id)
	if err != nil || len(b) != 32 {
		return nil, ErrInvalidPeerID
	}
	return b, nil
}

func (s *Store) Append(ctx context.Context, samples []ReducedSample) error {
	if s.StorageState() == StorageDegraded {
		return ErrStorageDegraded
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, sample := range samples {
		peerID, err := decodePeerID(sample.PeerID)
		if err != nil {
			return err
		}
		if sample.InterfaceID == "" || !sample.Accepted || sample.ObservedSeconds < 0 || sample.OnlineDeltaSeconds < 0 || sample.ClientUploadBytes < 0 || sample.ClientDownloadBytes < 0 {
			return ErrInvalidSample
		}
		ts := sample.ObservedAt.Unix()
		if _, err := tx.ExecContext(ctx, `
INSERT INTO peers(peer_id, interface_id, label, first_seen, last_seen) VALUES(?,?,?,?,?)
ON CONFLICT(peer_id) DO UPDATE SET interface_id=excluded.interface_id, label=excluded.label, last_seen=excluded.last_seen`,
			peerID, sample.InterfaceID, sample.Label, ts, ts); err != nil {
			return fmt.Errorf("append peer: %w", err)
		}
		if _, err := tx.ExecContext(ctx, `
INSERT INTO samples_raw(peer_id,ts,online,observed_s,online_delta_s,handshake_raw,handshake_age_s,upload_delta,download_delta,counter_generation,counter_reset)
VALUES(?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(peer_id,ts) DO UPDATE SET online=excluded.online,observed_s=excluded.observed_s,online_delta_s=excluded.online_delta_s,
handshake_raw=excluded.handshake_raw,handshake_age_s=excluded.handshake_age_s,upload_delta=excluded.upload_delta,
download_delta=excluded.download_delta,counter_generation=excluded.counter_generation,counter_reset=excluded.counter_reset`,
			peerID, ts, sample.Online, sample.ObservedSeconds, sample.OnlineDeltaSeconds, nullableInt(sample.HandshakeRaw),
			nullableInt(sample.HandshakeAgeSeconds), sample.ClientUploadBytes, sample.ClientDownloadBytes,
			sample.CounterGeneration, sample.CounterReset); err != nil {
			return fmt.Errorf("append raw sample: %w", err)
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	return s.refreshStorageState(ctx)
}

func nullableInt(v *int64) any {
	if v == nil {
		return nil
	}
	return *v
}

func (s *Store) History(ctx context.Context, peerID string, from, to int64, resolution Resolution, limit int) (model.History, error) {
	id, err := decodePeerID(peerID)
	if err != nil {
		return model.History{}, err
	}
	if from < 0 || to <= from || limit < 1 || limit > 2000 {
		return model.History{}, errors.New("invalid history range or limit")
	}
	if resolution == ResolutionAuto {
		switch span := to - from; {
		case span <= 7*24*3600:
			resolution = ResolutionRaw
		case span <= 90*24*3600:
			resolution = Resolution5M
		default:
			resolution = Resolution1H
		}
	}
	result := model.History{PeerID: peerID, From: from, To: to, Resolution: string(resolution), Points: []model.HistoryPoint{}}
	var rows *sql.Rows
	if resolution == ResolutionRaw {
		rows, err = s.db.QueryContext(ctx, `SELECT ts,observed_s,online_delta_s,upload_delta,download_delta,online,counter_reset FROM samples_raw
WHERE peer_id=? AND ts>=? AND ts<? ORDER BY ts LIMIT ?`, id, from, to, limit)
	} else {
		table := "samples_5m"
		bucketSeconds := int64(300)
		if resolution == Resolution1H {
			table = "samples_1h"
			bucketSeconds = 3600
		} else if resolution != Resolution5M {
			return model.History{}, errors.New("invalid resolution")
		}
		from = alignDown(from, bucketSeconds)
		to = alignUp(to, bucketSeconds)
		result.From = from
		result.To = to
		rows, err = s.db.QueryContext(ctx, `SELECT bucket_ts,observed_s,online_s,upload_delta,download_delta,last_online_ts,reset_count
FROM `+table+` WHERE peer_id=? AND bucket_ts>=? AND bucket_ts<? ORDER BY bucket_ts LIMIT ?`, id, from, to, limit)
	}
	if err != nil {
		return model.History{}, err
	}
	defer rows.Close()
	for rows.Next() {
		var point model.HistoryPoint
		var lastOnline sql.NullInt64
		var resets int64
		if resolution == ResolutionRaw {
			var online bool
			if err := rows.Scan(&point.At, &point.ObservedSeconds, &point.OnlineSeconds, &point.ClientUploadBytes, &point.ClientDownloadBytes, &online, &resets); err != nil {
				return model.History{}, err
			}
			if online {
				lastOnline = sql.NullInt64{Int64: point.At, Valid: true}
			}
		} else {
			if err := rows.Scan(&point.At, &point.ObservedSeconds, &point.OnlineSeconds, &point.ClientUploadBytes, &point.ClientDownloadBytes, &lastOnline, &resets); err != nil {
				return model.History{}, err
			}
		}
		result.Points = append(result.Points, point)
		result.ObservedSeconds += point.ObservedSeconds
		result.OnlineSeconds += point.OnlineSeconds
		result.ClientUploadBytes += point.ClientUploadBytes
		result.ClientDownloadBytes += point.ClientDownloadBytes
		result.CounterResets += resets
		if lastOnline.Valid {
			at := lastOnline.Int64
			result.LastOnlineAt = &at
		}
	}
	if err := rows.Err(); err != nil {
		return model.History{}, err
	}
	if window := to - from; window > 0 {
		result.CoverageRatio = float64(result.ObservedSeconds) / float64(window)
		if result.CoverageRatio > 1 {
			result.CoverageRatio = 1
		}
	}
	return result, nil
}

func (s *Store) Maintain(ctx context.Context, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	complete5m := alignDown(now.Unix(), 300)
	complete1h := alignDown(now.Unix(), 3600)
	if _, err := tx.ExecContext(ctx, `INSERT INTO samples_5m(peer_id,bucket_ts,observed_s,online_s,upload_delta,download_delta,last_online_ts,reset_count)
SELECT peer_id,(ts/300)*300,sum(observed_s),sum(online_delta_s),sum(upload_delta),sum(download_delta),max(CASE WHEN online=1 THEN ts END),
sum(counter_reset) FROM samples_raw WHERE ts<? GROUP BY peer_id,(ts/300)*300
ON CONFLICT(peer_id,bucket_ts) DO UPDATE SET observed_s=excluded.observed_s,online_s=excluded.online_s,
upload_delta=excluded.upload_delta,download_delta=excluded.download_delta,last_online_ts=excluded.last_online_ts,reset_count=excluded.reset_count`, complete5m); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO samples_1h(peer_id,bucket_ts,observed_s,online_s,upload_delta,download_delta,last_online_ts,reset_count)
SELECT peer_id,(bucket_ts/3600)*3600,sum(observed_s),sum(online_s),sum(upload_delta),sum(download_delta),max(last_online_ts),sum(reset_count)
FROM samples_5m WHERE bucket_ts<? GROUP BY peer_id,(bucket_ts/3600)*3600
ON CONFLICT(peer_id,bucket_ts) DO UPDATE SET observed_s=excluded.observed_s,online_s=excluded.online_s,
upload_delta=excluded.upload_delta,download_delta=excluded.download_delta,last_online_ts=excluded.last_online_ts,reset_count=excluded.reset_count`, complete1h); err != nil {
		return err
	}
	if err := chunkDelete(ctx, tx, "samples_raw", "ts", alignDown(now.Add(-s.rawRetention).Unix(), 300)); err != nil {
		return err
	}
	if err := chunkDelete(ctx, tx, "samples_5m", "bucket_ts", alignDown(now.Add(-s.fiveMinuteRetention).Unix(), 3600)); err != nil {
		return err
	}
	if err := chunkDelete(ctx, tx, "samples_1h", "bucket_ts", alignDown(now.Add(-s.hourlyRetention).Unix(), 3600)); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	return s.refreshStorageState(ctx)
}

func alignDown(value, bucket int64) int64 { return (value / bucket) * bucket }

func alignUp(value, bucket int64) int64 {
	down := alignDown(value, bucket)
	if down == value {
		return value
	}
	if down > int64(^uint64(0)>>1)-bucket {
		return int64(^uint64(0) >> 1)
	}
	return down + bucket
}

func (s *Store) refreshStorageState(ctx context.Context) error {
	var pageCount, pageSize int64
	if err := s.db.QueryRowContext(ctx, "PRAGMA page_count").Scan(&pageCount); err != nil {
		s.status.Store(1)
		return err
	}
	if err := s.db.QueryRowContext(ctx, "PRAGMA page_size").Scan(&pageSize); err != nil {
		s.status.Store(1)
		return err
	}
	bytes := pageCount * pageSize
	if info, err := os.Stat(s.path + "-wal"); err == nil {
		bytes += info.Size()
	} else if !os.IsNotExist(err) {
		s.status.Store(1)
		return err
	}
	if bytes > s.max {
		s.status.Store(1)
	} else {
		s.status.Store(0)
	}
	return nil
}

func chunkDelete(ctx context.Context, tx *sql.Tx, table, column string, cutoff int64) error {
	for {
		query := fmt.Sprintf("DELETE FROM %s WHERE (peer_id,%s) IN (SELECT peer_id,%s FROM %s WHERE %s<? LIMIT 1000)", table, column, column, table, column)
		result, err := tx.ExecContext(ctx, query, cutoff)
		if err != nil {
			return err
		}
		n, err := result.RowsAffected()
		if err != nil {
			return err
		}
		if n < 1000 {
			return nil
		}
	}
}

func (s *Store) StorageState() StorageStatus {
	if s.status.Load() != 0 {
		return StorageDegraded
	}
	return StorageOK
}

func (s *Store) Flush(context.Context) error { return nil }

func (s *Store) Close() error { return s.db.Close() }
