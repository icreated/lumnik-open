# When Ask refuses — and what to do

!!! info "lumnik Pro"
    `lm ask`, the TUI's `:ask` and the PWA's Chat need the AI. On lumnik Open, or with the AI off,
    `lm ask` answers `Error: API error 404 (no details from the server)`.

`lm ask` turns a question into SQL and runs it, read-only, in one scope. One rule decides every
case below: **a refusal is better than a confident wrong answer.** A refusal costs you a retry. A
wrong "there are none" costs a business decision.

The rules in full, for an auditor: [Ask — the rules, in detail](ask-rules.md).

## Read an answer

```
$ lm ask --scope desk "Combien de clients GOLD sont à Lyon ?"
Il y a 2 clients GOLD à Lyon.
  data as of: 2026-10-24T21:35:11.811114Z (last completed run)

  disputed — a source disagrees on a value this answer filtered on:
    P-1008  city = 'Lyon'  counted  t_acme_desk_erp: Lyon · t_acme_desk_crm: Villeurbanne

  COUNT
  2
  1 row returned

  SQL: SELECT COUNT(*)
FROM "connector"."v_acmecustomer"
WHERE "segment" = 'GOLD' AND "city" = 'Lyon'
LIMIT 500

  try: lm conflicts list  — where this scope has source conflicts or lookup ambiguities, row by row
```

| Line | Tells you |
|---|---|
| the first sentence | the answer, written by the model from the rows below |
| `data as of` | the last run that fed this scope: the answer is as fresh as that |
| `disputed` | a record counted on a value its sources disagree on: here, P-1008 is Lyon in the ERP and Villeurbanne in the CRM |
| the rows | what the query returned. Always shown |
| `SQL` | the query that ran. **Read it**: it is what was really asked |

## You see → it means → you do

| You see | It means | Do this |
|---|---|---|
| `Error: API error 409: no schema discovered for scope '…' — apply an entity or run discovery first` | Ask knows no table in this scope yet | Run a connector of that scope, or apply an entity. Then `lm source rediscover --scope <tag>` |
| *I have no data declared for this scope yet…* | the scope exists, with no table | Same |
| *Je ne peux pas répondre à partir des données disponibles pour ce périmètre — la question ne correspond pas au schéma déclaré.* | the question names nothing the scope holds | Rephrase with the words of the columns: `lm source schema --scope <tag>` lists them |
| *… not a declared value of segment (known: GOLD, SILVER, BRONZE)* | you asked about a value the entity does not declare | A typo: ask again. A real value: add it to the entity's `values`, re-apply |
| *… not a declared column of this scope* | the question needs a column nobody declared | Add it to the entity, or ask with an existing column |
| *… not a table in this scope* | the answer would read a table outside the selected scope | Ask in that scope, or add this scope to the connector |
| *I couldn't translate that into a safe query.* | the model wrote something that is not one plain read | Ask a simpler question |
| *La requête n'a renvoyé aucune ligne. Il peut n'y en avoir réellement aucune…* | zero rows. Maybe none exist; maybe the query missed the question | Read the SQL before saying "there are none" |
| *That filter matches N rows, so the question has no single answer…* | a name shared by several records | Ask again with the code the message lists |
| *The answer summarises the first 48 of the 312 rows returned.* | the sentence is written from a sample; every row is still printed | For an exact figure, ask for the count |
| *The SQL query stops at 500 rows: there may be more.* | the result was cut at 500 | Narrow the question |
| *The phrased answer stated a figure these rows do not back, so it is withheld.* | the model wrote a number the rows do not support | The rows and the SQL are the answer |
| *I couldn't tie this question to the previous one…* | a follow-up lumnik could not resolve | Ask the question in full |
| *I couldn't run that query — the database refused it* | the query failed | Read the SQL; if it names a missing table, `lm source rediscover --scope <tag>` |

The hub's own sentences follow `--lang`, else your locale (`fr_FR` above). The model answers in the
question's language.

## A zero-row answer is not a "no"

```
$ lm ask --scope desk "Quels clients sont à Villeurbanne ?"
La requête n'a renvoyé aucune ligne. Il peut n'y en avoir réellement aucune, ou la requête n'a pas saisi votre question — vérifiez le SQL avant d'affirmer qu'il n'y en a pas.
  data as of: 2026-10-24T21:35:11.811114Z (last completed run)

  disputed — a source disagrees on a value this answer filtered on:
    P-1008  city = 'Villeurbanne'  not counted  t_acme_desk_erp: Lyon · t_acme_desk_crm: Villeurbanne

  no rows returned

  SQL: SELECT "name" FROM "connector"."v_acmecustomer" WHERE "city" = 'Villeurbanne'
LIMIT 500
```

No customer reads Villeurbanne in the fused view. But the CRM places P-1008 there: `not counted`
says so. The disagreement is yours to settle: `lm conflicts list` lists every one.

## Ask questions that work

- **Use the words of the data.** *« Quels articles sont en rupture de stock ? »* is declined: no
  column says "rupture". *« Quels articles ont une quantité à zéro ? »* names the column, and works.
- **A name or a text.** *« de la catégorie Pompes »* matches the name exactly. *« dont la
  description contient Badu »* searches the text. Check which one the SQL used: `=` or `ILIKE`.
- **Dates:** write `2026-07-02`, or a month with its year: *juillet 2026*. `01/07/2026` is
  ambiguous and left to the model. The day is read in UTC.
- **An identifier:** a terse lookup such as `status SO-000004` works when the identifier column
  declares an `example` in the entity.

## A question that follows the previous one

Pass the previous question with `--previous`. The PWA's Chat does it for you:

```
$ lm ask --scope desk --previous "Combien de clients GOLD sont à Lyon ?" "Et à Paris ?"
Il n'y a pas de clients GOLD à Paris.
  understood as: Combien de clients GOLD sont à Paris ?
  data as of: 2026-10-24T21:35:11.811114Z (last completed run)

  COUNT
  0
  1 row returned

  SQL: SELECT COUNT(*) FROM "connector"."v_acmecustomer" WHERE "segment" = 'GOLD' AND "city" = 'Paris'
LIMIT 500
```

- `understood as` is the question really answered. Read it before trusting the figure.
- *« Et X ? »* replaces a value; *« seulement … »* narrows; *« aussi … »* widens.
- Only the previous **question** is remembered, never its rows: *« le deuxième »* is refused.
- How a follow-up is resolved, case by case:
  [the rules](ask-rules.md#a-question-that-follows-the-previous-one).

## When an answer looks wrong

The guards lower the odds of a wrong answer. They do not remove it.

1. **Read the SQL** that came with the answer.
2. **Fix the grounding:** a value or a column Ask should have known goes into the entity manifest;
   re-apply it. `lm source rediscover --scope <tag>` refreshes what Ask knows.
3. **Ask again:** the same question gives a SQL you can compare.
4. **Report it** to contact@lumnik.fr, with the question, the SQL and the scope.

## See also

- [Ask — the rules, in detail](ask-rules.md): every stage and every refusal.
- [Reading a run](ingestion-honesty.md): the same principle on the way in.
- [Scopes](../rag/scopes.md): the boundary every question stays in.
