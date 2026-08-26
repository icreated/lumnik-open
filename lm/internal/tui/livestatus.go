package tui

import "sync/atomic"

type LiveState int32

const (
	LiveOffline LiveState = iota
	LivePolling
	LiveSSE
)

type LiveStatus struct {
	state atomic.Int32
}

func (s *LiveStatus) Set(v LiveState) { s.state.Store(int32(v)) }
func (s *LiveStatus) Get() LiveState  { return LiveState(s.state.Load()) }

func (s *LiveStatus) Label() string {
	switch s.Get() {
	case LiveSSE:
		return "[SSE✓]"
	case LivePolling:
		return "[POLL]"
	default:
		return "[OFFLINE]"
	}
}
