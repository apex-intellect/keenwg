package model

type OperationResult string

const (
	ResultSuccess          OperationResult = "success"
	ResultFailedRolledBack OperationResult = "failed_rolled_back"
	ResultFailedNoChange   OperationResult = "failed_no_change"
	ResultUncertain        OperationResult = "uncertain"
)

const (
	OperationQueued   = "queued"
	OperationRunning  = "running"
	OperationTerminal = "terminal"
)

type Node struct {
	ID           string   `json:"id"`
	CanonicalURI string   `json:"canonical_uri"`
	DisplayName  string   `json:"display_name"`
	Country      string   `json:"country,omitempty"`
	Flag         string   `json:"flag,omitempty"`
	Host         string   `json:"host"`
	Port         int      `json:"port"`
	UUID         string   `json:"uuid"`
	PublicKey    string   `json:"public_key"`
	ShortID      string   `json:"short_id"`
	SNI          string   `json:"sni"`
	SpiderX      string   `json:"spider_x"`
	Fingerprint  string   `json:"fingerprint"`
	Transport    string   `json:"transport"`
	Security     string   `json:"security"`
	Flow         string   `json:"flow"`
	Warnings     []string `json:"warnings,omitempty"`
}

type PublicNode struct {
	ID          string   `json:"id"`
	DisplayName string   `json:"display_name"`
	Country     string   `json:"country,omitempty"`
	Flag        string   `json:"flag,omitempty"`
	Host        string   `json:"host"`
	Port        int      `json:"port"`
	Fingerprint string   `json:"fingerprint"`
	Transport   string   `json:"transport"`
	Security    string   `json:"security"`
	Flow        string   `json:"flow"`
	Active      bool     `json:"active"`
	Warnings    []string `json:"warnings"`
}

type SubscriptionState struct {
	RefreshedAt int64  `json:"refreshed_at"`
	Nodes       []Node `json:"nodes"`
}

type SubscriptionView struct {
	RefreshedAt *int64       `json:"refreshed_at"`
	Stale       bool         `json:"stale"`
	Nodes       []PublicNode `json:"nodes"`
}

type ActiveNode struct {
	PublicNode
	ResolvedIP              string `json:"resolved_ip"`
	ConfirmedAt             int64  `json:"confirmed_at"`
	MissingFromSubscription bool   `json:"missing_from_subscription"`
}

type Operation struct {
	IdempotencyKey string          `json:"idempotency_key"`
	Kind           string          `json:"kind"`
	State          string          `json:"state"`
	Result         OperationResult `json:"result,omitempty"`
	ErrorCode      string          `json:"error_code,omitempty"`
	StartedAt      int64           `json:"started_at"`
	FinishedAt     *int64          `json:"finished_at,omitempty"`
}

type ControllerStatus struct {
	Version      string           `json:"version"`
	StateVersion uint64           `json:"state_version"`
	Active       *ActiveNode      `json:"active"`
	Subscription SubscriptionView `json:"subscription"`
	Operation    *Operation       `json:"operation,omitempty"`
}

type TransactionSnapshot struct {
	OperationKey         string      `json:"operation_key"`
	Kind                 string      `json:"kind"`
	Phase                string      `json:"phase"`
	OriginalOutbounds    []byte      `json:"original_outbounds,omitempty"`
	OriginalExcludes     []byte      `json:"original_excludes,omitempty"`
	OriginalActive       *ActiveNode `json:"original_active,omitempty"`
	OriginalStateVersion uint64      `json:"original_state_version,omitempty"`
	OriginalIP           string      `json:"original_ip,omitempty"`
	CandidateIP          string      `json:"candidate_ip,omitempty"`
}

type ControllerState struct {
	StateVersion uint64               `json:"state_version"`
	Active       *ActiveNode          `json:"active"`
	Operations   []Operation          `json:"operations"`
	InProgress   *TransactionSnapshot `json:"in_progress,omitempty"`
}

func SanitizeNode(node Node, active bool) PublicNode {
	warnings := append([]string{}, node.Warnings...)
	return PublicNode{
		ID:          node.ID,
		DisplayName: node.DisplayName,
		Country:     node.Country,
		Flag:        node.Flag,
		Host:        node.Host,
		Port:        node.Port,
		Fingerprint: node.Fingerprint,
		Transport:   node.Transport,
		Security:    node.Security,
		Flow:        node.Flow,
		Active:      active,
		Warnings:    warnings,
	}
}
