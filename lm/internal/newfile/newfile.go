// Package newfile is the one place lm creates a file it must never overwrite.
package newfile

import (
	"errors"
	"os"
)

// Write creates path with data and refuses an existing file: an existing one comes back as the raw
// error, so a caller can tell it with errors.Is(err, fs.ErrExist). O_EXCL makes the check and the
// create one step, so a raced or mis-probed name can never clobber. Because this call created the
// file, a failed write may remove it: a stub left behind would otherwise block every retry with a
// misleading "already exists".
func Write(path string, data []byte) error {
	return WriteMode(path, data, 0o644)
}

// WriteMode is Write with the file's mode: 0o600 for a file only its owner may read (#2004).
func WriteMode(path string, data []byte, perm os.FileMode) error {
	return write(path, data, perm, func(f *os.File, b []byte) error {
		_, err := f.Write(b)
		return err
	})
}

// write is Write with its write step as a parameter, so a failure of that step can be tested.
func write(path string, data []byte, perm os.FileMode, put func(*os.File, []byte) error) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, perm)
	if err != nil {
		return err
	}
	if err := errors.Join(put(f, data), f.Close()); err != nil {
		_ = os.Remove(path)
		return err
	}
	return nil
}
