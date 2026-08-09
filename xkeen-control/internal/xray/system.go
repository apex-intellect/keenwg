package xray

import (
	"bytes"
	"context"
	"errors"
	"io"
	"io/fs"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/config"
)

var (
	ErrResolve  = errors.New("resolve_failed")
	ErrRead     = errors.New("read_failed")
	ErrWrite    = errors.New("write_failed")
	ErrValidate = errors.New("xray_validation_failed")
	ErrRestart  = errors.New("xkeen_restart_failed")
	ErrVerify   = errors.New("xkeen_verification_failed")
)

const (
	pidofBinary    = "/opt/bin/pidof"
	ipsetBinary    = "/opt/sbin/ipset"
	iptablesBinary = "/opt/sbin/iptables"
	maxFileBytes   = 2 << 20
	maxOutputBytes = 16 << 10
)

type System interface {
	ResolveIPv4(context.Context, string) ([]netip.Addr, error)
	ReadFile(string) ([]byte, error)
	WriteAtomic(string, []byte, fs.FileMode) error
	Validate(context.Context) error
	Restart(context.Context) error
	Verify(context.Context, netip.Addr) error
}

type resolver interface {
	LookupNetIP(context.Context, string, string) ([]netip.Addr, error)
}

type runner interface {
	Run(context.Context, string, []string, map[string]string) ([]byte, error)
}

type routerSystem struct {
	cfg      config.Config
	runner   runner
	resolver resolver
}

func NewSystem(cfg config.Config) System {
	return newSystem(cfg, execRunner{}, net.DefaultResolver)
}

func newSystem(cfg config.Config, runner runner, resolver resolver) *routerSystem {
	return &routerSystem{cfg: cfg, runner: runner, resolver: resolver}
}

func (s *routerSystem) ResolveIPv4(ctx context.Context, host string) ([]netip.Addr, error) {
	var addresses []netip.Addr
	if literal, err := netip.ParseAddr(host); err == nil {
		addresses = []netip.Addr{literal}
	} else {
		resolved, err := s.resolver.LookupNetIP(ctx, "ip4", host)
		if err != nil {
			return nil, ErrResolve
		}
		addresses = resolved
	}
	result := make([]netip.Addr, 0, len(addresses))
	seen := make(map[netip.Addr]struct{}, len(addresses))
	for _, address := range addresses {
		address = address.Unmap()
		if !s.acceptServerAddress(address) {
			continue
		}
		if _, exists := seen[address]; exists {
			continue
		}
		seen[address] = struct{}{}
		result = append(result, address)
	}
	if len(result) == 0 {
		return nil, ErrResolve
	}
	return result, nil
}

func (s *routerSystem) acceptServerAddress(address netip.Addr) bool {
	if !address.IsValid() || !address.Is4() || address.IsUnspecified() || address.IsLoopback() || address.IsMulticast() || address.IsLinkLocalUnicast() || address.IsLinkLocalMulticast() {
		return false
	}
	private := address.IsPrivate() || netip.MustParsePrefix("100.64.0.0/10").Contains(address)
	if private {
		return s.cfg.AllowPrivateServers
	}
	return address.IsGlobalUnicast()
}

func (s *routerSystem) ReadFile(filePath string) ([]byte, error) {
	if !s.allowedFile(filePath) {
		return nil, ErrRead
	}
	file, err := os.Open(filePath)
	if err != nil {
		return nil, ErrRead
	}
	defer file.Close()
	body, err := io.ReadAll(io.LimitReader(file, maxFileBytes+1))
	if err != nil || len(body) > maxFileBytes {
		return nil, ErrRead
	}
	return body, nil
}

func (s *routerSystem) WriteAtomic(filePath string, body []byte, mode fs.FileMode) error {
	if !s.allowedFile(filePath) || mode.Perm() != 0o600 || len(body) > maxFileBytes {
		return ErrWrite
	}
	directory := filepath.Dir(filePath)
	file, err := os.CreateTemp(directory, "."+filepath.Base(filePath)+".tmp-*")
	if err != nil {
		return ErrWrite
	}
	temporary := file.Name()
	closed := false
	defer func() {
		if !closed {
			_ = file.Close()
		}
		_ = os.Remove(temporary)
	}()
	if err := file.Chmod(mode); err != nil {
		return ErrWrite
	}
	if _, err := file.Write(body); err != nil {
		return ErrWrite
	}
	if err := file.Sync(); err != nil {
		return ErrWrite
	}
	if err := file.Close(); err != nil {
		return ErrWrite
	}
	closed = true
	if err := os.Rename(temporary, filePath); err != nil {
		return ErrWrite
	}
	if err := os.Chmod(filePath, mode); err != nil {
		return ErrWrite
	}
	return nil
}

