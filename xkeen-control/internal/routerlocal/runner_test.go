package routerlocal

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

func TestCommandBuildersRejectUnsafeInput(t *testing.T) {
	if _, err := QueryWireGuard("Wireguard0; reboot"); !errors.Is(err, ErrInvalidCommand) {
		t.Fatalf("unsafe interface error = %v", err)
	}
	if _, err := Mutate("interface Wireguard0 wireguard peer key\nreboot"); !errors.Is(err, ErrInvalidCommand) {
		t.Fatalf("unsafe mutation error = %v", err)
	}
	if _, err := Mutate("show running-config"); !errors.Is(err, ErrInvalidCommand) {
		t.Fatalf("non-mutation error = %v", err)
	}
}

func TestExecRunnerUsesExactNDMQArguments(t *testing.T) {
	t.Setenv("GO_WANT_ROUTERLOCAL_HELPER", "echo-args")
	runner := ExecRunner{commandContext: helperCommand}
	out, err := runner.Run(context.Background(), QueryHotspot())
	if err != nil {
		t.Fatal(err)
	}
	if got, want := strings.TrimSpace(string(out)), "-p|show ip hotspot|-x"; got != want {
		t.Fatalf("args = %q, want %q", got, want)
	}
}

func TestExecRunnerBoundsFailuresAndSanitizesErrors(t *testing.T) {
	tests := []struct {
		name string
		mode string
		want error
	}{
		{name: "nonzero", mode: "fail-secret", want: ErrCommandFailed},
		{name: "oversize", mode: "oversize", want: ErrOutputTooLarge},
		{name: "timeout", mode: "hang", want: ErrCommandTimeout},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("GO_WANT_ROUTERLOCAL_HELPER", tc.mode)
			timeout := 2 * time.Second
			if tc.mode == "hang" {
				timeout = 20 * time.Millisecond
			}
			runner := ExecRunner{Timeout: timeout, commandContext: helperCommand}
			_, err := runner.Run(context.Background(), QueryLeases())
			if !errors.Is(err, tc.want) {
				t.Fatalf("error = %v, want %v", err, tc.want)
			}
			if strings.Contains(err.Error(), "router-password") {
				t.Fatalf("raw stderr leaked: %q", err)
			}
		})
	}
}

func TestExecRunnerPreservesParentCancellation(t *testing.T) {
	t.Setenv("GO_WANT_ROUTERLOCAL_HELPER", "hang")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := (ExecRunner{commandContext: helperCommand}).Run(ctx, QueryRunningConfig())
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("error = %v", err)
	}
}

func helperCommand(ctx context.Context, _ string, args ...string) *exec.Cmd {
	commandArgs := append([]string{"-test.run=TestRouterLocalHelperProcess", "--"}, args...)
	return exec.CommandContext(ctx, os.Args[0], commandArgs...)
}

func TestRouterLocalHelperProcess(t *testing.T) {
	mode := os.Getenv("GO_WANT_ROUTERLOCAL_HELPER")
	if mode == "" {
		return
	}
	args := os.Args
	for index := range args {
		if args[index] == "--" {
			args = args[index+1:]
			break
		}
	}
	switch mode {
	case "echo-args":
		_, _ = os.Stdout.WriteString(strings.Join(args, "|"))
	case "fail-secret":
		_, _ = os.Stderr.WriteString("router-password must never escape")
		os.Exit(17)
	case "oversize":
		_, _ = os.Stdout.WriteString(strings.Repeat("x", maxStdoutBytes+1))
	case "hang":
		time.Sleep(10 * time.Second)
	default:
		os.Exit(18)
	}
	os.Exit(0)
}
