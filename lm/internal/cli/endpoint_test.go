package cli

import "testing"

func TestEndpointCmd_parsesConnectorSlashEndpoint(t *testing.T) {
	c, e, err := splitConnectorEndpoint("stripe/charges")
	if err != nil {
		t.Fatal(err)
	}
	if c != "stripe" || e != "charges" {
		t.Errorf("got %s/%s", c, e)
	}
	if _, _, err := splitConnectorEndpoint("invalid"); err == nil {
		t.Error("expected error")
	}
}
