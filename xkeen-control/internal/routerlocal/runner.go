package routerlocal

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"
)

var (
	ErrInvalidCommand    = errors.New("invalid router command")
	ErrCommandFailed     = errors.New("router command failed")
	ErrCommandTimeout    = errors.New("router command timed out")
	ErrOutputTooLarge    = errors.New("router response too large")
	ErrUnsupportedSchema = errors.New("unsupported router response")
	ErrTooManyItems      = errors.New("too many router items")
	ErrDuplicateIdentity = errors.New("duplicate router identity")
)

const (
	maxStdoutBytes = 1 << 20
	maxStderrBytes = 4 << 10
	maxItems       = 1024
)

type Command struct {
	value string
}

func QueryHotspot() Command       { return Command{value: "show ip hotspot"} }
func QueryLeases() Command        { return Command{value: "show ip dhcp bindings"} }
func QueryRunningConfig() Command { return Command{value: "show running-config"} }
func SaveConfiguration() Command  { return Command{value: "system configuration save"} }

var interfacePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9/_-]{0,63}$`)

func QueryWireGuard(interfaceID string) (Command, error) {
	if !interfacePattern.MatchString(interfaceID) {
		return Command{}, ErrInvalidCommand
	}
	return Command{value: "show interface " + interfaceID}, nil
}

var mutationPatterns = []*regexp.Regexp{
	regexp.MustCompile(`^ip dhcp host [0-9a-f]{2}(?::[0-9a-f]{2}){5} (?:0|[1-9][0-9]{0,2})(?:\.(?:0|[1-9][0-9]{0,2})){3}$`),
	regexp.MustCompile(`^no ip dhcp host [0-9a-f]{2}(?::[0-9a-f]{2}){5}$`),
	regexp.MustCompile(`^interface [A-Za-z0-9][A-Za-z0-9/_-]{0,63} no wireguard peer [A-Za-z0-9+/]{43}=$`),
	regexp.MustCompile(`^interface [A-Za-z0-9][A-Za-z0-9/_-]{0,63} wireguard peer [A-Za-z0-9+/]{43}= ![A-Za-z0-9][A-Za-z0-9_-]{0,63}$`),
	regexp.MustCompile(`^interface [A-Za-z0-9][A-Za-z0-9/_-]{0,63} wireguard peer [A-Za-z0-9+/]{43}= allow-ips (?:0|[1-9][0-9]{0,2})(?:\.(?:0|[1-9][0-9]{0,2})){3} 255\.255\.255\.255$`),
	regexp.MustCompile(`^interface [A-Za-z0-9][A-Za-z0-9/_-]{0,63} wireguard peer [A-Za-z0-9+/]{43}= keepalive-interval (?:0|[1-9][0-9]{0,3})$`),
	regexp.MustCompile(`^interface [A-Za-z0-9][A-Za-z0-9/_-]{0,63} wireguard peer [A-Za-z0-9+/]{43}= (?:connect|no connect)$`),
}

func Mutate(value string) (Command, error) {
	if strings.TrimSpace(value) != value || strings.ContainsAny(value, "\r\n\x00") {
		return Command{}, ErrInvalidCommand
	}
	for _, pattern := range mutationPatterns {
		if pattern.MatchString(value) {
			return Command{value: value}, nil
		}
	}
	return Command{}, ErrInvalidCommand
}

type Runner interface {
	Run(context.Context, Command) ([]byte, error)
}

type ExecRunner struct {
	Executable     string
	Timeout        time.Duration
	commandContext func(context.Context, string, ...string) *exec.Cmd
}

func (r ExecRunner) Run(ctx context.Context, command Command) ([]byte, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if command.value == "" {
		return nil, ErrInvalidCommand
	}
	timeout := r.Timeout
	if timeout <= 0 {
		timeout = 2 * time.Second
	}
	childCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	executable := r.Executable
	if executable == "" {
		executable = "ndmq"
	}
	commandContext := r.commandContext
	if commandContext == nil {
		commandContext = exec.CommandContext
	}
	cmd := commandContext(childCtx, executable, "-p", command.value, "-x")
	cmd.WaitDelay = 250 * time.Millisecond
	stdout := newBoundedBuffer(maxStdoutBytes)
	stderr := newBoundedBuffer(maxStderrBytes)
	cmd.Stdout = stdout
	cmd.Stderr = stderr
	if err := cmd.Start(); err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, ErrCommandFailed
	}
	waited := make(chan error, 1)
	go func() { waited <- cmd.Wait() }()
	var waitErr error
	select {
	case <-stdout.oversize:
		_ = cmd.Process.Kill()
		waitErr = <-waited
	case waitErr = <-waited:
	case <-childCtx.Done():
		_ = cmd.Process.Kill()
		waitErr = <-waited
	}
	if stdout.TooLarge() {
		return nil, ErrOutputTooLarge
	}
	if childCtx.Err() != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, ErrCommandTimeout
	}
	if waitErr != nil {
		return nil, ErrCommandFailed
	}
	return stdout.Bytes(), nil
}

type boundedBuffer struct {
	mu       sync.Mutex
	data     bytes.Buffer
	max      int
	tooLarge bool
	once     sync.Once
	oversize chan struct{}
}

func newBoundedBuffer(max int) *boundedBuffer {
	return &boundedBuffer{max: max, oversize: make(chan struct{})}
}

func (w *boundedBuffer) Write(value []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	original := len(value)
	remaining := w.max + 1 - w.data.Len()
	if remaining > 0 {
		if len(value) > remaining {
			value = value[:remaining]
		}
		_, _ = w.data.Write(value)
	}
	if w.data.Len() > w.max {
		w.tooLarge = true
		w.once.Do(func() { close(w.oversize) })
	}
	return original, nil
}

func (w *boundedBuffer) TooLarge() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.tooLarge
}

func (w *boundedBuffer) Bytes() []byte {
	w.mu.Lock()
	defer w.mu.Unlock()
	return append([]byte(nil), w.data.Bytes()...)
}

var _ io.Writer = (*boundedBuffer)(nil)
