package ownedsource

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/adapter"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/catalog"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/diagnostics"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/model"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/subscription"
	"github.com/apex-intellect/keenwg/xkeen-control/internal/transaction"
)

const (
	maxOwnedSourceBytes = 1 << 20
	maxOwnedNodes       = 512
)

var (
	ErrSourceUnavailable = errors.New("owned_source_unavailable")
	ErrNodeNotFound      = errors.New("owned_node_not_found")
)

type Fetcher interface {
	Fetch(context.Context, string, int64) ([]byte, error)
}

type DiagnosticChecker interface {
	Check(context.Context, []model.Node) diagnostics.Report
}

type Controller interface {
	LoadControllerState() (model.ControllerState, error)
	FindOperation(string) (model.Operation, bool, error)
}

type Engine interface {
	PrepareSelectNode(string, model.Node, uint64) (model.Operation, transaction.Job, error)
}

type Prepared struct {
	Nodes   []catalog.Node
	Payload []byte
}

func (p *Prepared) Clear() {
	for index := range p.Payload {
		p.Payload[index] = 0
	}
}

type Processor struct {
	fetcher     Fetcher
	diagnostics DiagnosticChecker
	controller  Controller
	engine      Engine
	keyFactory  func() string
}

func NewProcessor(fetcher Fetcher, checker DiagnosticChecker, controller Controller, engine Engine, keyFactory func() string) *Processor {
	if keyFactory == nil {
		keyFactory = randomKey
	}
	return &Processor{fetcher: fetcher, diagnostics: checker, controller: controller, engine: engine, keyFactory: keyFactory}
}

func (p *Processor) Prepare(ctx context.Context, sourceID string, kind catalog.SourceKind, raw []byte) (Prepared, error) {
	defer clearBytes(raw)
	if sourceID == "" || len(raw) == 0 || len(raw) > maxOwnedSourceBytes {
		return Prepared{}, ErrSourceUnavailable
	}
	var payload []byte
	var err error
	switch kind {
	case catalog.SourceSubscription:
		if p.fetcher == nil {
			return Prepared{}, ErrSourceUnavailable
		}
		payload, err = p.fetcher.Fetch(ctx, string(raw), maxOwnedSourceBytes)
	case catalog.SourceShareLink:
		payload = append([]byte(nil), raw...)
	default:
		return Prepared{}, ErrSourceUnavailable
	}
	if err != nil || len(payload) == 0 || len(payload) > maxOwnedSourceBytes {
		clearBytes(payload)
		return Prepared{}, ErrSourceUnavailable
	}
	native, err := parseNodes(sourceID, payload)
	if err != nil {
		clearBytes(payload)
		return Prepared{}, err
	}
	nodes := make([]catalog.Node, len(native))
	for index := range native {
		nodes[index] = projectNode(sourceID, native[index])
	}
	return Prepared{Nodes: nodes, Payload: payload}, nil
}

func (p *Processor) Test(ctx context.Context, sourceID, nodeID string, payload []byte) adapter.TestResult {
	node, err := findNode(sourceID, nodeID, payload)
	if err != nil || p.diagnostics == nil {
		return adapter.TestResult{NodeID: nodeID, ErrorCode: "node_not_found"}
	}
	report := p.diagnostics.Check(ctx, []model.Node{node})
	if len(report.Results) != 1 || report.Results[0].NodeID != node.ID {
		return adapter.TestResult{NodeID: nodeID, ErrorCode: "diagnostics_unavailable"}
	}
	checked := time.Unix(report.CheckedAt, 0).UTC()
	answer := adapter.TestResult{NodeID: nodeID, Reachable: report.Results[0].Status == diagnostics.StatusReachable, LatencyMS: report.Results[0].ConnectMS, ObservedAt: checked}
	if !answer.Reachable {
		answer.ErrorCode = report.Results[0].Status
	}
	return answer
}

