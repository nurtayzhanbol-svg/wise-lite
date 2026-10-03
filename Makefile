.PHONY: up down build test run-transfer run-payout run-recon system-test run-risk

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

run-rails:
	./gradlew :services:rails-simulator:bootRun

run-payout:
	./gradlew :services:payout-worker:bootRun

run-recon:
	./gradlew :services:reconciliation-job:bootRun

system-test:
	./gradlew systemTest

run-risk:
	./gradlew :services:risk-engine:bootRun
