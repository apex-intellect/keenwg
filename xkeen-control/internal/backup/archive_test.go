package backup

import (
	"bytes"
	"context"
	"errors"
	"testing"
	"time"
)

func TestEncryptedArchiveMigrationMatrixAndBounds(t *testing.T) {
	for _, version := range []string{"0.6.0", "0.7.0", "0.8.0", "0.9.0", "1.0.0"} {
		blob, err := Create(version, []Input{{ID: "routes", Data: []byte(`{"active":"nl-1"}`), Owned: true}}, []byte("correct horse"), bytes.NewReader(bytes.Repeat([]byte{7}, 64)), time.Unix(100, 0))
		if err != nil {
			t.Fatalf("version=%s create: %v", version, err)
		}
		plan, err := Preview(blob, []byte("correct horse"), "1.0.0")
		if err != nil {
			t.Fatalf("version=%s preview: %v", version, err)
		}
		if plan.SchemaVersion != 1 || plan.SourceVersion != version || string(plan.Entries[0].Data) != `{"active":"nl-1"}` {
			t.Fatalf("version=%s plan=%+v", version, plan)
		}
	}
	if _, err := Create("1.0.0", []Input{{ID: "too-big", Data: make([]byte, MaxEntryBytes+1), Owned: true}}, []byte("passphrase"), bytes.NewReader(make([]byte, 64)), time.Now()); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("oversize error=%v", err)
	}
}

func TestCorruptPartialWrongPasswordAndDowngradeFailClosed(t *testing.T) {
	blob, err := Create("1.0.0", []Input{{ID: "routes", Data: []byte("safe"), Owned: true}}, []byte("passphrase"), bytes.NewReader(bytes.Repeat([]byte{3}, 64)), time.Unix(100, 0))
	if err != nil {
		t.Fatal(err)
	}
	corrupt := append([]byte(nil), blob...)
	corrupt[len(corrupt)-1] ^= 0xff
	for name, candidate := range map[string][]byte{"corrupt": corrupt, "partial": blob[:len(blob)/2]} {
		if _, err := Preview(candidate, []byte("passphrase"), "1.0.0"); err == nil {
			t.Fatalf("%s accepted", name)
		}
	}
	if _, err := Preview(blob, []byte("wrong"), "1.0.0"); !errors.Is(err, ErrDecrypt) {
		t.Fatalf("wrong password=%v", err)
	}
	future, err := Create("1.1.0", []Input{{ID: "routes", Data: []byte("safe"), Owned: true}}, []byte("passphrase"), bytes.NewReader(bytes.Repeat([]byte{4}, 64)), time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Preview(future, []byte("passphrase"), "1.0.0"); !errors.Is(err, ErrDowngrade) {
		t.Fatalf("downgrade=%v", err)
	}
}

func TestExplicitApplyPreservesForeignResourcesAndActiveRoute(t *testing.T) {
	target := newFakeTarget(map[string]fakeValue{
		"routes":  {data: []byte("nl-1"), owned: true},
		"foreign": {data: []byte("keep-me"), owned: false},
	})
	plan := Plan{SchemaVersion: 1, SourceVersion: "0.9.0", Entries: []Entry{
		{ID: "routes", Data: []byte("nl-1"), Owned: true},
		{ID: "foreign", Data: []byte("replace-me"), Owned: true},
	}}
	result, err := Apply(context.Background(), target, plan)
	if err != nil {
		t.Fatal(err)
	}
	if string(target.values["foreign"].data) != "keep-me" || string(target.values["routes"].data) != "nl-1" {
		t.Fatalf("values=%+v", target.values)
	}
	if len(result.SkippedForeign) != 1 || result.SkippedForeign[0] != "foreign" {
		t.Fatalf("result=%+v", result)
	}
}

func TestExplicitApplyNeverAdoptsEmptyForeignResource(t *testing.T) {
	target := newFakeTarget(map[string]fakeValue{"foreign": {data: []byte{}, owned: false}})
	plan := Plan{SchemaVersion: 1, SourceVersion: "1.0.0", Entries: []Entry{
		{ID: "foreign", Data: []byte("replace-me"), Owned: true},
	}}
	result, err := Apply(context.Background(), target, plan)
	if err != nil {
		t.Fatal(err)
	}
	if len(target.values["foreign"].data) != 0 || len(result.SkippedForeign) != 1 {
		t.Fatalf("foreign resource was adopted: result=%+v value=%+v", result, target.values["foreign"])
	}
}

func TestFailedRestoreRollsBackInReverseAndRetainsRecoveryWhenUncertain(t *testing.T) {
	target := newFakeTarget(map[string]fakeValue{"routes": {data: []byte("nl-1"), owned: true}, "policy": {data: []byte("old"), owned: true}})
	target.failApply["policy"] = errors.New("injected")
	plan := Plan{SchemaVersion: 1, SourceVersion: "1.0.0", Entries: []Entry{{ID: "routes", Data: []byte("de-1"), Owned: true}, {ID: "policy", Data: []byte("new"), Owned: true}}}
	if _, err := Apply(context.Background(), target, plan); !errors.Is(err, ErrRolledBack) {
		t.Fatalf("error=%v", err)
	}
	if string(target.values["routes"].data) != "nl-1" || len(target.recovery) != 0 {
		t.Fatalf("rollback values=%+v recovery=%v", target.values, target.recovery)
	}

	target = newFakeTarget(map[string]fakeValue{"routes": {data: []byte("nl-1"), owned: true}, "policy": {data: []byte("old"), owned: true}})
	target.failApply["policy"] = errors.New("injected")
	target.failRestore["routes"] = errors.New("injected restore")
	if _, err := Apply(context.Background(), target, plan); !errors.Is(err, ErrUncertain) {
		t.Fatalf("error=%v", err)
	}
	if len(target.recovery) == 0 {
		t.Fatal("uncertain restore deleted recovery snapshot")
	}
}

type fakeValue struct {
	data  []byte
	owned bool
}
type fakeTarget struct {
	values                 map[string]fakeValue
	failApply, failRestore map[string]error
	recovery               map[string][]byte
}

func newFakeTarget(values map[string]fakeValue) *fakeTarget {
	return &fakeTarget{values: values, failApply: map[string]error{}, failRestore: map[string]error{}, recovery: map[string][]byte{}}
}
func (f *fakeTarget) Read(_ context.Context, id string) ([]byte, bool, error) {
	v, ok := f.values[id]
	return append([]byte(nil), v.data...), ok && v.owned, nil
}
func (f *fakeTarget) Apply(_ context.Context, id string, data []byte) error {
	if err := f.failApply[id]; err != nil {
		return err
	}
	f.values[id] = fakeValue{append([]byte(nil), data...), true}
	return nil
}
func (f *fakeTarget) Verify(_ context.Context, id string, data []byte) error {
	if !bytes.Equal(f.values[id].data, data) {
		return errors.New("mismatch")
	}
	return nil
}
func (f *fakeTarget) SaveRecovery(id string, data []byte) error {
	f.recovery[id] = append([]byte(nil), data...)
	return nil
}
func (f *fakeTarget) Restore(_ context.Context, id string, data []byte) error {
	if err := f.failRestore[id]; err != nil {
		return err
	}
	f.values[id] = fakeValue{append([]byte(nil), data...), true}
	return nil
}
func (f *fakeTarget) ClearRecovery() error { f.recovery = map[string][]byte{}; return nil }
