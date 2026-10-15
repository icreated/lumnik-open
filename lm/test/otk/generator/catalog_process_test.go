package generator

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestProcessApplyManifest_ReturnsMatchingCaseID(t *testing.T) {
	_, _, _, caseID, found := ProcessApplyManifest("wrong-role")
	require.True(t, found)
	assert.Equal(t, processCaseID("apply", "wrong-role"), caseID)
}

func TestProcessApplyManifest_UnknownVariant(t *testing.T) {
	_, _, _, caseID, found := ProcessApplyManifest("no-such-variant")
	assert.False(t, found)
	assert.Empty(t, caseID)
}

func TestInboxVariantByName_ReturnsMatchingCaseID(t *testing.T) {
	_, _, _, caseID, found := InboxVariantByName("huge-limit")
	require.True(t, found)
	assert.Equal(t, processCaseID("inbox", "huge-limit"), caseID)
}

func TestInboxVariantByName_UnknownVariant(t *testing.T) {
	_, _, _, caseID, found := InboxVariantByName("no-such-variant")
	assert.False(t, found)
	assert.Empty(t, caseID)
}
