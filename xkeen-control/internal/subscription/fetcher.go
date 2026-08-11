package subscription

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
)

var (
	ErrDownload         = errors.New("subscription_download_failed")
	ErrRedirectPolicy   = errors.New("redirect_policy")
	ErrResponseTooLarge = errors.New("response_too_large")
)

type Fetcher struct {
	Client *http.Client
}

func (f *Fetcher) Fetch(ctx context.Context, rawURL string, maxBytes int64) ([]byte, error) {
	u, err := url.Parse(rawURL)
	if err != nil || u.Scheme != "https" || u.Host == "" || maxBytes < 1 {
		return nil, ErrDownload
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, ErrDownload
	}
	req.Header.Set("Accept", "text/plain")
	req.Header.Set("User-Agent", "keenwg-companion/2.0")

	client := http.DefaultClient
	if f != nil && f.Client != nil {
		client = f.Client
	}
	secureClient := *client
	secureClient.CheckRedirect = func(next *http.Request, via []*http.Request) error {
		if next.URL.Scheme != "https" || len(via) > 3 {
			return ErrRedirectPolicy
		}
		return nil
	}
	resp, err := secureClient.Do(req)
	if err != nil {
		if errors.Is(err, ErrRedirectPolicy) {
			return nil, ErrRedirectPolicy
		}
		return nil, ErrDownload
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, ErrDownload
	}
	if resp.ContentLength > maxBytes {
		return nil, ErrResponseTooLarge
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBytes+1))
	if err != nil {
		return nil, ErrDownload
	}
	if int64(len(body)) > maxBytes {
		return nil, ErrResponseTooLarge
	}
	return body, nil
}
