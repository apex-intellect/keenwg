package subscription

import (
	"strings"
	"testing"
)

func FuzzParseNeverPanicsOrLeaksInput(f *testing.F) {
	f.Add([]byte(nl1URI), 128)
	f.Add([]byte("vless://private-secret@invalid"), 1)
	f.Add([]byte{0, 0xff, '\n'}, 512)
	f.Fuzz(func(t *testing.T, payload []byte, maxNodes int) {
		if len(payload) > 1<<20 {
			t.Skip()
		}
		result, err := Parse(payload, maxNodes)
		if err != nil && len(payload) >= 8 && strings.Contains(err.Error(), string(payload)) {
			t.Fatal("parser error leaked the input")
		}
		if len(result.Nodes) > 512 && maxNodes <= 512 {
			t.Fatalf("parser exceeded configured bound: %d", len(result.Nodes))
		}
	})
}
