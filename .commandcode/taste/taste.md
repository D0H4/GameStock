# Taste

## Communication
- Communicates in Korean and expects explanations/responses in Korean. Confidence: 0.6

## Architecture
- Prefers eagerly fetching expensive-to-fetch data at collection/write time and persisting it (e.g. storing fetched content into the DB) so requests are served from storage instead of being computed on-demand. Confidence: 0.7
- Prefers the persistent database as the default shared store/source of truth; treats Redis as an optional add-on to enable only when infrastructure already provides it. Confidence: 0.6
