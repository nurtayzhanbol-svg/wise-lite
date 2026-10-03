.PHONY: up down build test run-transfer run-payout

up:            ## start local infrastructure (Postgres, Kafka)
	docker compose up -d --wait

down:          ## stop and remove local infrastructure
	docker compose down -v

build:         ## compile + run all tests (needs Docker for Testcontainers)
	./gradlew build

test:
	./gradlew test

run-transfer:  ## run transfer-service against `make up` infrastructure
	./gradlew :services:transfer-service:bootRun

run-payout:
	./gradlew :services:payout-worker:bootRun
