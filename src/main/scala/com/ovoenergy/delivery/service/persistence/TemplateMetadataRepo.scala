package com.ovoenergy.delivery.service.persistence

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import com.ovoenergy.comms.model.UnexpectedDeliveryError
import com.ovoenergy.comms.templates.model.template.metadata.{TemplateId, TemplateSummary}
import com.ovoenergy.comms.templates.{TemplateMetadataContext, TemplateMetadataRepo => Repo}
import com.ovoenergy.delivery.service.domain.{DynamoError, TemplateDetailsNotFoundError}

trait TemplateMetadataRepo[F[_]] {
  def getTemplateMetadata(templateId: TemplateId): F[TemplateSummary]
}

object TemplateMetadataRepo {
  def apply[F[_]](context: TemplateMetadataContext)(implicit F: Sync[F]) = new TemplateMetadataRepo[F] {
    override def getTemplateMetadata(templateId: TemplateId): F[TemplateSummary] =
      F.delay {
        Repo
          .getTemplateSummary(context, templateId)
          .sequence
          .toEither
          .leftMap(errs =>
            DynamoError(UnexpectedDeliveryError,
                        s"Failed to fetch template summary from DynamoDB: ${errs.toList.mkString(", ")}"): Throwable)
          .flatMap(_.toRight(TemplateDetailsNotFoundError))
      }.rethrow
  }
}
