# GroceryGuru

**[Live demo](https://groceryguru-production-1b67.up.railway.app)** ·
[API docs](https://groceryguru-production-1b67.up.railway.app/swagger-ui/index.html)

## Try it in 30 seconds

1. Open the [live demo](https://groceryguru-production-1b67.up.railway.app) and press
   **Try the demo** (or sign in with `demo@gg.test` / `demo1234`).
2. Open the list called **Recruiter demo basket**.
3. Press **Find Cheapest Store**.

You get every chain ranked by what the whole basket costs there, cheapest first,
and the chains that don't stock everything listed separately as partial matches.
Tap the cheapest one for a shopping checklist, how much you save against the
priciest chain, and a link to find that store on a map.

To add your own items, type what you want in plain Croatian - `mlijeko`, `kruh`,
`jaja` - and it picks the cheapest matching product at each chain for you. Press
**Share this list** to invite another registered user; the list then syncs between
you both live.

Works in a phone browser, so you can add it to your home screen and use it like an
app - no install needed.

Grocery price comparison app for Croatia. Croatian stores are required to publish
their prices daily, so this project pulls those datasets (around 20 chains), cleans
them up and lets you build a shopping list and find out which store near you is
cheapest for the whole basket.

I built this to learn Spring Boot properly and because I was genuinely annoyed
at needing five different store apps to compare prices.

## What it does

- Search ~165k products with current prices across chains (Konzum, Lidl, Plodine, Kaufland...)
- Shopping lists with live sync between devices (WebSocket) and sharing with other users
- "Find cheapest store" - ranks every chain by what your whole basket costs there, and
  says which chains are missing items rather than substituting something else
- Nearby stores by distance, with a map on the phone build and map links on the web
- Cheaper alternative suggestions per item
- Product images matched via PostgreSQL pg_trgm trigram similarity against a locally
  cached index, plus barcode matching where available
- Raw store names like `JAB.BAZ.SIRUP 0,75L` get normalized into `Jabuka Bazga Sirup 0,75L`
- JWT auth, admin role for the ingestion/maintenance endpoints

## Search

Search ended up being most of the work. The feeds hand you truncated ALL-CAPS names
(`MLIJ`, `TJEST`, `S JAJ`), so matching on the raw strings ranks terribly. Searching
`jaja` (eggs) gave me chocolate Easter eggs and egg pasta, and `mlij` put a milk jug
above the actual milk.

The first fix was diacritics. Nobody types `čokolada` with the diacritics on a
regular keyboard, and `cokolada` was only finding 845 of the 3302 chocolate products
while `đumbir` (ginger) returned nothing at all unless you typed the đ. Everything
now goes through a `gg_fold()` function, which is just `unaccent` pinned to a
dictionary so it stays IMMUTABLE and can back a GIN trigram index.

That still didn't help with `jaja`, because "chocolate eggs" really does contain the
word eggs. So each product resolves to a concept, taken from the first word - in
Croatian product names that's almost always the product type - with override rules
for the ones that lead with one thing and are another. `Cokoladna Jaja Oreo` ends up
under `cokolada`, egg pasta under `tjestenina`. The rules are in `concepts.tsv`.

Ranking used to be a fixed order of tiers, which turned out to be the reason a
leftover truncation stocked in zero stores could beat a staple sold in 900 - it won
on an exact word match and nothing else got a say. It's a weighted score now, so
popularity and the concept matching can outweigh a slightly better string match.

The truncations themselves come from `truncations.tsv`, mined out of the corpus and
then filtered against a Croatian word list, because the naive version wanted to
"expand" real words - `repa` is beet, not `repair`, and `hren` is horseradish, which
is not the same thing as `hrenovke`.

## Stack

Backend: Java 21, Spring Boot 3.5 (Web, Data JPA, Security, WebSocket), PostgreSQL.
Mobile: React Native with Expo. There is also a small React web frontend.

## Running it

You need PostgreSQL running locally with a `groceryguru` database.

Create `src/main/resources/application-dev.properties` (gitignored) with your DB
credentials, a `jwt.secret` (any long random string) and `jwt.expiration=86400000`,
then:

```
./mvnw spring-boot:run
```

The schema is created by Hibernate on first start. To get data in, register a user,
set its role to ADMIN in the db, and trigger an ingestion run from
`/api/admin/ingestion` with the token. That downloads and imports the latest daily
archive (takes a few minutes). Swagger UI is at `/swagger-ui/index.html`.

Mobile app:

```
cd GroceryGuru_mobile
npm install
npx expo start
```

`services/api.js` points at the deployed Railway backend, so the app runs on a
phone without any LAN or tunnel setup. Change it to your machine's LAN IP if you
want it talking to a local server instead.

Tests run against an in-memory H2 database, so they don't need Postgres:

```
./mvnw test
```

There is a Dockerfile and railway.toml for deploying to Railway - set DATABASE_URL,
DATABASE_USERNAME, DATABASE_PASSWORD and JWT_SECRET in the service variables.

The deployed service also serves the Expo web build, so the demo and the API share
one URL. To refresh it after changing the app:

```
cd GroceryGuru_mobile && npx expo export --platform web
cp -r dist/* ../src/main/resources/static/
```

After an ingestion, rerun the name and ranking passes (ADMIN token, `?apply=true`):
`/api/products/improve-readability`, `/api/products/assign-concepts`.

## Notes

Price data comes from the official mandated publications. Product images are
scraped for this personal project only - if you own an image and want it removed,
open an issue.
