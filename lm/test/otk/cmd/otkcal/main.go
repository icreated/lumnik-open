//go:build otk && unix

// otkcal drives one OTK calibration end to end: control, injected, restored.
//
// It owns the lifecycle of every process it starts, on purpose. Restoration's second
// obligation is "the faulted hub stopped, by the pid the runner started" — a runner
// that did not start the hub cannot keep that promise, and the dangerous residue of
// this whole lot is not the patch but the faulted jar a developer runs the next
// morning. The same reasoning reaches further than the hub: a `mvn package` still
// running during restoration recreates that jar after it was deleted.
//
// The `unix` constraint is load-bearing: stopping a build means signalling a PROCESS
// GROUP (mvn forks a JVM, go test forks the test binary), and SysProcAttr.Setpgid
// does not exist on Windows. OTK's live lanes are Linux and macOS, so this costs
// nothing real and states the limit instead of failing obscurely.
//
// No git worktree: this project has already learned that two mvn invocations against
// a shared worktree is its own class of trouble, and a calibration run packages the
// hub.
package main

import (
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/icreated/lumnik-open/lm/test/otk/calibration"
)

// startedRe is Quarkus' own startup line. Anchored on "started in <seconds>s",
// which is what otk-gold.yml already greps for — so the runner and the workflow
// cannot disagree about what "ready" means.
var startedRe = regexp.MustCompile(`started in \d+\.\d+s`)

// main owns the session so restoration is reachable on EVERY exit path, including
// the ones run() never returns from. It does not defer-and-exit: os.Exit from inside
// a defer would skip the rest of them, and this is the one program where "the rest of
// them" means the faulted jar.
func main() {
	s := &session{}
	watchSignals(s)

	runErr := calibrate(s)
	restErr := s.restore()

	if runErr != nil {
		fmt.Fprintln(os.Stderr, "otkcal:", runErr)
	}
	if restErr != nil {
		fmt.Fprintln(os.Stderr, "otkcal:", restErr)
	}
	if runErr != nil || restErr != nil {
		os.Exit(1)
	}
	fmt.Println("otkcal: CALIBRATED — control clean, injected detected, everything restored")
}

