-- Review PR #16 (R-04): supplier names are unique ignoring case and repeated spaces (UC-31, UC-33, MSG78).
-- The app stores names trimmed with single spaces; bring existing rows to the same form first.
UPDATE supplier SET supplier_name = regexp_replace(btrim(supplier_name), '\s+', ' ', 'g')
WHERE supplier_name <> regexp_replace(btrim(supplier_name), '\s+', ' ', 'g');

DO $$
DECLARE
    dup TEXT;
BEGIN
    SELECT string_agg(DISTINCT supplier_name, ', ') INTO dup
    FROM supplier s
    WHERE (SELECT count(*) FROM supplier o WHERE lower(o.supplier_name) = lower(s.supplier_name)) > 1;
    IF dup IS NOT NULL THEN
        RAISE EXCEPTION 'Rename the suppliers that share a name before migrating: %', dup;
    END IF;
END $$;

CREATE UNIQUE INDEX ux_supplier_name_ci ON supplier (lower(supplier_name));
