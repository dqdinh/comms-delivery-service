package com.ovoenergy.delivery.service.kafka.domain

case class KafkaConfig(hosts: String,
                       groupId: String,
                       emailComposedTopic: String,
                       emailProgressedTopic: String,
                       commFailedTopic: String)
