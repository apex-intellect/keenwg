package capability

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

const transportCompanion = "companion"

var xkeenV2Pattern = regexp.MustCompile(`(?im)^#[[:space:]]*(?:Version|Версия):[[:space:]]*2\.[0-9]+`)

type Detector struct {
	root                 string
	xkeenInitPath        string
	ascPath              string
	collectorPath        string
	singBoxConfigured    bool
	awgManagerConfigured bool
	awgManagerProbe      func(context.Context) (bool, bool, string)
	singBoxProbe         func(context.Context) (bool, bool, string)
}

type Paths struct {
	XKeenInitPath        string
	ASCPath              string
	CollectorPath        string
	SingBoxConfigured    bool
	AWGManagerConfigured bool
	AWGManagerProbe      func(context.Context) (bool, bool, string)
	SingBoxProbe         func(context.Context) (bool, bool, string)
}

func NewDetector(root string) Detector {
	return Detector{
		root:          root,
		xkeenInitPath: "/opt/etc/init.d/S05xkeen",
		ascPath:       "/opt/sbin/asc",
		collectorPath: "/opt/etc/init.d/S95keenwg",
	}
}

func NewDetectorWithPaths(root string, paths Paths) Detector {
	detector := NewDetector(root)
	if paths.XKeenInitPath != "" {
		detector.xkeenInitPath = paths.XKeenInitPath
	}
	if paths.ASCPath != "" {
		detector.ascPath = paths.ASCPath
	}
	if paths.CollectorPath != "" {
		detector.collectorPath = paths.CollectorPath
	}
	detector.singBoxConfigured = paths.SingBoxConfigured
	detector.awgManagerConfigured = paths.AWGManagerConfigured
	detector.awgManagerProbe = paths.AWGManagerProbe
	detector.singBoxProbe = paths.SingBoxProbe
	return detector
}

func (d Detector) Detect(ctx context.Context) (Document, error) {
	if err := ctx.Err(); err != nil {
		return Document{}, err
	}

	xkeenAvailable, xkeenReason := d.detectXKeen()
	ascAvailable := d.isRegularFile(d.ascPath)
	collectorAvailable := d.isRegularFile(d.collectorPath)
	singBoxAvailable, singBoxWritable, singBoxReason := optionalAdapterAvailability(ctx, d.singBoxConfigured, "singbox_not_configured", d.singBoxProbe)
	awgAvailable, awgWritable, awgReason := optionalAdapterAvailability(ctx, d.awgManagerConfigured, "awg_not_configured", d.awgManagerProbe)

	items := []Capability{
		available(OverviewHealth, AccessRead),
		available(SystemDevices, AccessWrite),
		withAvailability(ConnectionsCatalog, AccessWrite, xkeenAvailable || singBoxAvailable || awgAvailable, "connection_adapter_not_found"),
		withAdapterAvailability(ConnectionsAWG, awgAvailable, awgWritable, awgReason),
		withAdapterAvailability(ConnectionsSingBox, singBoxAvailable, singBoxWritable, singBoxReason),
		withAvailability(ConnectionsXKeen, AccessWrite, xkeenAvailable, xkeenReason),
		withAvailability(RoutesDomains, AccessWrite, xkeenAvailable, xkeenReason),
		withAvailability(RoutesExclusions, AccessWrite, xkeenAvailable, xkeenReason),
		withAvailability(AccessWireGuard, AccessWrite, ascAvailable, "asc_not_found"),
		withAvailability(HistoryWireGuard, AccessRead, collectorAvailable, "collector_not_found"),
	}
	sort.Slice(items, func(i, j int) bool { return items[i].ID < items[j].ID })

	canonical, err := json.Marshal(items)
	if err != nil {
		return Document{}, err
	}
	digest := sha256.Sum256(canonical)
	return Document{
		SchemaVersion: 1,
		StateVersion:  binary.BigEndian.Uint64(digest[:8]),
		Capabilities:  items,
	}, nil
}

func optionalAdapterAvailability(
	ctx context.Context,
	configured bool,
	notConfiguredReason string,
	probe func(context.Context) (bool, bool, string),
) (bool, bool, string) {
	if !configured {
		return false, false, notConfiguredReason
	}
	if probe == nil {
		return true, true, ""
	}
	return probe(ctx)
}

func withAdapterAvailability(id string, isAvailable, writable bool, reason string) Capability {
	if !isAvailable {
		return withAvailability(id, AccessWrite, false, reason)
	}
	if writable {
		return available(id, AccessWrite)
	}
	return Capability{ID: id, SchemaVersion: 1, Access: AccessRead, Available: true, Transport: transportCompanion, Reason: reason}
}

func (d Detector) detectXKeen() (bool, string) {
	path := d.resolve(d.xkeenInitPath)
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return false, "xkeen_not_found"
	}
	if err != nil || !info.Mode().IsRegular() {
		return false, "xkeen_unreadable"
	}
	file, err := os.Open(path)
	if err != nil {
		return false, "xkeen_unreadable"
	}
	defer file.Close()
	body, err := io.ReadAll(io.LimitReader(file, 64*1024))
	if err != nil {
		return false, "xkeen_unreadable"
	}
	if !xkeenV2Pattern.Match(body) {
		return false, "xkeen_unsupported"
	}
	return true, ""
}

func (d Detector) isRegularFile(path string) bool {
	info, err := os.Lstat(d.resolve(path))
	return err == nil && info.Mode().IsRegular()
}

func (d Detector) resolve(path string) string {
	if d.root == "" || d.root == string(filepath.Separator) {
		return filepath.FromSlash(path)
	}
	return filepath.Join(d.root, filepath.FromSlash(strings.TrimPrefix(path, "/")))
}

func available(id string, access Access) Capability {
	return Capability{ID: id, SchemaVersion: 1, Access: access, Available: true, Transport: transportCompanion}
}

func withAvailability(id string, access Access, isAvailable bool, reason string) Capability {
	if isAvailable {
		return available(id, access)
	}
	return Capability{
		ID:            id,
		SchemaVersion: 1,
		Access:        AccessNone,
		Available:     false,
		Transport:     transportCompanion,
		Reason:        reason,
	}
}
