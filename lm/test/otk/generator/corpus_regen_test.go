package generator

import (
	"fmt"
	"os"
	"testing"
)

// corpusSeedMax is the inclusive upper bound of the committed seed-corpus range
// (1..corpusSeedMax). Chosen so all four categories appear; guarded by
// TestCorpusCoversAllCategories. If coverage ever fails, raise this and re-run.
const corpusSeedMax = 20

// TestRegenerateCorpus writes the committed corpus. It is a tool, not a check:
//
//	OTK_REGEN=1 go test -run TestRegenerateCorpus ./test/otk/generator/
func TestRegenerateCorpus(t *testing.T) {
	if os.Getenv("OTK_REGEN") == "" {
		t.Skip("set OTK_REGEN=1 to (re)write the committed corpus")
	}
	for seed := int64(1); seed <= corpusSeedMax; seed++ {
		path := fmt.Sprintf("../corpus/seed_%04d.feature", seed)
		if err := os.WriteFile(path, []byte(Generate(seed).Feature), 0o644); err != nil {
			t.Fatalf("write %s: %v", path, err)
		}
		runPath := fmt.Sprintf("../corpus/run_%04d.feature", seed)
		if err := os.WriteFile(runPath, []byte(GenerateRun(seed).Feature), 0o644); err != nil {
			t.Fatalf("write %s: %v", runPath, err)
		}
		authPath := fmt.Sprintf("../corpus/auth_%04d.feature", seed)
		if err := os.WriteFile(authPath, []byte(GenerateAuth(seed).Feature), 0o644); err != nil {
			t.Fatalf("write %s: %v", authPath, err)
		}
	}
	t.Logf("regenerated corpus seeds 1..%d (apply + run + auth families)", corpusSeedMax)
}

const corpusRestSeedMax = 20

// TestRegenerateRestCorpus writes the committed REST corpus. It is a tool:
//
//	OTK_REGEN=1 go test -run TestRegenerateRestCorpus ./lm/test/otk/generator/
func TestRegenerateRestCorpus(t *testing.T) {
	if os.Getenv("OTK_REGEN") == "" {
		t.Skip("set OTK_REGEN=1 to (re)write the committed REST corpus")
	}
	for seed := int64(1); seed <= corpusRestSeedMax; seed++ {
		path := fmt.Sprintf("../corpus/rest_%04d.feature", seed)
		if err := os.WriteFile(path, []byte(GenerateRest(seed).Feature), 0o644); err != nil {
			t.Fatalf("write %s: %v", path, err)
		}
	}
	t.Logf("regenerated REST corpus seeds 1..%d", corpusRestSeedMax)
}

const corpusEntitySeedMax = 20

// TestRegenerateEntityCorpus writes the committed entity corpus. It is a tool:
//
//	OTK_REGEN=1 go test -run TestRegenerateEntityCorpus ./lm/test/otk/generator/
func TestRegenerateEntityCorpus(t *testing.T) {
	if os.Getenv("OTK_REGEN") == "" {
		t.Skip("set OTK_REGEN=1 to (re)write the committed entity corpus")
	}
	for seed := int64(1); seed <= corpusEntitySeedMax; seed++ {
		path := fmt.Sprintf("../corpus/entity_%04d.feature", seed)
		if err := os.WriteFile(path, []byte(GenerateEntity(seed).Feature), 0o644); err != nil {
			t.Fatalf("write %s: %v", path, err)
		}
	}
	t.Logf("regenerated entity corpus seeds 1..%d", corpusEntitySeedMax)
}

const corpusProcessSeedMax = 20

// TestRegenerateProcessCorpus writes the committed process corpus. It is a tool:
//
//	OTK_REGEN=1 go test -run TestRegenerateProcessCorpus ./lm/test/otk/generator/
func TestRegenerateProcessCorpus(t *testing.T) {
	if os.Getenv("OTK_REGEN") == "" {
		t.Skip("set OTK_REGEN=1 to (re)write the committed process corpus")
	}
	for seed := int64(1); seed <= corpusProcessSeedMax; seed++ {
		path := fmt.Sprintf("../corpus/process_%04d.feature", seed)
		if err := os.WriteFile(path, []byte(GenerateProcess(seed).Feature), 0o644); err != nil {
			t.Fatalf("write %s: %v", path, err)
		}
	}
	t.Logf("regenerated process corpus seeds 1..%d", corpusProcessSeedMax)
}

const corpusViewSeedMax = 20

// TestRegenerateViewCorpus writes the committed view corpus. It is a tool:
//
//	OTK_REGEN=1 go test -run TestRegenerateViewCorpus ./lm/test/otk/generator/
func TestRegenerateViewCorpus(t *testing.T) {
	if os.Getenv("OTK_REGEN") == "" {
		t.Skip("set OTK_REGEN=1 to (re)write the committed view corpus")
	}
	for seed := int64(1); seed <= corpusViewSeedMax; seed++ {
		path := fmt.Sprintf("../corpus/view_%04d.feature", seed)
		if err := os.WriteFile(path, []byte(GenerateView(seed).Feature), 0o644); err != nil {
			t.Fatalf("write %s: %v", path, err)
		}
	}
	t.Logf("regenerated view corpus seeds 1..%d", corpusViewSeedMax)
}
