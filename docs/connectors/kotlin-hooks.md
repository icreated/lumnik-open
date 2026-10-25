# Kotlin hooks — when no transformer fits

> A `kotlin` cell hook runs a `.kts` script over each row of a `csv-file` connector. Use it
> only when no [transformer](transformers.md) does the job.

## Name the script in the manifest

The manifest never holds the script. It names one the hub already has:

```yaml
mapping:
  cell:
    - kotlin:
        script: hooks:ean-check.kts        # a script of your hub's hooks directory
        context: { defaultCountry: FR }    # optional; the script reads it as `context`
```

| `script:` | Where the script is |
|---|---|
| `hooks:<name>.kts` | The hooks directory of your hub. Its operator copies the files there ([how](../deploy.md#offer-kotlin-scripts-to-the-integrators)). Write the file name alone: lower-case letters, digits and hyphens, ending in `.kts`. |
| `classpath:<path>` | Built into the hub. One sample ships: `classpath:hooks/samples/normalize-date.kts`. |

A script runs with all of the hub's rights. That is why only the operator installs one, and a
manifest can only name it.

## Check what the hub holds

`lm hook scripts` lists the scripts of the hooks directory. The SHA-256 tells you which version
the hub holds.

`lm validate` and `lm apply` check every `script:`: it must exist and compile. A refusal names
the hook (`spec.mapping.cell[1].kotlin`) and says why:

| The refusal says | Do this |
|---|---|
| `this hub has no hooks directory` | Ask the hub's operator to set one up. |
| `hooks:<name> is not in the hooks directory` | Check the name with `lm hook scripts`. |
| `hooks: takes a file name of the hooks directory` | Write the file name alone: no folder, no capital letters. |
| `Kotlin script compilation failed for ean-check.kts: … (ean-check.kts:2:14)` | The script has an error at line 2, column 14. Send that line to its author. |

## Write a script

The script sample that ships with the hub rewrites `31/12/2019` into `2019-12-31`:

~~~~kotlin
--8<-- "docs/connectors/csv/hooks/normalize-date.kts"
~~~~

- `bindings["cells"]` is the row. Change it: the next hooks and the table see the change.
- `bindings["context"]` holds the manifest's `context:` values, with their types: `lead_days: 3`
  is a number. Read it as `(context["lead_days"] as Number).toInt()`.
- Reading a number `as String` fails the run, naming the script and its line. Use
  `.toString()`.

## A value the script cannot read

Do not fail the run: call `bindings["unreadable"]`. Each call counts one `cell_errors`, like a
built-in hook:

~~~~kotlin
--8<-- "docs/connectors/csv/hooks/read-quantity.kts"
~~~~

- The `kotlin` hook takes no `column:`. So [`on-error`](transformers-reference.md#when-a-hook-cannot-deliver-cell_errors-and-on-error)'s
  `default` decides the row: `soft` writes it, `dlq` sets it aside, `fail` stops the run.
- A script that **throws** fails the run, naming the script, the exception and the line:
  `✖ run failed: kotlin hook hooks:quantity.kts failed: NumberFormatException at line 4`.

## Which version a run used

A script the operator replaces applies at once, without a restart. `lm run get` names each
script the run used, with the SHA-256 `lm hook scripts` shows:

```text
• script hooks:shipping-fee.kts  sha256 23ba31c1b1827ff86228f4f7f36ffd2b73b7ab77ef155598bf05ef12c4b1273e
```

A script replaced while a run was going gives two lines: that run used both versions.

Runnable example: `docs/connectors/csv/50-kotlin-row-hook.yaml`.
