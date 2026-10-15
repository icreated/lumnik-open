package newfile

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestWrite_createsAFreshFile(t *testing.T) {
	p := filepath.Join(t.TempDir(), "fresh.yaml")
	require.NoError(t, Write(p, []byte("kind: View\n")))
	got, err := os.ReadFile(p)
	require.NoError(t, err)
	assert.Equal(t, "kind: View\n", string(got))
}

// O_EXCL makes the check and the create one step: even a raced or mis-probed name can never clobber.
func TestWrite_refusesAnExistingFileAndLeavesItIntact(t *testing.T) {
	p := filepath.Join(t.TempDir(), "a.yaml")
	require.NoError(t, os.WriteFile(p, []byte("precious"), 0o644))
	err := Write(p, []byte("clobber"))
	assert.ErrorIs(t, err, fs.ErrExist, "callers tell an existing file from any other failure")
	got, _ := os.ReadFile(p)
	assert.Equal(t, "precious", string(got))
}

// A stub left behind would block every retry with a misleading "already exists".
func TestWrite_aFailedWriteRemovesTheFileItCreated(t *testing.T) {
	p := filepath.Join(t.TempDir(), "half.yaml")
	boom := errors.New("disk full")
	err := write(p, []byte("x"), func(*os.File, []byte) error { return boom })
	assert.ErrorIs(t, err, boom)
	_, statErr := os.Stat(p)
	assert.ErrorIs(t, statErr, fs.ErrNotExist)
}

func TestWrite_aFailedWriteNeverRemovesAFileItDidNotCreate(t *testing.T) {
	p := filepath.Join(t.TempDir(), "a.yaml")
	require.NoError(t, os.WriteFile(p, []byte("precious"), 0o644))
	require.Error(t, Write(p, []byte("x")))
	got, err := os.ReadFile(p)
	require.NoError(t, err)
	assert.Equal(t, "precious", string(got))
}
