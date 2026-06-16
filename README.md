# GroceryGuru

Grocery price comparison app for Croatia. Croatian stores are required to publish
their prices daily, so this project pulls those datasets (around 20 chains via
[cijene.dev](https://api.cijene.dev), plus a few store sites directly), cleans them
up and lets you build a shopping list and find out which store near you is cheapest
for the whole basket.

I built this to learn Spring Boot properly and because I was genuinely annoyed
at needing five different store apps to compare prices.

## What it does

- Search ~100k products with current prices across chains (Konzum, Lidl, Plodine, Kaufland...)
- Shopping lists with live sync between devices (WebSocket) and sharing with other users
- "Find cheapest store" - optimizes your whole list against nearby stores, with geolocation
- Cheaper alternative suggestions per item
- Product images matched via PostgreSQL pg_trgm trigram similarity against a locally
  cached index, plus barcode matching where available
- Raw store names like `JAB.BAZ.SIRUP 0,75L` get normalized into `Jabuka Bazga Sirup 0,75L`
- JWT auth, admin role for the ingestion/maintenance endpoints

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
set its role to ADMIN in the db, and call `POST /api/admin/ingestion/cijene-api/run`
with the token. That downloads and imports the latest daily archive (takes a few
minutes). Swagger UI is at `/swagger-ui/index.html`.

Mobile app:

```
cd GroceryGuru_mobile
npm install
npx expo start
```

Point `services/api.js` at your machine's LAN IP if you run it on a real phone.

Tests run against an in-memory H2 database, so they don't need Postgres:

```
./mvnw test
```

There is a Dockerfile and railway.toml for deploying to Railway - set DATABASE_URL,
DATABASE_USERNAME, DATABASE_PASSWORD and JWT_SECRET in the service variables.

## Notes

Price data comes from the official mandated publications. Product images are
scraped for this personal project only - if you own an image and want it removed,
open an issue.