func (s *routerSystem) Validate(ctx context.Context) error {
	confdir := path.Dir(s.cfg.OutboundsPath)
	env := map[string]string{
		"XRAY_LOCATION_ASSET":   s.cfg.AssetDir,
		"XRAY_LOCATION_CONFDIR": confdir,
	}
	if _, err := s.run(ctx, s.cfg.XrayBinary, []string{"run", "-test", "-confdir", confdir}, env, 30*time.Second); err != nil {
		return ErrValidate
	}
	return nil
}

func (s *routerSystem) Restart(ctx context.Context) error {
	if _, err := s.run(ctx, s.cfg.InitScript, []string{"restart"}, nil, 45*time.Second); err != nil {
		return ErrRestart
	}
	return nil
}

func (s *routerSystem) Verify(ctx context.Context, address netip.Addr) error {
	if !validEndpointIP(address) {
		return ErrVerify
	}
	commands := []struct {
		name string
		args []string
	}{
		{s.cfg.InitScript, []string{"status"}},
		{pidofBinary, []string{"xray"}},
		{ipsetBinary, []string{"test", "user_exclude", address.String()}},
		{iptablesBinary, []string{"-t", "nat", "-C", "xkeen", "-m", "set", "--match-set", "user_exclude", "dst", "-m", "comment", "--comment", "xkeen_rule", "-j", "RETURN"}},
		{iptablesBinary, []string{"-t", "mangle", "-C", "xkeen", "-m", "set", "--match-set", "user_exclude", "dst", "-m", "comment", "--comment", "xkeen_rule", "-j", "RETURN"}},
	}
	for _, command := range commands {
		if _, err := s.run(ctx, command.name, command.args, nil, 15*time.Second); err != nil {
			return ErrVerify
		}
	}
	return nil
}

func (s *routerSystem) run(ctx context.Context, name string, args []string, env map[string]string, timeout time.Duration) ([]byte, error) {
	commandContext, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	return s.runner.Run(commandContext, name, args, env)
}

func (s *routerSystem) allowedFile(filePath string) bool {
	return filePath == s.cfg.OutboundsPath || filePath == s.cfg.ExcludePath || filePath == s.cfg.DomainPolicyPath ||
		filePath == s.cfg.DomainPolicyBackup || filePath == s.cfg.RoutingPath
}

func GeoSiteAvailable(assetDir, name string) bool {
	if name != "category-gov-ru" {
		return false
	}
	file, err := os.Open(filepath.Join(assetDir, "geosite_v2fly.dat"))
	if err != nil {
		return false
	}
	defer file.Close()
	body, err := io.ReadAll(io.LimitReader(file, 64<<20))
	if err != nil || len(body) == 64<<20 {
		return false
	}
	return bytes.Contains(bytes.ToUpper(body), []byte("CATEGORY-GOV-RU"))
}

type execRunner struct{}

func (execRunner) Run(ctx context.Context, name string, args []string, environment map[string]string) ([]byte, error) {
	command := exec.CommandContext(ctx, name, args...)
	command.Env = []string{"PATH=/opt/bin:/opt/sbin:/sbin:/bin:/usr/sbin:/usr/bin"}
	for key, value := range environment {
		command.Env = append(command.Env, key+"="+value)
	}
	readOutput, writeOutput, err := os.Pipe()
	if err != nil {
		return nil, err
	}
	defer readOutput.Close()
	command.Stdout = writeOutput
	command.Stderr = writeOutput
	buffer := &boundedBuffer{remaining: maxOutputBytes}
	if err := command.Start(); err != nil {
		_ = writeOutput.Close()
		return nil, err
	}
	_ = writeOutput.Close()
	copyDone := make(chan struct{})
	go func() {
		_, _ = io.Copy(buffer, readOutput)
		close(copyDone)
	}()
	err = command.Wait()
	_ = readOutput.Close()
	<-copyDone
	return append([]byte(nil), buffer.Bytes()...), err
}

type boundedBuffer struct {
	bytes.Buffer
	remaining int
}

func (b *boundedBuffer) Write(data []byte) (int, error) {
	original := len(data)
	if b.remaining > 0 {
		write := len(data)
		if write > b.remaining {
			write = b.remaining
		}
		_, _ = b.Buffer.Write(data[:write])
		b.remaining -= write
	}
	return original, nil
}
