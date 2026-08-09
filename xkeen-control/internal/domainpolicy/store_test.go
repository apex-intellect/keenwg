package domainpolicy

import (
	"errors"
	"io/fs"
	"testing"
)

type storeWrite struct {
	path string
	body []byte
	mode fs.FileMode
}

type storeFakeSystem struct {
	files    map[string][]byte
	writes   []storeWrite
	writeErr error
}

func (f *storeFakeSystem) ReadFile(path string) ([]byte, error) {
	body, ok := f.files[path]
	if !ok {
		return nil, fs.ErrNotExist
	}
	return append([]byte(nil), body...), nil
}
func (f *storeFakeSystem) WriteAtomic(path string, body []byte, mode fs.FileMode) error {
	if f.writeErr != nil {
		return f.writeErr
	}
	if f.files == nil {
		f.files = map[string][]byte{}
	}
	f.files[path] = append([]byte(nil), body...)
	f.writes = append(f.writes, storeWrite{path: path, body: append([]byte(nil), body...), mode: mode})
	return nil
}

func TestStoreRejectsUnknownFieldsAndNullRules(t *testing.T) {
	for name, body := range map[string]string{
		"unknown": `{"schema_version":1,"rules":[],"extra":true}`,
		"null":    `{"schema_version":1,"rules":null}`,
	} {
		t.Run(name, func(t *testing.T) {
			system := &storeFakeSystem{files: map[string][]byte{"policy": []byte(body)}}
			if _, _, err := NewStore("policy", "backup", system).Load(); err == nil {
				t.Fatal("invalid document accepted")
			}
		})
	}
}

func TestStoreSaveBacksUpAndPublishesCanonicalPolicy(t *testing.T) {
	previous := []byte("{\"schema_version\":1,\"rules\":[]}\n")
	system := &storeFakeSystem{files: map[string][]byte{"policy": previous}}
	store := NewStore("policy", "backup", system)
	rule, err := CanonicalizeRule(Rule{Kind: "domain", Value: "okko.sport", Effect: "direct", Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Save(Policy{SchemaVersion: 1, Rules: []Rule{rule}}, previous); err != nil {
		t.Fatal(err)
	}
	if len(system.writes) != 2 || system.writes[0].path != "backup" || system.writes[1].path != "policy" {
		t.Fatalf("writes=%+v", system.writes)
	}
	for _, write := range system.writes {
		if write.mode.Perm() != 0o600 {
			t.Fatalf("mode=%o", write.mode.Perm())
		}
	}
	policy, body, err := store.Load()
	if err != nil || len(policy.Rules) != 1 || PolicyVersion(body) == PolicyVersion(previous) {
		t.Fatalf("policy=%+v err=%v", policy, err)
	}
}

func TestStoreDoesNotPublishWhenBackupFails(t *testing.T) {
	system := &storeFakeSystem{files: map[string][]byte{"policy": []byte(`{"schema_version":1,"rules":[]}`)}, writeErr: errors.New("disk")}
	store := NewStore("policy", "backup", system)
	if err := store.Save(Policy{SchemaVersion: 1, Rules: []Rule{}}, system.files["policy"]); err == nil {
		t.Fatal("save succeeded")
	}
	if len(system.writes) != 0 {
		t.Fatalf("writes=%+v", system.writes)
	}
}
