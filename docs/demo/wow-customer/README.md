# WOW demo — the files

The files of the WOW demo: one Customer fused from a Siebel CRM over SOAP and a CSV export.

**The walkthrough lives in the docs:** [The WOW](../../wow.md) —
<https://docs.lumnik.io/wow/>. Run every command from the root of the clone.

| File | Role |
|------|------|
| `fake-siebel.py` | Stand-in Siebel: answers a `CustomerQueryPage` SOAP call. |
| `customer.csv` | The second source: a customer export. |
| `siebel-connector.yaml` | `rest-generic` connector with a `kind: soap` endpoint. |
| `csv-connector.yaml` | `csv-file` connector over `customer.csv`. |
| `customer-entity.yaml` | `kind: Entity`: fuses both on `customer_code`. |
| `clients-view.yaml` | `kind: View`: a list over the fused Customer. |
| `lyon-process.yaml` | `kind: Process`: a decision when a Lyon customer arrives. |
| `fake-n8n.py` | Stand-in webhook receiver. |
| `commandes.csv` | Orders with dirty statuses, `EN LITIGE` undeclared. |
| `commandes-connector.yaml` | `csv-file` connector over `commandes.csv`. |
| `commande-workflow.yaml` | `kind: Workflow`: the declared order lifecycle. |
| `docker-compose.wow.yml` | Overlay for the self-host stack: mounts this folder at `/demo`. |
