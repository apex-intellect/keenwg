package catalog

import (
	"crypto/sha256"
	"encoding/base64"
	"strconv"
	"strings"
)

func NodeIdentity(node Node) string {
	host, err := normalizeHost(node.Host)
	if err != nil {
		return ""
	}
	serverName := ""
	if strings.TrimSpace(node.ServerName) != "" {
		serverName, _ = normalizeHost(node.ServerName)
	}
	canonical := strings.Join([]string{
		strings.ToLower(string(node.Protocol)),
		host,
		strconv.Itoa(node.Port),
		strings.ToLower(strings.TrimSpace(node.Transport)),
		strings.ToLower(strings.TrimSpace(node.Security)),
		serverName,
		strings.ToLower(strings.TrimSpace(node.ALPN)),
		strings.ToLower(strings.TrimSpace(node.Flow)),
		strings.TrimSpace(node.VariantFingerprint),
	}, "\n")
	digest := sha256.Sum256([]byte(canonical))
	return base64.RawURLEncoding.EncodeToString(digest[:18])
}