// calibrate is the whole three-phase argument. It never restores on its RETURN paths:
// main does that, so an early return here cannot skip it.
//
// A panic is the one exit that returns nowhere, and main's sequential shape — which
// exists so os.Exit cannot skip a defer — is exactly what leaves it uncovered. The
// recovery therefore lives HERE, at the edge of the only code that can panic while a
// fault is on disk.
func calibrate(s *session) error {
	defer restoreOnPanic(s)

	name := os.Getenv("OTK_FAULT")
	if name == "" {
		return fmt.Errorf("set OTK_FAULT to the fault to calibrate; otkcal drives both phases itself, " +
			"so OTK_FAULT_PHASE is its business and not yours")
	}
	fault, err := calibration.ByName(name)
	if err != nil {
		return err
	}

	root, err := repoRoot()
	if err != nil {
		return err
	}
	if clean, dirt, cerr := calibration.TreeClean(root); cerr != nil {
		return cerr
	} else if !clean {
		return fmt.Errorf("REFUSING to start on a dirty tree — a patch-kind run reverts a patch, and a "+
			"revert must never be able to discard your work. Commit or stash first:\n%s", dirt)
	}

	// calibration.FaultsDir is relative to the calibration PACKAGE, which lives at
	// lm/test/otk/calibration/ — so the runner spells the package path itself. Every
	// kind owns exactly one artefact there (Name+Kind.Ext()), so this path is the
	// same expression whether it resolves to a .patch or a .sql file.
	artefact := filepath.Join(root, "lm", "test", "otk", "calibration", calibration.FaultsDir, fault.ArtefactName())

	// Arming the session is what creates the obligation to restore. Each branch
	// below arms BEFORE anything is packaged or applied, so there is no window in
	// which a fault could exist with no obligation to remove it — and main() and
	// the signal handler both reach restore() through the same sync.Once. arm's
	// root is set regardless of kind: TreeClean/RemoveAndVerify at restore time
	// need it even for a SQL fault, which never touches the patch field.
	switch fault.Kind {
	case calibration.KindPatch:
		if err := calibration.PatchApplies(root, artefact); err != nil {
			return fmt.Errorf("the fault no longer applies to the code it faults (a stale patch, which is "+
				"exactly what a patch is chosen to make loud): %w", err)
		}
		s.arm(root, artefact)
	case calibration.KindSQL:
		dsn := os.Getenv("OTK_DB_ADMIN_DSN")
		if dsn == "" {
			return fmt.Errorf("fault %q is a SQL fault: set OTK_DB_ADMIN_DSN to the admin (BYPASSRLS) "+
				"role's DSN — applying and undoing an RLS-policy mutation needs DDL rights the app role "+
				"does not have", fault.Name)
		}
		// Parsed here, read-only, before anything is packaged — the same posture
		// PatchApplies takes for a patch: a stale or malformed artefact is a hard
		// error before any hub is built, not a surprise forty seconds into a battery.
		b, rerr := os.ReadFile(artefact)
		if rerr != nil {
			return fmt.Errorf("read SQL fault artefact: %w", rerr)
		}
		if _, _, perr := calibration.ParseSQLFault(string(b)); perr != nil {
			return fmt.Errorf("the SQL fault artefact does not parse: %w", perr)
		}
		s.arm(root, "")
		s.armSQL(dsn, artefact)
	default:
		return fmt.Errorf("fault %q declares unknown kind %q", fault.Name, fault.Kind)
	}

	artefacts := filepath.Join(root, calibration.FaultedArtefacts)

	// PHASE 1 — control. Clean source, packaged and booted by us, and the declared
	// finding must be ABSENT. Presence alone is correlation; this is the half that
	// makes it causal. A control failure accuses the PRODUCT.
	//
	// The artefacts go FIRST, before the control build and not only before the
	// injected one. `mvn package` is not `mvn clean package`: a previous calibration
	// that was interrupted — or whose restoration failed and said so — can leave
	// faulted classes under target/, and Maven's staleness check is mtime-based, which
	// this repository has already watched replay a stale class once. The control phase
	// would then boot a still-faulted hub and find the declared finding, and the run
	// would fail phase 1 accusing the PRODUCT of a defect the harness had left behind.
	// The asymmetry in an earlier draft — removing before phase 2 only — was itself
	// the tell: if the injected build needs it, the build whose entire claim is
	// "clean" needs it more.
	fmt.Println("otkcal: PHASE 1 (control) — clearing artefacts, then packaging clean source")
	if err := s.packageAndBoot(root, artefacts, calibration.ControlValue); err != nil {
		return fmt.Errorf("phase 1: %w", err)
	}
	if err := s.battery(root, fault.Name, calibration.ControlValue); err != nil {
		return fmt.Errorf("PHASE 1 (control) FAILED — the unfaulted branch does not behave as the "+
			"calibration assumes: %w", err)
	}

	// Only RebuildHub needs the phase 1 hub stopped: it lives entirely in the
	// database (RebuildNone) or rebuilds the lm CLI, not the hub (RebuildCLI), and
	// either way the phase 1 hub is still correct to serve phase 2 — stopping it
	// would only cost a second package+boot for nothing. Rebuild — not Kind — is
	// what decides this, so a future fault of either kind can declare any answer
	// without this runner needing to change.
	switch fault.Rebuild {
	case calibration.RebuildHub:
		s.stopChildren()
	case calibration.RebuildCLI:
		fmt.Println("otkcal: fault rebuilds the CLI, not the hub — the phase 1 hub stays up for phase 2")
	default:
		fmt.Println("otkcal: fault needs no repackage — the phase 1 hub stays up for phase 2")
	}

	// PHASE 2 — injected. The same catalogue against a hub, database, or CLI broken
	// on purpose, and the declared finding must be PRESENT. An injected failure
	// accuses the HARNESS.
	fmt.Println("otkcal: PHASE 2 (injected) — applying", fault.ArtefactName())
	switch fault.Kind {
	case calibration.KindPatch:
		if err := s.applyFault(); err != nil {
			return fmt.Errorf("phase 2: %w", err)
		}
	case calibration.KindSQL:
		if err := s.applySQLFault(); err != nil {
			return fmt.Errorf("phase 2: %w", err)
		}
	}
	switch fault.Rebuild {
	case calibration.RebuildHub:
		if err := s.packageAndBoot(root, artefacts, "injected"); err != nil {
			return fmt.Errorf("phase 2: %w", err)
		}
	case calibration.RebuildCLI:
		if err := s.buildCLI(root); err != nil {
			return fmt.Errorf("phase 2: %w", err)
		}
	}
	if err := s.battery(root, fault.Name, ""); err != nil {
		return fmt.Errorf("PHASE 2 (injected) FAILED: %w", err)
	}

	// PHASE 3 is s.restore(), called by main() on every exit path, so an interrupted
	// run gets exactly the same checks as a successful one.
	return nil
}

