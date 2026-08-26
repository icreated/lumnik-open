package generator

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestEntityApplyVariants_count(t *testing.T) {
	variants := entityApplyVariants()
	assert.Len(t, variants, 10, "expect 10 apply variants")
	for _, v := range variants {
		assert.NotEmpty(t, v.name, "variant name must not be empty")
		assert.NotEmpty(t, v.manifestYAML, "variant manifestYAML must not be empty")
		assert.NotEmpty(t, v.feature, "variant feature must not be empty")
	}
}

func TestEntityReadVariants_count(t *testing.T) {
	variants := entityReadVariants()
	assert.Len(t, variants, 4, "expect 4 read variants")
	for _, v := range variants {
		assert.NotEmpty(t, v.name, "variant name must not be empty")
		assert.NotEmpty(t, v.readTarget, "variant readTarget must not be empty")
		assert.NotEmpty(t, v.feature, "variant feature must not be empty")
	}
}

func TestGenerateEntity_deterministicSplit(t *testing.T) {
	s1 := GenerateEntity(1)
	s2 := GenerateEntity(1)
	require.Equal(t, s1.SubType, s2.SubType, "same seed must produce same sub-type")
	require.Equal(t, s1.Feature, s2.Feature, "same seed must produce same feature")
}

func TestGenerateEntity_bothSubtypes(t *testing.T) {
	var gotApply, gotRead bool
	for i := int64(0); i < 100; i++ {
		s := GenerateEntity(i)
		if s.SubType == "apply" {
			gotApply = true
		} else if s.SubType == "read" {
			gotRead = true
		}
	}
	assert.True(t, gotApply, "should generate apply scenarios")
	assert.True(t, gotRead, "should generate read scenarios")
}
