# Credentials a source writes back

> Some APIs repeat the credential they received: a `401` that quotes the `Authorization` header,
> a GraphQL error that prints it, a SOAP Fault, a debug page that shows its query string. This
> page states what lumnik hides from such a response, where, and what it cannot hide.

## Where it applies

Every request a REST, GraphQL or SOAP connector sends with its credential: the pages of a run,
`lm source rest test`, and `lm source rest probe`. The response is cleaned once, as it arrives, so
everything after it sees the same text: a run's failure message, the sample `lm source rest test`
returns, the retries, and the rows written to the table.

The OAuth2 token request is not cleaned: its answer *is* the token, and lumnik never quotes it.

## The rules, and why

| Rule | Why |
|---|---|
| The whole `Authorization` value is replaced in every response: `Bearer eyJh…` becomes `Bearer <redacted>`. A query parameter whose name says it is a credential (`apikey=…`, `token=…`) becomes `apikey=<redacted>`. | No row writes `Bearer` followed by your token, or `apikey=` followed by your key. These forms are a credential wherever they appear, whatever their length. |
| A response that is **not ingested** loses the bare value too: an HTTP error, a GraphQL `errors` answer, a SOAP Fault. The value is replaced as sent, JSON-escaped (`\/`) and URL-encoded. The user and password of a Basic credential are replaced decoded as well. | This text only ever reaches a failure message or a test sample, never your tables, so replacing too much costs nothing. |
| In that same response, a value shorter than 8 characters is replaced only where it stands alone, not inside a longer word or number. | A short value is often part of the error text itself. Replacing it inside every word would make the message unreadable. |
| A response that **is ingested** never loses a bare value. | A token `12345678` and a `"customer_id": 12345678` are the same text. Rewriting it would change your data, and no length tells one from the other. |
| An OAuth2 `access_token` shorter than 8 characters is refused before the first page. No other credential is refused for its length. | lumnik obtains that token itself, so it can refuse one too short to keep out of an error text. A static key is yours, and lumnik will not lock you out for its length. |
| In an XML or HTML response the marker is written `&lt;redacted&gt;`. A parser reads it back as `<redacted>`. | `<redacted>` is a tag in markup. Writing it raw would break the envelope: a SOAP page would fail to parse, and a Fault would no longer be reported as one. |

## What is not covered

| Case | What happens |
|---|---|
| A credential sent in the **request body**: a `{{secret:…}}` inside a SOAP envelope, a form or JSON login. | Only headers and query parameters are known as credentials. A source that echoes a body credential shows it in the failure message. Prefer a header credential where the API offers one. |
| A header whose name does not contain `key`, `token`, `secret`, `password` or `auth`, for example `X-Signature` or `Cookie`. | It is not treated as a credential, and an echo of it is not hidden. |
| An echo in a **response header**. | Nothing lumnik prints shows a response header in full. A redirect's failure message names its host only. |

## See also

- [REST connector — Auth](../connectors/rest.md#auth)
- [SOAP connector](../connectors/soap.md)