// child is one process this runner started, together with the channel that closes
// when it has been reaped.
type child struct {
	cmd  *exec.Cmd
	done chan struct{}
	err  error // the Wait() result; safe to read once done is closed
}

// session is what must be undone, the fact of whether each thing was ever done, and
// every process still able to undo the undoing.
//
// Two goroutines reach it — main's normal path and the signal handler — so every
// field either is written once before either can run, or is guarded. `once` is what
// makes restore() safe rather than merely idempotent: an "if already done, return"
// flag is a test-and-set, and two callers can pass the test together and then both
// run `git apply -R`. sync.Once makes the second caller WAIT for the first and
// receive its result.
//
// EVERY child is tracked, not just the hub. An earlier draft tracked only the JVM,
// which left the worst case open: a signal during `mvn package` had the handler
// revert the source and delete lumnik-hub/target while Maven was still running, so
// Maven then RECREATED target from a half-reverted tree, after the removal had been
// verified. "No faulted artefact survives the run" is the one guarantee this lot
// makes, and a child outliving restoration is how it is lost.
type session struct {
	// mu guards the fields below. It is held only for short operations — a field
	// read, a process start, a `git apply` — never across a wait.
	mu      sync.Mutex
	root    string
	patch   string
	applied bool

	// sqlDSN/sqlFault/sqlApplied are the SQL-kind analog of patch/applied: the
	// admin (BYPASSRLS) DSN a SQL fault is applied and undone against, the
	// artefact path, and whether the apply half has run. A session only ever
	// populates ONE of {patch+applied, sqlFault+sqlApplied} — calibrate's own
	// switch on fault.Kind guarantees that — so doRestore can check both without
	// either ever doing something the other fault kind's run did not ask for.
	sqlDSN     string
	sqlFault   string
	sqlApplied bool

	children []*child
	tornDown bool

	// stopMu serialises the ENTIRE child sweep, including the reap, so a second
	// caller waits for the first instead of seeing an emptied list and concluding
	// nothing is running. It is a separate lock precisely because the sweep blocks
	// for as long as a SIGTERM grace, and mu must stay cheap. Order: stopMu, then mu.
	stopMu sync.Mutex

	once sync.Once
	err  error

	// restores counts how many times the restoration body actually ran. It exists so
	// TestRestoreRunsExactlyOnce can assert the Once rather than trust it: "it is
	// idempotent" is a claim, and an unwatched claim about concurrency is the kind
	// that stays true right up until it does not.
	restores int
}

// arm gives the session something to restore. Until it is called, restore() has
// nothing to undo and says so. root is required by every fault kind (restore's
// TreeClean/RemoveAndVerify both need it); patch is the empty string for a SQL
// fault, which arms its own DSN/artefact separately via armSQL.
func (s *session) arm(root, patch string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.root, s.patch = root, patch
}

// armSQL records a SQL fault's admin DSN and artefact path — additive to arm,
// which every calibration still calls first for root. Kept as a second method
// rather than folded into arm's signature so a patch-kind session's zero-value
// sqlDSN/sqlFault can never be mistaken for "armed": doRestore gates the SQL undo
// on sqlApplied, set only by applySQLFault, never by arm/armSQL themselves.
func (s *session) armSQL(dsn, path string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sqlDSN, s.sqlFault = dsn, path
}

// start launches cmd in its OWN PROCESS GROUP and registers it, both under the lock.
//
// The process group is the point. `mvn` forks a JVM and `go test` forks the compiled
// test binary, so signalling the direct child alone leaves the grandchild running —
// and it is the grandchild that writes to target/. A context would not help here:
// exec.CommandContext kills cmd.Process, which is the fork, not the group.
//
// Start happens UNDER the lock so registration cannot race it. An earlier draft
// started first and registered second, leaving a window in which a signal saw an
// empty child list and the process it had just spawned survived everything.
func (s *session) start(cmd *exec.Cmd) (*child, error) {
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	c := &child{cmd: cmd, done: make(chan struct{})}

	s.mu.Lock()
	if s.tornDown {
		s.mu.Unlock()
		close(c.done)
		// Refusing beats starting something nothing will ever stop.
		return nil, fmt.Errorf("refusing to start %s: the session is already being torn down", cmd.Path)
	}
	err := cmd.Start()
	if err == nil {
		s.children = append(s.children, c)
	}
	s.mu.Unlock()

	if err != nil {
		close(c.done)
		return nil, err
	}
	go func() { c.err = cmd.Wait(); close(c.done) }()
	return c, nil
}

