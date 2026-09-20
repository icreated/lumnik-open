//go:build otk

package otk

import (
	"os"
	"os/exec"

	"github.com/icreated/lumnik-open/lm/test/otk/auth"
	"github.com/icreated/lumnik-open/lm/test/otk/calibration"
)

// calibrationProbes wires each capability to the liveness probe the harness ALREADY
// runs, rather than to a list of variable names.
//
// The distinction is the point: DB truth is not "OTK_DB_DSN is set", it is "the
// snapshot probe returned live", which also covers psql being absent or failing.
// The battery already computes exactly that and logs it as DORMANT; re-deriving a
// weaker version beside it would make the refusal disagree with the log.
func calibrationProbes() map[calibration.Capability]calibration.Probe {
	return map[calibration.Capability]calibration.Probe{
		calibration.RequiresDBTruth: func() calibration.Liveness {
			l := calibration.Liveness{
				Probe: "hubSnapshot (schema + connector-count read through psql on OTK_DB_DSN) — " +
					"the same probe the battery logs as DORMANT",
				Remedy: "set OTK_DB_DSN to the non-owner app-role DSN and put psql on PATH",
			}
			_, live, err := hubSnapshot(os.Getenv("OTK_DB_DSN"))
			l.Live = live && err == nil
			if err != nil {
				l.Remedy = "psql ran but failed: " + err.Error()
			}
			return l
		},
		calibration.RequiresRealAuth: func() calibration.Liveness {
			l := calibration.Liveness{
				Probe: "controlled-key load (OTK_KEY_FILE parsed against OTK_OIDC_ISSUER) — " +
					"the same load TestOTK performs before the functional lanes",
				Remedy: "set OTK_OIDC_ISSUER and OTK_KEY_FILE to the key the hub verifies against " +
					"(an ephemeral key would not match)",
			}
			iss, kf := os.Getenv("OTK_OIDC_ISSUER"), os.Getenv("OTK_KEY_FILE")
			if iss == "" || kf == "" {
				return l
			}
			b, err := os.ReadFile(kf)
			if err != nil {
				l.Remedy = "OTK_KEY_FILE could not be read: " + err.Error()
				return l
			}
			if _, err := auth.NewControlledKeyFromPEM(b, iss, envOr("OTK_OIDC_AUDIENCE", "lumnik-backend")); err != nil {
				l.Remedy = "OTK_KEY_FILE is not a usable signing key: " + err.Error()
				return l
			}
			l.Live = true
			return l
		},
		calibration.RequiresAdminDB: func() calibration.Liveness {
			l := calibration.Liveness{
				Probe: "psql SELECT 1 on the BYPASSRLS admin role (OTK_DB_ADMIN_DSN, falling back " +
					"to OTK_DB_DSN) — the same DSN ensureEntityReadSeed uses for DDL",
				Remedy: "set OTK_DB_ADMIN_DSN to the lumnik_admin DSN and put psql on PATH",
			}
			dsn := envOr("OTK_DB_ADMIN_DSN", os.Getenv("OTK_DB_DSN"))
			if dsn == "" {
				return l
			}
			psql, err := exec.LookPath("psql")
			if err != nil {
				l.Remedy = "psql is not on PATH"
				return l
			}
			if _, err := exec.Command(psql, "-X", "-At", "-w", "-d", dsn, "-c", "SELECT 1").Output(); err != nil {
				l.Remedy = psqlErr("admin role probe", err).Error()
				return l
			}
			l.Live = true
			return l
		},
	}
}
