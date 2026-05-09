.PHONY: help test test-unit test-integration up down clean

# Gradle 7.5.1 (the wrapper) supports JDK 8–17. If the caller's JAVA_HOME
# already points at a supported JDK, use it. Otherwise try a Homebrew
# openjdk@17 install — covers the common local-dev setup on macOS.
ifeq ($(strip $(JAVA_HOME)),)
  BREW_OPENJDK17 := $(shell brew --prefix openjdk@17 2>/dev/null)
  ifneq ($(strip $(BREW_OPENJDK17)),)
    export JAVA_HOME := $(BREW_OPENJDK17)/libexec/openjdk.jdk/Contents/Home
  endif
endif

GRADLE := ./gradlew

help:
	@echo "Targets:"
	@echo "  test              - unit + integration tests (brings up Centrifugo)"
	@echo "  test-unit         - unit tests only"
	@echo "  test-integration  - integration tests only (brings up Centrifugo)"
	@echo "  up                - start Centrifugo via docker compose"
	@echo "  down              - stop Centrifugo"
	@echo "  clean             - gradle clean"

test-unit:
	$(GRADLE) :centrifuge:test

up:
	docker compose up -d --wait

down:
	docker compose down

test-integration: up
	$(GRADLE) :centrifuge:integrationTest

test: test-unit test-integration

clean:
	$(GRADLE) clean
