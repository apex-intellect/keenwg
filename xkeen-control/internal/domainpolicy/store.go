package domainpolicy

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
)

const maxPolicyBytes = 256 << 10

type StoreSystem interface {
	ReadFile(string) ([]byte, error)
	WriteAtomic(string, []byte, fs.FileMode) error
}

type Store struct {
	path       string
	backupPath string
	system     StoreSystem
}

func NewStore(path, backupPath string, system StoreSystem) *Store {
	return &Store{path: path, backupPath: backupPath, system: system}
}

func (s *Store) Load() (Policy, []byte, error) {
	body, err := s.system.ReadFile(s.path)
	if err != nil {
		return Policy{}, nil, err
	}
	if len(body) == 0 || len(body) > maxPolicyBytes {
		return Policy{}, nil, ErrInvalidPolicy
	}
	decoder := json.NewDecoder(bytes.NewReader(body))
	decoder.DisallowUnknownFields()
	var policy Policy
	if err := decoder.Decode(&policy); err != nil {
		return Policy{}, nil, ErrInvalidPolicy
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		return Policy{}, nil, ErrInvalidPolicy
	}
	if policy.Rules == nil || ValidatePolicy(policy) != nil {
		return Policy{}, nil, ErrInvalidPolicy
	}
	return policy, append([]byte(nil), body...), nil
}

func (s *Store) Save(policy Policy, previous []byte) error {
	if policy.Rules == nil || ValidatePolicy(policy) != nil {
		return ErrInvalidPolicy
	}
	body, err := json.MarshalIndent(policy, "", "  ")
	if err != nil || len(body)+1 > maxPolicyBytes {
		return ErrInvalidPolicy
	}
	body = append(body, '\n')
	if len(previous) > 0 {
		if err := s.system.WriteAtomic(s.backupPath, previous, 0o600); err != nil {
			return err
		}
	}
	return s.system.WriteAtomic(s.path, body, 0o600)
}