// run starts cmd and waits for it to finish, returning its exit status.
func (s *session) run(cmd *exec.Cmd) error {
	c, err := s.start(cmd)
	if err != nil {
		return err
	}
	<-c.done
	return c.err
}

// terminate signals the child's whole process group and waits for it to be reaped,
// escalating to SIGKILL after a grace period. Already-exited children return at once.
func (c *child) terminate() {
	select {
	case <-c.done:
		return
	default:
	}
	if c.cmd.Process == nil {
		return
	}
	// Setpgid made the child's pid its group id; the negative pid signals the GROUP.
	pgid := c.cmd.Process.Pid
	_ = syscall.Kill(-pgid, syscall.SIGTERM)
	select {
	case <-c.done:
	case <-time.After(30 * time.Second):
		_ = syscall.Kill(-pgid, syscall.SIGKILL)
	}
}

// stopChildren stops every process this runner started — the hub, and any mvn or go
// test still running — and WAITS for all of them before returning.
//
// The wait is not politeness: restoration reverts the source and deletes the
// packaged output next, and either is meaningless while a build is still writing.
// One mechanism for all children, so the hub is not a special case that a future
// third child forgets to copy.
//
// THE WHOLE SWEEP IS SERIALISED, not merely the list handover. There are two callers
// — the between-phases stop on the normal path, and restoration — and an earlier
// draft took the list under the lock and then released it before terminating. A
// signal arriving during the between-phases stop then found an EMPTY list, concluded
// nothing was running, and went on to revert the source and delete target while the
// first sweep was still inside a thirty-second SIGTERM grace. An empty list means
// "someone else took them", not "there are none", and only holding stopMu across the
// reap makes the second caller wait for that someone.
//
// Lock order is stopMu then mu, never the reverse: mu is only ever taken for a field
// read or a process start, both of which are short, and nothing holds mu while
// reaching for stopMu.
func (s *session) stopChildren() {
	s.stopMu.Lock()
	defer s.stopMu.Unlock()

	s.mu.Lock()
	kids := s.children
	s.children = nil
	s.mu.Unlock()

	for _, c := range kids {
		c.terminate()
	}
	for _, c := range kids {
		<-c.done // reaped, for certain, before anything touches the tree
	}
	if len(kids) > 0 {
		fmt.Printf("otkcal: %d child process(es) stopped and reaped\n", len(kids))
	}
}

// applyFault injects the patch and records that it must be reversed — both under the
// lock, and only if the session is not already being torn down.
//
// The tornDown check is what makes this atomic against restoration, and it is not
// optional. doRestore captures `applied` and sets tornDown in ONE critical section,
// so either applyFault completes first (restoration sees applied=true and reverts) or
// restoration's read happens first (applyFault refuses). Without the check there is a
// third outcome: restoration remembers applied=false, applyFault then applies the
// patch, and the process exits reporting "restored" with FAULTED SOURCE on disk —
// the exact residue this runner exists to make impossible.
//
// `git apply` runs while the lock is held. That is deliberate: it costs milliseconds,
// and it is what makes "the patch is on disk" and "the session knows it" a single
// indivisible fact rather than two that a signal can arrive between.
func (s *session) applyFault() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.tornDown {
		return fmt.Errorf("refusing to apply the fault: the session is already being torn down")
	}
	if err := calibration.ApplyPatch(s.root, s.patch); err != nil {
		return err
	}
	s.applied = true
	return nil
}

// applySQLFault is applyFault's SQL-kind analog: runs the fault's apply half
// against the admin DSN and records that sqlApplied, under the same lock and the
// same tornDown race guard, for the same reason — restoration must never be able
// to observe "not applied" while the mutation lands on the database a moment
// later.
func (s *session) applySQLFault() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.tornDown {
		return fmt.Errorf("refusing to apply the SQL fault: the session is already being torn down")
	}
	if err := calibration.ApplySQL(s.sqlDSN, s.sqlFault); err != nil {
		return err
	}
	s.sqlApplied = true
	return nil
}

