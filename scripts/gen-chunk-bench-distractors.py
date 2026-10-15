#!/usr/bin/env python3
"""Deterministic distractor chunks for the #675 chunk bench corpus (seed 675).

Regenerate with (one line, from the repository root):
    python3 scripts/gen-chunk-bench-distractors.py > lumnik-testkit/src/main/resources/search-corpus/chunks-v1/corpus-distractors.yaml

The output is committed; running this again must reproduce it byte for byte. Number ranges (INV-2022-*, SO-6*, tickets
50000+, KB-*) are disjoint from every identifier used by a query in corpus-core.yaml, so a distractor can never be a
correct answer. 80 chunks for tenant 91001, 10 for tenant 91002.
"""
import json
import random

rng = random.Random(675)

CUSTOMERS = ["Garnier Menuiserie", "Lefèvre Peintures", "Schulz Maschinenbau", "Okafor Trading", "Petit et Fils",
             "Nordmann Spedition", "Valentin Bâtiment", "Hollis Packaging", "Brosse Électricité", "Winkler Gartenbau",
             "Moreau Transports", "Fischer Werkzeuge", "Ashdown Supplies", "Perrin Mécanique", "Lindqvist Tools",
             "Rossi Fastenings"]
GOODS = ["stainless bolts", "zinc washers", "pipe clamps", "gasket kits", "bearing sets", "cable ties", "hex nuts",
         "anchor plugs"]
STATUSES = ["payée", "en attente", "partiellement payée"]
DE_ISSUES = ["Der Drucker im Versand reagiert nicht.", "Die Anbindung des Lagerterminals an das WLAN bricht ab.",
             "Der Etikettendruck zeigt die falsche Chargennummer.", "Das Scanner-Update bleibt bei 80 Prozent hängen.",
             "Der Bildschirm im Wareneingang flackert."]
DE_FIXES = ["Neustart des Druckdienstes.", "Zurücksetzen des Terminalprofils.", "Aktualisierung der Etikettenvorlage.",
            "Austausch des Scanners.", "Tausch des Videokabels."]
PRODUCTS = ["vis M8x40 zinguée", "écrou M8 autofreiné", "rondelle 8,4 mm", "collier de serrage 32 mm",
            "kit joint 3/4 pouce", "collier plastique 200 mm", "cheville 10x50", "tige filetée M10"]
SUPPLIERS = ["Kessler Fournitures", "Aubry Industrie", "Nordic Fixings", "Hartwig Technik"]
GENERIC = [
    "Les salles de réunion se réservent jusqu'à quatre semaines à l'avance depuis le portail des services généraux.",
    "Le restaurant d'entreprise propose un menu végétarien chaque jeudi et un plat du jour à 6,50 EUR.",
    "La formation à l'ergonomie du poste de travail est proposée à chaque nouvel arrivant durant le premier mois.",
    "Le tri des déchets en atelier suit un code couleur : bleu pour le carton, jaune pour les plastiques, gris pour le reste.",
    "Les places de parking visiteurs sont limitées à deux heures et se signalent à l'accueil.",
    "La journée portes ouvertes du site de Lyon aura lieu le premier samedi de mars.",
    "Die Kantine bietet jeden Donnerstag ein vegetarisches Menü an.",
    "Besprechungsräume können bis zu vier Wochen im Voraus über das Facility-Portal gebucht werden.",
    "Neue Mitarbeitende erhalten im ersten Monat eine Schulung zur ergonomischen Gestaltung des Arbeitsplatzes.",
    "Altpapier und Kartons werden in der Halle in die blauen Container sortiert.",
    "Das Sommerfest findet am zweiten Samstag im Juli auf dem Werksgelände statt.",
    "Meeting rooms can be booked up to four weeks ahead through the facilities portal.",
    "The company canteen serves a vegetarian menu every Thursday.",
    "Visitor parking is limited to two hours and must be registered at reception.",
    "The annual open day at the Lyon site takes place on the first Saturday of March.",
    "New starters receive an ergonomics briefing during their first month.",
]


def date():
    return f"{rng.randint(1, 28):02d}/{rng.randint(1, 12):02d}/2024"


def amount():
    whole = f"{rng.randint(120, 9800):,}".replace(",", " ")
    return f"{whole},{rng.randint(0, 99):02d}"


def invoice(i):
    return ("orders", "compta", f"Facture INV-2022-{40000 + i * 7:05d} du client {rng.choice(CUSTOMERS)}, montant "
            f"{amount()} EUR, échéance {date()}, statut {rng.choice(STATUSES)}.")


def order(i):
    return ("orders", "ventes", f"Order SO-{61000 + i * 13:05d} for {rng.choice(CUSTOMERS)}: {rng.randint(2, 40)} "
            f"pallets of {rng.choice(GOODS)}, delivery planned on {date()}.")


def ticket(i):
    k = rng.randrange(len(DE_ISSUES))
    return ("support", "ventes", f"Ticket {50000 + i * 11}: {DE_ISSUES[k]} Gelöst durch {DE_FIXES[k]}")


def product(i):
    return ("orders", "ventes", f"Article KB-{1000 + i * 3:04d}-{rng.choice('ABC')}: {rng.choice(PRODUCTS)}, stock "
            f"{rng.randint(0, 900)} unités, fournisseur {rng.choice(SUPPLIERS)}.")


def generic(i):
    return ("orders", "ventes", GENERIC[i])


rows = []  # (tenant, corpus, tag, text)
for i in range(16):
    for make in (invoice, order, ticket, product, generic):
        corpus, tag, text = make(i)
        rows.append((91001, corpus, tag, text))
for i in range(20, 22):
    for make in (invoice, order, ticket, product):
        corpus, tag, text = make(i)
        rows.append((91002, corpus, tag, text))
for i in range(2):
    corpus, tag, text = generic(i)
    rows.append((91002, corpus, tag, text))

print("# #675 chunk corpus — generated distractors (scripts/gen-chunk-bench-distractors.py, seed 675). Do not edit by hand.")
print("schemaVersion: 3")
print("seed: 675")
print("chunks:")
for n, (tenant, corpus, tag, text) in enumerate(rows, start=1):
    print(f"  - {{id: c-d-{n:03d}, tenantId: {tenant}, corpus: {corpus}, tags: [{tag}], type: connector, "
          f"text: {json.dumps(text, ensure_ascii=False)}}}")
