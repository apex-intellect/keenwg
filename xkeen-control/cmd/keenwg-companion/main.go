package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/goldb/keenwg/xkeen-control/internal/app"
	"github.com/goldb/keenwg/xkeen-control/internal/auth"
	"github.com/goldb/keenwg/xkeen-control/internal/config"
	"github.com/goldb/keenwg/xkeen-control/internal/identity"
)

var (
	version = "dev"
	commit  = "unknown"
)

const defaultConfigPath = "/opt/etc/keenwg/companion.json"

var errInvalidArguments = errors.New("invalid arguments")

func main() {
	err := command(os.Args[1:], os.Stdout, os.Getenv("KEENWG_DESTDIR"))
	if err == nil {
		return
	}
	fmt.Fprintln(os.Stderr, "keenwg-companion:", publicError(err))
	os.Exit(exitCode(err))
}

func command(arguments []string, output io.Writer, root string) error {
	flags := flag.NewFlagSet("keenwg-companion", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	configPath := flags.String("config", defaultConfigPath, "companion config")
	check := flags.Bool("check", false, "validate configuration and identity")
	bootstrapFrom := flags.String("bootstrap-from", "", "legacy controller config")
	bootstrapRequest := flags.String("bootstrap-request", "", "strict bootstrap request")
	pairingScope := flags.String("create-pairing-offer", "", "create first owner pairing offer")
	showVersion := flags.Bool("version", false, "print version")
	if err := flags.Parse(arguments); err != nil || flags.NArg() != 0 {
		return errInvalidArguments
	}
	bootstrapMode := *bootstrapFrom != "" || *bootstrapRequest != ""
	if bootstrapMode && (*bootstrapFrom == "" || *bootstrapRequest == "") {
		return errInvalidArguments
	}
	if *pairingScope != "" && *pairingScope != string(auth.ScopeOwner) {
		return errInvalidArguments
	}
	selected := 0
	for _, enabled := range []bool{*check, bootstrapMode, *pairingScope != "", *showVersion} {
		if enabled {
			selected++
		}
	}
	if selected > 1 {
		return errInvalidArguments
	}
	if *showVersion {
		_, err := fmt.Fprintf(output, "keenwg-companion %s (%s)\n", version, commit)
		return err
	}
	if bootstrapMode {
		_, err := app.BootstrapFromLegacy(*bootstrapFrom, *configPath, *bootstrapRequest, root, time.Now().UTC())
		return err
	}
	if *pairingScope != "" {
		result, err := app.CreatePairingOffer(*configPath, root, auth.ScopeOwner, 5*time.Minute)
		if err != nil {
			return err
		}
		return json.NewEncoder(output).Encode(struct {
			BaseURL        string    `json:"base_url"`
			CertificatePin string    `json:"certificate_pin"`
			OfferID        string    `json:"offer_id"`
			Secret         string    `json:"secret"`
			ExpiresAt      time.Time `json:"expires_at"`
		}{result.BaseURL, result.CertificatePin, result.Offer.ID, result.Offer.Secret, result.Offer.ExpiresAt})
	}
	if *check {
		return app.CheckCompanion(*configPath, root)
	}
	cfg, err := app.LoadConfig(*configPath)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	return app.RunCompanion(ctx, cfg, version, root)
}

func exitCode(err error) int {
	switch {
	case errors.Is(err, errInvalidArguments), errors.Is(err, config.ErrInvalidConfig):
		return 2
	case errors.Is(err, auth.ErrStoreCorrupt), errors.Is(err, auth.ErrStoreIO),
		errors.Is(err, identity.ErrIdentityCorrupt), errors.Is(err, identity.ErrUnsafePath), errors.Is(err, identity.ErrUnsafePermissions):
		return 3
	default:
		return 4
	}
}

func publicError(err error) string {
	switch exitCode(err) {
	case 2:
		return "invalid configuration or arguments"
	case 3:
		return "identity or device store unavailable"
	default:
		return "operation failed"
	}
}