// restore performs phase 3 and asserts all of it: the source reverted, the process
// stopped, the artefacts GONE, and (for SQL faults, when they exist) the injected
// mutation undone.
//
// It returns its verdict rather than exiting, so main() can report the run's failure
// AND the restoration's failure — the second must never hide the first.
func (s *session) restore() error {
	s.once.Do(func() { s.err = s.doRestore() })
	return s.err
}

// restoreOnPanic restores and then RE-PANICS. It is deferred by calibrate, which is
// the only frame that can panic while a fault is on disk.
//
// A panic unwinds past main's `runErr := calibrate(s)` without ever reaching the
// restore() on the next line, so the faulted source and the faulted jar would both
// survive — the exact residue this runner exists to make impossible. Recovering is
// therefore about the TREE, not about the failure: the panic is re-raised, with its
// stack, so nothing is swallowed and the exit stays loud.
//
// It is a named function rather than an inline closure so the path can be DRIVEN by a
// test: a panic inside a real calibration needs a hub, a build and a fault, which is
// not a thing to wait for in order to learn whether a defer fires.
func restoreOnPanic(s *session) {
	r := recover()
	if r == nil {
		return
	}
	fmt.Fprintln(os.Stderr, "\notkcal: PANICKED — restoring before the stack reaches you")
	if err := s.restore(); err != nil {
		fmt.Fprintln(os.Stderr, "otkcal:", err)
	}
	panic(r)
}

func (s *session) doRestore() error {
	s.mu.Lock()
	root, patch, applied := s.root, s.patch, s.applied
	sqlDSN, sqlFault, sqlApplied := s.sqlDSN, s.sqlFault, s.sqlApplied
	s.restores++
	// tornDown closes the holes a late writer could open: a child started after this
	// point would never be caught by the sweep below, and a patch or SQL fault
	// applied after this point would never be reverted, because `applied`/`sqlApplied`
	// were just read. start(), applyFault() and applySQLFault() all refuse once it is
	// set.
	s.tornDown = true
	s.mu.Unlock()
	if root == "" {
		return nil // never armed: nothing was packaged, applied or booted
	}

	fmt.Println("otkcal: PHASE 3 (restore)")
	var problems []string

	// The SQL undo runs FIRST, ahead even of stopChildren. Unlike the file-system
	// hazard below, undoing a live RLS-policy mutation has no dependency on any
	// process this runner started — but stopChildren can block for up to a 30s
	// SIGTERM grace, and every second of that is a second the weakened policy would
	// otherwise still be sitting on the database. Undo the thing that matters most
	// first; sweep processes after.
	if sqlApplied {
		if err := calibration.UndoSQL(sqlDSN, sqlFault); err != nil {
			problems = append(problems, "sql: "+err.Error())
		}
	}

	// Next, and not negotiably next: nothing below means anything while a build is
	// still running. A `mvn package` that outlives this sweep recreates the artefacts
	// AFTER they were deleted and verified, and does it from a half-reverted tree.
	s.stopChildren()

	if applied {
		if err := calibration.RevertPatch(root, patch); err != nil {
			problems = append(problems, "source: "+err.Error())
		}
	}
	if clean, dirt, err := calibration.TreeClean(root); err != nil {
		problems = append(problems, "source: "+err.Error())
	} else if !clean {
		problems = append(problems, "source: the tree is not clean after the revert:\n"+dirt)
	}

	// Both removed unconditionally, regardless of which one (if either) this fault
	// actually rebuilt: see FaultedCLIBinary's own doc comment for why that is
	// simpler and cheaper than doRestore needing to know the fault's Rebuild target.
	if err := calibration.RemoveAndVerify(
		filepath.Join(root, calibration.FaultedArtefacts),
		filepath.Join(root, calibration.FaultedCLIBinary),
	); err != nil {
		problems = append(problems, "artefacts: "+err.Error())
	}

	if len(problems) > 0 {
		return fmt.Errorf("RESTORATION INCOMPLETE — %d problem(s):\n  %s",
			len(problems), strings.Join(problems, "\n  "))
	}
	detail := "source clean"
	if sqlApplied {
		detail = "SQL fault undone"
	}
	fmt.Printf("otkcal: restored — %s, hub stopped, %s removed. "+
		"The next build starts from clean source.\n", detail, calibration.FaultedArtefacts)
	return nil
}

