package capability

type Access string

const (
	AccessNone  Access = "none"
	AccessRead  Access = "read"
	AccessWrite Access = "write"
)

const (
	OverviewHealth     = "overview.health"
	ConnectionsCatalog = "connections.catalog"
	ConnectionsAWG     = "connections.awg"
	ConnectionsSingBox = "connections.singbox"
	ConnectionsXKeen   = "connections.xkeen"
	RoutesDomains      = "routes.domains"
	RoutesExclusions   = "routes.exclusions"
	AccessWireGuard    = "access.wireguard"
	HistoryWireGuard   = "history.wireguard"
	SystemDevices      = "system.devices"
)

type Capability struct {
	ID            string `json:"id"`
	SchemaVersion int    `json:"schema_version"`
	Access        Access `json:"access"`
	Available     bool   `json:"available"`
	Transport     string `json:"transport"`
	Reason        string `json:"reason,omitempty"`
}

type Document struct {
	SchemaVersion int          `json:"schema_version"`
	StateVersion  uint64       `json:"state_version"`
	Capabilities  []Capability `json:"capabilities"`
}
