-- Register a no-op pg_trgm similarity() for the H2 test DB so search queries
-- (which use similarity() for ranking on PostgreSQL) run without it.
CREATE ALIAS IF NOT EXISTS similarity AS 'double similarity(String a, String b) { return 0d; }';
