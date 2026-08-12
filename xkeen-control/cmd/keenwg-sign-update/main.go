package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/selfupdate"
)

func main() {
	if err := run(os.Args[1:]); err != nil {
		_, _ = fmt.Fprintln(os.Stderr, "keenwg-sign-update:", err)
		os.Exit(1)
	}
}

func run(args []string) error {
	flags := flag.NewFlagSet("keenwg-sign-update", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	generate := flags.Bool("generate-key", false, "generate a publisher key pair")
	manifestPath := flags.String("manifest", "", "signed manifest path")
	archivePath := flags.String("archive", "", "archive path")
	privatePath := flags.String("private-key", "", "private seed path")
	publicPath := flags.String("public-key", "", "public key path")
	keyID := flags.String("key-id", "", "publisher key identifier")
	if err := flags.Parse(args); err != nil || flags.NArg() != 0 {
		return errors.New("invalid arguments")
	}
	if *generate {
		if *privatePath == "" || *publicPath == "" || *keyID == "" || *manifestPath != "" || *archivePath != "" {
			return errors.New("invalid generation arguments")
		}
		return generateKeys(*privatePath, *publicPath, *keyID)
	}
	if *manifestPath == "" || *archivePath == "" || *privatePath == "" || *publicPath != "" || *keyID != "" {
		return errors.New("invalid signing arguments")
	}
	return signManifest(*manifestPath, *archivePath, *privatePath)
}

func generateKeys(privatePath, publicPath, keyID string) error {
	if _, err := os.Stat(privatePath); !errors.Is(err, os.ErrNotExist) {
		return errors.New("private key path already exists")
	}
	if _, err := os.Stat(publicPath); !errors.Is(err, os.ErrNotExist) {
		return errors.New("public key path already exists")
	}
	public, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	seed := private.Seed()
	defer func() { clear(seed); clear(private) }()
	privateBody := []byte(base64.RawStdEncoding.EncodeToString(seed) + "\n")
	defer clear(privateBody)
	trusted := selfupdate.TrustedPublicKey{SchemaVersion: 1, KeyID: keyID, PublicKey: base64.RawStdEncoding.EncodeToString(public)}
	publicBody, err := json.Marshal(trusted)
	if err != nil {
		return err
	}
	publicBody = append(publicBody, '\n')
	if err := writeNew(privatePath, privateBody, 0o600); err != nil {
		return err
	}
	if err := writeNew(publicPath, publicBody, 0o644); err != nil {
		_ = os.Remove(privatePath)
		return err
	}
	return nil
}

func signManifest(manifestPath, archivePath, privatePath string) error {
	manifestRaw, err := os.ReadFile(manifestPath)
	if err != nil {
		return err
	}
	manifest, err := selfupdate.DecodeManifestForSigning(bytes.NewReader(manifestRaw))
	if err != nil {
		return err
	}
	archive, err := os.Open(archivePath)
	if err != nil {
		return err
	}
	hash := sha256.New()
	size, err := io.Copy(hash, io.LimitReader(archive, selfupdate.MaximumArchiveBytes+1))
	closeErr := archive.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if size < 1 || size > selfupdate.MaximumArchiveBytes {
		return errors.New("archive size is invalid")
	}
	manifest.ArchiveSize = size
	manifest.ArchiveSHA256 = hex.EncodeToString(hash.Sum(nil))
	seedText, err := os.ReadFile(privatePath)
	if err != nil {
		return err
	}
	defer clear(seedText)
	seed, err := base64.RawStdEncoding.DecodeString(strings.TrimSpace(string(seedText)))
	if err != nil || len(seed) != ed25519.SeedSize {
		clear(seed)
		return errors.New("invalid private seed")
	}
	defer clear(seed)
	private := ed25519.NewKeyFromSeed(seed)
	defer clear(private)
	manifest.Signature = base64.RawStdEncoding.EncodeToString(ed25519.Sign(private, manifest.CanonicalBytes()))
	body, err := json.Marshal(manifest)
	if err != nil {
		return err
	}
	body = append(body, '\n')
	return replaceAtomic(manifestPath, body, 0o644)
}

func writeNew(path string, body []byte, mode os.FileMode) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, mode)
	if err != nil {
		return err
	}
	if _, err = file.Write(body); err == nil {
		err = file.Sync()
	}
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		_ = os.Remove(path)
	}
	return err
}

func replaceAtomic(path string, body []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	temp, err := os.CreateTemp(dir, ".keenwg-sign-")
	if err != nil {
		return err
	}
	tempPath := temp.Name()
	ok := false
	defer func() {
		if !ok {
			_ = os.Remove(tempPath)
		}
	}()
	if err = temp.Chmod(mode); err == nil {
		_, err = temp.Write(body)
	}
	if err == nil {
		err = temp.Sync()
	}
	if closeErr := temp.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	if err = os.Rename(tempPath, path); err != nil {
		return err
	}
	ok = true
	return nil
}
