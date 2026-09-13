package client

// The hub clamps every listing page, and each door clamps its own — there is no universal
// ceiling. A caller that asks beyond these gets a shorter page than it requested, silently, so a
// client that wants to know whether a page came back FULL has to compare against the number the
// server actually applied. They live here, next to the calls they describe, because both surfaces
// that page — the CLI's list commands and the TUI's tables — must read the same number.
//
// Mirrored from the resources named beside them; a hub that raises one of these makes the client
// stop short, not overreach.
const (
	RunsPageCap         = 200  // RunAdminResource
	DlqPageCap          = 200  // DlqAdminResource
	InboxPageCap        = 200  // InboxResource
	OutputsPageCap      = 500  // PlatformDeliveryResource
	ConflictsPageCap    = 1000 // EntityConflictsResource
	ObservationsPageCap = 1000 // WorkflowResource
)
