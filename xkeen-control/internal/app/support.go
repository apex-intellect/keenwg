package app

import (
	"context"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/model"
	"github.com/goldb/keenwg/xkeen-control/internal/support"
)

type supportStateStore interface {
	LoadSubscription() (model.SubscriptionState, error)
	LoadControllerState() (model.ControllerState, error)
}

type supportBuilder interface {
	Build(context.Context, support.Input) support.Bundle
}

type supportReporter struct {
	store   supportStateStore
	version string
	builder supportBuilder
}

func newSupportReporter(store supportStateStore, version string, builder supportBuilder) *supportReporter {
	return &supportReporter{store: store, version: version, builder: builder}
}

func (r *supportReporter) SupportReport(ctx context.Context) (support.Bundle, error) {
	if err := ctx.Err(); err != nil {
		return support.Bundle{}, err
	}
	subscription, err := r.store.LoadSubscription()
	if err != nil {
		return support.Bundle{}, err
	}
	controller, err := r.store.LoadControllerState()
	if err != nil {
		return support.Bundle{}, err
	}
	input := support.Input{
		Version: r.version, StateVersion: controller.StateVersion, Active: controller.Active != nil,
		NodeCount: len(subscription.Nodes), Notes: supportTimestamps(subscription, controller),
	}
	if controller.Active != nil {
		for _, node := range subscription.Nodes {
			if node.ID == controller.Active.ID {
				input.Target = &support.Target{Host: node.Host, Port: node.Port, Transport: node.Transport}
				break
			}
		}
	}
	return r.builder.Build(ctx, input), nil
}

func supportTimestamps(subscription model.SubscriptionState, controller model.ControllerState) []string {
	notes := []string{}
	if subscription.RefreshedAt > 0 {
		notes = append(notes, "subscription_observed_at="+time.Unix(subscription.RefreshedAt, 0).UTC().Format(time.RFC3339))
	}
	if controller.Active != nil && controller.Active.ConfirmedAt > 0 {
		notes = append(notes, "active_route_observed_at="+time.Unix(controller.Active.ConfirmedAt, 0).UTC().Format(time.RFC3339))
	}
	if controller.Active != nil && controller.Active.MissingFromSubscription {
		notes = append(notes, "active_route_missing_from_subscription")
	}
	return notes
}
