package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/selfupdate"
)

var version = "dev"

func main() {
	if err := run(os.Args[1:]); err != nil {
		_, _ = fmt.Fprintln(os.Stderr, "keenwg-updater failed")
		os.Exit(1)
	}
}

func run(args []string) error {
	flags := flag.NewFlagSet("keenwg-updater", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	requestVirtual := flags.String("request", "", "fixed pending request")
	showVersion := flags.Bool("version", false, "print version")
	if err := flags.Parse(args); err != nil || flags.NArg() != 0 {
		return errors.New("invalid arguments")
	}
	if *showVersion {
		if *requestVirtual != "" {
			return errors.New("invalid arguments")
		}
		_, _ = fmt.Printf("keenwg-updater %s\n", version)
		return nil
	}
	if err := validateRequestPath(*requestVirtual); err != nil {
		return err
	}
	root := os.Getenv("KEENWG_DESTDIR")
	if root != "" {
		if !filepath.IsAbs(root) || filepath.Clean(root) != root || root == string(filepath.Separator) {
			return errors.New("invalid destination root")
		}
		info, err := os.Lstat(root)
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return errors.New("invalid destination root")
		}
	}
	updateDirectory := rooted(root, "/opt/etc/keenwg/update")
	requestPath := rooted(root, *requestVirtual)
	statusPath := filepath.Join(updateDirectory, "status.json")
	request, err := selfupdate.ReadPendingRequest(requestPath, updateDirectory)
	if err != nil {
		_ = selfupdate.WriteStatus(statusPath, selfupdate.Status{CurrentVersion: version, Supported: true, Phase: "verification", Result: "failed", Error: "invalid_request"})
		return err
	}
	trusted, publicKey, err := selfupdate.TrustedKey()
	if err != nil {
		return err
	}
	archivePath, err := selfupdate.VerifyPendingRequest(request, updateDirectory, publicKey, trusted.KeyID)
	if err != nil {
		_ = selfupdate.WriteStatus(statusPath, selfupdate.Status{CurrentVersion: request.CurrentVersion, Supported: true, Phase: "verification", Result: "failed", TargetVersion: request.TargetVersion, Error: "verification_failed"})
		return err
	}
	status := selfupdate.Status{CurrentVersion: request.CurrentVersion, Supported: true, Phase: "installing", Result: "running", TargetVersion: request.TargetVersion}
	if err := selfupdate.WriteStatus(statusPath, status); err != nil {
		return err
	}
	workDirectory := filepath.Join(updateDirectory, "work-"+request.OperationID)
	if _, err := os.Lstat(workDirectory); !errors.Is(err, os.ErrNotExist) {
		return fail(statusPath, status, "invalid_request")
	}
	if err := selfupdate.ExtractBundle(archivePath, workDirectory); err != nil {
		return fail(statusPath, status, "verification_failed")
	}
	defer os.RemoveAll(workDirectory)
	bootstrapVirtual := "/opt/tmp/keenwg-" + request.OperationID + ".json"
	bootstrapPath := rooted(root, bootstrapVirtual)
	if err := os.MkdirAll(filepath.Dir(bootstrapPath), 0o700); err != nil {
		return fail(statusPath, status, "install_failed")
	}
	if err := os.WriteFile(bootstrapPath, []byte("{\"schema_version\":1}\n"), 0o600); err != nil {
		return fail(statusPath, status, "install_failed")
	}
	defer os.Remove(bootstrapPath)
	installer := filepath.Join(workDirectory, "install-companion.sh")
	command := exec.Command("sh", installer, "--request", bootstrapVirtual)
	command.Stdout = io.Discard
	command.Stderr = io.Discard
	command.Env = os.Environ()
	if root != "" {
		command.Env = append(command.Env, "KEENWG_DESTDIR="+root)
	}
	if err := command.Run(); err != nil {
		return fail(statusPath, status, "install_failed")
	}
	status.CurrentVersion = request.TargetVersion
	status.Phase = "complete"
	status.Result = "installed"
	status.Error = ""
	if err := selfupdate.WriteStatus(statusPath, status); err != nil {
		return err
	}
	_ = os.Remove(requestPath)
	_ = os.Remove(archivePath)
	return nil
}

func fail(statusPath string, status selfupdate.Status, code string) error {
	if !safeErrorCode(code) {
		code = "install_failed"
	}
	status.Phase = "complete"
	status.Result = "failed"
	status.Error = code
	_ = selfupdate.WriteStatus(statusPath, status)
	return errors.New(code)
}

func validateRequestPath(value string) error {
	if value != "/opt/etc/keenwg/update/pending.json" {
		return errors.New("request path is not allowed")
	}
	return nil
}

func safeErrorCode(value string) bool {
	switch value {
	case "invalid_request", "verification_failed", "install_failed", "health_failed":
		return true
	default:
		return false
	}
}

func rooted(root, virtual string) string {
	if root == "" {
		return filepath.FromSlash(virtual)
	}
	return filepath.Join(root, filepath.FromSlash(strings.TrimPrefix(virtual, "/")))
}
