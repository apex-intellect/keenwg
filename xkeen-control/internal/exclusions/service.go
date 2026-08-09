package exclusions

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"errors"
	"io/fs"
	"net/netip"
	"strings"
	"sync"
)

const (
	beginMarker = "# BEGIN KEENWG XKeen ENDPOINT"
	endMarker   = "# END KEENWG XKeen ENDPOINT"
)

type System interface {
	ReadFile(string) ([]byte, error)
	WriteAtomic(string, []byte, fs.FileMode) error
	Restart(context.Context) error
}

type Entry struct {
	ID        string `json:"id"`
	Value     string `json:"value"`
	Protected bool   `json:"protected"`
}

type Status struct {
	SchemaVersion int      `json:"schema_version"`
	StateVersion  uint64   `json:"state_version"`
	Entries       []Entry  `json:"entries"`
	Warnings      []string `json:"warnings"`
}

type Mutation struct {
	StateVersion uint64 `json:"state_version"`
	Action       string `json:"action"`
	Value        string `json:"value"`
}

type Result struct {
	Result string `json:"result"`
	Status Status `json:"status"`
}

type ReplaceRequest struct {
	StateVersion uint64   `json:"state_version"`
	Values       []string `json:"values"`
}

type Service struct {
	path   string
	system System
	mu     sync.Mutex
}

func New(path string, system System) *Service { return &Service{path: path, system: system} }

func (s *Service) Status() (Status, error) {
	body, err := s.system.ReadFile(s.path)
	if err != nil {
		return Status{}, err
	}
	entries, err := parse(body)
	if err != nil {
		return Status{}, err
	}
	return Status{SchemaVersion: 1, StateVersion: version(body), Entries: entries, Warnings: []string{}}, nil
}

func (s *Service) Mutate(ctx context.Context, mutation Mutation) Result {
	s.mu.Lock()
	defer s.mu.Unlock()
	body, err := s.system.ReadFile(s.path)
	if err != nil {
		return Result{Result: "uncertain", Status: emptyStatus()}
	}
	current, err := statusFrom(body)
	if err != nil || mutation.StateVersion != current.StateVersion {
		return Result{Result: "rejected", Status: current}
	}
	canonical, ok := canonicalPrefix(mutation.Value)
	if !ok || (mutation.Action != "add" && mutation.Action != "delete") {
		return Result{Result: "rejected", Status: current}
	}
	for _, entry := range current.Entries {
		if entry.Value == canonical && entry.Protected {
			return Result{Result: "rejected", Status: current}
		}
	}
	candidate, changed := edit(body, mutation.Action, canonical)
	if !changed {
		return Result{Result: "rejected", Status: current}
	}
	err = s.system.WriteAtomic(s.path, candidate, 0o600)
	if err == nil {
		err = s.system.Restart(ctx)
	}
	if err == nil {
		status, loadErr := s.Status()
		if loadErr == nil {
			return Result{Result: "committed", Status: status}
		}
		err = loadErr
	}
	rollbackErr := s.system.WriteAtomic(s.path, body, 0o600)
	if rollbackErr == nil {
		rollbackErr = s.system.Restart(ctx)
	}
	status, _ := s.Status()
	if rollbackErr == nil {
		return Result{Result: "rolled_back", Status: status}
	}
	return Result{Result: "uncertain", Status: status}
}

func (s *Service) Replace(ctx context.Context, request ReplaceRequest) Result {
	s.mu.Lock()
	defer s.mu.Unlock()
	body, err := s.system.ReadFile(s.path)
	if err != nil {
		return Result{Result: "uncertain", Status: emptyStatus()}
	}
	current, err := statusFrom(body)
	if err != nil || request.StateVersion != current.StateVersion {
		return Result{Result: "rejected", Status: current}
	}
	if len(request.Values) > 4096 {
		return Result{Result: "rejected", Status: current}
	}
	protected := map[string]struct{}{}
	for _, entry := range current.Entries {
		if entry.Protected {
			protected[entry.Value] = struct{}{}
		}
	}
	values := make([]string, 0, len(request.Values))
	seen := map[string]struct{}{}
	for _, raw := range request.Values {
		value, ok := canonicalPrefix(raw)
		if !ok {
			return Result{Result: "rejected", Status: current}
		}
		if _, ok := protected[value]; ok {
			return Result{Result: "rejected", Status: current}
		}
		if _, ok := seen[value]; ok {
			return Result{Result: "rejected", Status: current}
		}
		seen[value] = struct{}{}
		values = append(values, value)
	}
	candidate, renderErr := renderUserEntries(body, values)
	if renderErr != nil {
		return Result{Result: "rejected", Status: current}
	}
	if string(candidate) == string(body) {
		return Result{Result: "committed", Status: current}
	}
	err = s.system.WriteAtomic(s.path, candidate, 0o600)
	if err == nil {
		err = s.system.Restart(ctx)
	}
	if err == nil {
		status, loadErr := s.Status()
		if loadErr == nil {
			return Result{Result: "committed", Status: status}
		}
		err = loadErr
	}
	rollbackErr := s.system.WriteAtomic(s.path, body, 0o600)
	if rollbackErr == nil {
		rollbackErr = s.system.Restart(ctx)
	}
	status, _ := s.Status()
	if rollbackErr == nil {
		return Result{Result: "rolled_back", Status: status}
	}
	return Result{Result: "uncertain", Status: status}
}