func (p *Processor) Activate(ctx context.Context, sourceID, nodeID string, payload []byte) adapter.OperationResult {
	node, err := findNode(sourceID, nodeID, payload)
	if err != nil {
		return adapter.OperationResult{Result: adapter.ResultRejected, ErrorCode: "node_not_found"}
	}
	controller, err := p.controller.LoadControllerState()
	if err != nil || controller.StateVersion == 0 {
		return adapter.OperationResult{Result: adapter.ResultUncertain, ErrorCode: "xkeen_unavailable"}
	}
	key := p.keyFactory()
	operation, job, err := p.engine.PrepareSelectNode(key, node, controller.StateVersion)
	if err != nil {
		return preparationFailure(err)
	}
	if job != nil {
		job(ctx)
		var found bool
		operation, found, err = p.controller.FindOperation(key)
		if err != nil || !found {
			return adapter.OperationResult{Result: adapter.ResultUncertain, ErrorCode: "operation_unavailable"}
		}
	}
	return operationResult(nodeID, operation)
}

func (p *Processor) Readback(ctx context.Context, nodeID string) (bool, uint64, error) {
	if err := ctx.Err(); err != nil {
		return false, 0, err
	}
	controller, err := p.controller.LoadControllerState()
	if err != nil {
		return false, 0, ErrSourceUnavailable
	}
	return controller.Active != nil && controller.Active.ID == nodeID, controller.StateVersion, nil
}

func parseNodes(sourceID string, payload []byte) ([]model.Node, error) {
	parsed, err := subscription.Parse(payload, maxOwnedNodes)
	if err != nil {
		return nil, ErrSourceUnavailable
	}
	for index := range parsed.Nodes {
		parsed.Nodes[index].ID = ownedNodeID(sourceID, parsed.Nodes[index].CanonicalURI)
	}
	return parsed.Nodes, nil
}

func findNode(sourceID, nodeID string, payload []byte) (model.Node, error) {
	nodes, err := parseNodes(sourceID, payload)
	if err != nil {
		return model.Node{}, err
	}
	for _, node := range nodes {
		if node.ID == nodeID {
			return node, nil
		}
	}
	return model.Node{}, ErrNodeNotFound
}

func projectNode(sourceID string, node model.Node) catalog.Node {
	return catalog.Node{
		ID: node.ID, SourceID: sourceID, GroupID: "primary", DisplayName: node.DisplayName,
		Country: node.Country, Protocol: catalog.ProtocolVLESS, Host: node.Host, Port: node.Port,
		Transport: node.Transport, Security: node.Security, Flow: node.Flow,
		Active: false, Testable: true, Activatable: true, Warnings: append([]string(nil), node.Warnings...),
	}
}

func ownedNodeID(sourceID, canonical string) string {
	digest := sha256.Sum256([]byte(sourceID + "\n" + canonical))
	return "owned-" + hex.EncodeToString(digest[:16])
}

func preparationFailure(err error) adapter.OperationResult {
	switch {
	case errors.Is(err, transaction.ErrStaleState):
		return adapter.OperationResult{Result: adapter.ResultRejected, ErrorCode: "stale_state"}
	case errors.Is(err, transaction.ErrBusy):
		return adapter.OperationResult{Result: adapter.ResultRejected, ErrorCode: "busy"}
	case errors.Is(err, transaction.ErrNodeNotFound):
		return adapter.OperationResult{Result: adapter.ResultRejected, ErrorCode: "node_not_found"}
	default:
		return adapter.OperationResult{Result: adapter.ResultUncertain, ErrorCode: "xkeen_unavailable"}
	}
}

func operationResult(nodeID string, operation model.Operation) adapter.OperationResult {
	switch operation.Result {
	case model.ResultSuccess:
		return adapter.OperationResult{Result: adapter.ResultCommitted, NodeID: nodeID}
	case model.ResultFailedRolledBack:
		return adapter.OperationResult{Result: adapter.ResultRolledBack, ErrorCode: operation.ErrorCode}
	case model.ResultFailedNoChange:
		return adapter.OperationResult{Result: adapter.ResultRejected, ErrorCode: operation.ErrorCode}
	default:
		return adapter.OperationResult{Result: adapter.ResultUncertain, ErrorCode: operation.ErrorCode}
	}
}

func randomKey() string {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		return ""
	}
	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(value)
	return fmt.Sprintf("%s-%s-%s-%s-%s", encoded[:8], encoded[8:12], encoded[12:16], encoded[16:20], encoded[20:])
}

func clearBytes(value []byte) {
	for index := range value {
		value[index] = 0
	}
}
