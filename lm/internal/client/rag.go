package client

// RagSearchHit is one scored hit from the v1 RAG search endpoint.
type RagSearchHit struct {
	Score   float64 `json:"score"`
	Snippet string  `json:"snippet"`
	Source  string  `json:"source"`
}

// RagSearchResult wraps the hits returned by RagSearch.
type RagSearchResult struct {
	Results []RagSearchHit `json:"results"`
}

// RagSearch runs a similarity search over the RAG corpus via
// POST /api/platform/rag/search, returning up to topK hits.
func (c *Client) RagSearch(query string, topK int) (*RagSearchResult, error) {
	var out RagSearchResult
	body := map[string]any{"query": query, "topK": topK}
	if err := c.POST("/api/platform/rag/search", body, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// RagStatus reports the RAG index's corpus size (chunk count) and when it was
// last reindexed.
type RagStatus struct {
	CorpusSize  int    `json:"corpusSize"`
	LastReindex string `json:"lastReindex"`
}

// RagStatus fetches the RAG index status via GET /api/platform/rag/status.
func (c *Client) RagStatus() (*RagStatus, error) {
	var out RagStatus
	if err := c.GET("/api/platform/rag/status", &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// RagReindex triggers a full RAG reindex via POST /api/platform/rag/reindex.
func (c *Client) RagReindex() error {
	return c.POST("/api/platform/rag/reindex", map[string]any{}, nil)
}

// === RAG Hub v2 (platform endpoints) ===

// RagSearchHitV2 is one scored search result from the v2 RAG search endpoint,
// keyed to the source row's content hash, with its indexed metadata attached.
type RagSearchHitV2 struct {
	RowHash    string         `json:"rowHash"`
	Content    string         `json:"content"`
	Similarity float64        `json:"similarity"`
	Metadata   map[string]any `json:"metadata"`
}

// RagSearchResponseV2 wraps the hits returned by RagSearchV2.
type RagSearchResponseV2 struct {
	Results []RagSearchHitV2 `json:"results"`
}

// RagChatResponse is the hub's answer to a RAG chat question, with citations
// back to the source rows it drew from.
type RagChatResponse struct {
	Answer    string   `json:"answer"`
	Citations []string `json:"citations"`
}

// RagCorpus summarizes one named RAG corpus: its identity and indexed chunk
// count.
type RagCorpus struct {
	Name       string `json:"name"`
	ChunkCount int64  `json:"chunkCount"`
}

// RagSearchV2 runs a similarity search over corpus (or the default corpus when
// empty) via POST /api/platform/rag/search, returning up to topK hits.
func (c *Client) RagSearchV2(query string, topK int, corpus string) (*RagSearchResponseV2, error) {
	body := map[string]any{"query": query, "topK": topK}
	if corpus != "" {
		body["corpus"] = corpus
	}
	var out RagSearchResponseV2
	if err := c.POST("/api/platform/rag/search", body, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// RagChat asks a natural-language question over corpus (or the default corpus
// when empty) via POST /api/platform/rag/chat, returning a cited answer.
func (c *Client) RagChat(question string, corpus string, topK int) (*RagChatResponse, error) {
	body := map[string]any{"question": question, "topK": topK}
	if corpus != "" {
		body["corpus"] = corpus
	}
	var out RagChatResponse
	if err := c.POSTSlow("/api/platform/rag/chat", body, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// RagCorpora lists the RAG corpora available to the caller via
// GET /api/platform/rag/corpora.
func (c *Client) RagCorpora() ([]RagCorpus, error) {
	var out []RagCorpus
	if err := c.GET("/api/platform/rag/corpora", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// RagScope summarizes one métier scope's RAG coverage: how many connectors feed
// it, how many chunks are indexed, and when it was last indexed.
type RagScope struct {
	Scope       string `json:"scope"`
	Connectors  int    `json:"connectors"`
	Chunks      int64  `json:"chunks"`
	LastIndexed string `json:"lastIndexed,omitempty"`
}

// RagScopes lists every métier scope's RAG coverage via
// GET /api/platform/rag/scopes.
func (c *Client) RagScopes() ([]RagScope, error) {
	var out []RagScope
	if err := c.GET("/api/platform/rag/scopes", &out); err != nil {
		return nil, err
	}
	return out, nil
}

// GetMyScope fetches the caller's default métier scope via
// GET /api/platform/users/me/scope.
func (c *Client) GetMyScope() (string, error) {
	var out struct {
		DefaultScope string `json:"defaultScope"`
	}
	if err := c.GET("/api/platform/users/me/scope", &out); err != nil {
		return "", err
	}
	return out.DefaultScope, nil
}

// SetMyScope sets the caller's default métier scope via
// PUT /api/platform/users/me/scope.
func (c *Client) SetMyScope(scope string) error {
	body := map[string]any{"defaultScope": scope}
	return c.PUT("/api/platform/users/me/scope", body, nil)
}

// RagChatScoped asks a natural-language question restricted to one métier scope
// via POST /api/platform/rag/chat, so the answer draws only from that scope's
// corpus.
func (c *Client) RagChatScoped(question, corpus, scope string, topK int) (*RagChatResponse, error) {
	body := map[string]any{"question": question, "topK": topK}
	if corpus != "" {
		body["corpus"] = corpus
	}
	if scope != "" {
		body["scope"] = scope
	}
	var out RagChatResponse
	if err := c.POSTSlow("/api/platform/rag/chat", body, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
