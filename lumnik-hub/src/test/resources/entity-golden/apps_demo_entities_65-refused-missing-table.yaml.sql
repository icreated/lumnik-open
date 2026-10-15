DROP VIEW IF EXISTS connector."v_acmerefusedmissing"
;;
CREATE VIEW connector."v_acmerefusedmissing" WITH (security_invoker = true) AS WITH unified AS (
SELECT 0::int AS _ord, 't_acme_desk_erp'::text AS _src, "code"::text AS "code", "name"::text AS "name" FROM connector."t_acme_desk_erp" WHERE "code" IS NOT NULL
UNION ALL
SELECT 1::int AS _ord, 't_acme_desk_nowhere'::text AS _src, "code"::text AS "code", NULL::text AS "name" FROM connector."t_acme_desk_nowhere" WHERE "code" IS NOT NULL
) SELECT "code", (array_agg("name" ORDER BY _ord, "name") FILTER (WHERE "name" IS NOT NULL))[1] AS "name", (1::text || '|' || octet_length("code"::text)::text || ':' || "code"::text) AS "_canonical_key", jsonb_strip_nulls(jsonb_build_object('name', CASE WHEN count(DISTINCT "name") > 1 THEN jsonb_agg(jsonb_build_object('src', _src, 'value', "name") ORDER BY _ord, "name") FILTER (WHERE "name" IS NOT NULL) END)) AS "_conflicts" FROM unified GROUP BY "code"
;;
GRANT SELECT ON connector."v_acmerefusedmissing" TO lumnik_app
;;
DO $lumnik_view$ BEGIN GRANT SELECT ON connector."v_acmerefusedmissing" TO lumnik_readonly; EXCEPTION WHEN OTHERS THEN NULL; END $lumnik_view$