# ─────────────────────────────────────────────────────────────
# Stage 1 – BUILD
# Use a full Node image to install dependencies and build
# ─────────────────────────────────────────────────────────────
FROM node:20-alpine AS build

WORKDIR /app

# Copy package files first for dependency layer caching
COPY package.json package-lock.json ./

# Install all dependencies (including devDependencies needed by react-scripts)
RUN npm ci --legacy-peer-deps

# Copy source code
COPY public ./public
COPY src ./src

# Build arguments — allows overriding the API base URL at build time
# Usage: docker build --build-arg REACT_APP_API_BASE=https://your-backend.com .
ARG REACT_APP_API_BASE=http://localhost:8080
ENV REACT_APP_API_BASE=$REACT_APP_API_BASE

# Build the production-optimised static files
RUN npm run build

# ─────────────────────────────────────────────────────────────
# Stage 2 – SERVE
# Use a tiny Nginx image to serve the static build output
# ─────────────────────────────────────────────────────────────
FROM nginx:1.27-alpine AS serve

# Remove default Nginx config and replace with our own
RUN rm /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy the React build output from Stage 1
COPY --from=build /app/build /usr/share/nginx/html

# Expose port 80 (Nginx default)
EXPOSE 80

# Healthcheck
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
