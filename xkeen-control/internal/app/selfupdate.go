package app

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/selfupdate"
)

type companionSelfUpdater struct {
	stager          *selfupdate.Stager
	updateDirectory string
	requestVirtual  string
	requestPath     string
	statusPath      string
	updaterPath     string
	currentVersion  string
	publicKey       ed25519.PublicKey
	keyID           string
	root            string
}

func newCompanionSelfUpdater(version, root string) (*companionSelfUpdater, error) {
	trusted, publicKey, err := selfupdate.TrustedKey()
	if err != nil {
		return nil, err
	}
	updateDirectory := rootedPath(root, "/opt/etc/keenwg/update")
	if err := cleanupAbandonedUpdates(updateDirectory); err != nil {
		return nil, err
	}
	_, binaryHash, err := currentBinaryHash(root)
	if err != nil {
		return nil, err
	}
	requestVirtual := "/opt/etc/keenwg/update/pending.json"
	return &companionSelfUpdater{
		stager: selfupdate.NewStager(selfupdate.StagerConfig{
			UpdateDirectory: updateDirectory, CurrentVersion: version, CurrentBinarySHA256: binaryHash,
			PublicKey: publicKey, KeyID: trusted.KeyID, Random: rand.Reader,
		}),
		updateDirectory: updateDirectory, requestVirtual: requestVirtual,
		requestPath: rootedPath(root, requestVirtual), statusPath: filepath.Join(updateDirectory, "status.json"),
		updaterPath:    rootedPath(root, "/opt/lib/keenwg-companion/current/keenwg-updater"),
		currentVersion: version, publicKey: publicKey, keyID: trusted.KeyID, root: root,
	}, nil
}

func (u *companionSelfUpdater) Status(context.Context) (selfupdate.Status, error) {
	status, err := selfupdate.ReadStatus(u.statusPath)
	if errors.Is(err, os.ErrNotExist) {
		return selfupdate.Status{SchemaVersion: 1, CurrentVersion: u.currentVersion, Supported: true, Phase: "idle", Result: "idle"}, nil
	}
	if err != nil {
		return selfupdate.Status{}, err
	}
	status.Supported = true
	if status.Result != "running" {
		status.CurrentVersion = u.currentVersion
	}
	return status, nil
}

func (u *companionSelfUpdater) Stage(_ context.Context, reader io.Reader) (selfupdate.AcceptedUpdate, error) {
	return u.stager.Stage(reader)
}

func (u *companionSelfUpdater) Launch(selfupdate.AcceptedUpdate) error {
	info, err := os.Lstat(u.updaterPath)
	if err != nil || !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 || info.Mode().Perm()&0o111 == 0 {
		return errors.New("updater unavailable")
	}
	command := exec.Command(u.updaterPath, "-request", u.requestVirtual)
	command.Stdin, command.Stdout, command.Stderr = nil, nil, nil
	command.Env = os.Environ()
	if u.root != "" {
		command.Env = append(command.Env, "KEENWG_DESTDIR="+u.root)
	}
	command.SysProcAttr = detachedProcessAttributes()
	if err := command.Start(); err != nil {
		status := selfupdate.Status{
			SchemaVersion: 1, CurrentVersion: u.currentVersion, Supported: true,
			Phase: "complete", Result: "failed", Error: "launch_failed",
		}
		_ = selfupdate.WriteStatus(u.statusPath, status)
		request, readErr := selfupdate.ReadPendingRequest(u.requestPath, u.updateDirectory)
		if readErr == nil {
			_ = os.Remove(filepath.Join(u.updateDirectory, request.ArchiveFile))
		}
		_ = os.Remove(u.requestPath)
		return err
	}
	return nil
}

func currentBinaryHash(root string) (string, string, error) {
	path := rootedPath(root, "/opt/lib/keenwg-companion/current/keenwg-companion")
	file, err := os.Open(path)
	if err != nil {
		return "", "", err
	}
	hasher := sha256.New()
	_, hashErr := io.Copy(hasher, io.LimitReader(file, selfupdate.MaximumArchiveBytes+1))
	closeErr := file.Close()
	if hashErr != nil {
		return "", "", hashErr
	}
	if closeErr != nil {
		return "", "", closeErr
	}
	return path, hex.EncodeToString(hasher.Sum(nil)), nil
}

func cleanupAbandonedUpdates(directory string) error {
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	if err := os.Chmod(directory, 0o700); err != nil {
		return err
	}
	entries, err := os.ReadDir(directory)
	if err != nil {
		return err
	}
	requestExists := false
	if info, statErr := os.Lstat(filepath.Join(directory, "pending.json")); statErr == nil && info.Mode().IsRegular() {
		requestExists = true
	}
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".upload-") {
			if err := os.Remove(filepath.Join(directory, entry.Name())); err != nil {
				return err
			}
		} else if !requestExists && strings.HasPrefix(entry.Name(), "pending-") && strings.HasSuffix(entry.Name(), ".tar.gz") {
			if err := os.Remove(filepath.Join(directory, entry.Name())); err != nil {
				return err
			}
		}
	}
	return nil
}
