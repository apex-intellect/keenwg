package subscription

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestFetcherBoundsBodyAndBlocksHTTPSDowngrade(t *testing.T) {
	t.Run("downgrade", func(t *testing.T) {
		server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			http.Redirect(w, r, "http://127.0.0.1/private-secret-path", http.StatusFound)
		}))
		defer server.Close()
		fetcher := &Fetcher{Client: server.Client()}
		_, err := fetcher.Fetch(context.Background(), server.URL+"/subscription-secret", 1024)
		if !errors.Is(err, ErrRedirectPolicy) || strings.Contains(err.Error(), "secret") {
			t.Fatalf("err=%v", err)
		}
	})

	t.Run("content length", func(t *testing.T) {
		server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Length", "64")
			_, _ = w.Write([]byte(strings.Repeat("x", 64)))
		}))
		defer server.Close()
		_, err := (&Fetcher{Client: server.Client()}).Fetch(context.Background(), server.URL+"/subscription-secret", 32)
		if !errors.Is(err, ErrResponseTooLarge) || strings.Contains(err.Error(), "secret") {
			t.Fatalf("err=%v", err)
		}
	})

	t.Run("streamed body", func(t *testing.T) {
		server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.Header().Del("Content-Length")
			_, _ = fmt.Fprint(w, strings.Repeat("x", 64))
		}))
		defer server.Close()
		_, err := (&Fetcher{Client: server.Client()}).Fetch(context.Background(), server.URL, 32)
		if !errors.Is(err, ErrResponseTooLarge) {
			t.Fatalf("err=%v", err)
		}
	})
}

func TestFetcherUsesExactHeadersAndReturnsSuccessfulBody(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Accept") != "text/plain" || r.Header.Get("User-Agent") != "keenwg-xkeen-control/0.4" {
			t.Errorf("headers=%v", r.Header)
		}
		_, _ = w.Write([]byte("subscription-body"))
	}))
	defer server.Close()

	body, err := (&Fetcher{Client: server.Client()}).Fetch(context.Background(), server.URL, 64)
	if err != nil || string(body) != "subscription-body" {
		t.Fatalf("body=%q err=%v", body, err)
	}
}

func TestFetcherRejectsNonHTTPSBeforeNetworking(t *testing.T) {
	called := false
	client := &http.Client{Transport: roundTripperFunc(func(*http.Request) (*http.Response, error) {
		called = true
		return nil, errors.New("unexpected")
	})}
	_, err := (&Fetcher{Client: client}).Fetch(context.Background(), "http://vpn.example.test/private-secret", 64)
	if !errors.Is(err, ErrDownload) || called || strings.Contains(err.Error(), "secret") {
		t.Fatalf("called=%v err=%v", called, err)
	}
}

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
