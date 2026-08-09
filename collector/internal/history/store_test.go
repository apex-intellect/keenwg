package history

import (
	"context"
	"errors"
	"math"
	"strings"
	"testing"
	"time"
)

func openTempStore(t *testing.T, maxBytes ...int64) *Store {
	t.Helper()
	max := int64(96 << 20)
	if len(maxBytes) > 0 {
		max = maxBytes[0]
	}
	s, err := Open(context.Background(), Config{Path: t.TempDir() + "/history.db", MaxBytes: max})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	return s
}

func sample(id string, at, observed, online, upload, download int64) ReducedSample {
	return ReducedSample{
		PeerID: id, InterfaceID: "Wireguard0", ObservedAt: time.Unix(at, 0),
		Online: online > 0, ObservedSeconds: observed, OnlineDeltaSeconds: online,
		ClientUploadBytes: upload, ClientDownloadBytes: download, Accepted: true,
	}
}

func TestAppendAndHistoryRoundTrip(t *testing.T) {
	s := openTempStore(t)
	id := strings.Repeat("a", 64)
	rows := []ReducedSample{
		sample(id, 1, 30, 20, 10, 20),
		sample(id, 61, 50, 40, 30, 50),
		sample(id, 121, 70, 0, 0, 0),
	}
	if err := s.Append(context.Background(), rows); err != nil {
		t.Fatal(err)
	}
	got, err := s.History(context.Background(), id, 0, 180, ResolutionRaw, 2000)
	if err != nil {
		t.Fatal(err)
	}
	if got.ObservedSeconds != 150 || got.OnlineSeconds != 60 || got.ClientUploadBytes != 40 || got.ClientDownloadBytes != 70 {
		t.Fatalf("history totals = %+v", got)
	}
}

func TestFlushTransactionIsAllOrNothing(t *testing.T) {
	s := openTempStore(t)
	id := strings.Repeat("a", 64)
	err := s.Append(context.Background(), []ReducedSample{sample(id, 1, 60, 60, 1, 1), sample("", 61, 60, 60, 1, 1)})
	if err == nil {
		t.Fatal("Append() succeeded with invalid peer id")
	}
	if !errors.Is(err, ErrInvalidPeerID) {
		t.Fatalf("Append error = %v, want ErrInvalidPeerID", err)
	}
	var count int
	if err := s.db.QueryRow("SELECT count(*) FROM samples_raw").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatalf("raw rows = %d, want 0", count)
	}
}

func TestAppendClassifiesInvalidSampleSeparatelyFromPeerID(t *testing.T) {
	s := openTempStore(t)
	row := sample(strings.Repeat("a", 64), 1, 60, 60, 1, 1)
	row.InterfaceID = ""
	err := s.Append(context.Background(), []ReducedSample{row})
	if !errors.Is(err, ErrInvalidSample) || errors.Is(err, ErrInvalidPeerID) {
		t.Fatalf("Append error = %v", err)
	}
}

func TestMaintainBuildsFiveMinuteAndHourlyRollupsBeforeDelete(t *testing.T) {
	s := openTempStore(t)
	id := strings.Repeat("a", 64)
	ctx := context.Background()
	rows := make([]ReducedSample, 0, 8*24*60)
	for minute := int64(0); minute < 8*24*60; minute++ {
		rows = append(rows, sample(id, minute*60+1, 60, 60, 1, 2))
	}
	for len(rows) > 0 {
		n := 500
		if len(rows) < n {
			n = len(rows)
		}
		if err := s.Append(ctx, rows[:n]); err != nil {
			t.Fatal(err)
		}
		rows = rows[n:]
	}
	now := time.Unix(8*24*3600, 0)
	if err := s.Maintain(ctx, now); err != nil {
		t.Fatal(err)
	}
	for table, wantPositive := range map[string]bool{"samples_5m": true, "samples_1h": true} {
		var count int
		if err := s.db.QueryRow("SELECT count(*) FROM " + table).Scan(&count); err != nil {
			t.Fatal(err)
		}
		if wantPositive && count == 0 {
			t.Fatalf("%s rollups were not created", table)
		}
	}
	var oldest int64
	if err := s.db.QueryRow("SELECT min(ts) FROM samples_raw").Scan(&oldest); err != nil {
		t.Fatal(err)
	}
	if oldest < 24*3600 {
		t.Fatalf("oldest raw timestamp = %d, want >= %d", oldest, 24*3600)
	}
}

