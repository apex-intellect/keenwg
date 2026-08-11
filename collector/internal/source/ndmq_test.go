package source

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestParseKeenOS5PeersPreservesRawSentinel(t *testing.T) {
	peers, err := ParseXML(fixture(t, "keenos5.xml"), "Wireguard0", time.Unix(1_800_000_000, 0))
	if err != nil {
		t.Fatal(err)
	}
	if len(peers) != 2 {
		t.Fatalf("len(peers) = %d, want 2", len(peers))
	}
	if peers[1].HandshakeRaw == nil || *peers[1].HandshakeRaw != 2_147_483_647 {
		t.Fatalf("HandshakeRaw = %v, want 2147483647", peers[1].HandshakeRaw)
	}
	if peers[1].Online {
		t.Fatal("sentinel fixture peer unexpectedly online")
	}
	if peers[0].RouterRXBytes != 1234 || peers[0].RouterTXBytes != 5678 || peers[0].Label != "phone" {
		t.Fatalf("first peer = %+v", peers[0])
	}
}

func TestParseNetCrazeDirectInterfaceResponse(t *testing.T) {
	peers, err := ParseXML(fixture(t, "netcraze_hopper.xml"), "Wireguard0", time.Unix(1_800_000_000, 0))
	if err != nil {
		t.Fatal(err)
	}
	if len(peers) != 2 {
		t.Fatalf("len(peers) = %d, want 2", len(peers))
	}
	if !peers[0].Online || !peers[0].Enabled || peers[0].Label != "phone" {
		t.Fatalf("first peer state = %+v", peers[0])
	}
	if peers[0].RouterRXBytes != 269297884 || peers[0].RouterTXBytes != 3309709420 {
		t.Fatalf("first peer counters = %d/%d", peers[0].RouterRXBytes, peers[0].RouterTXBytes)
	}
	if peers[1].Online || peers[1].Enabled || peers[1].Label != "tablet" {
		t.Fatalf("second peer state = %+v", peers[1])
	}
	if peers[1].HandshakeRaw == nil || *peers[1].HandshakeRaw != 2_147_483_647 {
		t.Fatalf("HandshakeRaw = %v, want 2147483647", peers[1].HandshakeRaw)
	}
}

func TestParseRejectsSchemaWithoutRequiredPeerFields(t *testing.T) {
	_, err := ParseXML(fixture(t, "unsupported.xml"), "Wireguard0", time.Now())
	if !errors.Is(err, ErrUnsupportedSchema) {
		t.Fatalf("error = %v, want ErrUnsupportedSchema", err)
	}
}

func TestParseRejectsMoreThan1024Peers(t *testing.T) {
	peer := `<peer><public-key>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</public-key><online>true</online><rxbytes>1</rxbytes><txbytes>2</txbytes></peer>`
	xml := []byte("<response><interface><id>Wireguard0</id>" + strings.Repeat(peer, 1025) + "</interface></response>")
	_, err := ParseXML(xml, "Wireguard0", time.Now())
	if !errors.Is(err, ErrTooManyPeers) {
		t.Fatalf("error = %v, want ErrTooManyPeers", err)
	}
}

func TestParseRequiresResponseRootAndRequestedInterface(t *testing.T) {
	validPeer := `<peer><public-key>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</public-key><online>true</online><rxbytes>1</rxbytes><txbytes>2</txbytes></peer>`
	for name, document := range map[string]string{
		"wrong root":      `<not-response><interface><id>Wireguard0</id>` + validPeer + `</interface></not-response>`,
		"wrong interface": `<response><interface><id>Wireguard1</id>` + validPeer + `</interface></response>`,
	} {
		t.Run(name, func(t *testing.T) {
			_, err := ParseXML([]byte(document), "Wireguard0", time.Now())
			if !errors.Is(err, ErrUnsupportedSchema) {
				t.Fatalf("error = %v, want ErrUnsupportedSchema", err)
			}
		})
	}
}

func TestParseRejectsWhitespaceAroundCanonicalPublicKey(t *testing.T) {
	document := `<response><interface><id>Wireguard0</id><peer><public-key> AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= </public-key><online>true</online><rxbytes>1</rxbytes><txbytes>2</txbytes></peer></interface></response>`
	_, err := ParseXML([]byte(document), "Wireguard0", time.Now())
	if !errors.Is(err, ErrUnsupportedSchema) {
		t.Fatalf("error = %v, want ErrUnsupportedSchema", err)
	}
}

func TestParseRejectsTrailingRootOrMalformedSuffix(t *testing.T) {
	valid := `<response><interface><id>Wireguard0</id><peer><public-key>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</public-key><online>true</online><rxbytes>1</rxbytes><txbytes>2</txbytes></peer></interface></response>`
	for name, suffix := range map[string]string{
		"second root":      `<response/>`,
		"malformed suffix": `<broken`,
	} {
		t.Run(name, func(t *testing.T) {
			_, err := ParseXML([]byte(valid+suffix), "Wireguard0", time.Now())
			if !errors.Is(err, ErrUnsupportedSchema) {
				t.Fatalf("error = %v, want ErrUnsupportedSchema", err)
			}
		})
	}
}

