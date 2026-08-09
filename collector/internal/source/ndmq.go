package source

import (
	"bytes"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/goldb/keenwg/collector/internal/model"
)

var (
	ErrUnsupportedSchema  = errors.New("unsupported ndmq schema")
	ErrTooManyPeers       = errors.New("too many peers")
	ErrInvalidInterfaceID = model.ErrInvalidInterfaceID
	ErrOutputTooLarge     = errors.New("ndmq output too large")
	ErrCommandTimeout     = errors.New("ndmq command timeout")
	ErrCommandFailed      = errors.New("ndmq command failed")
)

const maxOutput = 512 * 1024

type Runner struct {
	Command        string
	commandContext func(context.Context, string, ...string) *exec.Cmd
}

func (r Runner) Run(ctx context.Context, interfaceID string) ([]model.RuntimePeer, error) {
	if err := model.ValidateInterfaceID(interfaceID); err != nil {
		return nil, ErrInvalidInterfaceID
	}
	command := r.Command
	if command == "" {
		command = "ndmq"
	}
	childCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	commandContext := r.commandContext
	if commandContext == nil {
		commandContext = exec.CommandContext
	}
	cmd := commandContext(childCtx, command, "-p", "show interface "+interfaceID, "-x")
	cmd.WaitDelay = 250 * time.Millisecond
	stdout := newCappedOutput(maxOutput)
	cmd.Stdout = stdout
	var stderr bytes.Buffer
	cmd.Stderr = &cappedWriter{dst: &stderr, remaining: 4 << 10}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("%w: start", ErrCommandFailed)
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
	b := stdout.Bytes()
	peers, err := ParseXML(b, interfaceID, time.Now())
	if err != nil {
		return nil, fmt.Errorf("ndmq parse: %w", err)
	}
	return peers, nil
}

type cappedOutput struct {
	mu       sync.Mutex
	data     bytes.Buffer
	max      int
	tooLarge bool
	once     sync.Once
	oversize chan struct{}
}

func newCappedOutput(max int) *cappedOutput {
	return &cappedOutput{max: max, oversize: make(chan struct{})}
}

func (w *cappedOutput) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	original := len(p)
	remaining := w.max + 1 - w.data.Len()
	if remaining > 0 {
		if len(p) > remaining {
			p = p[:remaining]
		}
		_, _ = w.data.Write(p)
	}
	if w.data.Len() > w.max {
		w.tooLarge = true
		w.once.Do(func() { close(w.oversize) })
	}
	return original, nil
}

func (w *cappedOutput) TooLarge() bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.tooLarge
}

func (w *cappedOutput) Bytes() []byte {
	w.mu.Lock()
	defer w.mu.Unlock()
	return append([]byte(nil), w.data.Bytes()...)
}

type cappedWriter struct {
	dst       io.Writer
	remaining int
}

func (w *cappedWriter) Write(p []byte) (int, error) {
	original := len(p)
	if len(p) > w.remaining {
		p = p[:w.remaining]
	}
	if len(p) > 0 {
		if _, err := w.dst.Write(p); err != nil {
			return 0, err
		}
		w.remaining -= len(p)
	}
	return original, nil
}

type xmlPeer struct {
	PublicKey   string  `xml:"public-key"`
	Name        string  `xml:"name"`
	Description string  `xml:"description"`
	Online      *string `xml:"online"`
	Enabled     *string `xml:"enabled"`
	Handshake   *int64  `xml:"last-handshake"`
	RXBytes     *uint64 `xml:"rxbytes"`
	TXBytes     *uint64 `xml:"txbytes"`
}

