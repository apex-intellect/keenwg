package api

import (
	"context"
	"net/http"

	"github.com/apex-intellect/keenwg/xkeen-control/internal/auth"
)

type Principal struct {
	DeviceID string
	Scope    auth.Scope
}

type principalContextKey struct{}

func withPrincipal(request *http.Request, principal Principal) *http.Request {
	return request.WithContext(context.WithValue(request.Context(), principalContextKey{}, principal))
}

func principalFrom(request *http.Request) (Principal, bool) {
	principal, ok := request.Context().Value(principalContextKey{}).(Principal)
	return principal, ok
}

func scopeAllows(actual, required auth.Scope) bool {
	return scopeRank(actual) >= scopeRank(required)
}

func scopeRank(scope auth.Scope) int {
	switch scope {
	case auth.ScopeOwner:
		return 3
	case auth.ScopeOperator:
		return 2
	case auth.ScopeViewer:
		return 1
	default:
		return 0
	}
}
