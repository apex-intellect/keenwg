package routerlocal

import (
	"errors"
	"os"
	"strings"
	"testing"
)

func TestParseHomeDevicesLiveShape(t *testing.T) {
	devices, err := ParseHomeDevices(fixture(t, "home.xml"), fixture(t, "leases.xml"), fixture(t, "running.xml"))
	if err != nil {
		t.Fatal(err)
	}
	if got, want := len(devices), 5; got != want {
		t.Fatalf("device count = %d, want %d", got, want)
	}
	gotOrder := make([]string, 0, len(devices))
	for _, device := range devices {
		gotOrder = append(gotOrder, device.MAC)
	}
	wantOrder := []string{
		"02:00:00:00:00:02",
		"02:00:00:00:00:01",
		"02:00:00:00:00:04",
		"02:00:00:00:00:05",
		"02:00:00:00:00:03",
	}
	if strings.Join(gotOrder, ",") != strings.Join(wantOrder, ",") {
		t.Fatalf("order = %v, want %v", gotOrder, wantOrder)
	}
	if devices[1].ReservedIP != "192.168.1.10" || !devices[1].StaticReservation {
		t.Fatalf("reservation was not merged: %+v", devices[1])
	}
	if devices[0].RSSI == nil || *devices[0].RSSI != -54 || devices[0].InterfaceName != "Home" {
		t.Fatalf("hotspot details were not preserved: %+v", devices[0])
	}
}

func TestParseWireGuardInterfaceLiveShape(t *testing.T) {
	running := fixture(t, "running.xml")
	interfaces, err := DiscoverWireGuardInterfaces(running)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := strings.Join(interfaces, ","), "Wireguard0"; got != want {
		t.Fatalf("interfaces = %q, want %q", got, want)
	}
	value, err := ParseWireGuardInterface(fixture(t, "wireguard.xml"), running, "Wireguard0")
	if err != nil {
		t.Fatal(err)
	}
	if value.ID != "Wireguard0" || len(value.Peers) != 6 {
		t.Fatalf("interface = %+v", value)
	}
	if value.Peers[0].Name != "artem_phone" || value.Peers[0].AllowedIP != "10.8.0.2" || !value.Peers[0].Online {
		t.Fatalf("first peer was not merged: %+v", value.Peers[0])
	}
	if value.Peers[0].LastHandshakeSec == nil || *value.Peers[0].LastHandshakeSec != 45 {
		t.Fatalf("handshake age was not preserved: %+v", value.Peers[0])
	}
}

func TestParseWireGuardEndpointFromLiveInterfaceShape(t *testing.T) {
	endpoints, err := ParseWireGuardEndpoints(fixture(t, "interfaces.xml"))
	if err != nil {
		t.Fatal(err)
	}
	if len(endpoints) != 1 || endpoints[0].InterfaceID != "Wireguard0" || endpoints[0].Endpoint != "198.51.100.24:51820" {
		t.Fatalf("endpoints = %+v", endpoints)
	}
}

func TestParseWireGuardEndpointRejectsPrivateDefaultGateway(t *testing.T) {
	value := []byte(`<response><interface><id>ISP</id><address>192.168.1.2</address><global>yes</global><defaultgw>yes</defaultgw></interface><interface><id>Wireguard0</id><wireguard><listen-port>51820</listen-port></wireguard></interface></response>`)
	endpoints, err := ParseWireGuardEndpoints(value)
	if err != nil || len(endpoints) != 0 {
		t.Fatalf("endpoints = %+v, error = %v", endpoints, err)
	}
}

func TestParsersRejectMalformedDuplicateAndUnboundedInput(t *testing.T) {
	validHome := `<response><host><mac>02:00:00:00:00:01</mac><ip>192.168.1.2</ip><active>yes</active></host></response>`
	tests := []struct {
		name    string
		hotspot string
		want    error
	}{
		{name: "wrong root", hotspot: `<hosts></hosts>`, want: ErrUnsupportedSchema},
		{name: "trailing root", hotspot: validHome + `<response/>`, want: ErrUnsupportedSchema},
		{name: "duplicate mac", hotspot: `<response><host><mac>02:00:00:00:00:01</mac></host><host><mac>02:00:00:00:00:01</mac></host></response>`, want: ErrDuplicateIdentity},
		{name: "missing mac", hotspot: `<response><host><ip>192.168.1.2</ip></host></response>`, want: ErrUnsupportedSchema},
		{name: "too many", hotspot: `<response>` + strings.Repeat(`<host><mac>02:00:00:00:00:01</mac></host>`, maxItems+1) + `</response>`, want: ErrTooManyItems},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := ParseHomeDevices([]byte(tc.hotspot), []byte(`<response/>`), []byte(`<response/>`))
			if !errors.Is(err, tc.want) {
				t.Fatalf("error = %v, want %v", err, tc.want)
			}
		})
	}
}

func TestWireGuardParserRejectsUnsafeAndDuplicateIdentity(t *testing.T) {
	running := []byte(`<response><message>interface Wireguard0</message><message>wireguard peer AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= !one</message><message>!</message><message>wireguard peer AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= !two</message><message>!</message></response>`)
	_, err := ParseWireGuardInterface([]byte(`<response><interface><id>Wireguard0</id><wireguard/></interface></response>`), running, "Wireguard0")
	if !errors.Is(err, ErrDuplicateIdentity) {
		t.Fatalf("duplicate error = %v", err)
	}
	_, err = ParseWireGuardInterface([]byte(`<response/>`), running, "Wireguard0; reboot")
	if !errors.Is(err, ErrInvalidCommand) {
		t.Fatalf("unsafe interface error = %v", err)
	}
}

func TestWireGuardParserRejectsEpochLikeHandshakeValue(t *testing.T) {
	runtime := []byte(`<response><interface><id>Wireguard0</id><wireguard><peer><public-key>AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=</public-key><last-handshake>1799999999</last-handshake></peer></wireguard></interface></response>`)
	running := []byte(`<response><message>interface Wireguard0</message><message>wireguard peer AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE= !phone</message><message>!</message></response>`)

	_, err := ParseWireGuardInterface(runtime, running, "Wireguard0")

	if !errors.Is(err, ErrUnsupportedSchema) {
		t.Fatalf("error = %v, want unsupported schema", err)
	}
}

func TestWireGuardParserAcceptsKeenOSInvalidHandshakeSentinel(t *testing.T) {
	runtime := []byte(`<response><interface><id>Wireguard0</id><wireguard><peer><public-key>AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=</public-key><online>no</online><enabled>yes</enabled><last-handshake>2147483647</last-handshake></peer></wireguard></interface></response>`)
	running := []byte(`<response><message>interface Wireguard0</message><message>wireguard peer AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE= !phone</message><message>!</message></response>`)

	value, err := ParseWireGuardInterface(runtime, running, "Wireguard0")
	if err != nil {
		t.Fatal(err)
	}
	if len(value.Peers) != 1 || value.Peers[0].LastHandshakeSec == nil || *value.Peers[0].LastHandshakeSec != 2_147_483_647 {
		t.Fatalf("sentinel handshake was not preserved: %+v", value.Peers)
	}
}

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	value, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return value
}