// hubLogPath is where one phase's hub stdout goes. OUTSIDE the repository, on
// purpose: TreeClean counts untracked files, because Maven compiles an untracked
// .java, and a log written inside the tree would make the runner's own exhaust
// fail its own guard. OTK_CAL_LOG lets CI pin a path it can then upload.
//
// It is PHASE-SPECIFIC — the phase name goes in the filename, not just the log body
// — because boot() truncates the log fresh every phase (os.Create), and a single
// fixed path would let phase 2 silently erase phase 1's log. That is exactly the
// log worth having when a control-then-injected run gives a confusing result: what
// did the CONTROL hub actually see. CI's fixed OTK_CAL_LOG is treated as a base name
// here, with the phase inserted before the extension, so the workflow's upload step
// globs for both rather than naming one.
func hubLogPath(phase string) string {
	base := filepath.Join(os.TempDir(), "otkcal-hub.log")
	if p := os.Getenv("OTK_CAL_LOG"); p != "" {
		base = p
	}
	ext := filepath.Ext(base)
	return strings.TrimSuffix(base, ext) + "-" + phase + ext
}

// packageAndBoot clears any faulted artefact, packages the hub from whatever source
// is on disk, and boots it — the sequence both phases share verbatim, so they cannot
// silently drift apart on the one property that matters: artefacts cleared BEFORE
// packaging, every phase, not just the injected one (see the comment on phase 1 in
// calibrate for why).
func (s *session) packageAndBoot(root, artefacts, phase string) error {
	if err := calibration.RemoveAndVerify(artefacts); err != nil {
		return err
	}
	if err := s.pkg(root); err != nil {
		return err
	}
	return s.boot(root, phase)
}

func (s *session) boot(root, phase string) error {
	logPath := hubLogPath(phase)
	log, err := os.Create(logPath)
	if err != nil {
		return fmt.Errorf("create hub log %s: %w", logPath, err)
	}
	defer log.Close()

	cmd := exec.Command("java", "-jar", filepath.Join(root, "lumnik-hub", "target", "quarkus-app", "quarkus-run.jar"))
	cmd.Dir = root
	cmd.Stdout, cmd.Stderr = log, log
	cmd.Env = append(os.Environ(), "QUARKUS_PROFILE=otkauth", "QUARKUS_OTEL_SDK_DISABLED=true")
	// The hub is a child like any other — registered before it can run, stopped and
	// reaped by the same sweep. It is long-lived rather than special.
	c, err := s.start(cmd)
	if err != nil {
		return fmt.Errorf("boot the hub: %w", err)
	}
	fmt.Printf("otkcal: hub booting (pid %d), log %s\n", cmd.Process.Pid, logPath)

	for i := 0; i < 180; i++ {
		b, _ := os.ReadFile(logPath)
		if hubReady(string(b)) {
			fmt.Printf("otkcal: hub ready after %ds\n", i)
			return nil
		}
		select {
		case <-c.done:
			return fmt.Errorf("the hub exited before becoming ready; see %s", logPath)
		case <-time.After(time.Second):
		}
	}
	return fmt.Errorf("the hub did not become ready within 180s; see %s", logPath)
}

// hubReady reports whether the hub's own startup line has appeared. It is the same
// signal otk-gold.yml waits on, so the two cannot disagree about what "ready" means.
func hubReady(log string) bool {
	return startedRe.MatchString(log)
}

// hubURL is the address the hub actually comes up on. boot() sets no
// QUARKUS_HTTP_PORT, so the jar binds Quarkus' own default port, 8080 — this
// constant names that fact in exactly one place rather than leaving battery() below
// to hardcode a second copy of the same literal.
const hubURL = "http://localhost:8080"

// batteryTimeout bounds a single phase's `go test` call. go test's own default is
// 10 minutes — comfortably enough for every fault calibrated before
// security-terminal-run-hidden-from-api, whose ~20 run-lane catalogue cases each
// pay its full 90s poll deadline (thenRunTerminal) before that fault's finding can
// fire, well over 10 minutes on their own. Left at the default, phase 2 panics on
// go test's own timeout — a harness defect indistinguishable in its own output from
// the fault having hung the hub for real. 45 minutes is generous over that fault's
// worst case while leaving otk-calibrate.yml's 60-minute job budget enough margin
// for the packaging/boot overhead around both phases.
const batteryTimeout = 45 * time.Minute

