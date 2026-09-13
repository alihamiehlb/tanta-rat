FROM node:20-alpine
WORKDIR /app

# Copy backend files only
COPY backend/package*.json ./
RUN npm ci --production

COPY backend/ .

EXPOSE 3000
CMD ["node", "server.js"]
