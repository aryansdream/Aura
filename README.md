# AURA

AURA is a daily commitments and focus app. Choose missions for today, track completion, submit a screen-time report to validate the day, and follow verified Aura Points and progress over time.

**Live app:** [aura-day-tracker.vercel.app](https://aura-day-tracker.vercel.app/)

## Features

- A searchable library of more than 1,000 activities across life areas.
- Daily mission selection that returns each day, with task rewards that taper as chapters increase.
- Account sign-in and server-backed mission completion, points, and leaderboard.
- Screen-time report submission for daily verification. Uploaded report images are processed for validation and are not retained.
- Monthly and yearly verified progress history.
- Focus timer with custom session lengths and an end-of-session sound.
- AURA checklist shortcut for a phone home screen, plus installable web app support.

## Run locally

Requirements: Node.js 20 or newer and npm.

```sh
npm install
npm run dev
```

To create a production build:

```sh
npm run build
```

## Environment variables

The API uses these server-side variables. Configure them in your local `.env.local` or in the hosting provider's environment settings. Never commit real values.

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | Neon Postgres connection string used by the API. |
| `NEON_AUTH_BASE_URL` | Neon Auth endpoint used for account sign-in and session handling. |
| `AURA_APP_ORIGIN` | Optional canonical app origin forwarded to the auth service. Defaults to the production AURA URL. |

## Deploy

AURA is configured for Vercel. Link the project with the Vercel CLI, set the server-side environment variables above, then deploy:

```sh
vercel link
vercel deploy --prod
```

The database connection string and auth endpoint must remain server-side; do not add them to browser code or commit them to Git.
