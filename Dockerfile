FROM node:20-bullseye

# deps for chromium in puppeteer
RUN apt-get update && apt-get install -y \
  ca-certificates fonts-liberation libnss3 libxss1 libasound2 libatk1.0-0 \
  libatk-bridge2.0-0 libgtk-3-0 libdrm2 libgbm1 libxcb1 libxcomposite1 \
  libxrandr2 libxi6 libxcursor1 libxdamage1 libxfixes3 libxkbcommon0 \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package.json package-lock.json* /app/
RUN npm install --production

COPY . /app

ENV NODE_ENV=production
ENV PORT=3000
EXPOSE 3000

CMD ["node", "index.js"]
