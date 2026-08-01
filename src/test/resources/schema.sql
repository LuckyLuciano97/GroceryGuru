-- Register a no-op pg_trgm similarity() for the H2 test DB so search queries
-- (which use similarity() for ranking on PostgreSQL) run without it.
CREATE ALIAS IF NOT EXISTS similarity AS 'double similarity(String a, String b) { return 0d; }';

-- gg_fold() is a PostgreSQL function built on the unaccent extension. Search and
-- the basket optimizer match through it, so H2 needs an equivalent to parse and
-- run those queries. java.text.Normalizer folds the same way.
CREATE ALIAS IF NOT EXISTS gg_fold AS 'String ggFold(String s) { if (s == null) return null; String t = s.replace((char) 273, (char) 100).replace((char) 272, (char) 68); t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFKD).replaceAll("\\p{InCombiningDiacriticalMarks}+", ""); return t.toLowerCase(); }';

-- Ranking pulls the head noun out with these two PostgreSQL string functions.
CREATE ALIAS IF NOT EXISTS split_part AS 'String splitPart(String s, String delim, int n) { if (s == null) return null; String[] parts = s.split(java.util.regex.Pattern.quote(delim), -1); return (n >= 1 && n <= parts.length) ? parts[n - 1] : ""; }';
CREATE ALIAS IF NOT EXISTS btrim AS 'String btrim(String s) { return s == null ? null : s.trim(); }';

-- concept and store_count are maintained by SQL rather than by the Product
-- entity, so Hibernate's create-drop never produces them.
ALTER TABLE products ADD COLUMN IF NOT EXISTS concept VARCHAR(64);
ALTER TABLE products ADD COLUMN IF NOT EXISTS store_count INT DEFAULT 0;
