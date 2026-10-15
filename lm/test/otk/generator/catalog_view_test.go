package generator

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestViewVariantByName_ReturnsMatchingCaseID(t *testing.T) {
	_, _, _, _, _, _, caseID, found := ViewVariantByName("unknown-scope")
	require.True(t, found)
	assert.Equal(t, viewCaseID("unknown-scope"), caseID)
}

func TestViewVariantByName_UnknownVariant(t *testing.T) {
	_, _, _, _, _, _, caseID, found := ViewVariantByName("no-such-variant")
	assert.False(t, found)
	assert.Empty(t, caseID)
}
