FROM nginx:alpine
# Patch OS packages (e.g. openssl/libcrypto) to the latest Alpine security fixes
RUN apk upgrade --no-cache
COPY . /usr/share/nginx/html
