# comm-delivery-service

[![CircleCI](https://circleci.com/gh/ovotech/comms-delivery-service.svg?style=svg&circle-token=29b5c39281290ccfe989e327aba05427d2c7d8a2)](https://circleci.com/gh/ovotech/comms-delivery-service)

Service listens to communication composed events, issuing the communication to the relevant gateway for delivery. 

Only sends emails for now. At some point in the future it will support other channels, e.g. SMS, push.

## Gateways

* Email
  * Mailgun is used for the sending of emails

## Running it locally

The following environment variables are required to run the service locally:
* KAFKA_HOSTS
  * Hosts in the format host1:9092,host2:9092
* MAILGUN_DOMAIN
  * Mailgun domain emails to be issued from
* MAILGUN_API_KEY
  * API KEY for accessing Mailgun API

You can run the service directly with SBT via `sbt run`

### Docker

The docker image can be pushed to your local repo via `sbt docker:publishLocal`

The docker-compose.yml file included in the project is used for service testing and if used will spin up a kafka/zookeeper instance and a mock http server that the service will then interact with, this is not really suitable for manually testing with (stick with `sbt run`).

## Tests

### Unit Test

Tests are executed via `sbt test`

### Service Tests

Service tests (https://github.com/ovotech/comms-delivery-service/blob/master/src/test/scala/com/ovoenergy/delivery/service/ServiceTestIT.scala) execute the service as a 'black box' using docker-compose, as described above.

If you wish to execute the tests you need to produce a local image `sbt docker:publishLocal` and then you can execute via `sbt dockerComposeTest`

If you wish to execute the service tests in your IDE then, making sure you have produced a local image, run `sbt dockerComposeUp` and run the tests in your IDE.

## Deployment

The service is deployed continuously to both the UAT and PRD (TBD) environments via the [CircleCI build](https://circleci.com/gh/ovotech/comms-delivery-service) 

