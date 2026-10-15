---
{
  "lumnik-dossier": 1,
  "catalogue": "2026.10.0",
  "created": "2026-10-03",
  "language": "fr",
  "words": "Nos commandes restent bloquées sans que personne ne s'en aperçoive.",
  "answers": [
    { "id": "goal", "kind": "choice", "asked": "Que voulez-vous obtenir ?", "value": "stalled-documents", "label": "Repérer les dossiers qui n'avancent plus", "source": "prospect" },
    { "id": "has-status", "kind": "yes-no-unknown", "asked": "Vos documents portent-ils un statut (en cours, terminé…) ?", "value": "yes", "source": "prospect" },
    { "id": "has-stable-id", "kind": "yes-no-unknown", "asked": "Chaque document a-t-il un identifiant qui ne change pas ?", "value": "unknown", "source": "prospect" }
  ],
  "leads": [
    {
      "id": "C1",
      "rule": "stalled-documents@1",
      "source": "catalogue",
      "title": "Surveiller les documents qui n'avancent plus",
      "says": "lumnik peut déclarer le cycle de vie d'une colonne de statut et alerter quand un document reste dans le même état au-delà d'un seuil.",
      "does-not-say": [
        "L'immobilité est mesurée depuis la première observation par lumnik, jamais depuis la date d'entrée dans votre ERP.",
        "Un document sans identifiant n'est pas suivi.",
        "La surveillance porte sur les données d'une source telles qu'elles sont reçues, pas sur une fiche fusionnée de plusieurs sources."
      ],
      "proposes": { "kind": "Workflow", "uses": ["spec.on.table", "spec.on.column", "spec.on.key", "spec.dormancy.after"] },
      "conditions": [
        { "id": "C1", "needs": "status-column-exists", "verifier": "workflow-data-truth", "label": "Une colonne de statut existe", "backed-by": "has-status", "check": "unverified" },
        { "id": "C2", "needs": "status-column-is-lifecycle", "verifier": "none", "label": "Cette colonne décrit le cycle de vie du document", "backed-by": "has-status", "check": "unverified" },
        { "id": "C3", "needs": "key-column-exists", "verifier": "workflow-data-truth", "label": "Une colonne d'identifiant existe", "backed-by": "has-stable-id", "check": "missing" },
        { "id": "C4", "needs": "stable-document-identity", "verifier": "none", "label": "L'identifiant désigne durablement chaque document", "backed-by": "has-stable-id", "check": "missing", "note": "L'intégrateur doit établir que la clé identifie durablement chaque document suivi. Le contrôle actuel ne vérifie pas cette garantie." },
        { "id": "C5", "needs": "dormancy-target-accepted", "verifier": "workflow-apply-door", "label": "La cible choisie est acceptée par la validation de la surveillance", "check": "missing" }
      ]
    }
  ]
}
---
<!-- Generated from the header above. Only the header is read back; edits below are ignored. -->

# Dossier lumnik — 3 octobre 2026
