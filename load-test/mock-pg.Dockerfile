FROM node:22-alpine
WORKDIR /app
COPY load-test/mock-pg.mjs /app/mock-pg.mjs
CMD ["node", "/app/mock-pg.mjs"]
