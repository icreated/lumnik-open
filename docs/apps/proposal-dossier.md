# Proposal dossiers

> A business person describes a problem on lumnik.fr and downloads a **proposal dossier**. You
> draft the workflow from it, then check the dossier against your data.

## Check a dossier against your data

Ask the hub what it can establish on your data, against a workflow you already applied. No `lm`
command does this: call the hub over HTTP.

```bash
curl -X POST "$HUB/api/proposal/check?workflow=<name>" \
  -H "Authorization: Bearer $TOKEN" \
  --data-binary @dossier.md > dossier-checked.md
```

It needs `lm_integrator`. Mint `$TOKEN` as in [Test the API](../recipes/test-the-api.md#mint-a-token). The answer is the
same file, checked; nothing is applied, and the original answers are untouched.
Without `workflow=`, the dossier is only recomputed.

| Point | Confirmed when | Refuted when |
|---|---|---|
| *a status column exists* | the comparison read the column | the table is there and the column is not |
| *an identifier column exists* | the table has the `spec.on.key` column | the table is there and does not have it (judged only if the status column exists) |
| *the target is accepted by the watch validation* | the workflow is stored | never |

- What only a person can establish (*the column describes the lifecycle*, *the identifier is
  stable*) stays *to be established*.
- A table not ingested yet, a timeout or a missing grant leave a point unchanged.
- The proof is about your data **today**.
- A dossier for another catalogue version is returned recomputed, unverified.
- A malformed dossier is a `400` with the reason (`duplicate-key`); a file that is not UTF-8 is a
  `400` `malformed-header`.

## Draft a workflow from a dossier

`lm proposal draft` writes the **start** of the manifest a dossier proposes. It does not contact
the hub:

```bash
lm proposal draft dossier.md \
  --name orders-lifecycle --scope sales \
  --table connector.t_commandes --column statut --key n_commande --after 14d \
  -o orders-lifecycle.yaml
```

- Every flag is optional; a value you leave out is left out of the file, with a comment saying
  where it goes.
- States and transitions are never generated: you write them before `lm workflow apply`.
- `--after` without `--key` is accepted here and refused at apply.
- The header comments list each point, **refuted first**, then missing, unverified, confirmed;
  what no tool can check is marked **to establish yourself**.
- `-o` never overwrites a file; without it the draft goes to stdout. The dossier can come from
  stdin (`lm proposal draft -`).
- A dossier with no `stalled-documents@1` lead, or two, is refused; other leads are named on
  stderr.

The loop: draft → complete and `lm workflow apply -f` → check the dossier against the stored
workflow (the `curl` above) → draft again from the checked dossier, to a new file.