// battery runs the whole catalogue for one fault in one phase. Its exit code is the
// phase's verdict: the inverted gate lives inside TestOTK, not here.
//
// phase is "" for the injected phase and calibration.ControlValue for the control —
// and it is passed EXPLICITLY rather than inherited, so a stray OTK_FAULT_PHASE in
// the operator's shell cannot turn phase 2 into a second control run that then
// passes for the wrong reason.
//
// LUMNIK_URL is pinned the same way, and for the same reason. otkcal boots its OWN
// hub on hubURL, but a plain append(os.Environ(), ...) would still hand the battery
// whatever LUMNIK_URL the operator's shell already exports — and this repo's own
// developers commonly export one pointing at the shared cloud test instance for
// everyday work. Left unpinned, a local calibration run would fault a hub nobody
// tests while the battery quietly runs the whole catalogue against the remote one
// instead, then report "the harness is blind" against the wrong hub entirely.
// os/exec keeps the LAST matching KEY=value when duplicates exist, so appending
// this after os.Environ() overrides any ambient value regardless of what the shell
// set.
func (s *session) battery(root, fault, phase string) error {
	cmd := exec.Command("go", "test", "-tags", "otk", "./test/otk/", "-run", "TestOTK", "-v", "-count=1",
		"-timeout", batteryTimeout.String())
	cmd.Dir = filepath.Join(root, "lm")
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	cmd.Env = append(os.Environ(), "OTK_FAULT="+fault, "OTK_FAULT_PHASE="+phase, "LUMNIK_URL="+hubURL)
	return s.run(cmd)
}

// pkg builds the hub. It goes through s.run like every other child, because the one
// thing that must never outlive restoration is a build writing into target/.
func (s *session) pkg(root string) error {
	cmd := exec.Command("mvn", "-q", "-pl", "lumnik-hub", "-am", "package", "-DskipTests")
	cmd.Dir = root
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	return s.run(cmd)
}

// buildCLI rebuilds the lm binary at lm/lm — the RebuildCLI analog of pkg(), for a
// fault that lives in the CLI's own source rather than the hub's. It goes through
// s.run for the same reason pkg does, and needs no artefact-clearing precaution to
// match packageAndBoot's: unlike Maven's mtime-based incremental staleness (the
// exact bug this repo has already watched replay a stale class), `go build -o`
// always overwrites its output fresh, unconditionally.
func (s *session) buildCLI(root string) error {
	cmd := exec.Command("go", "build", "-o", "lm", "./cmd/lm")
	cmd.Dir = filepath.Join(root, "lm")
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	return s.run(cmd)
}

func repoRoot() (string, error) {
	out, err := exec.Command("git", "rev-parse", "--show-toplevel").Output()
	if err != nil {
		if ee, ok := err.(*exec.ExitError); ok && len(ee.Stderr) > 0 {
			return "", fmt.Errorf("otkcal must run inside the repository: %v\ngit stderr: %s", err, ee.Stderr)
		}
		return "", fmt.Errorf("otkcal must run inside the repository: %w", err)
	}
	return strings.TrimSpace(string(out)), nil
}

// watchSignals makes an interrupted run restore too. A harness that edits your
// source has one obligation above the rest: leave nothing behind, including when
// someone reaches for Ctrl-C.
//
// It races main's own restore() by construction, and that is fine: both go through
// the same sync.Once, so whichever arrives second WAITS for the first to finish and
// reads its verdict rather than starting a second revert beside it.
//
// FOUR signals, not two. Ctrl-C and a `kill` are the ones everybody thinks of, but a
// calibration runs for minutes and the other two are how a long run actually dies:
// SIGHUP when the terminal or the ssh session goes away, SIGQUIT when someone reaches
// for Ctrl-\ because Ctrl-C "did nothing" during a thirty-second SIGTERM grace. Both
// default to killing the process, and a default kill here leaves the patch applied
// and lumnik-hub/target faulted.
func watchSignals(s *session) {
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, os.Interrupt, syscall.SIGTERM, syscall.SIGHUP, syscall.SIGQUIT)
	go func() {
		<-ch
		fmt.Fprintln(os.Stderr, "\notkcal: interrupted — restoring before exit")
		fmt.Fprintln(os.Stderr, "otkcal: restoring; this cannot be interrupted — only SIGKILL will stop it")
		if err := s.restore(); err != nil {
			fmt.Fprintln(os.Stderr, "otkcal:", err)
		}
		os.Exit(130)
	}()
}