func TestDatabaseCapPausesHistoryWrites(t *testing.T) {
	s := openTempStore(t)
	id := strings.Repeat("a", 64)
	if err := s.Append(context.Background(), []ReducedSample{sample(id, 1, 60, 60, 1, 1)}); err != nil {
		t.Fatal(err)
	}
	s.max = 1
	if err := s.Maintain(context.Background(), time.Unix(2, 0)); err != nil {
		t.Fatal(err)
	}
	if s.StorageState() != StorageDegraded {
		t.Fatalf("storage = %q", s.StorageState())
	}
	if err := s.Append(context.Background(), []ReducedSample{sample(id, 61, 60, 60, 1, 1)}); err == nil {
		t.Fatal("Append() succeeded after database cap was exceeded")
	}
}

func TestHistoryCountsCounterGenerationTransitions(t *testing.T) {
	s := openTempStore(t)
	id := strings.Repeat("b", 64)
	first := sample(id, 1, 60, 60, 1, 1)
	second := sample(id, 61, 60, 60, 0, 0)
	second.CounterGeneration = 1
	second.CounterReset = true
	if err := s.Append(context.Background(), []ReducedSample{first, second}); err != nil {
		t.Fatal(err)
	}
	got, err := s.History(context.Background(), id, 0, 180, ResolutionRaw, 2000)
	if err != nil {
		t.Fatal(err)
	}
	if got.CounterResets != 1 {
		t.Fatalf("counter_resets = %d, want 1", got.CounterResets)
	}
}

func TestMaintainPublishesOnlyCompleteBucketsAndIsIdempotent(t *testing.T) {
	s := openTempStore(t)
	id := strings.Repeat("c", 64)
	ctx := context.Background()
	rows := []ReducedSample{}
	for _, at := range []int64{1, 61, 121, 181, 241, 301} {
		rows = append(rows, sample(id, at, 60, 60, 1, 2))
	}
	if err := s.Append(ctx, rows); err != nil {
		t.Fatal(err)
	}
	if err := s.Maintain(ctx, time.Unix(350, 0)); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := s.db.QueryRow("SELECT count(*) FROM samples_5m").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("5m rows after partial bucket = %d, want 1", count)
	}
	if err := s.Maintain(ctx, time.Unix(359, 0)); err != nil {
		t.Fatal(err)
	}
	var observed, upload int64
	if err := s.db.QueryRow("SELECT observed_s,upload_delta FROM samples_5m WHERE bucket_ts=0").Scan(&observed, &upload); err != nil {
		t.Fatal(err)
	}
	if observed != 300 || upload != 5 {
		t.Fatalf("repeated totals = observed %d upload %d", observed, upload)
	}
	more := []ReducedSample{}
	for _, at := range []int64{361, 421, 481, 541} {
		more = append(more, sample(id, at, 60, 60, 1, 2))
	}
	if err := s.Append(ctx, more); err != nil {
		t.Fatal(err)
	}
	if err := s.Maintain(ctx, time.Unix(601, 0)); err != nil {
		t.Fatal(err)
	}
	if err := s.db.QueryRow("SELECT observed_s,upload_delta FROM samples_5m WHERE bucket_ts=300").Scan(&observed, &upload); err != nil {
		t.Fatal(err)
	}
	if observed != 300 || upload != 5 {
		t.Fatalf("completed partial totals = observed %d upload %d", observed, upload)
	}
}