func ParseXML(data []byte, interfaceID string, observedAt time.Time) ([]model.RuntimePeer, error) {
	if err := model.ValidateInterfaceID(interfaceID); err != nil {
		return nil, ErrInvalidInterfaceID
	}
	decoder := xml.NewDecoder(bytes.NewReader(data))
	type interfaceFrame struct {
		id    string
		peers []xmlPeer
	}
	var current *interfaceFrame
	var direct interfaceFrame
	var requested []xmlPeer
	rootSeen, rootClosed, requestedSeen := false, false, false
	directWireGuard, directWireGuardSeen := false, false
	depth, peerCount := 0, 0
	for {
		token, err := decoder.Token()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("%w: malformed XML", ErrUnsupportedSchema)
		}
		switch value := token.(type) {
		case xml.StartElement:
			depth++
			if depth == 1 {
				if rootSeen || rootClosed || value.Name.Local != "response" {
					return nil, ErrUnsupportedSchema
				}
				rootSeen = true
				continue
			}
			if !rootSeen || rootClosed {
				return nil, ErrUnsupportedSchema
			}
			if depth == 2 {
				if value.Name.Local == "interface" {
					current = &interfaceFrame{}
					continue
				}
				if value.Name.Local == "id" {
					if direct.id != "" {
						return nil, ErrUnsupportedSchema
					}
					if err := decoder.DecodeElement(&direct.id, &value); err != nil {
						return nil, ErrUnsupportedSchema
					}
					depth--
					continue
				}
				if value.Name.Local == "wireguard" {
					if directWireGuardSeen {
						return nil, ErrUnsupportedSchema
					}
					directWireGuard = true
					directWireGuardSeen = true
					continue
				}
				if err := decoder.Skip(); err != nil {
					return nil, ErrUnsupportedSchema
				}
				depth--
				continue
			}
			if depth == 3 && current != nil {
				switch value.Name.Local {
				case "id":
					if current.id != "" {
						return nil, ErrUnsupportedSchema
					}
					if err := decoder.DecodeElement(&current.id, &value); err != nil {
						return nil, ErrUnsupportedSchema
					}
					depth--
				case "peer":
					peerCount++
					if peerCount > 1024 {
						return nil, ErrTooManyPeers
					}
					var raw xmlPeer
					if err := decoder.DecodeElement(&raw, &value); err != nil {
						return nil, ErrUnsupportedSchema
					}
					current.peers = append(current.peers, raw)
					depth--
				default:
					if err := decoder.Skip(); err != nil {
						return nil, ErrUnsupportedSchema
					}
					depth--
				}
				continue
			}
			if depth == 3 && directWireGuard {
				if value.Name.Local == "peer" {
					peerCount++
					if peerCount > 1024 {
						return nil, ErrTooManyPeers
					}
					var raw xmlPeer
					if err := decoder.DecodeElement(&raw, &value); err != nil {
						return nil, ErrUnsupportedSchema
					}
					direct.peers = append(direct.peers, raw)
					depth--
					continue
				}
				if err := decoder.Skip(); err != nil {
					return nil, ErrUnsupportedSchema
				}
				depth--
				continue
			}
			if err := decoder.Skip(); err != nil {
				return nil, ErrUnsupportedSchema
			}
			depth--
		case xml.EndElement:
			if depth == 2 && value.Name.Local == "interface" && current != nil {
				if strings.TrimSpace(current.id) == interfaceID {
					if requestedSeen {
						return nil, ErrUnsupportedSchema
					}
					requested = current.peers
					requestedSeen = true
				}
				current = nil
			}
			if depth == 2 && value.Name.Local == "wireguard" && directWireGuard {
				directWireGuard = false
			}
			if depth == 1 && value.Name.Local == "response" {
				if strings.TrimSpace(direct.id) == interfaceID {
					if requestedSeen {
						return nil, ErrUnsupportedSchema
					}
					requested = direct.peers
					requestedSeen = true
				}
				rootClosed = true
			}
			depth--
		case xml.CharData:
			if (depth == 0 || rootClosed) && strings.TrimSpace(string(value)) != "" {
				return nil, ErrUnsupportedSchema
			}
		}
	}
	if !rootSeen || !rootClosed || depth != 0 || !requestedSeen {
		return nil, ErrUnsupportedSchema
	}
	peers := make([]model.RuntimePeer, 0, len(requested))
	for _, raw := range requested {
		if raw.PublicKey == "" || raw.Online == nil || raw.RXBytes == nil || raw.TXBytes == nil {
			return nil, ErrUnsupportedSchema
		}
		online, err := parseNDMQBool(*raw.Online)
		if err != nil {
			return nil, ErrUnsupportedSchema
		}
		enabled := true
		if raw.Enabled != nil {
			enabled, err = parseNDMQBool(*raw.Enabled)
			if err != nil {
				return nil, ErrUnsupportedSchema
			}
		}
		peerID, err := model.PeerID(interfaceID, raw.PublicKey)
		if err != nil {
			return nil, fmt.Errorf("%w: invalid public key", ErrUnsupportedSchema)
		}
		label := strings.TrimSpace(raw.Name)
		if label == "" {
			label = strings.TrimSpace(raw.Description)
		}
		peers = append(peers, model.RuntimePeer{
			PeerID: peerID, InterfaceID: interfaceID, Label: label,
			Online: online, Enabled: enabled, HandshakeRaw: raw.Handshake,
			RouterRXBytes: *raw.RXBytes, RouterTXBytes: *raw.TXBytes, ObservedAt: observedAt,
		})
	}
	if len(peers) == 0 {
		return nil, ErrUnsupportedSchema
	}
	return peers, nil
}

func parseNDMQBool(value string) (bool, error) {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "yes":
		return true, nil
	case "no":
		return false, nil
	default:
		return strconv.ParseBool(strings.TrimSpace(value))
	}
}
