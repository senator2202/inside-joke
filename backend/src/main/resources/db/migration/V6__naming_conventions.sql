-- Naming conventions (docs/CONVENTIONS.md).

-- Booleans read as questions (is_...), and one concept has one name: a game's "free" flag is is_free everywhere.
ALTER TABLE ai_call RENAME COLUMN is_free_game TO is_free;
ALTER TABLE entitlement RENAME COLUMN granted_by_admin TO is_granted_by_admin;

-- Every constraint says what it is: pk_<table>, fk_<table>_<columns>, ck_<table>_<columns>, ux_<table>_<columns>.
-- PostgreSQL named the ones created inline in V1-V2 (app_user_pkey, purchase_status_check, ...). Spring Session's and
-- Flyway's own tables keep their names.
DO $$
DECLARE
    c      record;
    cols   text;
    base   text;
    target text;
    n      int;
BEGIN
    FOR c IN
        SELECT con.conname, con.contype, con.conkey, con.conrelid, rel.relname AS tbl
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace ns ON ns.oid = rel.relnamespace
        WHERE ns.nspname = current_schema()
          AND rel.relname NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
          AND con.contype IN ('p', 'f', 'c', 'u')
          AND con.conname !~ '^(pk|fk|ck|ux)_'
        ORDER BY rel.relname, con.conname
    LOOP
        SELECT string_agg(att.attname, '_' ORDER BY k.ord) INTO cols
        FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
        JOIN pg_attribute att ON att.attrelid = c.conrelid AND att.attnum = k.attnum;
        base := CASE c.contype
                    WHEN 'p' THEN 'pk_' || c.tbl
                    WHEN 'f' THEN 'fk_' || c.tbl || '_' || cols
                    WHEN 'u' THEN 'ux_' || c.tbl || '_' || cols
                    ELSE 'ck_' || c.tbl || coalesce('_' || cols, '')
                END;
        target := left(base, 63);
        n := 1;
        WHILE EXISTS (SELECT 1 FROM pg_constraint WHERE conname = target) LOOP
            n := n + 1;
            target := left(base, 60) || '_' || n;
        END LOOP;
        EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I', c.tbl, c.conname, target);
    END LOOP;
END $$;
