package exclusions

import (
	"context"
	"errors"
	"io/fs"
	"testing"
)

type fakeSystem struct {
	body            []byte
	restartErr      error
	restartFailures int
	restarts        int
}

func (f *fakeSystem) ReadFile(string) ([]byte, error) { return append([]byte(nil), f.body...), nil }
func (f *fakeSystem) WriteAtomic(_ string, body []byte, _ fs.FileMode) error {
	f.body = append([]byte(nil), body...)
	return nil
}
func (f *fakeSystem) Restart(context.Context) error {
	f.restarts++
	if f.restartFailures > 0 {
		f.restartFailures--
		return f.restartErr
	}
	return nil
}

func TestStatusSeparatesProtectedManagedEntry(t *testing.T) {
	system := &fakeSystem{body: []byte("198.18.0.0/15\n# BEGIN KEENWG XKeen ENDPOINT\n203.0.113.10/32\n# END KEENWG XKeen ENDPOINT\n")}
	status, err := New("/opt/etc/xkeen/ip_exclude.lst", system).Status()
	if err != nil || len(status.Entries) != 2 {
		t.Fatalf("status=%#v err=%v", status, err)
	}
	if status.Entries[0].Protected || !status.Entries[1].Protected {
		t.Fatalf("protection=%#v", status.Entries)
	}
}

func TestAddRestartsAndFailedRestartRollsBack(t *testing.T) {
	original := []byte("# BEGIN KEENWG XKeen ENDPOINT\n203.0.113.10/32\n# END KEENWG XKeen ENDPOINT\n")
	system := &fakeSystem{body: append([]byte(nil), original...)}
	service := New("/opt/etc/xkeen/ip_exclude.lst", system)
	before, _ := service.Status()
	result := service.Mutate(context.Background(), Mutation{StateVersion: before.StateVersion, Action: "add", Value: "198.18.0.0/15"})
	if result.Result != "committed" || system.restarts != 1 {
		t.Fatalf("result=%#v restarts=%d", result, system.restarts)
	}

	system.restartErr = errors.New("restart")
	system.restartFailures = 1
	before, _ = service.Status()
	result = service.Mutate(context.Background(), Mutation{StateVersion: before.StateVersion, Action: "delete", Value: "198.18.0.0/15"})
	if result.Result != "rolled_back" {
		t.Fatalf("result=%#v", result)
	}
	if string(system.body) == string(original) {
		t.Fatal("rollback must restore state immediately before failed mutation, including added entry")
	}
}

func TestMutationRejectsProtectedAndInvalidValues(t *testing.T) {
	system := &fakeSystem{body: []byte("# BEGIN KEENWG XKeen ENDPOINT\n203.0.113.10/32\n# END KEENWG XKeen ENDPOINT\n")}
	service := New("/opt/etc/xkeen/ip_exclude.lst", system)
	status, _ := service.Status()
	for _, value := range []string{"203.0.113.10/32", "1.2.3.4; reboot"} {
		result := service.Mutate(context.Background(), Mutation{StateVersion: status.StateVersion, Action: "delete", Value: value})
		if result.Result != "rejected" {
			t.Fatalf("value=%q result=%#v", value, result)
		}
	}
}

func TestReplaceCommitsAllUserEntriesOnceAndPreservesProtectedBlock(t *testing.T) {
	system := &fakeSystem{body: []byte("198.18.0.0/15\n# BEGIN KEENWG XKeen ENDPOINT\n203.0.113.10/32\n# END KEENWG XKeen ENDPOINT\n")}
	service := New("exclude", system)
	before, _ := service.Status()
	result := service.Replace(context.Background(), ReplaceRequest{StateVersion: before.StateVersion, Values: []string{"192.0.2.0/24", "198.51.100.7"}})
	if result.Result != "committed" || system.restarts != 1 {
		t.Fatalf("result=%+v restarts=%d", result, system.restarts)
	}
	if len(result.Status.Entries) != 3 || result.Status.Entries[0].Value != "192.0.2.0/24" || result.Status.Entries[1].Value != "198.51.100.7" || !result.Status.Entries[2].Protected {
		t.Fatalf("entries=%+v", result.Status.Entries)
	}
	stale := service.Replace(context.Background(), ReplaceRequest{StateVersion: before.StateVersion, Values: []string{"10.0.0.0/8"}})
	if stale.Result != "rejected" {
		t.Fatalf("stale=%+v", stale)
	}
}
