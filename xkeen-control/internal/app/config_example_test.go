package app

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/config"
)

func TestCompanionExampleConfigIsStrictlyValid(t *testing.T) {
	file, err := os.Open(filepath.Join("..", "..", "packaging", "companion.config.example.json"))
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	cfg, err := config.Decode(file)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.SchemaVersion != 2 || cfg.SecureListenAddress != "10.8.0.1:18779" {
		t.Fatalf("unexpected example mode: %+v", cfg)
	}
}
