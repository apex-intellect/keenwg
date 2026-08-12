package routerlocal

type HomeDevice struct {
	ID                string `json:"id"`
	MAC               string `json:"mac"`
	Name              string `json:"name"`
	Hostname          string `json:"hostname,omitempty"`
	IP                string `json:"ip,omitempty"`
	ReservedIP        string `json:"reserved_ip,omitempty"`
	Online            bool   `json:"online"`
	StaticReservation bool   `json:"static_reservation"`
	InterfaceName     string `json:"interface_name,omitempty"`
	RSSI              *int   `json:"rssi,omitempty"`
}

type WireGuardInterface struct {
	ID         string          `json:"id"`
	PublicKey  string          `json:"public_key,omitempty"`
	Addresses  []string        `json:"addresses"`
	ListenPort int             `json:"listen_port,omitempty"`
	MTU        int             `json:"mtu,omitempty"`
	Peers      []WireGuardPeer `json:"peers"`
}

type WireGuardPeer struct {
	PublicKey        string `json:"public_key"`
	Name             string `json:"name"`
	AllowedIP        string `json:"allowed_ip,omitempty"`
	Keepalive        int    `json:"keepalive"`
	Enabled          bool   `json:"enabled"`
	Online           bool   `json:"online"`
	LastHandshakeSec *int64 `json:"last_handshake_sec,omitempty"`
	RXBytes          uint64 `json:"rx_bytes"`
	TXBytes          uint64 `json:"tx_bytes"`
}
