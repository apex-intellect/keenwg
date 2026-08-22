package subscription

import (
	"context"
	"encoding/base64"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

var (
	ErrDownload         = errors.New("subscription_download_failed")
	ErrRedirectPolicy   = errors.New("redirect_policy")
	ErrResponseTooLarge = errors.New("response_too_large")
)

type Fetcher struct {
	Client *http.Client
}

type Metadata struct {
	ProfileTitle  string
	UploadBytes   *int64
	DownloadBytes *int64
	TotalBytes    *int64
	ExpiresAt     *int64
}

type Download struct {
	Body     []byte
	Metadata Metadata
}

func (f *Fetcher) Fetch(ctx context.Context, rawURL string, maxBytes int64) ([]byte, error) {
	download, err := f.FetchWithMetadata(ctx, rawURL, maxBytes)
	return download.Body, err
}

func (f *Fetcher) FetchWithMetadata(ctx context.Context, rawURL string, maxBytes int64) (Download, error) {
	u, err := url.Parse(rawURL)
	if err != nil || u.Scheme != "https" || u.Host == "" || maxBytes < 1 {
		return Download{}, ErrDownload
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return Download{}, ErrDownload
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
			return Download{}, ErrRedirectPolicy
		}
		return Download{}, ErrDownload
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return Download{}, ErrDownload
	}
	if resp.ContentLength > maxBytes {
		return Download{}, ErrResponseTooLarge
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBytes+1))
	if err != nil {
		return Download{}, ErrDownload
	}
	if int64(len(body)) > maxBytes {
		return Download{}, ErrResponseTooLarge
	}
	bodyMetadata := metadataFromBody(body)
	headerMetadata := metadataFromValues(resp.Header.Get("Profile-Title"), resp.Header.Get("Subscription-Userinfo"))
	return Download{Body: body, Metadata: mergeMetadata(headerMetadata, bodyMetadata)}, nil
}

func metadataFromBody(body []byte) Metadata {
	decoded, err := decodeRawOrBase64(body)
	if err != nil {
		return Metadata{}
	}
	var title, userInfo string
	for _, line := range nonEmptyLines(decoded) {
		lower := strings.ToLower(line)
		switch {
		case strings.HasPrefix(lower, "#profile-title:"):
			title = strings.TrimSpace(line[len("#profile-title:"):])
		case strings.HasPrefix(lower, "#subscription-userinfo:"):
			userInfo = strings.TrimSpace(line[len("#subscription-userinfo:"):])
		}
	}
	return metadataFromValues(title, userInfo)
}

func metadataFromValues(rawTitle, rawUserInfo string) Metadata {
	metadata := Metadata{ProfileTitle: sanitizeProfileTitle(rawTitle)}
	for _, field := range strings.Split(rawUserInfo, ";") {
		parts := strings.SplitN(field, "=", 2)
		if len(parts) != 2 {
			continue
		}
		value, err := strconv.ParseInt(strings.TrimSpace(parts[1]), 10, 64)
		if err != nil || value < 0 {
			continue
		}
		switch strings.ToLower(strings.TrimSpace(parts[0])) {
		case "upload":
			metadata.UploadBytes = int64Pointer(value)
		case "download":
			metadata.DownloadBytes = int64Pointer(value)
		case "total":
			metadata.TotalBytes = int64Pointer(value)
		case "expire":
			if value > 0 && value <= 253402300799 {
				metadata.ExpiresAt = int64Pointer(value)
			}
		}
	}
	return metadata
}

func sanitizeProfileTitle(raw string) string {
	value := strings.TrimSpace(raw)
	if strings.HasPrefix(strings.ToLower(value), "base64:") {
		encoded := strings.TrimSpace(value[len("base64:"):])
		for _, encoding := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
			decoded, err := encoding.DecodeString(encoded)
			if err == nil && utf8.Valid(decoded) {
				value = strings.TrimSpace(string(decoded))
				break
			}
		}
	}
	if value == "" || !utf8.ValidString(value) || strings.ContainsAny(value, "\r\n\x00") {
		return ""
	}
	for _, character := range value {
		if unicode.IsControl(character) {
			return ""
		}
	}
	if strings.HasPrefix(strings.ToLower(value), "http://") || strings.HasPrefix(strings.ToLower(value), "https://") {
		return ""
	}
	runes := []rune(value)
	if len(runes) > 25 {
		value = string(runes[:25])
	}
	return value
}

func mergeMetadata(preferred, fallback Metadata) Metadata {
	if preferred.ProfileTitle == "" {
		preferred.ProfileTitle = fallback.ProfileTitle
	}
	if preferred.UploadBytes == nil {
		preferred.UploadBytes = fallback.UploadBytes
	}
	if preferred.DownloadBytes == nil {
		preferred.DownloadBytes = fallback.DownloadBytes
	}
	if preferred.TotalBytes == nil {
		preferred.TotalBytes = fallback.TotalBytes
	}
	if preferred.ExpiresAt == nil {
		preferred.ExpiresAt = fallback.ExpiresAt
	}
	return preferred
}

func int64Pointer(value int64) *int64 { return &value }
