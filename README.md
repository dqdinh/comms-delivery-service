# comm-delivery-service

[![CircleCI](https://circleci.com/gh/ovotech/comms-delivery-service.svg?style=svg&circle-token=29b5c39281290ccfe989e327aba05427d2c7d8a2)](https://circleci.com/gh/ovotech/comms-delivery-service)

Service listens to communication composed events, issuing the communication to the relevant gateway for delivery. 

It currently deals with emails and SMS's.  The intention is that this is the point at which all outward interaction with 3rd parties happen.

## Gateways

* Email
  * Mailgun is used for the sending of emails
* SMS
  * Twilio is used for the sending of SMS's

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

[Service tests] (https://github.com/ovotech/comms-delivery-service/tree/master/src/servicetest/scala/servicetest) execute the service as a 'black box' using docker-compose, as described above.

If you wish to execute the tests just execute `sbt servicetest:test`

## Deployment

The service is deployed continuously to both the UAT and PRD (TBD) environments via the [CircleCI build](https://circleci.com/gh/ovotech/comms-delivery-service) 

## Credstash

This service uses credstash for secret management, and this dependency is required if you want to publish the docker container for this project locally or to a remote server, or run the docker-compose tests. Information on how to install credstash can be found in the [Credstash readme](https://github.com/fugue/credstash)
