package tui

import "sync/atomic"

type LiveState int32

const (
	LiveConnecting LiveState = iota
	LiveOpen
	LiveReconnecting
	LiveOffline
)

// LiveStatus is the event stream's CONNECTION state: open once the hub accepted it, reconnecting
// after it ended. It used to be inferred from traffic — [POLL] until a first event, [SSE✓] for
// ever after one — so a quiet stream read dead and a dropped one read healthy (#1108).
type LiveStatus struct {
	state atomic.Int32
}

func (s *LiveStatus) Set(v LiveState) { s.state.Store(int32(v)) }
func (s *LiveStatus) Get() LiveState  { return LiveState(s.state.Load()) }

// Observe takes the reconnect loop's report: nil when a stream opened, its error when one ended.
func (s *LiveStatus) Observe(err error) {
	if err == nil {
		s.Set(LiveOpen)
	} else {
		s.Set(LiveReconnecting)
	}
}

func (s *LiveStatus) Label() string {
	switch s.Get() {
	case LiveOpen:
		return "[LIVE✓]"
	case LiveConnecting:
		return "[CONNECTING]"
	case LiveReconnecting:
		return "[RECONNECTING]"
	default:
		return "[OFFLINE]"
	}
}
