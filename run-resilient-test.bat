@echo off
cd /d "d:\Work\openmrs\openmrs-rest-representation-analyzer"
mvn test -Dtest=ResilientContractTest "-Dopenapi.spec.path=openapi-spec.json"
