FROM alpine:latest

LABEL maintainer="eikek0 <eike@docspell.org>"

RUN apk add --no-cache unzip curl bash inotify-tools

RUN mkdir -p /opt \
  && cd /opt \
  && curl -L -o docspell.zip https://github.com/eikek/docspell/releases/download/v0.11.1/docspell-tools-0.11.1.zip \
  && unzip docspell.zip \
  && rm docspell.zip \
  && apk del unzip \
  && chmod 755 /opt/docspell-tools-0.11.1/*.sh

ENTRYPOINT ["/opt/docspell-tools-0.11.1/consumedir.sh"]
