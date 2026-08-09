package config

import (
	"bytes"
	"strings"
	"testing"
)

func FuzzDecodeFailsClosedWithoutLeakingInput(f *testing.F) {
	f.Add([]byte(`{"listen_address":"10.8.0.1:18778","token":"0123456789abcdef0123456789abcdef"}`))
	f.Add([]byte(`{"secure_listen_address":"0.0.0.0:18779","password":"private-secret"}`))
	f.Add([]byte{0, 0xff, '{'})
	f.Fuzz(func(t *testing.T, body []byte) {
		if len(body) > 1<<20 {
			t.Skip()
		}
		_, err := Decode(bytes.NewReader(body))
		if err != nil && len(body) >= 8 && strings.Contains(err.Error(), string(body)) {
			t.Fatal("configuration error leaked the input")
		}
	})
}
