package app

import (
	"bytes"
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/model"
	"github.com/goldb/keenwg/xkeen-control/internal/state"
	"github.com/goldb/keenwg/xkeen-control/internal/support"
)

func TestSupportReporterSelectsOnlyActiveTargetWithoutExportingIdentity(t *testing.T) {
	directory := t.TempDir()
	store := state.New(state.Paths{Subscription: filepath.Join(directory, "subscription.json"), State: filepath.Join(directory, "state.json")}, bytes.NewReader(bytes.Repeat([]byte{0x42}, 64)))
	node := model.Node{
		CanonicalURI: "vless://private-user@edge.secret.example:443", DisplayName: "Private edge",
		Host: "edge.secret.example", Port: 443, UUID: "550e8400-e29b-41d4-a716-446655440000",
		PublicKey: "private-peer-key", ShortID: "0123456789abcdef", SNI: "sni.secret.example",
		Fingerprint: "firefox", Transport: "quic", Security: "reality", Flow: "xtls-rprx-vision",
	}
	subscription, err := store.SaveSubscription([]model.Node{node}, time.Unix(1_786_147_210, 0))
	if err != nil {
		t.Fatal(err)
	}
	active := model.ActiveNode{PublicNode: model.SanitizeNode(subscription.Nodes[0], true), ResolvedIP: "203.0.113.42", ConfirmedAt: 1_786_147_211}
	if err := store.SaveControllerState(model.ControllerState{StateVersion: 9, Active: &active, Operations: []model.Operation{}}); err != nil {
		t.Fatal(err)
	}
	builder := &capturingSupportBuilder{}
	reporter := newSupportReporter(store, "0.9.0", builder)

	if _, err := reporter.SupportReport(context.Background()); err != nil {
		t.Fatal(err)
	}

	if builder.input.Target == nil || builder.input.Target.Host != "edge.secret.example" || builder.input.Target.Transport != "quic" {
		t.Fatalf("target=%+v", builder.input.Target)
	}
	if builder.input.StateVersion != 9 || !builder.input.Active || builder.input.NodeCount != 1 {
		t.Fatalf("input=%+v", builder.input)
	}
	for _, note := range builder.input.Notes {
		if bytes.Contains([]byte(note), []byte("edge.secret.example")) || bytes.Contains([]byte(note), []byte(active.ID)) || bytes.Contains([]byte(note), []byte(active.ResolvedIP)) {
			t.Fatalf("identity leaked into notes: %q", note)
		}
	}
}

func TestSupportReporterWorksWithoutSubscriptionOrActiveRoute(t *testing.T) {
	directory := t.TempDir()
	store := state.New(state.Paths{Subscription: filepath.Join(directory, "subscription.json"), State: filepath.Join(directory, "state.json")}, bytes.NewReader(nil))
	builder := &capturingSupportBuilder{}
	reporter := newSupportReporter(store, "0.9.0", builder)

	if _, err := reporter.SupportReport(context.Background()); err != nil {
		t.Fatal(err)
	}
	if builder.input.Target != nil || builder.input.Active || builder.input.NodeCount != 0 {
		t.Fatalf("input=%+v", builder.input)
	}
}

type capturingSupportBuilder struct{ input support.Input }

func (b *capturingSupportBuilder) Build(_ context.Context, input support.Input) support.Bundle {
	b.input = input
	return support.Bundle{SchemaVersion: 1}
}