func renderUserEntries(body []byte, values []string) ([]byte, error) {
	if _, err := parse(body); err != nil {
		return nil, err
	}
	lines := strings.Split(strings.TrimSuffix(strings.ReplaceAll(string(body), "\r\n", "\n"), "\n"), "\n")
	kept := make([]string, 0, len(lines)+len(values))
	managed := false
	inserted := false
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == beginMarker {
			if !inserted {
				kept = append(kept, values...)
				inserted = true
			}
			managed = true
			kept = append(kept, line)
			continue
		}
		if trimmed == endMarker {
			managed = false
			kept = append(kept, line)
			continue
		}
		if !managed {
			if _, ok := canonicalPrefix(trimmed); ok {
				continue
			}
		}
		kept = append(kept, line)
	}
	if !inserted {
		return nil, errors.New("managed block missing")
	}
	return []byte(strings.Join(kept, "\n") + "\n"), nil
}

func statusFrom(body []byte) (Status, error) {
	entries, err := parse(body)
	if err != nil {
		return emptyStatus(), err
	}
	return Status{SchemaVersion: 1, StateVersion: version(body), Entries: entries, Warnings: []string{}}, nil
}

func emptyStatus() Status { return Status{SchemaVersion: 1, Entries: []Entry{}, Warnings: []string{}} }

func parse(body []byte) ([]Entry, error) {
	lines := strings.Split(strings.ReplaceAll(string(body), "\r\n", "\n"), "\n")
	entries := []Entry{}
	managed := false
	seenBegin, seenEnd := false, false
	for _, raw := range lines {
		line := strings.TrimSpace(raw)
		switch line {
		case beginMarker:
			if seenBegin || managed {
				return nil, errors.New("invalid managed block")
			}
			seenBegin, managed = true, true
			continue
		case endMarker:
			if !managed || seenEnd {
				return nil, errors.New("invalid managed block")
			}
			seenEnd, managed = true, false
			continue
		}
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if value, ok := canonicalPrefix(line); ok {
			hash := sha256.Sum256([]byte(line))
			entries = append(entries, Entry{ID: strings.ToLower(stringHex(hash[:8])), Value: value, Protected: managed})
		}
	}
	if managed || seenBegin != seenEnd || !seenBegin {
		return nil, errors.New("invalid managed block")
	}
	return entries, nil
}

func edit(body []byte, action, value string) ([]byte, bool) {
	text := strings.ReplaceAll(string(body), "\r\n", "\n")
	lines := strings.Split(strings.TrimSuffix(text, "\n"), "\n")
	managed := false
	if action == "delete" {
		out := make([]string, 0, len(lines))
		changed := false
		for _, line := range lines {
			trimmed := strings.TrimSpace(line)
			if trimmed == beginMarker {
				managed = true
			}
			if !managed && trimmed == value && !changed {
				changed = true
				continue
			}
			out = append(out, line)
			if trimmed == endMarker {
				managed = false
			}
		}
		return []byte(strings.Join(out, "\n") + "\n"), changed
	}
	for _, line := range lines {
		if strings.TrimSpace(line) == value {
			return body, false
		}
	}
	insert := len(lines)
	for index, line := range lines {
		if strings.TrimSpace(line) == beginMarker {
			insert = index
			break
		}
	}
	lines = append(lines, "")
	copy(lines[insert+1:], lines[insert:])
	lines[insert] = value
	return []byte(strings.Join(lines, "\n") + "\n"), true
}

func canonicalPrefix(value string) (string, bool) {
	trimmed := strings.TrimSpace(value)
	if strings.Contains(trimmed, "/") {
		prefix, err := netip.ParsePrefix(trimmed)
		if err != nil || !prefix.Addr().Is4() || prefix.String() != trimmed {
			return "", false
		}
		return prefix.String(), true
	}
	address, err := netip.ParseAddr(trimmed)
	if err != nil || !address.Is4() || address.String() != trimmed {
		return "", false
	}
	return address.String(), true
}

func version(body []byte) uint64 {
	hash := sha256.Sum256(body)
	return binary.BigEndian.Uint64(hash[:8])
}
func stringHex(value []byte) string {
	const chars = "0123456789abcdef"
	out := make([]byte, len(value)*2)
	for i, b := range value {
		out[i*2] = chars[b>>4]
		out[i*2+1] = chars[b&15]
	}
	return string(out)
}
