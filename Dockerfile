FROM debian:bullseye

# Install Node + Chromium + dependencies
RUN apt-get update && apt-get install -y \
  curl gnupg ca-certificates git \
  chromium libnss3 libxss1 libasound2 libatk1.0-0 libatk-bridge2.0-0 \
  libcups2 libdrm2 libgbm1 libgtk-3-0 libxcomposite1 libxrandr2 \
  libxi6 libxcursor1 libxdamage1 libxfixes3 libxkbcommon0 \
  && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
  && apt-get install -y nodejs \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package*.json ./
RUN npm install --omit=dev

COPY . .

ENV PORT=3000
ENV CHROME_PATH="/usr/bin/chromium"

EXPOSE 3000
CMD ["node", "index.js"]