func TestParseEnforcesPeerCapBeforeMalformedTail(t *testing.T) {
	peer := `<peer><public-key>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</public-key><online>true</online><rxbytes>1</rxbytes><txbytes>2</txbytes></peer>`
	document := `<response><interface><id>Wireguard0</id>` + strings.Repeat(peer, 1025) + `<broken`
	_, err := ParseXML([]byte(document), "Wireguard0", time.Now())
	if !errors.Is(err, ErrTooManyPeers) {
		t.Fatalf("error = %v, want immediate ErrTooManyPeers", err)
	}
}

func TestRunnerRejectsUnsafeInterfaceBeforeStartingCommand(t *testing.T) {
	r := Runner{Command: filepath.Join(t.TempDir(), "missing-ndmq")}
	_, err := r.Run(context.Background(), "Wireguard0; reboot")
	if !errors.Is(err, ErrInvalidInterfaceID) {
		t.Fatalf("error = %v, want ErrInvalidInterfaceID", err)
	}
}

func TestRunnerReportsOversizeOutput(t *testing.T) {
	r := helperRunner(t, "oversize-block")
	started := time.Now()
	_, err := r.Run(context.Background(), "Wireguard0")
	if !errors.Is(err, ErrOutputTooLarge) {
		t.Fatalf("error = %v, want ErrOutputTooLarge", err)
	}
	if elapsed := time.Since(started); elapsed >= time.Second {
		t.Fatalf("oversize process was not terminated promptly: %s", elapsed)
	}
}

func TestRunnerInheritedStdoutCannotHangWait(t *testing.T) {
	r := helperRunner(t, "inherited-stdout")
	started := time.Now()
	_, err := r.Run(context.Background(), "Wireguard0")
	if err == nil {
		t.Fatal("Run() succeeded while a descendant retained stdout")
	}
	// Race instrumentation makes the two helper-process startups noticeably
	// slower on shared CI runners. The descendant deliberately retains stdout
	// for five seconds, so a three-second ceiling still proves WaitDelay broke
	// the inherited-pipe hang instead of merely waiting for that descendant.
	if elapsed := time.Since(started); elapsed >= 3*time.Second {
		t.Fatalf("inherited stdout blocked Run for %s", elapsed)
	}
}

func TestRunnerSanitizesNonzeroExitAndPassesExactArguments(t *testing.T) {
	r := helperRunner(t, "nonzero")
	_, err := r.Run(context.Background(), "Wireguard0")
	if !errors.Is(err, ErrCommandFailed) {
		t.Fatalf("error = %v, want ErrCommandFailed", err)
	}
	if strings.Contains(err.Error(), "router-secret") {
		t.Fatalf("error exposed ndmq stderr: %v", err)
	}

	r = helperRunner(t, "exact-args")
	peers, err := r.Run(context.Background(), "Wireguard0")
	if err != nil {
		t.Fatal(err)
	}
	if len(peers) != 1 {
		t.Fatalf("len(peers) = %d, want 1", len(peers))
	}
}

func TestRunnerClassifiesTimeoutDistinctly(t *testing.T) {
	r := helperRunner(t, "timeout")
	_, err := r.Run(context.Background(), "Wireguard0")
	if !errors.Is(err, ErrCommandTimeout) || errors.Is(err, ErrOutputTooLarge) {
		t.Fatalf("error = %v, want only ErrCommandTimeout", err)
	}
}

func helperRunner(t *testing.T, mode string) Runner {
	t.Helper()
	t.Setenv("GO_WANT_NDMQ_HELPER", "1")
	t.Setenv("NDMQ_HELPER_MODE", mode)
	return Runner{
		Command: os.Args[0],
		commandContext: func(ctx context.Context, _ string, args ...string) *exec.Cmd {
			childArgs := append([]string{"-test.run=TestNDMQHelperProcess", "--"}, args...)
			return exec.CommandContext(ctx, os.Args[0], childArgs...)
		},
	}
}

func TestNDMQHelperProcess(t *testing.T) {
	if os.Getenv("GO_WANT_NDMQ_HELPER") != "1" {
		return
	}
	mode := os.Getenv("NDMQ_HELPER_MODE")
	args := os.Args
	for i, arg := range args {
		if arg == "--" {
			args = args[i+1:]
			break
		}
	}
	validXML := `<response><interface><id>Wireguard0</id><peer><public-key>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</public-key><online>true</online><rxbytes>1</rxbytes><txbytes>2</txbytes></peer></interface></response>`
	switch mode {
	case "oversize-block":
		_, _ = os.Stdout.Write(make([]byte, maxOutput+1))
		time.Sleep(10 * time.Second)
	case "inherited-stdout":
		cmd := exec.Command(os.Args[0], "-test.run=TestNDMQHelperProcess")
		cmd.Env = append(os.Environ(), "NDMQ_HELPER_MODE=linger-descendant")
		cmd.Stdout = os.Stdout
		if err := cmd.Start(); err != nil {
			os.Exit(91)
		}
		_, _ = os.Stdout.WriteString(validXML)
	case "linger-descendant":
		time.Sleep(5 * time.Second)
	case "nonzero":
		_, _ = os.Stderr.WriteString("router-secret")
		os.Exit(9)
	case "timeout":
		time.Sleep(10 * time.Second)
	case "exact-args":
		want := []string{"-p", "show interface Wireguard0", "-x"}
		if strings.Join(args, "\x00") != strings.Join(want, "\x00") {
			os.Exit(92)
		}
		_, _ = os.Stdout.WriteString(validXML)
	default:
		os.Exit(93)
	}
	os.Exit(0)
}