func TestOpenRestoresDegradedStateFromExistingDatabaseSize(t *testing.T) {
	path := t.TempDir() + "/history.db"
	ctx := context.Background()
	s, err := Open(ctx, Config{Path: path, MaxBytes: 96 << 20})
	if err != nil {
		t.Fatal(err)
	}
	id := strings.Repeat("d", 64)
	if err := s.Append(ctx, []ReducedSample{sample(id, 1, 60, 60, 1, 1)}); err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	s, err = Open(ctx, Config{Path: path, MaxBytes: 1})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if s.StorageState() != StorageDegraded {
		t.Fatalf("reopened storage = %q", s.StorageState())
	}
	if err := s.Append(ctx, []ReducedSample{sample(id, 61, 60, 60, 1, 1)}); !errors.Is(err, ErrStorageDegraded) {
		t.Fatalf("append error = %v", err)
	}
}

func TestRollupQueryIncludesBucketContainingUnalignedFrom(t *testing.T) {
	s := openTempStore(t)
	id := strings.Repeat("e", 64)
	ctx := context.Background()
	rows := []ReducedSample{}
	for _, at := range []int64{1, 61, 121, 181, 241} {
		rows = append(rows, sample(id, at, 60, 60, 1, 2))
	}
	if err := s.Append(ctx, rows); err != nil {
		t.Fatal(err)
	}
	if err := s.Maintain(ctx, time.Unix(301, 0)); err != nil {
		t.Fatal(err)
	}
	got, err := s.History(ctx, id, 1, 299, Resolution5M, 10)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Points) != 1 || got.Points[0].At != 0 {
		t.Fatalf("points = %+v", got.Points)
	}
	if got.From != 0 || got.To != 300 || got.ObservedSeconds != 300 || got.OnlineSeconds != 300 || got.LastOnlineAt == nil || *got.LastOnlineAt != 241 || got.CoverageRatio != 1 {
		t.Fatalf("aligned history = %+v", got)
	}
}

func TestAutoResolutionHandlesMaximumEpochSpanWithoutOverflow(t *testing.T) {
	s := openTempStore(t)
	id := strings.Repeat("f", 64)
	got, err := s.History(context.Background(), id, 0, math.MaxInt64, ResolutionAuto, 10)
	if err != nil {
		t.Fatal(err)
	}
	if got.Resolution != string(Resolution1H) {
		t.Fatalf("resolution = %q, want 1h", got.Resolution)
	}
}

func TestMaintainUsesConfiguredRetentionWindows(t *testing.T) {
	ctx := context.Background()
	s, err := Open(ctx, Config{Path: t.TempDir() + "/history.db", MaxBytes: 96 << 20, RawRetention: time.Hour, FiveMinuteRetention: 2 * time.Hour, HourlyRetention: 3 * time.Hour})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	id := strings.Repeat("9", 64)
	rows := []ReducedSample{}
	for _, at := range []int64{1, 3601, 7201} {
		rows = append(rows, sample(id, at, 60, 60, 1, 1))
	}
	if err := s.Append(ctx, rows); err != nil {
		t.Fatal(err)
	}
	if err := s.Maintain(ctx, time.Unix(7201, 0)); err != nil {
		t.Fatal(err)
	}
	var oldest int64
	if err := s.db.QueryRow("SELECT min(ts) FROM samples_raw").Scan(&oldest); err != nil {
		t.Fatal(err)
	}
	if oldest < 3600 {
		t.Fatalf("oldest raw = %d, want >=3600", oldest)
	}
}

func TestAppendRefreshesCapAndPausesNextBatch(t *testing.T) {
	s := openTempStore(t)
	s.max = 1
	id := strings.Repeat("8", 64)
	ctx := context.Background()
	if err := s.Append(ctx, []ReducedSample{sample(id, 1, 60, 60, 1, 1)}); err != nil {
		t.Fatal(err)
	}
	if s.StorageState() != StorageDegraded {
		t.Fatalf("storage after crossing cap = %q", s.StorageState())
	}
	if err := s.Append(ctx, []ReducedSample{sample(id, 61, 60, 60, 1, 1)}); !errors.Is(err, ErrStorageDegraded) {
		t.Fatalf("second append error = %v", err)
	}
}
