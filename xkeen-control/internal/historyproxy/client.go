package historyproxy

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	maximumConfigBytes          = 64 << 10
	maximumHistoryResponseBytes = 1 << 20
)

type Client struct {
	configPath string
	http       *http.Client
}

func New(configPath string) *Client {
	transport := &http.Transport{
		Proxy:                 nil,
		DialContext:           (&net.Dialer{Timeout: 3 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		ForceAttemptHTTP2:     false,
		MaxIdleConns:          2,
		MaxIdleConnsPerHost:   2,
		ResponseHeaderTimeout: 3 * time.Second,
		IdleConnTimeout:       30 * time.Second,
	}
	return &Client{
		configPath: configPath,
		http: &http.Client{
			Transport: transport,
			Timeout:   5 * time.Second,
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		},
	}
}

func (c *Client) History(ctx context.Context, query Query) (History, error) {
	if err := ValidateQuery(query); err != nil {
		return History{}, err
	}
	config, err := readConfig(c.configPath)
	if err != nil {
		return History{}, ErrUnavailable
	}
	target := url.URL{
		Scheme: "http",
		Host:   config.address,
		Path:   "/v1/peers/" + query.PeerID + "/history",
	}
	values := url.Values{}
	values.Set("from", strconv.FormatInt(query.From, 10))
	values.Set("to", strconv.FormatInt(query.To, 10))
	values.Set("resolution", query.Resolution)
	values.Set("limit", strconv.Itoa(query.Limit))
	target.RawQuery = values.Encode()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
	if err != nil {
		return History{}, ErrUnavailable
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Authorization", "Bearer "+config.token)
	request.Header.Set("Cache-Control", "no-store")
	response, err := c.http.Do(request)
	if err != nil {
		return History{}, ErrUnavailable
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return History{}, ErrUnavailable
	}
	mediaType, _, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		return History{}, ErrUnavailable
	}
	limited := io.LimitReader(response.Body, maximumHistoryResponseBytes+1)
	body, err := io.ReadAll(limited)
	if err != nil || len(body) > maximumHistoryResponseBytes {
		return History{}, ErrUnavailable
	}
	decoder := json.NewDecoder(strings.NewReader(string(body)))
	decoder.DisallowUnknownFields()
	var history History
	if err := decoder.Decode(&history); err != nil {
		return History{}, ErrUnavailable
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return History{}, ErrUnavailable
	}
	if history.Points == nil {
		history.Points = []Point{}
	}
	if err := ValidateHistory(query, history); err != nil {
		return History{}, ErrUnavailable
	}
	return history, nil
}

type localConfig struct {
	address string
	token   string
}

func readConfig(path string) (localConfig, error) {
	file, err := os.Open(path)
	if err != nil {
		return localConfig{}, err
	}
	defer file.Close()
	body, err := io.ReadAll(io.LimitReader(file, maximumConfigBytes+1))
	if err != nil || len(body) > maximumConfigBytes {
		return localConfig{}, ErrUnavailable
	}
	var raw struct {
		ListenAddress string `json:"listen_address"`
		Token         string `json:"token"`
	}
	decoder := json.NewDecoder(strings.NewReader(string(body)))
	if err := decoder.Decode(&raw); err != nil {
		return localConfig{}, err
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return localConfig{}, ErrUnavailable
	}
	host, portText, err := net.SplitHostPort(raw.ListenAddress)
	if err != nil {
		return localConfig{}, ErrUnavailable
	}
	address, err := netip.ParseAddr(host)
	port, portErr := strconv.Atoi(portText)
	if err != nil || !address.Is4() || (!address.IsPrivate() && !address.IsLoopback()) || portErr != nil || port < 1 || port > 65535 {
		return localConfig{}, ErrUnavailable
	}
	decodedToken, err := base64.StdEncoding.Strict().DecodeString(raw.Token)
	if err != nil || len(decodedToken) < 32 || len(raw.Token) > 512 || raw.Token == "" || strings.IndexFunc(raw.Token, func(r rune) bool { return r < 0x20 || r == 0x7f }) >= 0 {
		return localConfig{}, ErrUnavailable
	}
	return localConfig{address: net.JoinHostPort(address.String(), strconv.Itoa(port)), token: raw.Token}, nil
}
