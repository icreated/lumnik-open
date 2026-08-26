package client

// Mapping is a mapping manifest's list-view summary: id, version, and target
// table.
type Mapping struct {
	ID      string `json:"id"`
	Version int    `json:"version"`
	Target  string `json:"target"`
}

// MappingDetail is a mapping manifest's full detail: its target, processing
// mode, and the number of dataset/row/cell hooks it declares.
type MappingDetail struct {
	ID      string `json:"id"`
	Version int    `json:"version"`
	Target  string `json:"target"`
	Mode    string `json:"mode"`
	Dataset int    `json:"dataset"`
	Row     int    `json:"row"`
	Cell    int    `json:"cell"`
}

// MappingList lists every mapping via GET /api/platform/mappings.
func (c *Client) MappingList() ([]Mapping, error) {
	var out []Mapping
	if err := c.GET("/api/platform/mappings", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// MappingGet fetches one mapping's detail via GET /api/platform/mappings/{id}.
func (c *Client) MappingGet(id string) (*MappingDetail, error) {
	var out MappingDetail
	if err := c.GET("/api/platform/mappings/"+id, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// MappingGetRaw fetches a mapping's raw YAML source via
// GET /api/platform/mappings/{id}?raw=true.
func (c *Client) MappingGetRaw(id string) (string, error) {
	var out struct {
		Yaml string `json:"yaml"`
	}
	if err := c.GET("/api/platform/mappings/"+id+"?raw=true", &out); err != nil {
		return "", err
	}
	return out.Yaml, nil
}

// MappingApply overwrites a mapping's YAML source via
// PUT /api/platform/mappings/{id}.
func (c *Client) MappingApply(id, yaml string) error {
	return c.PUT("/api/platform/mappings/"+id, map[string]any{"yaml": yaml}, nil)
}
