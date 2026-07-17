FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM alpine:3.22 AS export
COPY --from=build /app/dist /dist
CMD ["sh", "-c", "cp -r /dist/. /export/"]
